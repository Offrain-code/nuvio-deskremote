import { koffi } from '../koffi';
import { Win32Bindings, attachToInteractiveDesktop } from '../injection/win32';
import { MonitorInfo } from '../protocol';
import { warn } from '../logger';

/**
 * Screen capture provider: GDI/DIB (BitBlt + GetDIBits), top-down BGRA format.
 * Supports multiple monitors (Display 1, Display 2/TV, or full virtual desktop)
 * using virtual coordinates (srcX, srcY, width, height).
 */

const SRCCOPY = 0x00cc0020;
const CAPTUREBLT = 0x40000000;
const BI_RGB = 0;
const DIB_RGB_COLORS = 0;

const SM_CXSCREEN = 0;
const SM_CYSCREEN = 1;
const SM_XVIRTUALSCREEN = 76;
const SM_YVIRTUALSCREEN = 77;
const SM_CXVIRTUALSCREEN = 78;
const SM_CYVIRTUALSCREEN = 79;

type Fn = (...args: unknown[]) => unknown;

let monFns: { MonitorFromPoint: Fn; GetMonitorInfoW: Fn } | null = null;

export class GdiCapture {
  private bind: Win32Bindings;
  private user32GetDC: Fn;
  private user32ReleaseDC: Fn;
  private gdi32: Record<string, Fn>;
  private srcX = 0;
  private srcY = 0;
  private width = 0;
  private height = 0;
  private hScreenDc: unknown = null;
  private hMemDc: unknown = null;
  private hBitmap: unknown = null;
  private hOldBmp: unknown = null;
  private pixels: Buffer | null = null;

  constructor(bind: Win32Bindings) {
    this.bind = bind;
    this.user32GetDC = bind.user32.func('void * __stdcall GetDC(void *hWnd)') as unknown as Fn;
    this.user32ReleaseDC = bind.user32.func('int __stdcall ReleaseDC(void *hWnd, void *hDC)') as unknown as Fn;
    const g = bind.gdi32 as unknown as ReturnType<typeof koffi.load>;
    const f = (sig: string): Fn => g.func(sig) as unknown as Fn;
    this.gdi32 = {
      CreateCompatibleDC: f('void * __stdcall CreateCompatibleDC(void *hDC)'),
      CreateCompatibleBitmap: f('void * __stdcall CreateCompatibleBitmap(void *hDC, int cx, int cy)'),
      SelectObject: f('void * __stdcall SelectObject(void *hDC, void *hObj)'),
      BitBlt: f('int __stdcall BitBlt(void *hDest, int x, int y, int w, int h, void *hSrc, int x2, int y2, uint32 rop)'),
      GetDIBits: f('int __stdcall GetDIBits(void *hDC, void *hBmp, uint32 start, uint32 lines, void *bits, void *bmi, uint32 usage)'),
      DeleteObject: f('int __stdcall DeleteObject(void *hObj)'),
      DeleteDC: f('int __stdcall DeleteDC(void *hDC)'),
    };
  }

  /** Lists physical monitors (via MonitorFromPoint + GetMonitorInfoW) plus an "All" option. */
  static listMonitors(bind: Win32Bindings): MonitorInfo[] {
    attachToInteractiveDesktop(bind);
    const pw = Number(bind.GetSystemMetrics(SM_CXSCREEN)) || 1280;
    const ph = Number(bind.GetSystemMetrics(SM_CYSCREEN)) || 1024;
    const vx = Number(bind.GetSystemMetrics(SM_XVIRTUALSCREEN)) || 0;
    const vy = Number(bind.GetSystemMetrics(SM_YVIRTUALSCREEN)) || 0;
    const vw = Number(bind.GetSystemMetrics(SM_CXVIRTUALSCREEN)) || pw;
    const vh = Number(bind.GetSystemMetrics(SM_CYVIRTUALSCREEN)) || ph;

    const found: MonitorInfo[] = [];
    try {
      if (!monFns) {
        monFns = {
          MonitorFromPoint: bind.user32.func('uintptr_t __stdcall MonitorFromPoint(int64 pt, uint32 dwFlags)') as unknown as Fn,
          GetMonitorInfoW: bind.user32.func('int __stdcall GetMonitorInfoW(uintptr_t hMonitor, void *lpmi)') as unknown as Fn,
        };
      }
      const seen = new Set<string>();
      const steps = 12;
      for (let iy = 0; iy <= steps; iy++) {
        for (let ix = 0; ix <= steps; ix++) {
          const px = Math.round(vx + (ix / steps) * (vw - 1));
          const py = Math.round(vy + (iy / steps) * (vh - 1));
          const packed = (BigInt(py) << 32n) | (BigInt(px) & 0xffffffffn);
          const signed = BigInt.asIntN(64, packed);
          const hMon = monFns.MonitorFromPoint(signed, 0);
          const key = String(hMon);
          if (!hMon || key === '0' || seen.has(key)) continue;
          seen.add(key);
          const mi = Buffer.alloc(104);
          mi.writeUInt32LE(104, 0);
          if (!monFns.GetMonitorInfoW(hMon, mi)) continue;
          const l = mi.readInt32LE(4), t = mi.readInt32LE(8), r = mi.readInt32LE(12), b = mi.readInt32LE(16);
          const flags = mi.readUInt32LE(36);
          found.push({ index: 0, label: '', x: l, y: t, width: r - l, height: b - t, primary: (flags & 1) === 1 });
        }
      }
    } catch (err) {
      warn(`capture: monitor enumeration failed: ${(err as Error).message}`);
    }

    if (!found.length) {
      found.push({ index: 0, label: '', x: 0, y: 0, width: pw, height: ph, primary: true });
    }
    // Primary monitor first, then left-to-right
    found.sort((a, b) => (a.primary === b.primary ? a.x - b.x : a.primary ? -1 : 1));
    const monitors: MonitorInfo[] = found.map((m, i) => ({
      ...m,
      index: i + 1,
      label: i === 0 ? `Display 1 (${m.width}×${m.height})` : `Display ${i + 1}${m.width >= 1920 ? ' / TV' : ''} (${m.width}×${m.height})`,
    }));
    if (monitors.length >= 2) {
      monitors.push({ index: 0, label: `All (${vw}×${vh})`, x: vx, y: vy, width: vw, height: vh, primary: false });
    }
    return monitors;
  }

  /** Prepares DCs and DIB for the selected monitor region. */
  prepare(width: number, height: number, srcX = 0, srcY = 0): { ok: boolean; detail: string } {
    this.release();
    attachToInteractiveDesktop(this.bind);
    if (width <= 0 || height <= 0) return { ok: false, detail: `invalid size ${width}x${height}` };
    try {
      this.srcX = srcX;
      this.srcY = srcY;
      this.width = width;
      this.height = height;
      this.hScreenDc = this.user32GetDC(null);
      if (!this.hScreenDc) return { ok: false, detail: 'GetDC(null) failed' };
      this.hMemDc = this.gdi32.CreateCompatibleDC(this.hScreenDc);
      this.hBitmap = this.gdi32.CreateCompatibleBitmap(this.hScreenDc, width, height);
      if (!this.hMemDc || !this.hBitmap) return { ok: false, detail: 'CreateCompatibleDC/Bitmap failed' };
      this.hOldBmp = this.gdi32.SelectObject(this.hMemDc, this.hBitmap);
      this.pixels = Buffer.alloc(width * height * 4);
      return { ok: true, detail: `capture ${width}x${height} @ (${srcX},${srcY}) ready (GDI)` };
    } catch (err) {
      this.release();
      return { ok: false, detail: `GDI prepare failed: ${(err as Error).message}` };
    }
  }

  /** Captures the configured region (srcX, srcY, width, height) into the prepared DIB buffer. */
  captureFrame(): Buffer | null {
    if (!this.pixels || !this.hMemDc || !this.hBitmap) return null;
    const w = this.width, h = this.height;
    const sx = this.srcX, sy = this.srcY;
    try {
      if (!this.gdi32.BitBlt(this.hMemDc, 0, 0, w, h, this.hScreenDc, sx, sy, SRCCOPY | CAPTUREBLT)) {
        attachToInteractiveDesktop(this.bind);
        if (this.hScreenDc) this.user32ReleaseDC(null, this.hScreenDc);
        this.hScreenDc = this.user32GetDC(null);
        if (!this.hScreenDc || !this.gdi32.BitBlt(this.hMemDc, 0, 0, w, h, this.hScreenDc, sx, sy, SRCCOPY)) {
          warn('capture: BitBlt failed (locked session/UAC/display off) — keeping last buffer');
          return this.pixels;
        }
      }
      const bmi = Buffer.alloc(40);
      bmi.writeUInt32LE(40, 0); // biSize
      bmi.writeInt32LE(w, 4);
      bmi.writeInt32LE(-h, 8); // top-down
      bmi.writeUInt16LE(1, 12); // biPlanes
      bmi.writeUInt16LE(32, 14); // biBitCount
      bmi.writeUInt32LE(BI_RGB, 16);
      bmi.writeUInt32LE(w * h * 4, 20); // biSizeImage
      const lines = this.gdi32.GetDIBits(this.hMemDc, this.hBitmap, 0, h, this.pixels, bmi, DIB_RGB_COLORS);
      if (Number(lines) !== h) {
        warn(`capture: GetDIBits read ${Number(lines)} of ${h} lines`);
        return this.pixels;
      }
      return this.pixels;
    } catch (err) {
      warn(`capture: captureFrame failed: ${(err as Error).message}`);
      return null;
    }
  }

  nativeSize(): { width: number; height: number } {
    attachToInteractiveDesktop(this.bind);
    return { width: Number(this.bind.GetSystemMetrics(0)), height: Number(this.bind.GetSystemMetrics(1)) };
  }

  release(): void {
    try {
      if (this.hOldBmp && this.hMemDc) this.gdi32.SelectObject(this.hMemDc, this.hOldBmp);
      if (this.hBitmap) this.gdi32.DeleteObject(this.hBitmap);
      if (this.hMemDc) this.gdi32.DeleteDC(this.hMemDc);
      if (this.hScreenDc) this.user32ReleaseDC(null, this.hScreenDc);
    } catch (err) {
      warn(`capture: release failed: ${(err as Error).message}`);
    }
    this.hOldBmp = this.hBitmap = this.hMemDc = this.hScreenDc = null;
    this.pixels = null;
  }
}
