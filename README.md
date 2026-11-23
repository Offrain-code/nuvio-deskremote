# Nuvio DeskRemote

**All-in-one Android remote control & Windows Desktop Companion for [NuvioDesktop (HTPC)](https://github.com/UmbraProjects/NuvioDesktop) + full Windows PC control.**

Nuvio DeskRemote is an independent, GPL-3.0 open-source companion/fork built for users who want more than just an HTPC remote: control **Nuvio HTPC** natively (D-pad, library, music, lyrics, Live TV, Tonight, sources, subtitles, Wake-on-LAN) **and** control your entire **Windows PC from the couch** (low-latency trackpad, remote keyboard, bidirectional clipboard, `Alt+Tab`, monitor curtain, and live H.264 WebRTC screen share with WASAPI audio).

---

## Key Features

### Nuvio Mode (`Remote` & `Apps` tabs)
- **Native Nuvio HTPC Bridge (`127.0.0.1:47321`) + Win32 Fallback:** real-time *Now Playing* (film & music), seek, volume, subtitles, audio tracks, sources, music queue, synchronized word-by-word lyrics, Live TV mirror, Tonight mirror, AI recap, playlists, and library/music browsing.
- **Cinema OLED Mode (`#000000`):** pitch-black gesture screen for dark rooms.
- **Android System Integrations:** lock-screen media controls (`MediaSession`), Quick Settings Tile (status + Wake-on-LAN), Share-to-Nuvio links, and voice search.

### PC Mode (`Trackpad` & `Screen` tabs)
- **Precision Trackpad:** relative cursor over UDP fast-path, scroll rail, drag lock, two-finger right-click, adjustable sensitivity, remote keyboard, mute PC, and display sleep.
- **Live Screen Share (`Screen`):** WebRTC H.264 ultra-low-latency streaming (`ddagrab` / GDI + `libx264` zerolatency), 40 Hz local cursor overlay, WASAPI system audio loopback, multi-monitor switching, zoom/pan, clipboard sync, `Alt+Tab`, and privacy curtain.

### Consumer-Friendly Security, Bilingual UI & Setup
- **Bilingual UI (English Default + Português BR):** live in-app language switchers built into both **Android Settings** (`System` / `English` / `Português`) and the **Windows Desktop Companion** header (`EN` / `PT-BR`).
- **Native Windows Companion Window (Tauri v2 + System Tray):** displays a live **QR Code + 6-digit PIN** (120s TTL), service status, paired devices list with 1-click revoke, automatic `Nuvio.exe` detection, and Windows startup toggle.
- **LAN-First Security:** 6-digit CSPRNG PIN with rate limiting, SHA-256 hashed session tokens, `LAN_ONLY` enforcement on `/api/v1/pair` and `/apk`.
- **Optional Remote Access:** anonymous Cloudflare Quick Tunnel is **disabled by default** and can be toggled on demand from the Windows Companion window (PIN pairing always remains `LAN_ONLY`).

---

## Quick Start (End Users)

1. **Windows PC:** Download and run the **Nuvio DeskRemote Companion** installer (`.exe`) from [Releases](../../releases). Firewall rules for LAN ports (`8787`, `8765`, `8766`, `8768`) are added automatically.
2. **Android Phone:** Install the **Nuvio DeskRemote** APK from [Releases](../../releases) (Android 8.0+ / API 26+).
3. **Connect:** Open the app on the same Wi-Fi — tap the discovered PC or scan the **QR Code** displayed in the Windows Companion window.

---

## Project Structure

- `android/` — Native Android app (Kotlin 2.0.20, Jetpack Compose, Liquid Glass theme, CameraX + ZXing QR scanner, WebRTC).
- `server/` — Windows Companion server (Node.js + TypeScript, `koffi` Win32 bindings, `node-datachannel` WebRTC, `ffmpeg`, WASAPI audio loopback, Nuvio `PhoneRemote` TCP bridge).
- `desktop/` — Native Windows desktop wrapper & NSIS installer (Rust + Tauri v2 + WebView2 + System Tray + offline QR SVG generator).
- `ui/` — Liquid Glass design specification (`DESIGN-SPEC.md`), interactive prototype (`mockup-interactive.html`), and reference screenshots (`mockups/`).
- `tests/` — Windows diagnostic & validation scripts (Win32 focus/input latency, H.264 encoder benchmark, Nuvio log state machine).
- `scripts/` — Automated privacy & security hygiene audit (`scripts/privacy-audit.ps1`).
- `.github/workflows/` — CI quality gates (`ci.yml`) and release pipeline (`release.yml`).

---

## Building from Source

See [CONTRIBUTING.md](CONTRIBUTING.md) for full build instructions, code quality gates, and contribution guidelines.

### Quick verification commands
```bash
# Server
cd server && npm ci && npm run build && node --test test/nuvio-bridge.test.js && node --test test/webrtc-h264.test.js

# Android quality gate
cd android && ./gradlew ktlintCheck detekt lintDebug testDebugUnitTest

# Windows Desktop installer (Tauri v2)
powershell -ExecutionPolicy Bypass -File desktop/scripts/prepare-bundle.ps1
cd desktop/src-tauri && cargo tauri build
```

---

## License & Credits

Licensed under the **GNU General Public License v3.0** ([LICENSE](LICENSE)).

Compatible with the `PhoneRemote` protocol of [UmbraProjects/NuvioDesktop](https://github.com/UmbraProjects/NuvioDesktop) (GPL-3.0). Thanks to the NuvioDesktop & Spyder maintainers for their HTPC protocol work.
