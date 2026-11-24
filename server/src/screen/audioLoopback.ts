import { spawn, type ChildProcessByStdio } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import type { Readable } from 'node:stream';

/**
 * Captures Windows system loopback audio via bin/nuvio-audio-capture.exe
 * Outputs 24,000 Hz, 16-bit signed PCM, Stereo (1920 bytes per 20ms chunk).
 * Prepends 4-byte header:
 *   [0] = 0x06 (Audio PCM packet)
 *   [1] = 0x01 (Format: 24kHz Stereo s16le)
 *   [2] = 0x01 (480 samples high byte -> 0x01E0)
 *   [3] = 0xE0 (480 samples low byte)
 */
export class AudioLoopbackStreamer {
  private proc: ChildProcessByStdio<null, Readable, Readable> | null = null;
  private running = false;
  private remainder: Buffer = Buffer.alloc(0);
  private readonly onPacket: (packet: Buffer) => void;
  private static readonly CHUNK_BYTES = 1920; // 480 stereo samples * 4 bytes (20ms @ 24kHz)

  constructor(onPacket: (packet: Buffer) => void) {
    this.onPacket = onPacket;
  }

  public isRunning(): boolean {
    return this.running;
  }

  private static resolveHelperPath(): string {
    const byDir = path.resolve(__dirname, '..', '..', 'bin', 'nuvio-audio-capture.exe');
    if (fs.existsSync(byDir)) return byDir;
    return path.resolve(process.cwd(), 'bin', 'nuvio-audio-capture.exe');
  }

  public start(): void {
    if (this.running) return;
    const binPath = AudioLoopbackStreamer.resolveHelperPath();
    if (!fs.existsSync(binPath)) {
      // eslint-disable-next-line no-console
      console.warn(`[nuvio-remote] Audio capture helper not found at ${binPath}`);
      return;
    }

    this.running = true;
    this.remainder = Buffer.alloc(0);

    try {
      const child = spawn(binPath, [], {
        stdio: ['ignore', 'pipe', 'pipe'],
        windowsHide: true,
      });
      this.proc = child;

      child.stdout.on('data', (chunk: Buffer) => {
        if (!this.running || this.proc !== child) return;
        this.consumePcm(chunk);
      });

      child.stderr.on('data', () => {
        // ignore stderr
      });

      child.on('error', () => {
        if (this.proc !== child) return;
        this.running = false;
        this.proc = null;
      });

      child.on('close', () => {
        if (this.proc !== child) return;
        this.running = false;
        this.proc = null;
      });
    } catch {
      this.running = false;
      this.proc = null;
    }
  }

  public stop(): void {
    const wasActive = this.running || this.proc !== null;
    this.running = false;
    this.remainder = Buffer.alloc(0);
    const proc = this.proc;
    this.proc = null;
    if (proc) {
      try {
        proc.kill();
      } catch {
        // ignore
      }
    }
    if (!wasActive) return;
    // Restore PC speaker audio only if capture was active (helper preserves user pre-mute state)
    const binPath = AudioLoopbackStreamer.resolveHelperPath();
    if (fs.existsSync(binPath)) {
      try {
        const unmuter = spawn(binPath, ['--unmute'], {
          stdio: 'ignore',
          windowsHide: true,
        });
        unmuter.unref();
      } catch {
        // ignore
      }
    }
  }

  private consumePcm(chunk: Buffer): void {
    const combined =
      this.remainder.length > 0 ? Buffer.concat([this.remainder, chunk]) : chunk;

    let offset = 0;
    const step = AudioLoopbackStreamer.CHUNK_BYTES;

    while (offset + step <= combined.length) {
      const packet = Buffer.allocUnsafe(4 + step);
      packet[0] = 0x06;
      packet[1] = 0x01; // 24000 Hz, 16-bit PCM Stereo
      packet[2] = 0x01; // 480 samples (0x01E0)
      packet[3] = 0xe0;
      combined.copy(packet, 4, offset, offset + step);
      this.onPacket(packet);
      offset += step;
    }

    if (offset < combined.length) {
      this.remainder = Buffer.from(combined.subarray(offset));
    } else {
      this.remainder = Buffer.alloc(0);
    }
  }
}
