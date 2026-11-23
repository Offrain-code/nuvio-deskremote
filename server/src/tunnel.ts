import { spawn, type ChildProcess } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { info, warn } from './logger';

export class TunnelManager {
  private proc: ChildProcess | null = null;
  private url: string | null = null;
  private stopped = false;
  private restartTimer: NodeJS.Timeout | null = null;
  private readonly port: number;
  private readonly onUrlCallback: (url: string) => void;

  constructor(port: number, onUrl: (url: string) => void) {
    this.port = port;
    this.onUrlCallback = onUrl;
  }

  public getUrl(): string | null {
    return this.url;
  }

  public start(): void {
    this.stopped = false;
    this.spawnTunnel();
  }

  public stop(): void {
    this.stopped = true;
    if (this.restartTimer) {
      clearTimeout(this.restartTimer);
      this.restartTimer = null;
    }
    if (this.proc) {
      try {
        this.proc.kill();
      } catch {
        // ignore
      }
      this.proc = null;
    }
  }

  private resolveCloudflaredExe(): string {
    const localAppData = process.env.LOCALAPPDATA ?? '';
    const programFiles = process.env.ProgramFiles ?? 'C:\\Program Files';
    const programFilesX86 = process.env['ProgramFiles(x86)'] ?? 'C:\\Program Files (x86)';
    const candidates = [
      path.resolve(process.cwd(), 'bin', 'cloudflared.exe'),
      localAppData ? path.join(localAppData, 'Microsoft', 'WinGet', 'Links', 'cloudflared.exe') : '',
      path.join(programFiles, 'cloudflared', 'cloudflared.exe'),
      path.join(programFilesX86, 'cloudflared', 'cloudflared.exe'),
    ];
    for (const candidate of candidates) {
      if (candidate && fs.existsSync(candidate)) {
        return candidate;
      }
    }
    return 'cloudflared';
  }

  private spawnTunnel(): void {
    if (this.stopped) return;

    const exe = this.resolveCloudflaredExe();

    try {
      const child = spawn(
        exe,
        ['tunnel', '--url', `http://127.0.0.1:${this.port}`, '--no-autoupdate'],
        {
          stdio: ['ignore', 'pipe', 'pipe'],
          windowsHide: true,
        },
      );
      this.proc = child;

      const parseChunk = (chunk: Buffer) => {
        const text = chunk.toString('utf8');
        const match = text.match(/https:\/\/[a-z0-9-]+\.trycloudflare\.com/i);
        if (match && match[0]) {
          const foundUrl = match[0].toLowerCase();
          if (this.url !== foundUrl) {
            this.url = foundUrl;
            info(`tunnel: Remote Tunnel active: ${foundUrl}`);
            this.onUrlCallback(foundUrl);
          }
        }
      };

      child.stdout?.on('data', parseChunk);
      child.stderr?.on('data', parseChunk);

      child.on('error', (err) => {
        const errCode = (err as NodeJS.ErrnoException).code;
        if (errCode === 'ENOENT') {
          this.stopped = true;
          warn('tunnel: cloudflared not found in server/bin or PATH (install Cloudflare.cloudflared to enable Remote Access)');
          return;
        }
        warn(`tunnel: cloudflared error: ${err.message}`);
      });

      child.on('close', (code) => {
        this.proc = null;
        this.url = null;
        if (!this.stopped) {
          warn(`tunnel: cloudflared exited (code=${code ?? '?'}); restarting in 5s`);
          this.restartTimer = setTimeout(() => this.spawnTunnel(), 5000);
        }
      });
    } catch (err) {
      warn(`tunnel: failed to start cloudflared: ${(err as Error).message}`);
    }
  }
}
