import * as path from 'path';
import { Worker } from 'node:worker_threads';
import { Config, ScreenProfile } from '../config';
import { info, warn } from '../logger';
import { GdiCapture } from './capture';
import {
  H264StreamEncoder,
  MjpegCaptureMode,
  MjpegEncoder,
  probeDdaOutputs,
  resolveH264StreamCodec,
  warmUpCaptureCapabilities,
} from './encoder';
import { WebRtcScreenSession } from './webrtc';
import { AudioLoopbackStreamer } from './audioLoopback';
import { Win32Bindings, attachToInteractiveDesktop } from '../injection/win32';

/**
 * On-demand screen streaming server (SPEC §5):
 * - Starts only when the "Screen" tab is opened on the client (screen_start);
 * - Stops immediately when leaving (screen_stop) or disconnecting the WebSocket;
 * - Off-event-loop capture with 3 automatic tiers: GPU ddagrab -> FFmpeg gdigrab -> GDI Worker.
 */

interface ProfileDims { width: number; height: number; fps: number }

export function profileDims(profile: ScreenProfile): ProfileDims {
  switch (profile) {
    case '720p30': return { width: 1280, height: 720, fps: 30 };
    case '720p60': return { width: 1280, height: 720, fps: 60 };
    case '1080p30': return { width: 1920, height: 1080, fps: 30 };
    case '1080p60': return { width: 1920, height: 1080, fps: 60 };
    default: return { width: 1280, height: 720, fps: 30 };
  }
}

type StatusCb = (status: 'starting' | 'running' | 'stopped' | 'error', profile?: ScreenProfile, detail?: string, monitor?: number) => void;
type FrameCb = (ownerId: string, jpg: Buffer, width: number, height: number, monitor: number, directCursorEmbedded: boolean) => void;
type AudioCb = (ownerId: string, pcmPacket: Buffer) => void;

interface ScreenSession {
  ownerId: string;
  profile: ScreenProfile;
  monitor: number;
  isTunnel: boolean;
  rect: { x: number; y: number; width: number; height: number };
  outW: number;
  outH: number;
  mjpegFps: number;
  qscale: number;
  targetFps: number;
  h264BitrateKbps: number;
  h264Codec: 'h264_amf' | 'libx264';
  h264Tier: MjpegCaptureMode;
  hasH264Frame: boolean;
  h264StartupTimer: NodeJS.Timeout | null;
  h264: H264StreamEncoder;
  ddaOutputIdx: number | null;
  captureTier: MjpegCaptureMode;
  hasFrame: boolean;
  startupTimer: NodeJS.Timeout | null;
  fallbackTimer: NodeJS.Timeout | null;
  webrtcLive: boolean;
  mjpeg: MjpegEncoder;
  worker: Worker | null;
  audio: AudioLoopbackStreamer | null;
  webrtc: WebRtcScreenSession | null;
}

export function profileBitrateKbps(profile: ScreenProfile): number {
  switch (profile) {
    case '720p30': return 2500;
    case '720p60': return 4000;
    case '1080p30': return 3800;
    case '1080p60': return 5500;
    default: return 3000;
  }
}

export class ScreenServer {
  private cfg: Config;
  private bind: Win32Bindings;
  private session: ScreenSession | null = null;
  private statusCb: StatusCb | null = null;
  private frameCb: FrameCb | null = null;
  private audioCb: AudioCb | null = null;
  private audioEnabled = true;
  private idCounter = 0;
  private currentMonitor = 1;

  constructor(cfg: Config, bind: Win32Bindings) {
    this.cfg = cfg;
    this.bind = bind;
    warmUpCaptureCapabilities();
  }

  setStatusCb(cb: StatusCb): void {
    this.statusCb = cb;
  }

  setFrameCb(cb: FrameCb): void {
    this.frameCb = cb;
  }

  setAudioCb(cb: AudioCb): void {
    this.audioCb = cb;
  }

  setAudioEnabled(enabled: boolean): void {
    this.audioEnabled = enabled;
    const s = this.session;
    if (!s) return;
    if (enabled) {
      if (!s.audio) {
        const ownerId = s.ownerId;
        s.audio = new AudioLoopbackStreamer((pkt) => {
          if (this.session && this.session.ownerId === ownerId && this.audioEnabled) {
            this.audioCb?.(ownerId, pkt);
          }
        });
      }
      s.audio.start();
      info('screen: PC loopback audio enabled for Screen Share');
    } else {
      s.audio?.stop();
      info('screen: PC loopback audio muted for Screen Share');
    }
  }

  isRunning(): boolean {
    return this.session !== null;
  }

  activeMonitor(): number {
    return this.session?.monitor ?? this.currentMonitor;
  }

  private lastCursor = { nx: 0.5, ny: 0.5, visible: false };

  /** Normalized cursor position (0..1) within the streamed display, respecting Windows CURSOR_SHOWING visibility. */
  cursorNorm(): { nx: number; ny: number; visible: boolean } {
    const s = this.session;
    if (!s) return { nx: this.lastCursor.nx, ny: this.lastCursor.ny, visible: false };

    let isShowing = true;
    try {
      const ci = Buffer.alloc(24);
      ci.writeUInt32LE(24, 0); // cbSize = 24 on x64
      const ok = Number(this.bind.GetCursorInfo(ci));
      if (ok !== 0) {
        const flags = ci.readUInt32LE(4);
        isShowing = (flags & 1) !== 0; // CURSOR_SHOWING = 0x00000001
      }
    } catch {
      // fallback best-effort
    }

    const pt = Buffer.alloc(8);
    const hasPos = Boolean(this.bind.GetCursorPos(pt));
    if (hasPos) {
      const x = pt.readInt32LE(0), y = pt.readInt32LE(4);
      const nx = (x - s.rect.x) / Math.max(1, s.rect.width);
      const ny = (y - s.rect.y) / Math.max(1, s.rect.height);
      const inBounds = nx >= 0 && nx <= 1 && ny >= 0 && ny <= 1;
      this.lastCursor = { nx: Math.max(0, Math.min(1, nx)), ny: Math.max(0, Math.min(1, ny)), visible: isShowing && inBounds };
      return this.lastCursor;
    }

    this.lastCursor.visible = false;
    return this.lastCursor;
  }

  addRemoteCandidate(candidate: string, mid = '0'): void {
    this.session?.webrtc?.addRemoteCandidate(candidate, mid);
  }

  private ensureCursorInRect(rect: { x: number; y: number; width: number; height: number }): void {
    try {
      const pt = Buffer.alloc(8);
      const hasPos = Number(this.bind.GetCursorPos(pt)) !== 0;
      const curX = hasPos ? pt.readInt32LE(0) : -99999;
      const curY = hasPos ? pt.readInt32LE(4) : -99999;
      const insideTarget =
        curX >= rect.x &&
        curX < rect.x + rect.width &&
        curY >= rect.y &&
        curY < rect.y + rect.height;
      if (!insideTarget) {
        const centerX = Math.round(rect.x + rect.width / 2);
        const centerY = Math.round(rect.y + rect.height / 2);
        this.bind.SetCursorPos(centerX, centerY);
      }
    } catch {
      // ignore
    }
  }

  async start(
    profile: ScreenProfile,
    offerSdp: string,
    ownerId: string,
    monitorIndex?: number | string,
    isTunnel?: boolean,
  ): Promise<{ ok: boolean; detail: string; answerSdp?: string }> {
    let monIdx = this.currentMonitor;
    if (typeof monitorIndex === 'number' && Number.isFinite(monitorIndex)) {
      monIdx = monitorIndex;
    } else if (typeof monitorIndex === 'string') {
      const parsed = parseInt(monitorIndex.replace(/\D/g, ''), 10);
      if (Number.isFinite(parsed) && parsed > 0) monIdx = parsed;
    }
    this.currentMonitor = monIdx;
    const onTunnel = Boolean(isTunnel);

    if (
      this.session &&
      this.session.ownerId === ownerId &&
      this.session.profile === profile &&
      this.session.monitor === monIdx &&
      this.session.isTunnel === onTunnel &&
      (this.session.mjpeg.isRunning() || this.session.h264.isRunning()) &&
      !offerSdp
    ) {
      this.statusCb?.('running', profile, this.session.webrtcLive ? 'webrtc-h264' : 'mjpeg', monIdx);
      return { ok: true, detail: 'already-running' };
    }

    if (this.session) {
      this.stop('replacing previous session (new screen_start)');
    }
    attachToInteractiveDesktop(this.bind);
    const dims = profileDims(profile);
    const monitors = GdiCapture.listMonitors(this.bind);
    const targetMon = monitors.find((m) => m.index === monIdx) ?? monitors[0] ?? {
      index: 1,
      label: 'Primary',
      x: 0,
      y: 0,
      width: Number(this.bind.GetSystemMetrics(0)) || 1920,
      height: Number(this.bind.GetSystemMetrics(1)) || 1080,
      primary: true,
    };

    const native = {
      x: targetMon.x,
      y: targetMon.y,
      width: targetMon.width,
      height: targetMon.height,
    };

    this.ensureCursorInRect(native);

    this.idCounter += 1;
    const peerId = `nuvio-screen-${Date.now()}-${this.idCounter}`;

    let ddaPromise: Promise<{ supported: boolean; outputs: Array<{ idx: number; width: number; height: number }> }> | null = null;
    if (monIdx !== 0) {
      ddaPromise = probeDdaOutputs();
    }
    const h264CodecPromise = !onTunnel && offerSdp.trim() ? resolveH264StreamCodec() : Promise.resolve<'h264_amf' | 'libx264'>('libx264');

    let webrtc: WebRtcScreenSession | null = null;
    let sessionRef: ScreenSession | null = null;
    if (!onTunnel && offerSdp && offerSdp.trim()) {
      try {
        webrtc = await WebRtcScreenSession.create(peerId, {
          offerSdp,
          fps: dims.fps,
          width: dims.width,
          height: dims.height,
          portRangeBegin: this.cfg.screen.portRangeBegin,
          portRangeEnd: this.cfg.screen.portRangeEnd,
          onTrackOpen: () => {
            const cur = sessionRef;
            if (!cur || this.session !== cur) return;
            // Track open: re-arm fallback timeout while waiting for the first decoded frame on the client
            if (cur.fallbackTimer) {
              clearTimeout(cur.fallbackTimer);
            }
            if (!cur.webrtcLive && !cur.mjpeg.isRunning()) {
              cur.fallbackTimer = setTimeout(() => {
                cur.fallbackTimer = null;
                if (this.session === cur && !cur.webrtcLive && !cur.mjpeg.isRunning()) {
                  warn('screen: WebRTC had no confirmed frame after track open; enabling MJPEG fallback');
                  this.ensureMjpegFallback(cur);
                }
              }, 1500);
              cur.fallbackTimer.unref?.();
            }
          },
          onDisconnected: (st) => {
            const cur = sessionRef;
            if (!cur || this.session !== cur || !cur.webrtc) return;
            cur.webrtcLive = false;
            if (!cur.mjpeg.isRunning() && !cur.fallbackTimer && (st === 'failed' || st === 'disconnected')) {
              warn(`screen: WebRTC ${st}; enabling MJPEG fallback`);
              this.ensureMjpegFallback(cur);
            }
          },
        });
      } catch (err) {
        warn(`screen: WebRTC unavailable (${(err as Error).message}); falling back to direct WS MJPEG stream`);
        webrtc = null;
      }
    }

    this.statusCb?.('starting', profile, undefined, monIdx);

    const maxW = profile.startsWith('1080p') ? 1920 : 1280;
    const maxH = profile.startsWith('1080p') ? 1080 : 800;
    const ratio = Math.min(maxW / native.width, maxH / native.height, 1);
    const outW = Math.max(2, Math.round(native.width * ratio) & ~1);
    const outH = Math.max(2, Math.round(native.height * ratio) & ~1);
    const mjpegFps = onTunnel ? Math.min(dims.fps, profile.endsWith('60') ? 45 : 30) : dims.fps;
    const qscale = onTunnel ? 6 : 5;

    let ddaOutputIdx: number | null = null;
    if (ddaPromise) {
      const dda = await ddaPromise;
      if (dda.supported) {
        const matches = dda.outputs.filter((o) => o.width === native.width && o.height === native.height);
        if (matches.length === 1) {
          ddaOutputIdx = matches[0].idx;
        }
      }
    }

    const initialTier: MjpegCaptureMode = ddaOutputIdx !== null ? 'ddagrab' : 'gdigrab';
    const h264Codec = await h264CodecPromise;

    let audio: AudioLoopbackStreamer | null = null;
    if (this.audioEnabled) {
      audio = new AudioLoopbackStreamer((pkt) => {
        if (this.session && this.session.ownerId === ownerId && this.audioEnabled) {
          this.audioCb?.(ownerId, pkt);
        }
      });
      audio.start();
    }

    const session: ScreenSession = {
      ownerId,
      profile,
      monitor: monIdx,
      isTunnel: onTunnel,
      rect: native,
      outW,
      outH,
      mjpegFps,
      qscale,
      targetFps: dims.fps,
      h264BitrateKbps: profileBitrateKbps(profile),
      h264Codec,
      h264Tier: initialTier,
      hasH264Frame: false,
      h264StartupTimer: null,
      h264: new H264StreamEncoder(),
      ddaOutputIdx,
      captureTier: initialTier,
      hasFrame: false,
      startupTimer: null,
      fallbackTimer: null,
      webrtcLive: false,
      mjpeg: new MjpegEncoder(),
      worker: null,
      audio,
      webrtc,
    };
    sessionRef = session;
    this.session = session;

    let encDetail: string;
    if (webrtc) {
      encDetail = this.launchH264Tier(session, initialTier, h264Codec);
      // Enable MJPEG fallback only if WebRTC does not confirm a decoded frame within 1500ms
      session.fallbackTimer = setTimeout(() => {
        session.fallbackTimer = null;
        if (this.session === session && !session.webrtcLive && !session.mjpeg.isRunning()) {
          warn('screen: WebRTC still has no confirmed frame after 1500ms; enabling MJPEG fallback');
          this.ensureMjpegFallback(session);
        }
      }, 1500);
      session.fallbackTimer.unref?.();
      this.statusCb?.('running', profile, 'webrtc-h264', monIdx);
    } else {
      encDetail = this.launchCaptureTier(session, initialTier);
      this.statusCb?.('running', profile, 'mjpeg', monIdx);
    }
    info(`screen: streaming ${profile} monitor=${monIdx} active (${native.width}x${native.height}@(${native.x},${native.y}) -> ${outW}x${outH}, ${encDetail})`);
    return { ok: true, detail: encDetail, answerSdp: webrtc?.answer.sdp };
  }

  /** Android client confirms SurfaceViewRenderer decoded and displayed a real H.264 frame -> stops MJPEG TCP stream. */
  setWebRtcLive(ownerId: string, live: boolean): void {
    const s = this.session;
    if (!s || s.ownerId !== ownerId) return;
    if (live) {
      if (s.fallbackTimer) {
        clearTimeout(s.fallbackTimer);
        s.fallbackTimer = null;
      }
      if (!s.webrtcLive) {
        s.webrtcLive = true;
        this.stopMjpegOnly(s);
        this.statusCb?.('running', s.profile, 'webrtc-h264', s.monitor);
        info('screen: WebRTC H.264 live on client (MJPEG fallback paused; TCP channel free)');
      }
    } else if (s.webrtcLive) {
      s.webrtcLive = false;
      this.ensureMjpegFallback(s);
    }
  }

  private ensureMjpegFallback(session: ScreenSession): void {
    if (this.session !== session || session.mjpeg.isRunning()) return;
    const detail = this.launchCaptureTier(session, session.captureTier);
    this.statusCb?.('running', session.profile, 'mjpeg', session.monitor);
    info(`screen: MJPEG fallback active (${detail})`);
  }

  private launchH264Tier(session: ScreenSession, tier: MjpegCaptureMode, codec: 'h264_amf' | 'libx264'): string {
    this.stopH264Only(session);
    session.h264Tier = tier;
    session.h264Codec = codec;
    session.hasH264Frame = false;

    const encRes = session.h264.start(
      {
        mode: tier,
        codec,
        width: session.rect.width,
        height: session.rect.height,
        srcX: session.rect.x,
        srcY: session.rect.y,
        ddaOutputIdx: session.ddaOutputIdx ?? 0,
        fps: session.targetFps,
        scaleTo: { width: session.outW, height: session.outH },
        bitrateKbps: session.h264BitrateKbps,
      },
      (chunk) => {
        if (this.session !== session) return;
        if (!session.hasH264Frame) {
          session.hasH264Frame = true;
          if (session.h264StartupTimer) {
            clearTimeout(session.h264StartupTimer);
            session.h264StartupTimer = null;
          }
        }
        session.webrtc?.feedAnnexBChunk(chunk);
      },
      (reason) => {
        if (this.session !== session) return;
        this.fallbackH264Capture(session, reason);
      },
    );

    if (tier !== 'pipe') {
      session.h264StartupTimer = setTimeout(() => {
        if (this.session === session && !session.hasH264Frame) {
          this.fallbackH264Capture(session, `${tier}/${codec} produced no frames after 1.5s`);
        }
      }, 1500);
      session.h264StartupTimer.unref?.();
    } else if (!session.worker) {
      this.startWorkerCapture(session);
    }

    return `${encRes.detail} (mon=${session.monitor}, lan)`;
  }

  private fallbackH264Capture(session: ScreenSession, reason: string): void {
    if (this.session !== session) return;
    if (session.h264Codec === 'h264_amf') {
      warn(`screen: H.264 AMF failed (${reason}); switching to libx264`);
      this.launchH264Tier(session, session.h264Tier, 'libx264');
      return;
    }
    if (session.h264Tier === 'ddagrab') {
      warn(`screen: H.264 ddagrab failed (${reason}); switching to gdigrab`);
      this.launchH264Tier(session, 'gdigrab', 'libx264');
      return;
    }
    if (session.h264Tier === 'gdigrab') {
      warn(`screen: H.264 gdigrab failed (${reason}); switching to GDI Worker`);
      this.launchH264Tier(session, 'pipe', 'libx264');
      return;
    }
    warn(`screen: H.264 stopped (${reason}); ensuring MJPEG fallback`);
    this.stopH264Only(session);
    this.ensureMjpegFallback(session);
  }

  private launchCaptureTier(session: ScreenSession, tier: MjpegCaptureMode): string {
    this.stopMjpegOnly(session);
    session.captureTier = tier;
    session.hasFrame = false;

    const encRes = session.mjpeg.start(
      {
        mode: tier,
        width: session.rect.width,
        height: session.rect.height,
        srcX: session.rect.x,
        srcY: session.rect.y,
        ddaOutputIdx: session.ddaOutputIdx ?? 0,
        fps: session.mjpegFps,
        scaleTo: { width: session.outW, height: session.outH },
        qscale: session.qscale,
      },
      (jpg) => {
        if (this.session !== session) return;
        if (!session.hasFrame) {
          session.hasFrame = true;
          if (session.startupTimer) {
            clearTimeout(session.startupTimer);
            session.startupTimer = null;
          }
        }
        this.frameCb?.(
          session.ownerId,
          jpg,
          session.mjpeg.outWidth,
          session.mjpeg.outHeight,
          session.monitor,
          session.mjpeg.isDirectCapture,
        );
      },
      (reason) => {
        if (this.session !== session) return;
        this.fallbackCapture(session, reason);
      },
    );

    if (tier !== 'pipe') {
      session.startupTimer = setTimeout(() => {
        if (this.session === session && !session.hasFrame) {
          this.fallbackCapture(session, `${tier} produced no frames after 1.5s`);
        }
      }, 1500);
      session.startupTimer.unref?.();
    } else if (!session.worker) {
      this.startWorkerCapture(session);
    }

    return `${encRes.detail} (mon=${session.monitor}${session.isTunnel ? ', tunnel-opt' : ', lan'})`;
  }

  private startWorkerCapture(session: ScreenSession): void {
    const frameBytes = session.rect.width * session.rect.height * 4;
    const sab = new SharedArrayBuffer(frameBytes * 2);
    const slots = [
      Buffer.from(sab, 0, frameBytes),
      Buffer.from(sab, frameBytes, frameBytes),
    ];

    const worker = new Worker(path.join(__dirname, 'captureWorker.js'), {
      workerData: {
        width: session.rect.width,
        height: session.rect.height,
        srcX: session.rect.x,
        srcY: session.rect.y,
        fps: session.mjpegFps,
        frameBytes,
        sab,
      },
    });
    session.worker = worker;

    worker.on('message', (msg: { type?: string; slot?: number; detail?: string }) => {
      if (this.session !== session) return;
      if (msg.type === 'frame' && typeof msg.slot === 'number') {
        const buf = slots[msg.slot];
        if (buf) {
          if (session.h264.isRunning()) session.h264.writeFrame(buf);
          if (session.mjpeg.isRunning()) session.mjpeg.writeFrame(buf);
        }
      } else if (msg.type === 'error') {
        warn(`screen: captureWorker failed: ${msg.detail ?? 'error'}`);
        this.stop('captureWorker failed');
      }
    });
    worker.on('error', (err) => {
      if (this.session !== session) return;
      const msg = err instanceof Error ? err.message : String(err);
      warn(`screen: captureWorker error: ${msg}`);
      this.stop('captureWorker error');
    });
  }

  private fallbackCapture(session: ScreenSession, reason: string): void {
    if (this.session !== session) return;
    if (session.captureTier === 'ddagrab') {
      warn(`screen: ddagrab failed (${reason}); switching to native gdigrab`);
      this.launchCaptureTier(session, 'gdigrab');
      return;
    }
    if (session.captureTier === 'gdigrab') {
      warn(`screen: gdigrab failed (${reason}); switching to off-main-thread GDI Worker`);
      this.launchCaptureTier(session, 'pipe');
      return;
    }
    warn(`screen: worker-GDI encoder exited (${reason})`);
    if (!session.h264.isRunning()) {
      this.stop('mjpeg encoder stopped');
    }
  }

  private stopH264Only(session: ScreenSession): void {
    if (session.h264StartupTimer) {
      clearTimeout(session.h264StartupTimer);
      session.h264StartupTimer = null;
    }
    session.h264.stop();
  }

  private stopMjpegOnly(session: ScreenSession): void {
    if (session.startupTimer) {
      clearTimeout(session.startupTimer);
      session.startupTimer = null;
    }
    session.mjpeg.stop();
  }

  private stopSessionCaptureOnly(session: ScreenSession): void {
    if (session.fallbackTimer) {
      clearTimeout(session.fallbackTimer);
      session.fallbackTimer = null;
    }
    this.stopH264Only(session);
    this.stopMjpegOnly(session);
    const w = session.worker;
    session.worker = null;
    if (w) {
      try {
        w.postMessage('stop');
        setTimeout(() => {
          void w.terminate().catch(() => undefined);
        }, 250);
      } catch {
        void w.terminate().catch(() => undefined);
      }
    }
  }

  /** Stops immediately (Screen tab closed or WebSocket disconnected). */
  stop(reason: string): boolean {
    const s = this.session;
    if (!s) return false;
    this.session = null;
    this.stopSessionCaptureOnly(s);
    s.audio?.stop();
    s.webrtc?.close();
    this.emit('stopped', s.profile, reason);
    info(`screen: stopped (${reason})`);
    return true;
  }

  stopIfOwner(ownerId: string): boolean {
    if (this.session && ownerId && this.session.ownerId === ownerId) {
      this.stop('client disconnected');
      return true;
    }
    return false;
  }

  private emit(status: 'starting' | 'running' | 'stopped' | 'error', profile?: ScreenProfile, detail?: string): void {
    this.statusCb?.(status, profile, detail);
  }
}

