import * as fs from 'fs';
import { EventEmitter } from 'events';
import { error, info, warn } from './logger';

/**
 * Active tail of nuvio.log.
 * - Tracks file growth as well as truncation/rotation.
 * - Emits "line" and state-detection events for relevant `(Tag)` log entries.
 */
export class NuvioLogTail extends EventEmitter {
  private filePath: string;
  private enterRe: RegExp[];
  private exitRe: RegExp[];
  private fd: fs.promises.FileHandle | null = null;
  private lastSize = 0;
  private partial = '';
  private closed = false;
  private watcher: fs.FSWatcher | null = null;
  private poller: NodeJS.Timeout | null = null;
  private pumping = false;

  constructor(filePath: string, enterMarkers: string[], exitMarkers: string[]) {
    super();
    this.filePath = filePath;
    this.enterRe = compile(enterMarkers);
    this.exitRe = compile(exitMarkers);
  }

  start(): { ok: boolean; detail: string } {
    if (this.closed) return { ok: false, detail: 'tail closed' };
    if (!fs.existsSync(this.filePath)) {
      return { ok: false, detail: `file does not exist: ${this.filePath}` };
    }
    void this.open();
    this.watcher = fs.watch(this.filePath, { persistent: false }, () => this.onWatch());
    // NTFS delays fs.watch notifications for files held open by Nuvio: light polling ensures immediate Player detection
    this.poller = setInterval(() => this.onWatch(), 300);
    this.poller.unref?.();
    info(`logtail: watching ${this.filePath}`);
    return { ok: true, detail: this.filePath };
  }

  stop(): void {
    this.closed = true;
    this.watcher?.close();
    this.watcher = null;
    if (this.poller) clearInterval(this.poller);
    this.poller = null;
    void this.fd?.close().catch(() => undefined);
    this.fd = null;
  }

  private async open(): Promise<void> {
    try {
      this.fd = await fs.promises.open(this.filePath, 'r');
      const st = await this.fd.stat();
      // Read the last 24 KB on startup to immediately sync if Nuvio is already on Player/Detail/Home
      const tailLen = Math.min(st.size, 24 * 1024);
      if (tailLen > 0) {
        const buf = Buffer.alloc(tailLen);
        const { bytesRead } = await this.fd.read(buf, 0, tailLen, st.size - tailLen);
        const text = buf.subarray(0, bytesRead).toString('utf8');
        const lines = text.split(/\r?\n/);
        // Drop the first line if it starts mid-line
        if (tailLen < st.size && lines.length > 1) lines.shift();
        for (const l of lines) {
          this.handleLine(l, true);
        }
      }
      this.lastSize = st.size;
    } catch (err) {
      warn(`logtail: failed to open ${this.filePath}: ${(err as Error).message}`);
    }
  }

  private onWatch(): void {
    if (this.closed || !this.fd) return;
    setImmediate(() => {
      void this.pump();
    });
  }

  private async pump(): Promise<void> {
    const fd = this.fd;
    if (!fd || this.closed || this.pumping) return;
    this.pumping = true;
    try {
      const st = await fd.stat();
      if (st.size < this.lastSize) {
        // Truncated/rotated: reopen and read from offset 0
        info('logtail: log truncated or rotated; reopening');
        await fd.close().catch(() => undefined);
        this.fd = null;
        this.lastSize = 0;
        this.partial = '';
        this.fd = await fs.promises.open(this.filePath, 'r');
        return;
      }
      if (st.size === this.lastSize) return;
      const len = Math.min(st.size - this.lastSize, 1024 * 1024);
      const buf = Buffer.alloc(len);
      const { bytesRead } = await fd.read(buf, 0, len, this.lastSize);
      this.lastSize += bytesRead;
      this.emitLines(this.partial + buf.subarray(0, bytesRead).toString('utf8'));

      const st2 = await fd.stat();
      if (st2.size > this.lastSize) setImmediate(() => void this.pump()); // more data arrived while reading
    } catch (err) {
      warn(`logtail: pump failed: ${(err as Error).message}`);
    } finally {
      this.pumping = false;
    }
  }

  private emitLines(chunk: string): void {
    const lines = chunk.split(/\r?\n/);
    this.partial = lines.pop() ?? '';
    for (const line of lines) this.handleLine(line, false);
  }

  private handleLine(line: string, isInitialBootstrap = false): void {
    if (!line.trim()) return;
    let tag = '';
    const m = /\((\w+)\)/.exec(line);
    if (m) tag = m[1];
    if (!isInitialBootstrap && (tag === 'PlaybackStartTrace' || tag === 'BingeAdvance' || this.exitRe.some((re) => re.test(line)))) {
      this.emit('line', { tag, line });
    }
    if (!isInitialBootstrap && line.includes('trailer dismissed from the nav edge for')) {
      this.emit('nav_edge_dismissed', line);
    }
    for (const re of this.enterRe) {
      if (re.test(line)) {
        this.emit('player_enter', line);
        return;
      }
    }
    for (const re of this.exitRe) {
      if (re.test(line)) {
        this.emit('player_exit', line);
        return;
      }
    }
    if (
      line.includes('subtitle="Detalhes" type=Browsing') ||
      line.includes('(DetailHeroLogo)')
    ) {
      this.emit('screen_detected', 'detail', line);
      return;
    }
    if (
      line.includes('title="A explorar o Nuvio"') ||
      line.includes('title="A explorar TV em direto"') ||
      line.includes('title="A explorar a Biblioteca"') ||
      line.includes('title="A explorar Descobrir"') ||
      line.includes('(HomeHeroTrailer) gate autoplay=true homeActive=true') ||
      line.includes('trailer dismissed from the nav edge for')
    ) {
      this.emit('screen_detected', 'home', line);
    }
  }
}

function compile(markers: string[]): RegExp[] {
  const out: RegExp[] = [];
  for (const mk of markers) {
    try {
      out.push(new RegExp(mk));
    } catch (err) {
      error(`logtail: invalid regex in config (${mk}): ${(err as Error).message}`);
    }
  }
  return out;
}
