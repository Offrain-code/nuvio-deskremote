# Validation Test Plan & Technical Notes — Nuvio DeskRemote

This document summarizes the technical validation requirements for Win32 input injection, H.264 video encoding, Nuvio HTPC log parsing, and the diagnostic test scripts under `tests/`.

---

## 1. Win32 Input Injection & Foreground Focus (`SetForegroundWindow`)

Test script: `tests/test-focus-and-input.ps1`

### Technical Requirements
1. **Windows `ForegroundLockTimeout` Bypass:** when the Companion server runs in the background without an active window, plain `SetForegroundWindow` calls may only flash the taskbar button. The server calls `ShowWindow(SW_RESTORE)` if minimized and attaches to the foreground thread (`AttachThreadInput`) before focusing.
2. **Remote Text Input (`Search`):** to support accented and Unicode characters independently of keyboard layout, the Companion injects text via `SendInput` with `wVk = 0`, `wScan = (ushort)char`, and `dwFlags = KEYEVENTF_UNICODE (0x0004)`.
3. **Interactive User Session:** the Companion must run in the logged-in interactive desktop session, never as a Session 0 Windows Service (where `DXGI Desktop Duplication` and `SetForegroundWindow` return `ACCESS_DENIED`).

---

## 2. H.264 Encoder Benchmark (`Screen` Tab)

Test script: `tests/test-h264-encoders.ps1`

- Benchmarks available system encoders (`h264_amf`, `h264_nvenc`, `h264_qsv`, `h264_mf`, and `libx264 -preset ultrafast -tune zerolatency`) at `720p60` and `1080p60`.
- On multi-display systems, `ddagrab` / GDI capture targets the display selected by the user.

---

## 3. `%LOCALAPPDATA%\NuvioHTPC\logs\nuvio.log` Audit & State Machine

Test script: `tests/test-log-state-machine.ps1`

### Log File Behavior
- **Default path:** `%LOCALAPPDATA%\NuvioHTPC\logs\nuvio.log` (with automatic rotation to `nuvio.log.1`, `nuvio.log.2`, etc.).
- **Non-blocking reads:** concurrent reads using `FileShare.ReadWrite`; when file size shrinks after rotation, the tail resets to offset `0`.
- **IPTV flood protection:** `playerAttach` alone marks startup; full playback confirmation occurs on `fileLoaded` / `profileRendered` / `[nuvio-discord] ... type=Playback`, while `PlaybackError` cancels pending transitions.

### State Log Signatures
| Target State | `nuvio.log` Patterns (EN & PT-BR) |
|---|---|
| `HOME` | `[nuvio-discord] activity updated title="(Browsing Nuvio\|A explorar o Nuvio\|...)"` or `(HomeHeroTrailer) gate .* homeActive=true` |
| `SEARCH` | `[nuvio-discord] activity updated title="(Searching.*\|A pesquisar.*)" type=Browsing playing=false` |
| `DETAIL` | `[nuvio-discord] activity updated title=".*" subtitle="(Details\|Sources\|Person)"` or `(PlaybackStartTrace) id=\d+ (beginPending\|open:streamPicker\|navigate:streamRoute)` |
| `PLAYER` | `(PlaybackStartTrace) id=\d+ (fileLoaded\|profileRendered)` or `[nuvio-discord] ... type=Playback playing=(true\|false)` |

---

## 4. Running the Diagnostic Scripts

```powershell
# 1. Full suite (Win32 + H.264 + Log)
powershell -NoProfile -ExecutionPolicy Bypass -File "tests\test-screen-flows.ps1" -Action Suite

# 2. Screen-flow dry run
powershell -NoProfile -ExecutionPolicy Bypass -File "tests\test-screen-flows.ps1" -Action FlowDryRun

# 3. Tail nuvio.log in real time
powershell -NoProfile -ExecutionPolicy Bypass -File "tests\test-log-state-machine.ps1" -Mode LiveTail

# 4. Validate focus and safe 'H' key injection
powershell -NoProfile -ExecutionPolicy Bypass -File "tests\test-focus-and-input.ps1" -InjectSafePingKey
```
