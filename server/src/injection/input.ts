import * as fs from 'fs';
import { spawn } from 'child_process';
import { NavAction, KeyName, MouseButton, WindowAction } from '../protocol';
import {
  Win32Bindings, ensureForeground, findNuvioWindow, hwndValue, packInput, packMouseInputs, sendInputs,
  MOUSEEVENTF_MOVE, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP,
  MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP, MOUSEEVENTF_WHEEL, WHEEL_DELTA,
} from './win32';

/**
 * Win32 input injection for Nuvio HTPC and Windows desktop control (SPEC §3 and §4):
 * - SetForegroundWindow is REQUIRED before sending keyboard input to Nuvio;
 * - keyboard shortcuts follow SPEC.md §3;
 * - relative mouse deltas, clicks, and scrolling work even when Nuvio is closed (for PC control),
 *   focusing Nuvio automatically when needed.
 */

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

interface KeySpec { vk: number; scan: number; extended: boolean }

const MAPVK_VK_TO_VSC = 0;

function getCandidateNuvioExePaths(configuredPath?: string | null): string[] {
  const localAppData = process.env.LOCALAPPDATA ?? '';
  const programFiles = process.env.ProgramFiles ?? 'C:\\Program Files';
  const programFilesX86 = process.env['ProgramFiles(x86)'] ?? 'C:\\Program Files (x86)';
  const userProfile = process.env.USERPROFILE ?? '';
  const candidates = [
    configuredPath?.trim() ?? '',
    process.env.NUVIO_EXE_PATH?.trim() ?? '',
    localAppData ? `${localAppData}\\Programs\\Nuvio\\Nuvio.exe` : '',
    localAppData ? `${localAppData}\\NuvioHTPC\\Nuvio.exe` : '',
    `${programFiles}\\Nuvio\\Nuvio.exe`,
    `${programFiles}\\NuvioHTPC\\Nuvio.exe`,
    `${programFilesX86}\\Nuvio\\Nuvio.exe`,
    userProfile ? `${userProfile}\\Nuvio\\Nuvio.exe` : '',
  ];
  return candidates.filter((p, idx, arr) => p.length > 0 && arr.indexOf(p) === idx);
}

export function buildKeySpecs(bind: Win32Bindings): Record<string, KeySpec> {
  const mk = (vk: number, extended = false): KeySpec => ({
    vk,
    scan: Number(bind.MapVirtualKeyW(vk, MAPVK_VK_TO_VSC)),
    extended,
  });
  return {
    // navigation
    Up: mk(0x26, true), Down: mk(0x28, true), Left: mk(0x25, true), Right: mk(0x27, true),
    Enter: mk(0x0d), Backspace: mk(0x08), Escape: mk(0x1b),
    Home: mk(0x24, true), End: mk(0x23, true),
    H: mk(0x48), S: mk(0x53), L: mk(0x4c), D: mk(0x44), C: mk(0x43), V: mk(0x56), G: mk(0x47), Y: mk(0x59), U: mk(0x55),
    Z: mk(0x5a), X: mk(0x58), W: mk(0x57),
    F4: mk(0x73), F5: mk(0x74), F11: mk(0x7a),
    // modifiers
    Control: mk(0x11), Shift: mk(0x10), Alt: mk(0x12),
    // player (mpv)
    Space: mk(0x20), K: mk(0x4b), Tab: mk(0x09), A: mk(0x41), M: mk(0x4d), O: mk(0x4f),
    E: mk(0x45), I: mk(0x49), PageUp: mk(0x21, true), PageDown: mk(0x22, true),
    // system media keys
    VolumeMute: mk(0xad, true), VolumeDown: mk(0xae, true), VolumeUp: mk(0xaf, true),
    Delete: mk(0x2e, true), LWin: mk(0x5b, true),
  };
}

export type InputOutcome = { ok: true } | { ok: false; code: string; message: string };

const okOutcome: InputOutcome = { ok: true };
const fail = (code: string, message: string): InputOutcome => ({ ok: false, code, message });

export class InputInjector {
  private bind: Win32Bindings;
  private keys: Record<string, KeySpec>;
  private hwnd: unknown = null;
  private hwndTitle: string;
  private configuredNuvioExePath: string | null;
  private focusDelayMs = 45;
  private hIBeam = 0n;
  private lastWindowProbeAt = 0;

  constructor(bind: Win32Bindings, nuvioWindowTitle: string, configuredNuvioExePath?: string | null) {
    this.bind = bind;
    this.hwndTitle = nuvioWindowTitle;
    this.configuredNuvioExePath = configuredNuvioExePath?.trim() || null;
    this.keys = buildKeySpecs(bind);
  }

  setNuvioExePath(exePath: string | null): void {
    this.configuredNuvioExePath = exePath?.trim() || null;
  }

  resolveNuvioExePath(): string | null {
    return getCandidateNuvioExePaths(this.configuredNuvioExePath).find((p) => fs.existsSync(p)) ?? null;
  }

  /**
   * Context-aware key plan by screen state (SPEC §3: the same letter maps differently in Player).
   * Returns the key name to inject or a refusal reason.
   */
  planNav(action: NavAction, currentScreen: string): { key: string | null; refused?: string } {
    const inPlayer = currentScreen === 'player';
    if (!this.keys) return { key: null, refused: 'keymap not initialized' };
    const direct: Record<string, string> = {
      up: 'Up', down: 'Down', left: 'Left', right: 'Right', ok: 'Enter', back: 'Escape', fullscreen: 'F11',
    };
    const navLetter: Record<string, string> = {
      home: 'H', search: 'S', library: 'L', discover: 'D', calendar: 'C', live: 'V', game: 'G',
      youtube: 'Y', music: 'U',
    };
    if (action in direct) {
      const key = direct[action];
      return this.keys[key] ? { key } : { key: null, refused: `key ${key} has no mapped VK` };
    }
    if (action in navLetter) {
      if (inPlayer) {
        return { key: null, refused: `nav=${action} ignored in Player (conflicts with mpv shortcut); use back to exit` };
      }
      const key = navLetter[action];
      return this.keys[key] ? { key } : { key: null, refused: `key ${key} has no mapped VK` };
    }
    return { key: null, refused: `unknown action ${action}` };
  }

  private async focus(requireNuvio = true): Promise<InputOutcome> {
    if (this.hwnd && (hwndValue(this.hwnd) === 0n || !this.bind.IsWindow(this.hwnd))) {
      this.hwnd = null;
      this.lastWindowProbeAt = 0;
    }
    if (!this.hwnd) {
      const now = Date.now();
      if (requireNuvio || now - this.lastWindowProbeAt >= 3000) {
        this.lastWindowProbeAt = now;
        this.hwnd = findNuvioWindow(this.bind, this.hwndTitle);
      }
    }
    if (!this.hwnd || hwndValue(this.hwnd) === 0n) {
      if (!requireNuvio) return okOutcome;
      return fail('NUVIO_NOT_RUNNING', `window "${this.hwndTitle}" not found (is Nuvio running?)`);
    }
    const fg = ensureForeground(this.bind, this.hwnd);
    if (!fg.ok) {
      if (!requireNuvio) return okOutcome;
      return fail('FOREGROUND_FAILED', `focus failed: ${fg.detail}`);
    }
    if (!fg.hadFocus) await sleep(this.focusDelayMs);
    return okOutcome;
  }

  private key(name: KeyName): KeySpec | null {
    return this.keys[name] ?? null;
  }

  private async injectCombo(modifiers: KeySpec[], mainKey: KeySpec): Promise<InputOutcome> {
    const downEvents = [...modifiers, mainKey].map((k) => ({ ...k, keyup: false }));
    const upEvents = [mainKey, ...[...modifiers].reverse()].map((k) => ({ ...k, keyup: true }));
    sendInputs(this.bind, packInput(downEvents), downEvents.length);
    await sleep(18);
    const r = sendInputs(this.bind, packInput(upEvents), upEvents.length);
    return r.ok ? okOutcome : fail('SENDINPUT_FAILED', `combo SendInput failed (${r.sent}/${upEvents.length})`);
  }

  async injectKey(name: KeyName, forceForegroundActive = false, repeatCount = 1): Promise<InputOutcome> {
    const count = Math.max(1, Math.min(500, repeatCount));
    const comboTable: Record<string, [string[], string]> = {
      AltTab: [['Alt'], 'Tab'],
      WinD: [['LWin'], 'D'],
      WinTab: [['LWin'], 'Tab'],
      WinL: [['LWin'], 'L'],
      AltF4: [['Alt'], 'F4'],
      CtrlC: [['Control'], 'C'],
      CtrlV: [['Control'], 'V'],
      CtrlX: [['Control'], 'X'],
      CtrlZ: [['Control'], 'Z'],
      CtrlA: [['Control'], 'A'],
      CtrlW: [['Control'], 'W'],
      CtrlShiftEsc: [['Control', 'Shift'], 'Escape'],
    };
    const combo = comboTable[name];
    if (combo) {
      const [modNames, mainName] = combo;
      const mods = modNames.map((m) => this.keys[m]).filter((x): x is KeySpec => Boolean(x));
      const mainSpec = this.keys[mainName];
      if (mods.length !== modNames.length || !mainSpec) {
        return fail('UNKNOWN_KEY', `shortcut ${name} has no mapped VK`);
      }
      return this.injectCombo(mods, mainSpec);
    }
    const spec = this.key(name);
    if (!spec) return fail('UNKNOWN_KEY', `key ${name} is not mapped`);
    const isSystemKey = name === 'VolumeMute' || name === 'VolumeUp' || name === 'VolumeDown' || name === 'LWin' || name === 'Delete';
    if (!forceForegroundActive && !isSystemKey) {
      await this.focus(false);
    }
    if (count > 1 && (name === 'Backspace' || name === 'Delete')) {
      // Inject multiple Backspace/Delete repeats in a single burst without per-key delay
      const batch: Array<{ vk: number; scan: number; extended: boolean; keyup: boolean }> = [];
      for (let i = 0; i < count; i++) {
        batch.push({ ...spec, keyup: false });
        batch.push({ ...spec, keyup: true });
      }
      const r = sendInputs(this.bind, packInput(batch), batch.length);
      return r.ok ? okOutcome : fail('SENDINPUT_FAILED', `SendInput batch failed (${r.sent}/${batch.length})`);
    }
    for (let i = 0; i < count; i++) {
      const downBuf = packInput([{ ...spec, keyup: false }]);
      sendInputs(this.bind, downBuf, 1);
      if (count === 1) await sleep(8);
      const upBuf = packInput([{ ...spec, keyup: true }]);
      sendInputs(this.bind, upBuf, 1);
    }
    return okOutcome;
  }

  /** Remote text typing into the currently focused field (or Nuvio outside Screen Share). */
  async typeText(text: string, forceForegroundActive = false): Promise<InputOutcome> {
    if (!text) return okOutcome;
    if (!forceForegroundActive) {
      await this.focus(false);
    }
    for (const ch of text) {
      if (ch === '\n' || ch === '\r') {
        const spec = this.keys.Enter;
        if (!spec) continue;
        sendInputs(this.bind, packInput([{ ...spec, keyup: false }, { ...spec, keyup: true }]), 2);
        continue;
      }
      if (ch === '\b') {
        const spec = this.keys.Backspace;
        if (!spec) continue;
        sendInputs(this.bind, packInput([{ ...spec, keyup: false }, { ...spec, keyup: true }]), 2);
        continue;
      }
      const cp = ch.codePointAt(0) ?? 0;
      sendInputs(this.bind, packInput([
        { vk: 0, scan: cp, extended: false, unicode: cp, keyup: false },
        { vk: 0, scan: cp, extended: false, unicode: cp, keyup: true },
      ]), 2);
    }
    return okOutcome;
  }

  /**
   * Detects whether the current Windows cursor is an I-Beam (text box / search input / URL bar / editor).
   */
  isTextCursorActive(): boolean {
    try {
      // CURSORINFO on x64: cbSize (4 bytes) + flags (4 bytes) + hCursor (8 bytes) + ptScreenPos (8 bytes) = 24 bytes
      const ci = Buffer.alloc(24);
      ci.writeUInt32LE(24, 0);
      const ok = Number(this.bind.GetCursorInfo(ci));
      if (!ok) return false;
      const flags = ci.readUInt32LE(4);
      if ((flags & 1) === 0) return false; // CURSOR_SHOWING = 1
      const hCursor = ci.readBigUInt64LE(8);
      if (this.hIBeam === 0n) {
        const loadCursor = this.bind.user32.func('uintptr_t __stdcall LoadCursorW(uintptr_t hInstance, uintptr_t lpCursorName)');
        this.hIBeam = hwndValue(loadCursor(0, 32513));
      }
      return this.hIBeam !== 0n && hCursor === this.hIBeam;
    } catch {
      return false;
    }
  }

  /**
   * Zero-latency relative mouse movement (immediate execution in <0.01ms via user32.mouse_event).
   * Avoids Windows timer sleep granularity (15.6ms) to prevent accumulated pointer lag.
   */
  async mouseMove(dx: number, dy: number): Promise<InputOutcome> {
    if (dx === 0 && dy === 0) return okOutcome;
    try {
      this.bind.mouse_event(MOUSEEVENTF_MOVE, dx, dy, 0, 0);
      return okOutcome;
    } catch {
      const r = sendInputs(this.bind, packMouseInputs([{ dx, dy, flags: MOUSEEVENTF_MOVE }]), 1);
      return r.ok ? okOutcome : fail('MOUSE_MOVE_FAILED', 'mouse move failed');
    }
  }

  /** Moves the cursor directly to normalized coordinates (0..1) within the target monitor. */
  async mouseMoveAbs(nx: number, ny: number, monitorRect: { x: number; y: number; width: number; height: number }): Promise<InputOutcome> {
    const clampedX = Math.max(0, Math.min(1, nx));
    const clampedY = Math.max(0, Math.min(1, ny));
    const targetX = Math.round(monitorRect.x + clampedX * (monitorRect.width - 1));
    const targetY = Math.round(monitorRect.y + clampedY * (monitorRect.height - 1));
    this.bind.SetCursorPos(targetX, targetY);
    return okOutcome;
  }

  async click(btn: MouseButton, double: boolean): Promise<InputOutcome> {
    const downFlag = mouseFlag(btn, false);
    const upFlag = mouseFlag(btn, true);
    const pause = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));
    try {
      this.bind.mouse_event(downFlag, 0, 0, 0, 0);
      await pause(12);
      this.bind.mouse_event(upFlag, 0, 0, 0, 0);
      if (double) {
        await pause(24);
        this.bind.mouse_event(downFlag, 0, 0, 0, 0);
        await pause(12);
        this.bind.mouse_event(upFlag, 0, 0, 0, 0);
      }
      return okOutcome;
    } catch {
      const r1 = sendInputs(this.bind, packMouseInputs([{ flags: downFlag }, { flags: upFlag }]), 2);
      if (!r1.ok) return fail('CLICK_FAILED', 'click failed');
      if (double) {
        const r2 = sendInputs(this.bind, packMouseInputs([{ flags: downFlag }, { flags: upFlag }]), 2);
        return r2.ok ? okOutcome : fail('CLICK_FAILED', 'double click failed');
      }
      return okOutcome;
    }
  }

  async mouseHold(btn: MouseButton, down: boolean): Promise<InputOutcome> {
    const flag = mouseFlag(btn, !down);
    try {
      this.bind.mouse_event(flag, 0, 0, 0, 0);
      return okOutcome;
    } catch {
      const r = sendInputs(this.bind, packMouseInputs([{ flags: flag }]), 1);
      return r.ok ? okOutcome : fail('HOLD_FAILED', 'mouse hold failed');
    }
  }

  async scroll(delta: number): Promise<InputOutcome> {
    try {
      this.bind.mouse_event(MOUSEEVENTF_WHEEL, 0, 0, Math.round(delta) * WHEEL_DELTA, 0);
      return okOutcome;
    } catch {
      const r = sendInputs(this.bind, packMouseInputs([{ dx: 0, dy: 0, flags: MOUSEEVENTF_WHEEL, data: Math.round(delta) * WHEEL_DELTA }]), 1);
      return r.ok ? okOutcome : fail('SCROLL_FAILED', 'scroll failed');
    }
  }

  getBind(): Win32Bindings {
    return this.bind;
  }

  /** Window and display control actions (Apps / Screen tabs). */
  async handleWindowAction(action: WindowAction): Promise<InputOutcome> {
    switch (action) {
      case 'focus':
      case 'nuvio': {
        const focus = await this.focus(false);
        if (this.hwnd && hwndValue(this.hwnd) !== 0n) return focus;
        return this.launchOrRestartNuvio(false);
      }
      case 'restart_nuvio':
        return this.launchOrRestartNuvio(true);
      case 'mute_toggle':
        return this.injectKey('VolumeMute');
      case 'beep':
        this.bind.MessageBeep(0x00000040);
        return okOutcome;
      case 'suspend_display':
      case 'curtain_on': {
        // HWND_BROADCAST (0xffff), WM_SYSCOMMAND (0x0112), SC_MONITORPOWER (0xF170), 2 (off)
        this.bind.SendMessageW(0xffff, 0x0112, 0xf170, 2);
        return okOutcome;
      }
      case 'curtain_off': {
        // Slightly nudge cursor and wake physical display (-1 = power on)
        this.bind.mouse_event(MOUSEEVENTF_MOVE, 1, 0, 0, 0);
        this.bind.SendMessageW(0xffff, 0x0112, 0xf170, -1);
        return okOutcome;
      }
      default:
        return fail('UNKNOWN_WINDOW_ACTION', `unknown window action: ${String(action)}`);
    }
  }

  private async launchOrRestartNuvio(restart: boolean): Promise<InputOutcome> {
    const exe = this.resolveNuvioExePath();
    if (!exe) {
      return fail('NUVIO_NOT_FOUND', 'Nuvio.exe not found; configure its path in config.json or the Desktop Companion UI');
    }
    try {
      if (restart) {
        await new Promise<void>((resolve) => {
          const k = spawn('taskkill.exe', ['/IM', 'Nuvio.exe', '/F'], { windowsHide: true });
          k.on('close', () => resolve());
          k.on('error', () => resolve());
        });
        this.hwnd = null;
        await sleep(500);
      }
      const child = spawn(exe, [], { detached: true, stdio: 'ignore', windowsHide: false });
      child.unref();
      await sleep(1200);
      this.hwnd = findNuvioWindow(this.bind, this.hwndTitle);
      if (this.hwnd) ensureForeground(this.bind, this.hwnd);
      return okOutcome;
    } catch (err) {
      return fail('WINDOW_ACTION_FAILED', (err as Error).message);
    }
  }

  /** Read-only window probe without injecting input. */
  probeWindow(): { found: boolean; visible: boolean; title: string; detail: string } {
    const h = (this.hwnd && hwndValue(this.hwnd) !== 0n && this.bind.IsWindow(this.hwnd))
      ? this.hwnd
      : findNuvioWindow(this.bind, this.hwndTitle);
    if (!h || hwndValue(h) === 0n) {
      return { found: false, visible: false, title: '', detail: `no window matching "Nuvio" (${this.hwndTitle})` };
    }
    this.hwnd = h;
    const buf = Buffer.alloc(512);
    const len = Number(this.bind.GetWindowTextW(h, buf, 256));
    const title = len > 0 ? buf.subarray(0, len * 2).toString('utf16le') : '(untitled)';
    return { found: true, visible: !!this.bind.IsWindowVisible(h), title, detail: 'probe ok' };
  }
}

function mouseFlag(btn: MouseButton, up: boolean): number {
  if (btn === 'left') return up ? MOUSEEVENTF_LEFTUP : MOUSEEVENTF_LEFTDOWN;
  if (btn === 'right') return up ? MOUSEEVENTF_RIGHTUP : MOUSEEVENTF_RIGHTDOWN;
  return up ? MOUSEEVENTF_MIDDLEUP : MOUSEEVENTF_MIDDLEDOWN;
}
