import { koffi } from '../koffi';

const k = koffi as unknown as Koffi;

interface Koffi {
  load(name: string): KoffiLib;
  struct(name: string, fields: Record<string, string>): unknown;
  sizeof(t: unknown): number;
  create(proto: unknown): object;
}

interface KoffiLib {
  func(signature: string): (...args: unknown[]) => unknown;
}

/**
 * user32/gdi32/kernel32 bindings (koffi). Windows x64 only.
 * Includes automatic attachment to the interactive "Default" desktop,
 * AttachThreadInput + SetFocus (ensuring Nuvio's SunAwtFrame receives arrow keys),
 * and SendInput for keyboard and mouse injection.
 */

export type W32Fn = (...args: unknown[]) => unknown;

export interface Win32Bindings {
  user32: KoffiLib;
  gdi32: KoffiLib;
  kernel32: KoffiLib;
  OpenDesktopW: W32Fn;
  OpenInputDesktop: W32Fn;
  SetThreadDesktop: W32Fn;
  FindWindowW: W32Fn;
  GetForegroundWindow: W32Fn;
  GetTopWindow: W32Fn;
  GetWindow: W32Fn;
  GetWindowTextW: W32Fn;
  GetWindowThreadProcessId: W32Fn;
  GetCurrentThreadId: W32Fn;
  AttachThreadInput: W32Fn;
  GetFocus: W32Fn;
  SetFocus: W32Fn;
  SetActiveWindow: W32Fn;
  IsWindowVisible: W32Fn;
  IsIconic: W32Fn;
  IsWindow: W32Fn;
  ShowWindow: W32Fn;
  SetForegroundWindow: W32Fn;
  BringWindowToTop: W32Fn;
  SendInput: W32Fn;
  SetCursorPos: W32Fn;
  GetCursorPos: W32Fn;
  GetCursorInfo: W32Fn;
  MapVirtualKeyW: W32Fn;
  GetSystemMetrics: W32Fn;
  SendMessageW: W32Fn;
  PostMessageW: W32Fn;
  MessageBeep: W32Fn;
  mouse_event: W32Fn;
}

const VK_MENU = 0x12; // ALT — used in foreground focus fallback
const DESKTOP_ALL_ACCESS = 0x01ff;

export function loadWin32(): Win32Bindings {
  const user32 = k.load('user32.dll');
  const gdi32 = k.load('gdi32.dll');
  const kernel32 = k.load('kernel32.dll');

  // Ensure 1:1 physical coordinates between SetCursorPos/GetMonitorInfoW and DXGI/GDI capture
  try {
    const setDpiCtx = user32.func('int __stdcall SetProcessDpiAwarenessContext(intptr_t value)');
    setDpiCtx(-4); // DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2
  } catch {
    // silent fallback if API is unavailable
  }

  const sig = (s: string) => user32.func(s);
  const ksig = (s: string) => kernel32.func(s);

  const bindings: Win32Bindings = {
    user32,
    gdi32,
    kernel32,
    OpenDesktopW: sig('uintptr_t __stdcall OpenDesktopW(const wchar_t *lpszDesktop, uint32 dwFlags, int fInherit, uint32 dwDesiredAccess)'),
    OpenInputDesktop: sig('uintptr_t __stdcall OpenInputDesktop(uint32 dwFlags, int fInherit, uint32 dwDesiredAccess)'),
    SetThreadDesktop: sig('int __stdcall SetThreadDesktop(uintptr_t hDesktop)'),
    FindWindowW: sig('uintptr_t __stdcall FindWindowW(const wchar_t *lpClassName, const wchar_t *lpWindowName)'),
    GetForegroundWindow: sig('uintptr_t __stdcall GetForegroundWindow(void)'),
    GetTopWindow: sig('uintptr_t __stdcall GetTopWindow(uintptr_t hWnd)'),
    GetWindow: sig('uintptr_t __stdcall GetWindow(uintptr_t hWnd, int nCmd)'),
    GetWindowTextW: sig('int __stdcall GetWindowTextW(uintptr_t hWnd, void *lpString, int nMaxCount)'),
    GetWindowThreadProcessId: sig('uint32 __stdcall GetWindowThreadProcessId(uintptr_t hWnd, void *lpdwProcessId)'),
    GetCurrentThreadId: ksig('uint32 __stdcall GetCurrentThreadId(void)'),
    AttachThreadInput: sig('int __stdcall AttachThreadInput(uint32 idAttach, uint32 idAttachTo, int fAttach)'),
    GetFocus: sig('uintptr_t __stdcall GetFocus(void)'),
    SetFocus: sig('uintptr_t __stdcall SetFocus(uintptr_t hWnd)'),
    SetActiveWindow: sig('uintptr_t __stdcall SetActiveWindow(uintptr_t hWnd)'),
    IsWindowVisible: sig('int __stdcall IsWindowVisible(uintptr_t hWnd)'),
    IsIconic: sig('int __stdcall IsIconic(uintptr_t hWnd)'),
    IsWindow: sig('int __stdcall IsWindow(uintptr_t hWnd)'),
    ShowWindow: sig('int __stdcall ShowWindow(uintptr_t hWnd, int nCmdShow)'),
    SetForegroundWindow: sig('int __stdcall SetForegroundWindow(uintptr_t hWnd)'),
    BringWindowToTop: sig('int __stdcall BringWindowToTop(uintptr_t hWnd)'),
    SendInput: sig('uint32 __stdcall SendInput(uint32 cInputs, void *pInputs, int cbSize)'),
    SetCursorPos: sig('int __stdcall SetCursorPos(int X, int Y)'),
    GetCursorPos: sig('int __stdcall GetCursorPos(void *lpPoint)'),
    GetCursorInfo: sig('int __stdcall GetCursorInfo(void *pci)'),
    MapVirtualKeyW: sig('uint32 __stdcall MapVirtualKeyW(uint32 uCode, uint32 uMapType)'),
    GetSystemMetrics: sig('int __stdcall GetSystemMetrics(int nIndex)'),
    SendMessageW: sig('intptr_t __stdcall SendMessageW(uintptr_t hWnd, uint32 Msg, uintptr_t wParam, intptr_t lParam)'),
    PostMessageW: sig('int __stdcall PostMessageW(uintptr_t hWnd, uint32 Msg, uintptr_t wParam, intptr_t lParam)'),
    MessageBeep: sig('int __stdcall MessageBeep(uint32 uType)'),
    mouse_event: sig('void __stdcall mouse_event(uint32 dwFlags, uint32 dx, uint32 dy, uint32 dwData, uintptr_t dwExtraInfo)'),
  };

  attachToInteractiveDesktop(bindings);
  return bindings;
}

/**
 * Ensures the main Node.js thread is attached to the user's interactive "Default" desktop
 * so FindWindowW, SendInput, and BitBlt work reliably when started from a non-interactive host.
 */
export function attachToInteractiveDesktop(bind: Win32Bindings): boolean {
  try {
    const hDesk = bind.OpenDesktopW('Default', 0, 0, DESKTOP_ALL_ACCESS) || bind.OpenInputDesktop(0, 0, DESKTOP_ALL_ACCESS);
    if (hwndValue(hDesk) !== 0n) {
      return Number(bind.SetThreadDesktop(hDesk)) !== 0;
    }
  } catch {
    // ignore
  }
  return false;
}

/** Normalizes koffi pointer/handle values to a BigInt comparable with `===`. */
export function hwndValue(h: unknown): bigint {
  if (!h) return 0n;
  if (typeof h === 'bigint') return h;
  if (typeof h === 'number') return BigInt(h);
  try {
    return BigInt(String(h));
  } catch {
    return 0n;
  }
}

export function sameHwnd(a: unknown, b: unknown): boolean {
  const va = hwndValue(a);
  const vb = hwndValue(b);
  return va !== 0n && va === vb;
}

/** Reads a window title (UTF-16LE via Buffer). */
export function windowTitle(bind: Win32Bindings, hwnd: unknown): string {
  const buf = Buffer.alloc(512);
  const len = Number(bind.GetWindowTextW(hwnd, buf, 256));
  if (len <= 0) return '';
  return buf.subarray(0, len * 2).toString('utf16le');
}

/** Finds the Nuvio window: exact title match first, then case-insensitive substring fallback. */
export function findNuvioWindow(bind: Win32Bindings, titleExact: string): unknown {
  attachToInteractiveDesktop(bind);
  const exact = bind.FindWindowW(null, titleExact);
  if (hwndValue(exact) !== 0n && bind.IsWindowVisible(exact)) return exact;

  const lowerNeedle = titleExact.toLowerCase();
  let h = bind.GetTopWindow(0);
  const GW_HWNDNEXT = 2;
  let best: { h: unknown; score: number } | null = null;
  for (let i = 0; i < 1024 && hwndValue(h) !== 0n; i++) {
    if (bind.IsWindowVisible(h)) {
      const title = windowTitle(bind, h);
      const lower = title.toLowerCase();
      if (lower.includes('nuvio') && !lower.includes('visual studio code') && !lower.includes('nuvio-remote')) {
        const score = lower === lowerNeedle ? 3 : lower.includes(lowerNeedle.replace('&', '')) ? 2 : 1;
        if (!best || score > best.score) best = { h, score };
      }
    }
    h = bind.GetWindow(h, GW_HWNDNEXT);
  }
  return best ? best.h : null;
}

/** Ensures real foreground and keyboard focus (via AttachThreadInput) on Nuvio before injecting keys. */
export interface ForegroundResult {
  ok: boolean;
  hwnd: unknown;
  hadFocus: boolean;
  detail: string;
}

export function ensureForeground(bind: Win32Bindings, hwnd: unknown): ForegroundResult {
  attachToInteractiveDesktop(bind);
  if (!hwnd || hwndValue(hwnd) === 0n || !bind.IsWindow(hwnd)) {
    return { ok: false, hwnd: null, hadFocus: false, detail: 'Nuvio window invalid or closed' };
  }

  if (bind.IsIconic(hwnd)) bind.ShowWindow(hwnd, 9); // SW_RESTORE

  const fgRaw = bind.GetForegroundWindow();
  const targetTid = Number(bind.GetWindowThreadProcessId(hwnd, null));
  const curTid = Number(bind.GetCurrentThreadId());

  if (sameHwnd(fgRaw, hwnd)) {
    // Even in foreground, SunAwtFrame (Compose Desktop) may report GetFocus() == 0
    if (targetTid && targetTid !== curTid) {
      bind.AttachThreadInput(curTid, targetTid, 1);
      const focused = bind.GetFocus();
      if (hwndValue(focused) === 0n) {
        const child = bind.GetWindow(hwnd, 5); // GW_CHILD (SunAwtCanvas)
        bind.SetActiveWindow(hwnd);
        bind.SetFocus(hwndValue(child) !== 0n ? child : hwnd);
      }
      bind.AttachThreadInput(curTid, targetTid, 0);
    }
    return { ok: true, hwnd, hadFocus: true, detail: 'already focused' };
  }

  const fgTid = hwndValue(fgRaw) !== 0n ? Number(bind.GetWindowThreadProcessId(fgRaw, null)) : 0;
  if (fgTid && fgTid !== curTid) bind.AttachThreadInput(curTid, fgTid, 1);
  if (targetTid && targetTid !== curTid) bind.AttachThreadInput(curTid, targetTid, 1);

  let set = bind.SetForegroundWindow(hwnd);
  if (!set || !sameHwnd(bind.GetForegroundWindow(), hwnd)) {
    sendKeyRaw(bind, { vk: VK_MENU, scan: 0x38, extended: false }, true);
    sendKeyRaw(bind, { vk: VK_MENU, scan: 0x38, extended: false }, false);
    set = bind.SetForegroundWindow(hwnd) || bind.BringWindowToTop(hwnd);
  }

  const child = bind.GetWindow(hwnd, 5); // GW_CHILD (SunAwtCanvas)
  bind.SetActiveWindow(hwnd);
  bind.SetFocus(hwndValue(child) !== 0n ? child : hwnd);

  if (targetTid && targetTid !== curTid) bind.AttachThreadInput(curTid, targetTid, 0);
  if (fgTid && fgTid !== curTid) bind.AttachThreadInput(curTid, fgTid, 0);

  const fg2 = bind.GetForegroundWindow();
  if (!sameHwnd(fg2, hwnd)) {
    if (set) return { ok: true, hwnd, hadFocus: false, detail: 'focus requested via SetForegroundWindow' };
    return { ok: false, hwnd, hadFocus: false, detail: 'focus not confirmed after SetForegroundWindow' };
  }
  return { ok: true, hwnd, hadFocus: false, detail: 'focus acquired' };
}

/** Packs and sends INPUT[] structs via SendInput (user32 receives Buffer as void*). */
export const INPUT_SIZE_X64 = 40;
const KEYEVENTF_EXTENDEDKEY = 0x0001;
export const KEYEVENTF_KEYUP = 0x0002;
export const KEYEVENTF_UNICODE = 0x0004;
export const MOUSEEVENTF_MOVE = 0x0001;
export const MOUSEEVENTF_LEFTDOWN = 0x0002;
export const MOUSEEVENTF_LEFTUP = 0x0004;
export const MOUSEEVENTF_RIGHTDOWN = 0x0008;
export const MOUSEEVENTF_RIGHTUP = 0x0010;
export const MOUSEEVENTF_MIDDLEDOWN = 0x0020;
export const MOUSEEVENTF_MIDDLEUP = 0x0040;
export const MOUSEEVENTF_WHEEL = 0x0800;
export const WHEEL_DELTA = 120;

export interface KeyRaw {
  vk: number; // 0 for UNICODE
  scan: number;
  extended: boolean;
  unicode?: number; // 16-bit codepoint
  keyup?: boolean;
}

export function packInput(inputs: KeyRaw[]): Buffer {
  const buf = Buffer.alloc(INPUT_SIZE_X64 * inputs.length);
  inputs.forEach((inp, i) => {
    const off = i * INPUT_SIZE_X64;
    if (inp.unicode !== undefined) {
      buf.writeUInt32LE(1, off); // INPUT_KEYBOARD
      buf.writeUInt16LE(0, off + 8); // wVk = 0
      buf.writeUInt16LE(inp.unicode, off + 10); // wScan = char
      let flags = KEYEVENTF_UNICODE;
      if (inp.keyup) flags |= KEYEVENTF_KEYUP;
      buf.writeUInt32LE(flags, off + 12);
      buf.writeUInt32LE(0, off + 16); // time
    } else {
      buf.writeUInt32LE(1, off); // INPUT_KEYBOARD
      buf.writeUInt16LE(inp.vk, off + 8);
      buf.writeUInt16LE(inp.scan, off + 10);
      let flags = 0;
      if (inp.extended) flags |= KEYEVENTF_EXTENDEDKEY;
      if (inp.keyup) flags |= KEYEVENTF_KEYUP;
      buf.writeUInt32LE(flags, off + 12);
      buf.writeUInt32LE(0, off + 16);
    }
    buf.writeBigUInt64LE(BigInt(0), off + 24); // dwExtraInfo
  });
  return buf;
}

export function packMouseInputs(mouse: { dx?: number; dy?: number; flags: number; data?: number }[]): Buffer {
  const buf = Buffer.alloc(INPUT_SIZE_X64 * mouse.length);
  mouse.forEach((inp, i) => {
    const off = i * INPUT_SIZE_X64;
    buf.writeUInt32LE(0, off); // INPUT_MOUSE
    buf.writeInt32LE(inp.dx ?? 0, off + 8);
    buf.writeInt32LE(inp.dy ?? 0, off + 12);
    buf.writeInt32LE(inp.data ?? 0, off + 16); // mouseData (wheel delta may be negative)
    buf.writeUInt32LE(inp.flags, off + 20);
    buf.writeUInt32LE(0, off + 24); // time
    buf.writeBigUInt64LE(BigInt(0), off + 32); // dwExtraInfo
  });
  return buf;
}

/** Executes SendInput with a pre-packed batch buffer. */
export function sendInputs(bind: Win32Bindings, buf: Buffer, count: number): { ok: boolean; sent: number } {
  const sent = Number(bind.SendInput(count, buf, INPUT_SIZE_X64));
  return { ok: sent === count, sent };
}
function sendKeyRaw(bind: Win32Bindings, key: Omit<KeyRaw, 'keyup'>, up: boolean): void {
  sendInputs(bind, packInput([{ ...key, keyup: up }]), 1);
}
