# Changelog

All notable changes to **Nuvio DeskRemote** (Android app + Windows Companion) are documented in this file.

## [0.5.2] - 2026-11-23

### Security
- **LAN_ONLY & Auth Hardening:** Closed empty `cf-connecting-ip` header bypasses, enforced strict `Host` and per-session `X-Nuvio-Local-Token` IPC authentication on local/tunnel routes, rate-limited PIN requests, redacted `tokenHash` from device listings, stopped unauthenticated WebSocket broadcasts, and bounded HTTP/WS/TCP payload sizes.
- **Immediate Revocation:** Revoking a paired phone now immediately terminates its active WebSocket session (`1008`), stops screen streaming if owned by that device, and rebuilds the UDP mouse whitelist (`8768`); unpairing from Android also sends best-effort self-revocation.
- **Android Storage & Discovery:** Removed `?auth=` tokens from WebSocket URLs, blocked cloud/device backup via `dataExtractionRules`, replaced plaintext Keystore fallback with recovery + ephemeral in-memory storage, and validated UDP discovery source IP and `server_id` before auto-connecting.
- **Auto-Updater Hardening (Android & Desktop):** Restricted downloads and HTTP redirects to the official GitHub repository and release CDNs, added `SHA256SUMS.txt` fallback with mandatory SHA-256 verification (plus pre-install re-verification on Windows), and fixed SemVer pre-release ordering.

### Fixed
- **Screen Share & WASAPI Audio (Windows):** Guarded `ScreenServer.start()` with generation IDs to prevent orphaned `ffmpeg.exe`/audio processes, added FFmpeg 4.1.0 (`-vsync 0`) compatibility, fixed `ddagrab` mapping for dual identical-resolution monitors, fixed PPS-after-SPS H.264 ordering, preserved prior PC mute state in `WasapiLoopback.cs`, handled audio device invalidation, and added 24-bit PCM support.
- **Win32 Input & Lifecycle:** Fixed inverted Alt key press in `ensureForeground()`, closed leaked `HDESK` handles, changed `mouse_event` deltas to signed `int32`, added UTF-16 surrogate pair support for emojis in `typeText()`, released held mouse buttons on disconnect, switched Curtain broadcast to non-blocking `PostMessageW`, and added graceful Companion shutdown across restart/quit/exit/update.
- **Desktop Companion & NSIS:** Made `companion_request` async with a shared HTTP client, paused background polling while minimized to tray, compiled `WasapiLoopback.cs` during bundle staging, cleaned child helpers and autostart entries in NSIS hooks, and scoped firewall rules to `localsubnet`.
- **Android WebRTC, Media Service & Gestures:** Fixed native `PeerConnection.dispose()` and EGL surface leaks, eliminated duplicate `screen_start` on monitor switch, resumed Screen Share after background/reconnect, fixed `NowPlayingMediaService` FGS crash and Android 13+ `POST_NOTIFICATIONS` request, fixed `NuvioGlassSlider` Light Theme contrast and scrubbing, fixed drag cancellation in `CinemaDarkScreen`, fixed post-drag tap suppression in `ScreenShareScreen`, fixed 2-finger tap in `TrackpadScreen`, synced Curtain state across PC tabs, and normalized Live TV permille progress (`0..1000` -> `0..100`).
- **CI/CD:** Enforced `lintDebug` and release keystore verification on tagged builds, automated `SHA256SUMS.txt` and `release-notes.json` publishing, and enabled all server security/Win32 tests and Rust `cargo test --lib` in CI.

## [0.5.1] - 2026-11-23
- Fixed Windows NSIS upgrades/uninstalls by stopping the bundled Companion `node.exe` cleanly before replacing files.

## [0.5.0] - 2026-11-23
- Added double-tap-and-hold drag on Trackpad and Screen Share.
- Added in-app GitHub Releases auto-updater for Android and Windows Companion.

## [0.4.3] - 2026-11-22
- Enabled `perMachine` NSIS installation for automatic Windows Firewall rules.

## [0.4.2] - 2026-11-22
- Enforced single-instance window focus for the Windows Desktop Companion.

## [0.4.1] - 2026-11-22
- Stripped Windows `\\?\` verbatim path prefix before spawning the bundled Node.js Companion server.
