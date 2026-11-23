# Contributing to Nuvio DeskRemote

Contributions, bug reports, and pull requests are welcome.

## Architecture Principles

1. **Strict Two-Mode Separation:**
   - **Nuvio Mode** (`Remote`, `Apps`) is strictly for Nuvio HTPC controls.
   - **PC Mode** (`Trackpad`, `Screen`) is strictly for Windows PC controls (mouse, keyboard, clipboard, `Alt+Tab`, curtain, screen share).
   - Never mix PC controls into `Remote` or Nuvio buttons into `Trackpad`/`Screen`.
2. **Liquid Glass UI (No Material 3):**
   - Glass surfaces belong only on floating functional layers (D-pad, docks, top bar, floating menus/sheets), never on content lists, cards, trackpad, or video viewports.
   - See `ui/DESIGN-SPEC.md` for tokens, contrast rules, API fallbacks, and responsive breakpoints.
3. **Security Invariants:**
   - Ports `8787`, `8788`, `8765`, `8766`, `8768`, 6-digit PIN pairing, and `LAN_ONLY` restrictions on `/api/v1/pair` and `/apk` must be preserved.
   - Remote tunnel (`tunnel.enabled`) must remain disabled by default.

## Prerequisites

- **Server:** Node.js 20+ (Windows 10/11 x64 for Win32/WebRTC runtime)
- **Android:** JDK 17 or JDK 21 LTS, Android SDK 35
- **Desktop Wrapper:** Rust (stable), Tauri CLI v2 (`cargo install tauri-cli --version "^2"`), WebView2 Runtime

## Required Checks Before Opening a PR

1. **Privacy check (no personal paths/IPs/tokens):**
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/privacy-audit.ps1
   ```
2. **Server build & unit tests:**
   ```bash
   cd server
   npm ci
   npm run build
   node --test test/nuvio-bridge.test.js
   node --test test/webrtc-h264.test.js
   ```
3. **Android mandatory gate:**
   ```bash
   cd android
   ./gradlew ktlintCheck detekt lintDebug testDebugUnitTest
   ```
4. **Desktop check (when touching `desktop/`):**
   ```bash
   cd desktop/src-tauri
   cargo check
   ```

## License

By contributing, you agree that your contributions will be licensed under the **GPL-3.0** license (see [LICENSE](LICENSE)).
