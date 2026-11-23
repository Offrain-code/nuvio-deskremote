import * as ndc from 'node-datachannel';
import { info, warn } from '../logger';
import * as cryptoMod from 'crypto';
import { H264AccessUnit, H264AccessUnitParser } from './h264FrameParser';

export interface WebRtcParams {
  offerSdp: string;
  fps: number;
  width: number;
  height: number;
  portRangeBegin: number;
  portRangeEnd: number;
  onTrackOpen?: () => void;
  onDisconnected?: (state: string) => void;
}

export interface H264OfferCodec {
  payloadType: number;
  fmtp: string;
}

/**
 * Selects the H264/90000 payloadType offered by the Android client (RFC 3264 Offer/Answer),
 * avoiding answering on payloadType 96 when 96 in the offer is VP8.
 */
export function pickH264OfferCodec(offerSdp: string): H264OfferCodec {
  const defaultFmtp = 'level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f';
  const videoBlockMatch = /m=video[^\r\n]*([\s\S]*?)(?=\r?\nm=|$)/.exec(offerSdp);
  const section = videoBlockMatch ? videoBlockMatch[0] : offerSdp;

  const h264Pts: number[] = [];
  const rtpmapRe = /a=rtpmap:(\d+)\s+H264\/90000/gi;
  let m: RegExpExecArray | null;
  while ((m = rtpmapRe.exec(section)) !== null) {
    h264Pts.push(Number(m[1]));
  }
  if (h264Pts.length === 0) {
    return { payloadType: 96, fmtp: defaultFmtp };
  }

  const fmtps = new Map<number, string>();
  const fmtpRe = /a=fmtp:(\d+)\s+([^\r\n]+)/gi;
  while ((m = fmtpRe.exec(section)) !== null) {
    fmtps.set(Number(m[1]), m[2].trim());
  }

  // Preference: Constrained Baseline (42e0..) -> Baseline (4200..) -> any packetization-mode=1 -> first H264
  const pickBy = (pred: (fmtp: string) => boolean): number | undefined =>
    h264Pts.find((pt) => pred(fmtps.get(pt) ?? ''));

  const chosenPt =
    pickBy((f) => /profile-level-id=42e0/i.test(f) && /packetization-mode=1/i.test(f)) ??
    pickBy((f) => /profile-level-id=4200/i.test(f) && /packetization-mode=1/i.test(f)) ??
    pickBy((f) => /packetization-mode=1/i.test(f)) ??
    h264Pts[0];

  return {
    payloadType: chosenPt,
    fmtp: fmtps.get(chosenPt) || defaultFmtp,
  };
}

/**
 * Extracts the playout-delay RTP header extension ID from the m=video section of the offer SDP
 * (http://www.webrtc.org/experiments/rtp-hdrext/playout-delay).
 */
export function pickPlayoutDelayExtId(offerSdp: string): number | null {
  const videoSecMatch = /m=video[^\r\n]*([\s\S]*?)(?=\r?\nm=|$)/.exec(offerSdp);
  if (!videoSecMatch) return null;
  const extMatch =
    /^a=extmap:(\d+)(?:\/\S+)?\s+http:\/\/www\.webrtc\.org\/experiments\/rtp-hdrext\/playout-delay\b/im.exec(
      videoSecMatch[0],
    );
  if (!extMatch) return null;
  const id = Number.parseInt(extMatch[1], 10);
  return Number.isFinite(id) && id > 0 && id <= 14 ? id : null;
}

/**
 * WebRTC screen-streaming session (libdatachannel via node-datachannel):
 * - SendOnly H.264 video aligned to the negotiated offer payloadType;
 * - Chain: H264RtpPacketizer ('StartSequence') -> RtcpSrReporter -> RtcpNackResponder;
 * - Sends complete Access Units using a real-time 90 kHz RTP clock with IDR keyframe caching.
 */
export class WebRtcScreenSession {
  private pc: ndc.PeerConnection;
  private track: ndc.Track;
  private cfg: ndc.RtpPacketizationConfig;
  private readonly baseRtpTs: number;
  private readonly startPerfMs: number;
  private lastSentRtpTs = -1;
  private readonly parser: H264AccessUnitParser;
  private lastKeyFrameAu: Buffer | null = null;
  private sentWarmupAfterOpen = false;
  private closed = false;

  static async create(id: string, p: WebRtcParams): Promise<WebRtcScreenSession> {
    if (!p.offerSdp || !p.offerSdp.includes('m=video')) {
      throw new Error('missing offer SDP or missing m=video section');
    }

    const pc = new ndc.PeerConnection(id, {
      iceServers: [], // LAN pairing; direct local candidates
      portRangeBegin: p.portRangeBegin,
      portRangeEnd: p.portRangeEnd,
    });

    // Extract the exact video mid and H.264 payloadType from the Android offer
    const midMatch = /m=video[\s\S]*?a=mid:([^\r\n]+)/.exec(p.offerSdp);
    const videoMid = midMatch ? midMatch[1].trim() : '0';
    const codec = pickH264OfferCodec(p.offerSdp);
    const playoutDelayId = pickPlayoutDelayExtId(p.offerSdp);

    // IMPORTANT: addTrack BEFORE setRemoteDescription, and do NOT call setLocalDescription() afterwards
    const video = new ndc.Video(videoMid, 'SendOnly');
    video.addH264Codec(codec.payloadType, codec.fmtp);
    if (playoutDelayId !== null) {
      // Advertise extmap on local media description to preserve playout-delay in the answer
      video.parseSdpLine(`a=extmap:${playoutDelayId} http://www.webrtc.org/experiments/rtp-hdrext/playout-delay`);
    }
    const track = pc.addTrack(video);

    const cfg = new ndc.RtpPacketizationConfig(pickSsrc(id), 'nuvio-screen', codec.payloadType, 90000);
    if (playoutDelayId !== null) {
      // playout-delay 0..0 instructs libwebrtc to render immediately without extra jitter buffering
      cfg.playoutDelayId = playoutDelayId;
      cfg.playoutDelayMin = 0;
      cfg.playoutDelayMax = 0;
    }
    const packetizer = new ndc.H264RtpPacketizer('StartSequence', cfg, 1180);
    const sr = new ndc.RtcpSrReporter(cfg);
    const nack = new ndc.RtcpNackResponder(512);
    packetizer.addToChain(sr);
    sr.addToChain(nack);
    track.setMediaHandler(packetizer);

    let sessionRef: WebRtcScreenSession | null = null;
    pc.onGatheringStateChange((st) => info(`webrtc: gathering ${st}`));
    track.onOpen(() => {
      info(
        `webrtc: track open (pt=${codec.payloadType}, playoutDelayId=${playoutDelayId ?? 'none'}, H.264 stream active)`,
      );
      sessionRef?.flushCachedKeyFrame();
      p.onTrackOpen?.();
    });
    track.onError((msg: string) => warn(`webrtc: track error: ${msg}`));
    pc.onStateChange((st) => {
      info(`webrtc: pc state ${st}`);
      if (st === 'failed' || st === 'disconnected' || st === 'closed') {
        p.onDisconnected?.(st);
      }
    });

    pc.setRemoteDescription(p.offerSdp, 'offer');

    // On LAN, resolve quickly as soon as the first local candidates are gathered
    await waitForGatheringFast(pc, 400);

    const answer = pc.localDescription();
    if (!answer || !answer.sdp) {
      throw new Error('localDescription() was null after setRemoteDescription (negotiation failed)');
    }

    const session = new WebRtcScreenSession(pc, track, cfg, answer);
    sessionRef = session;
    return session;
  }

  private constructor(
    pc: ndc.PeerConnection,
    track: ndc.Track,
    cfg: ndc.RtpPacketizationConfig,
    public answer: { type: string; sdp: string },
  ) {
    this.pc = pc;
    this.track = track;
    this.cfg = cfg;
    this.baseRtpTs = (cfg.timestamp ?? 0) >>> 0;
    this.startPerfMs = performance.now();
    this.parser = new H264AccessUnitParser((au) => this.handleAccessUnit(au));
  }

  /** Adds a trickle ICE candidate received from the Android client. */
  addRemoteCandidate(candidate: string, mid = '0'): void {
    if (this.closed || !candidate) return;
    try {
      this.pc.addRemoteCandidate(candidate, mid || '0');
    } catch (err) {
      warn(`webrtc: addRemoteCandidate failed: ${(err as Error).message}`);
    }
  }

  /** Feeds raw Annex-B chunks from FFmpeg H.264 stdout. */
  feedAnnexBChunk(chunk: Buffer): void {
    if (this.closed) return;
    this.parser.push(chunk);
  }

  /** Sends a single NAL unit or full Access Unit prefixed with StartSequence (00 00 00 01). */
  sendNal(unit: Buffer): void {
    if (this.closed || !this.track.isOpen()) return;
    try {
      this.track.sendMessageBinary(unit);
    } catch {
      // track closing
    }
  }

  private flushCachedKeyFrame(): void {
    if (this.closed || this.sentWarmupAfterOpen || !this.lastKeyFrameAu || !this.track.isOpen()) return;
    this.sentWarmupAfterOpen = true;
    this.updateClockTimestamp();
    try {
      this.track.sendMessageBinary(this.lastKeyFrameAu);
    } catch {
      // ignore
    }
  }

  private handleAccessUnit(au: H264AccessUnit): void {
    if (this.closed) return;
    if (au.isKeyFrame) {
      this.lastKeyFrameAu = au.auBuffer;
    }
    if (!this.track.isOpen()) return;
    if (au.isKeyFrame) {
      this.sentWarmupAfterOpen = true;
    } else if (!this.sentWarmupAfterOpen && this.lastKeyFrameAu) {
      this.flushCachedKeyFrame();
    }
    this.updateClockTimestamp();
    try {
      this.track.sendMessageBinary(au.auBuffer);
    } catch {
      // track closing
    }
  }

  private updateClockTimestamp(): void {
    const elapsedTicks = Math.max(1, Math.round((performance.now() - this.startPerfMs) * 90));
    let next = (this.baseRtpTs + elapsedTicks) >>> 0;
    if (this.lastSentRtpTs === next) {
      next = (next + 1) >>> 0;
    }
    this.lastSentRtpTs = next;
    (this.cfg as { timestamp: number }).timestamp = next;
  }

  /** Manually advances the RTP timestamp (compatibility helper). */
  advanceFrameTimestamp(fps = 60): void {
    (this.cfg as { timestamp: number }).timestamp = ((this.cfg.timestamp + Math.round(90000 / Math.max(1, fps))) >>> 0);
  }

  isOpen(): boolean {
    return !this.closed && this.track.isOpen();
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.lastKeyFrameAu = null;
    this.parser.reset();
    try { this.track.close(); } catch { /* already closed */ }
    try { this.pc.close(); } catch { /* already closed */ }
  }
}

function pickSsrc(seed: string): number {
  const h = cryptoMod.createHash('sha1').update(seed).digest();
  return (h.readUInt32LE(0) >>> 1) || 0x12345;
}

/** Waits briefly for local ICE candidate gathering without blocking on external STUN. */
function waitForGatheringFast(pc: ndc.PeerConnection, timeoutMs: number): Promise<void> {
  return new Promise((resolve) => {
    if (pc.gatheringState() === 'complete') {
      resolve();
      return;
    }
    let done = false;
    let settleAfterCandidate: NodeJS.Timeout | null = null;
    const finish = () => {
      if (done) return;
      done = true;
      if (settleAfterCandidate) clearTimeout(settleAfterCandidate);
      clearTimeout(hardTimer);
      resolve();
    };
    pc.onGatheringStateChange((st) => {
      if (st === 'complete') finish();
    });
    pc.onLocalCandidate(() => {
      if (!settleAfterCandidate) {
        settleAfterCandidate = setTimeout(finish, 80);
        settleAfterCandidate.unref?.();
      }
    });
    const hardTimer = setTimeout(finish, timeoutMs);
    hardTimer.unref?.();
  });
}
