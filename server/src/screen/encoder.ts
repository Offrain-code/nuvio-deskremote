import { spawn, spawnSync, ChildProcess, ChildProcessWithoutNullStreams } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
// eslint-disable-next-line @typescript-eslint/no-var-requires
const ffmpegInstaller = require('@ffmpeg-installer/ffmpeg') as { path: string; version: string };

/**
 * H.264 encoder (BGRA in -> Annex-B out) for the WebRTC packetizer.
 * - Hardware (h264_mf / Media Foundation) when supported by the FFmpeg build;
 * - Fallback to libx264 (ultrafast/zerolatency).
 * Output: Annex-B chunks; NAL fragmentation is handled by H264RtpPacketizer.
 */

export type EncodedCb = (chunk: Buffer) => void;

interface EncoderOpts {
  width: number;   // Input width (native desktop)
  height: number;
  fps: number;
  scaleTo: { width: number; height: number };
  bitrateKbps: number;
  gopSeconds: number;
  preferred: 'auto' | 'h264_mf' | 'libx264';
}

export class H264Encoder {
  private proc: ChildProcessWithoutNullStreams | null = null;
  private annexB: EncodedCb | null = null;
  private codecUsed = '';
  private stderrTail = '';

  async resolveCodec(preferred: EncoderOpts['preferred']): Promise<string> {
    if (preferred !== 'auto') return preferred;
    try {
      const stdout = await new Promise<string>((resolve, reject) => {
        const p = spawn(ffmpegInstaller.path, ['-hide_banner', '-encoders'], { windowsHide: true });
        let out = '';
        p.stdout?.on('data', (d: Buffer) => (out += d.toString()));
        p.on('error', reject);
        p.on('close', () => resolve(out));
        setTimeout(() => reject(new Error('encoder list probe timeout')), 10000);
      });
      return /h264_mf/.test(stdout) ? 'h264_mf' : 'libx264';
    } catch {
      return 'libx264';
    }
  }

  async start(opts: EncoderOpts, onAnnexB: EncodedCb): Promise<{ ok: boolean; detail: string }> {
    this.stop();
    const codec = await this.resolveCodec(opts.preferred);
    const sw = Math.max(2, opts.scaleTo.width - (opts.scaleTo.width % 2));
    const sh = Math.max(2, opts.scaleTo.height - (opts.scaleTo.height % 2));
    const args = [
      '-hide_banner', '-loglevel', 'error',
      '-f', 'rawvideo', '-pix_fmt', 'bgra', '-s', `${opts.width}x${opts.height}`,
      '-r', String(opts.fps), '-i', 'pipe:0',
      '-vf', `scale=${sw}:${sh},format=yuv420p`,
    ];
    if (codec === 'h264_mf') {
      args.push('-c:v', 'h264_mf', '-b:v', `${opts.bitrateKbps}k`);
    } else {
      args.push(
        '-c:v', 'libx264',
        '-profile:v', 'baseline', '-level', '3.1', '-pix_fmt', 'yuv420p',
        '-preset', 'ultrafast', '-tune', 'zerolatency',
        '-x264-params', 'repeat-headers=1:annexb=1',
        '-g', String(Math.max(15, opts.fps)), '-keyint_min', String(Math.max(10, Math.floor(opts.fps / 2))),
        '-b:v', `${opts.bitrateKbps}k`, '-minrate', `${opts.bitrateKbps}k`,
        '-maxrate', `${Math.round(opts.bitrateKbps * 1.5)}k`, '-bufsize', `${opts.bitrateKbps * 2}k`,
        '-bf', '0',
      );
    }
    args.push('-f', 'h264', 'pipe:1');

    const proc = spawn(ffmpegInstaller.path, args, { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    proc.stdout?.on('data', (d: Buffer) => this.annexB?.(d));
    proc.stderr?.on('data', (d: Buffer) => {
      this.stderrTail = (this.stderrTail + d.toString()).slice(-400);
    });
    // Record early FFmpeg exit details (missing codec / launch error) for diagnostics
    proc.on('close', (code) => {
      if (this.proc === proc && code !== null && code !== 0) {
        this.diedWith = `${code}: ${this.stderrTail}`;
      }
    });
    this.proc = proc;
    this.annexB = onAnnexB;
    this.codecUsed = codec;
    return { ok: true, detail: `ffmpeg active codec=${codec} in=${opts.width}x${opts.height} out=${sw}x${sh}@${opts.fps}fps` };
  }

  get codec(): string {
    return this.codecUsed;
  }

  get died(): string | null {
    return this.diedWith;
  }

  private diedWith: string | null = null;

  writeFrame(bgra: Buffer): boolean {
    const proc = this.proc;
    if (!proc || !proc.stdin.writable) return false;
    try {
      proc.stdin.write(bgra);
      return true;
    } catch {
      return false; // stream closed; caller decides whether to stop
    }
  }

  isRunning(): boolean {
    return !!this.proc && !this.proc.killed && this.proc.stdin.writable;
  }

  stop(): void {
    const proc = this.proc;
    this.proc = null;
    if (!proc) return;
    try {
      proc.stdin.end();
      setTimeout(() => {
        try { proc.kill(); } catch { /* already exited */ }
      }, 500);
    } catch { /* already exited */ }
  }
}

let cachedFfmpegResolution: { path: string; supportsDdagrab: boolean } | null = null;

function findSystemFfmpeg(): string | null {
  const envPath = process.env.FFMPEG_PATH?.trim();
  if (envPath && fs.existsSync(envPath)) return envPath;

  const bundled = path.resolve(__dirname, '..', '..', 'bin', 'ffmpeg.exe');
  if (fs.existsSync(bundled)) return bundled;

  const localAppData = process.env.LOCALAPPDATA ?? '';
  const wingetLink = localAppData ? path.join(localAppData, 'Microsoft', 'WinGet', 'Links', 'ffmpeg.exe') : '';
  if (wingetLink && fs.existsSync(wingetLink)) return wingetLink;

  try {
    const res = spawnSync('where.exe', ['ffmpeg.exe'], { encoding: 'utf8', windowsHide: true, timeout: 2000 });
    if (res.status === 0 && res.stdout) {
      const first = res.stdout.split(/\r?\n/).map((s) => s.trim()).find((s) => s.length > 0 && fs.existsSync(s));
      if (first) return first;
    }
  } catch {
    // ignore
  }
  return null;
}

/** Prefers system/bundled FFmpeg with D3D11 Desktop Duplication (ddagrab), falling back to @ffmpeg-installer/ffmpeg. */
export function getFfmpegPath(): { path: string; supportsDdagrab: boolean } {
  if (cachedFfmpegResolution) return cachedFfmpegResolution;
  const sysPath = findSystemFfmpeg();
  if (sysPath) {
    cachedFfmpegResolution = { path: sysPath, supportsDdagrab: true };
    return cachedFfmpegResolution;
  }
  cachedFfmpegResolution = { path: ffmpegInstaller.path, supportsDdagrab: false };
  return cachedFfmpegResolution;
}

export interface DdaOutputInfo {
  idx: number;
  width: number;
  height: number;
}

let cachedDdaProbe: { at: number; supported: boolean; outputs: DdaOutputInfo[] } | null = null;
let cachedDdaInflight: Promise<{ supported: boolean; outputs: DdaOutputInfo[] }> | null = null;
let cachedH264Codec: 'h264_amf' | 'libx264' | null = null;

/** Warms up DXGI output and H.264 codec caches in the background on server boot. */
export function warmUpCaptureCapabilities(): void {
  void probeDdaOutputs().catch(() => undefined);
  void resolveH264StreamCodec().catch(() => undefined);
}

/**
 * Picks the lowest-latency H.264 encoder for WebRTC streaming:
 * `libx264` (`ultrafast` + `zerolatency` + `threads=1`) runs with 0 frames of internal buffering (~7ms/frame),
 * whereas `h264_amf` holds 1–2 frames in its async D3D11 queue (~53–105ms).
 */
export async function resolveH264StreamCodec(): Promise<'h264_amf' | 'libx264'> {
  cachedH264Codec = 'libx264';
  return cachedH264Codec;
}

/**
 * Probes available DXGI outputs for `ddagrab` in parallel.
 * Cached for 30s to avoid spawning extra processes when switching tabs or displays.
 */
export async function probeDdaOutputs(): Promise<{ supported: boolean; outputs: DdaOutputInfo[] }> {
  const now = Date.now();
  if (cachedDdaProbe && now - cachedDdaProbe.at < 30_000) {
    return { supported: cachedDdaProbe.supported, outputs: cachedDdaProbe.outputs };
  }
  if (cachedDdaInflight) {
    return cachedDdaInflight;
  }

  const ff = getFfmpegPath();
  if (!ff.supportsDdagrab) {
    cachedDdaProbe = { at: now, supported: false, outputs: [] };
    return { supported: false, outputs: [] };
  }

  cachedDdaInflight = (async () => {
    const probeOne = (idx: number) =>
      new Promise<{ idx: number; ok: boolean; w: number; h: number }>((resolve) => {
        const p = spawn(
          ff.path,
          ['-hide_banner', '-f', 'lavfi', '-i', `ddagrab=output_idx=${idx}:framerate=15`, '-frames:v', '1', '-f', 'null', '-'],
          { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] },
        );
        let errOut = '';
        let settled = false;
        const done = (ok: boolean, w = 0, h = 0) => {
          if (settled) return;
          settled = true;
          resolve({ idx, ok, w, h });
        };
        const timer = setTimeout(() => {
          try { p.kill(); } catch { /* ignore */ }
          done(false);
        }, 1400);
        timer.unref?.();
        p.stderr?.on('data', (d: Buffer) => {
          errOut += d.toString('utf8');
        });
        p.on('error', () => {
          clearTimeout(timer);
          done(false);
        });
        p.on('close', (code) => {
          clearTimeout(timer);
          const m = /Stream\s+#0:0:\s+Video:.*?,\s+(\d{3,5})x(\d{3,5})\b/.exec(errOut);
          if (code === 0 && m) {
            done(true, Number(m[1]), Number(m[2]));
          } else {
            done(false);
          }
        });
      });

    const results = await Promise.all([probeOne(0), probeOne(1), probeOne(2), probeOne(3)]);
    const outputs = results
      .filter((r) => r.ok)
      .sort((a, b) => a.idx - b.idx)
      .map((r) => ({ idx: r.idx, width: r.w, height: r.h }));
    const supported = outputs.length > 0;
    cachedDdaProbe = { at: Date.now(), supported, outputs };
    cachedDdaInflight = null;
    return { supported, outputs };
  })();

  return cachedDdaInflight;
}

export type MjpegCaptureMode = 'ddagrab' | 'gdigrab' | 'pipe';

/**
 * Ultra-low-latency MJPEG encoder for direct WebSocket fallback streaming.
 * - `ddagrab`: GPU D3D11 Desktop Duplication directly inside FFmpeg;
 * - `gdigrab`: Native Win32 capture inside FFmpeg (zero Node.js blocking);
 * - `pipe`: Raw BGRA frames fed over stdin from the off-main-thread Worker.
 */
export class MjpegEncoder {
  private proc: ChildProcess | null = null;
  private accum: Buffer = Buffer.alloc(0);
  private busy = false;
  private stderrTail = '';
  private mode: MjpegCaptureMode = 'pipe';
  private lastSentFrame: Buffer | null = null;
  private lastSentAt = 0;
  public outWidth = 1280;
  public outHeight = 720;
  public isDirectCapture = false;

  start(
    opts: {
      mode: MjpegCaptureMode;
      width: number;
      height: number;
      srcX?: number;
      srcY?: number;
      ddaOutputIdx?: number;
      fps: number;
      scaleTo: { width: number; height: number };
      qscale?: number;
    },
    onJpegFrame: (jpeg: Buffer) => void,
    onUnexpectedExit?: (detail: string) => void,
  ): { ok: boolean; detail: string } {
    this.stop();
    const sw = Math.max(2, opts.scaleTo.width - (opts.scaleTo.width % 2));
    const sh = Math.max(2, opts.scaleTo.height - (opts.scaleTo.height % 2));
    this.outWidth = sw;
    this.outHeight = sh;
    this.mode = opts.mode;
    this.stderrTail = '';
    this.lastSentFrame = null;
    this.lastSentAt = 0;
    const q = opts.qscale ?? 9;
    const ff = getFfmpegPath();

    const isUnscaled = sw === opts.width && sh === opts.height;
    let args: string[];
    let detail: string;
    if (opts.mode === 'ddagrab') {
      this.isDirectCapture = false;
      const ddaIdx = opts.ddaOutputIdx ?? 0;
      const vfChain = isUnscaled
        ? 'hwdownload,format=bgra,format=yuvj420p'
        : `hwdownload,format=bgra,scale=${sw}:${sh}:flags=lanczos,format=yuvj420p`;
      args = [
        '-hide_banner', '-loglevel', 'error',
        '-probesize', '32', '-analyzeduration', '0', '-fpsprobesize', '0',
        '-fflags', 'nobuffer', '-flags', 'low_delay',
        '-f', 'lavfi',
        '-i', `ddagrab=output_idx=${ddaIdx}:framerate=${opts.fps}:draw_mouse=0:dup_frames=1:allow_fallback=1`,
        '-vf', vfChain,
        '-fps_mode', 'passthrough',
        '-thread_type', 'slice', '-threads', '4',
        '-c:v', 'mjpeg', '-pix_fmt', 'yuvj420p', '-huffman', 'default', '-q:v', String(q),
        '-f', 'image2pipe', '-flush_packets', '1', 'pipe:1',
      ];
      detail = `mjpeg GPU-ddagrab[${ddaIdx}] ${sw}x${sh}@${opts.fps}fps (q=${q})`;
    } else if (opts.mode === 'gdigrab') {
      this.isDirectCapture = false;
      const vfChain = isUnscaled
        ? 'format=yuvj420p'
        : `scale=${sw}:${sh}:flags=lanczos,format=yuvj420p`;
      args = [
        '-hide_banner', '-loglevel', 'error',
        '-probesize', '32', '-analyzeduration', '0', '-fpsprobesize', '0',
        '-fflags', 'nobuffer', '-flags', 'low_delay',
        '-f', 'gdigrab',
        '-draw_mouse', '0',
        '-framerate', String(opts.fps),
        '-offset_x', String(opts.srcX ?? 0),
        '-offset_y', String(opts.srcY ?? 0),
        '-video_size', `${opts.width}x${opts.height}`,
        '-i', 'desktop',
        '-vf', vfChain,
        '-fps_mode', 'passthrough',
        '-thread_type', 'slice', '-threads', '4',
        '-c:v', 'mjpeg', '-pix_fmt', 'yuvj420p', '-huffman', 'default', '-q:v', String(q),
        '-f', 'image2pipe', '-flush_packets', '1', 'pipe:1',
      ];
      detail = `mjpeg native-gdigrab ${sw}x${sh}@${opts.fps}fps (q=${q})`;
    } else {
      this.isDirectCapture = false;
      const vfChain = isUnscaled
        ? 'format=yuvj420p'
        : `scale=${sw}:${sh}:flags=lanczos,format=yuvj420p`;
      args = [
        '-hide_banner', '-loglevel', 'error',
        '-probesize', '32', '-analyzeduration', '0', '-fpsprobesize', '0',
        '-fflags', 'nobuffer', '-flags', 'low_delay',
        '-f', 'rawvideo', '-pix_fmt', 'bgra', '-s', `${opts.width}x${opts.height}`,
        '-r', String(opts.fps), '-i', 'pipe:0',
        '-vf', vfChain,
        '-fps_mode', 'passthrough',
        '-thread_type', 'slice', '-threads', '4',
        '-c:v', 'mjpeg', '-pix_fmt', 'yuvj420p', '-huffman', 'default', '-q:v', String(q),
        '-f', 'image2pipe', '-flush_packets', '1', 'pipe:1',
      ];
      detail = `mjpeg worker-GDI ${sw}x${sh}@${opts.fps}fps (q=${q})`;
    }

    const stdio: Array<'pipe' | 'ignore'> = opts.mode === 'pipe' ? ['pipe', 'pipe', 'pipe'] : ['ignore', 'pipe', 'pipe'];
    const proc = spawn(ff.path, args, { windowsHide: true, stdio });
    this.proc = proc;
    this.busy = false;

    proc.stdout?.on('data', (chunk: Buffer) => {
      if (this.proc !== proc) return;
      this.accum = this.accum.length === 0 ? chunk : Buffer.concat([this.accum, chunk]);
      this.extractJpegs(onJpegFrame);
    });
    proc.stderr?.on('data', (chunk: Buffer) => {
      this.stderrTail = (this.stderrTail + chunk.toString('utf8')).slice(-400);
    });
    proc.stdin?.on('error', () => { /* ignore EPIPE on stop */ });
    proc.on('error', (err) => {
      if (this.proc === proc) {
        this.proc = null;
        onUnexpectedExit?.(err.message);
      }
    });
    proc.on('close', (code) => {
      if (this.proc === proc) {
        this.proc = null;
        const msg = this.stderrTail.trim() || `exit code ${code ?? '?'}`;
        onUnexpectedExit?.(msg);
      }
    });

    return { ok: true, detail };
  }

  private static readonly SOI_MARKER = Buffer.from([0xff, 0xd8]);
  private static readonly EOI_MARKER = Buffer.from([0xff, 0xd9]);

  private extractJpegs(cb: (jpeg: Buffer) => void): void {
    let latestFrame: Buffer | null = null;
    while (this.accum.length >= 4) {
      const soi = this.accum.indexOf(MjpegEncoder.SOI_MARKER);
      if (soi < 0) {
        this.accum = Buffer.alloc(0);
        break;
      }
      if (soi > 0) {
        this.accum = this.accum.subarray(soi);
      }
      const eoi = this.accum.indexOf(MjpegEncoder.EOI_MARKER, 2);
      if (eoi < 0) {
        if (this.accum.length > 2 * 1024 * 1024) {
          this.accum = Buffer.alloc(0);
        }
        break;
      }
      const end = eoi + 2;
      latestFrame = Buffer.from(this.accum.subarray(0, end));
      this.accum = end === this.accum.length ? Buffer.alloc(0) : Buffer.from(this.accum.subarray(end));
    }
    // Drop stale frames queued in the pipe; emit only the freshest frame in real time
    // and skip resending identical frames on a static screen (keeping 1 keepalive/s)
    if (latestFrame) {
      const now = Date.now();
      const isDuplicate =
        this.lastSentFrame !== null &&
        this.lastSentFrame.length === latestFrame.length &&
        now - this.lastSentAt < 1000 &&
        this.lastSentFrame.equals(latestFrame);
      if (!isDuplicate) {
        this.lastSentFrame = latestFrame;
        this.lastSentAt = now;
        cb(latestFrame);
      }
    }
  }

  writeFrame(bgra: Buffer): boolean {
    const proc = this.proc;
    if (this.mode !== 'pipe' || !proc || !proc.stdin?.writable || this.busy) return false;
    try {
      const flushed = proc.stdin.write(bgra);
      if (!flushed) {
        this.busy = true;
        proc.stdin.once('drain', () => {
          this.busy = false;
        });
      }
      return true;
    } catch {
      return false;
    }
  }

  isRunning(): boolean {
    return !!this.proc && !this.proc.killed && this.proc.exitCode === null && (this.mode !== 'pipe' || Boolean(this.proc.stdin?.writable));
  }

  stop(): void {
    const proc = this.proc;
    this.proc = null;
    this.accum = Buffer.alloc(0);
    this.busy = false;
    this.lastSentFrame = null;
    this.lastSentAt = 0;
    if (!proc) return;
    try {
      proc.stdin?.end();
      setTimeout(() => {
        try { proc.kill(); } catch { /* ignore */ }
      }, 250);
    } catch { /* ignore */ }
  }
}

export interface H264StreamOpts {
  mode: MjpegCaptureMode;
  codec: 'h264_amf' | 'libx264';
  width: number;
  height: number;
  srcX?: number;
  srcY?: number;
  ddaOutputIdx?: number;
  fps: number;
  scaleTo: { width: number; height: number };
  bitrateKbps: number;
}

function buildH264CaptureInputArgs(opts: H264StreamOpts, pixFmt: 'nv12' | 'yuv420p'): { args: string[]; label: string; directCapture: boolean } {
  const sw = Math.max(2, opts.scaleTo.width - (opts.scaleTo.width % 2));
  const sh = Math.max(2, opts.scaleTo.height - (opts.scaleTo.height % 2));
  const isUnscaled = sw === opts.width && sh === opts.height;

  if (opts.mode === 'ddagrab') {
    const ddaIdx = opts.ddaOutputIdx ?? 0;
    const vfChain = isUnscaled
      ? `hwdownload,format=bgra,format=${pixFmt}`
      : `hwdownload,format=bgra,scale=${sw}:${sh}:flags=fast_bilinear,format=${pixFmt}`;
    return {
      directCapture: false,
      label: `GPU-ddagrab[${ddaIdx}]`,
      args: [
        '-hide_banner', '-loglevel', 'error',
        '-probesize', '32', '-analyzeduration', '0', '-fpsprobesize', '0',
        '-fflags', 'nobuffer', '-flags', 'low_delay',
        '-f', 'lavfi',
        '-i', `ddagrab=output_idx=${ddaIdx}:framerate=${opts.fps}:draw_mouse=0:dup_frames=1:allow_fallback=1`,
        '-vf', vfChain,
      ],
    };
  }

  if (opts.mode === 'gdigrab') {
    const vfChain = isUnscaled
      ? `format=${pixFmt}`
      : `scale=${sw}:${sh}:flags=fast_bilinear,format=${pixFmt}`;
    return {
      directCapture: false,
      label: 'native-gdigrab',
      args: [
        '-hide_banner', '-loglevel', 'error',
        '-probesize', '32', '-analyzeduration', '0', '-fpsprobesize', '0',
        '-fflags', 'nobuffer', '-flags', 'low_delay',
        '-f', 'gdigrab',
        '-draw_mouse', '0',
        '-framerate', String(opts.fps),
        '-offset_x', String(opts.srcX ?? 0),
        '-offset_y', String(opts.srcY ?? 0),
        '-video_size', `${opts.width}x${opts.height}`,
        '-i', 'desktop',
        '-vf', vfChain,
      ],
    };
  }

  const vfChain = isUnscaled
    ? `format=${pixFmt}`
    : `scale=${sw}:${sh}:flags=fast_bilinear,format=${pixFmt}`;
  return {
    directCapture: false,
    label: 'pipe-GDI',
    args: [
      '-hide_banner', '-loglevel', 'error',
      '-probesize', '32', '-analyzeduration', '0', '-fpsprobesize', '0',
      '-fflags', 'nobuffer', '-flags', 'low_delay',
      '-f', 'rawvideo', '-pix_fmt', 'bgra', '-s', `${opts.width}x${opts.height}`,
      '-r', String(opts.fps), '-i', 'pipe:0',
      '-vf', vfChain,
    ],
  };
}

/**
 * Real-time H.264 Annex-B encoder (`ddagrab` / `gdigrab` / `pipe`) supporting `h264_amf`
 * (AMD GPU ultra-low-latency) and `libx264` zerolatency for WebRTC RTP transport.
 */
export class H264StreamEncoder {
  private proc: ChildProcess | null = null;
  private busy = false;
  private stderrTail = '';
  private mode: MjpegCaptureMode = 'pipe';
  public outWidth = 1280;
  public outHeight = 720;
  public isDirectCapture = false;
  public codecUsed: 'h264_amf' | 'libx264' = 'h264_amf';

  start(
    opts: H264StreamOpts,
    onAnnexBChunk: (chunk: Buffer) => void,
    onUnexpectedExit?: (detail: string) => void,
  ): { ok: boolean; detail: string } {
    this.stop();
    const sw = Math.max(2, opts.scaleTo.width - (opts.scaleTo.width % 2));
    const sh = Math.max(2, opts.scaleTo.height - (opts.scaleTo.height % 2));
    this.outWidth = sw;
    this.outHeight = sh;
    this.mode = opts.mode;
    this.codecUsed = opts.codec;
    this.stderrTail = '';

    const ff = getFfmpegPath();
    const pixFmt = opts.codec === 'h264_amf' ? 'nv12' : 'yuv420p';
    const inputSpec = buildH264CaptureInputArgs(opts, pixFmt);
    this.isDirectCapture = inputSpec.directCapture;

    const maxRateKbps = Math.round(opts.bitrateKbps * 1.35);
    const bufSizeKbps = Math.max(400, Math.round(opts.bitrateKbps / 2));
    const gopSize = Math.max(30, opts.fps * 2);
    const codecArgs =
      opts.codec === 'h264_amf'
        ? [
            '-fps_mode', 'passthrough',
            '-c:v', 'h264_amf',
            '-usage', 'ultralowlatency',
            '-latency', 'true',
            '-quality', 'speed',
            '-async_depth', '1',
            '-profile:v', 'constrained_baseline',
            '-rc', 'vbr_latency',
            '-refs', '1',
            '-preencode', 'false',
            '-preanalysis', 'false',
            '-vbaq', 'false',
            '-high_motion_quality_boost_enable', 'false',
            '-filler_data', 'false',
            '-aud', 'false',
            '-g', String(gopSize),
            '-header_spacing', String(gopSize),
            '-forced_idr', 'true',
            '-b:v', `${opts.bitrateKbps}k`,
            '-maxrate', `${maxRateKbps}k`,
            '-bufsize', `${bufSizeKbps}k`,
            '-bf', '0',
          ]
        : [
            '-fps_mode', 'passthrough',
            '-c:v', 'libx264',
            '-profile:v', 'baseline',
            '-pix_fmt', 'yuv420p',
            '-preset', 'ultrafast',
            '-tune', 'zerolatency',
            '-threads', '1',
            '-x264-params', 'repeat-headers=1:annexb=1:ref=1:rc-lookahead=0:sync-lookahead=0:bframes=0:sliced-threads=0',
            '-g', String(gopSize),
            '-b:v', `${opts.bitrateKbps}k`,
            '-maxrate', `${maxRateKbps}k`,
            '-bufsize', `${bufSizeKbps}k`,
            '-bf', '0',
          ];

    const args = [
      ...inputSpec.args,
      ...codecArgs,
      '-fflags', 'nobuffer+flush_packets',
      '-avioflags', 'direct',
      '-flush_packets', '1',
      '-f', 'h264',
      'pipe:1',
    ];
    const proc = spawn(ff.path, args, {
      windowsHide: true,
      stdio: [opts.mode === 'pipe' ? 'pipe' : 'ignore', 'pipe', 'pipe'],
    });
    this.proc = proc;

    proc.stdout?.on('data', (chunk: Buffer) => {
      if (this.proc !== proc) return;
      onAnnexBChunk(chunk);
    });
    proc.stderr?.on('data', (d: Buffer) => {
      this.stderrTail = (this.stderrTail + d.toString('utf8')).slice(-500);
    });
    proc.on('error', (err) => {
      if (this.proc === proc) {
        this.proc = null;
        onUnexpectedExit?.(err.message);
      }
    });
    proc.on('close', (code) => {
      if (this.proc === proc) {
        this.proc = null;
        const msg = this.stderrTail.trim() || `exit code ${code ?? '?'}`;
        onUnexpectedExit?.(msg);
      }
    });

    const detail = `h264(${opts.codec}) ${inputSpec.label} ${sw}x${sh}@${opts.fps}fps (${opts.bitrateKbps}k)`;
    return { ok: true, detail };
  }

  writeFrame(bgra: Buffer): boolean {
    const proc = this.proc;
    if (this.mode !== 'pipe' || !proc || !proc.stdin?.writable || this.busy) return false;
    try {
      const flushed = proc.stdin.write(bgra);
      if (!flushed) {
        this.busy = true;
        proc.stdin.once('drain', () => {
          this.busy = false;
        });
      }
      return true;
    } catch {
      return false;
    }
  }

  isRunning(): boolean {
    return !!this.proc && !this.proc.killed && this.proc.exitCode === null && (this.mode !== 'pipe' || Boolean(this.proc.stdin?.writable));
  }

  stop(): void {
    const proc = this.proc;
    this.proc = null;
    this.busy = false;
    if (!proc) return;
    try {
      proc.stdin?.end();
      setTimeout(() => {
        try { proc.kill(); } catch { /* ignore */ }
      }, 250);
    } catch { /* ignore */ }
  }
}


