# Changelog

All notable changes to **Nuvio DeskRemote** (Android app + Windows Companion) are documented in this file.

## [0.5.5] - 2026-11-26

### Added
- **Remote Swipe Pad (`[ D-Pad | Swipe ]` — Nuvio Mode):** Added a persistent mode switcher centered above the Remote cockpit to toggle between the classic D-Pad ring and a Liquid Glass gesture squircle (`PrecisionSwipePad`: swipe for arrows, single tap for `OK`, long press for `Back`).
- **Persistent Opaque Mini-Player on `Apps` (Nuvio Mode):** Added `AppsPersistentMiniPlayer` (100% opaque `ContentCardSurface`, zero glass) docked above the bottom bar whenever media is playing, with thumbnail, progress bar, `Play/Pause`, and tap-to-open Player.
- **Android Home Screen Widgets (`4x1` & `4x2`) and App Shortcuts:** Added `NuvioCompactWidgetProvider` (`4x1`: Wake-on-LAN when offline; `Play/Pause`, `Vol -/+`, `Mute` when online) and `NuvioExpandedWidgetProvider` (`4x2`: Now Playing progress + transport/volume/WoL), plus launcher App Shortcuts to jump directly to `Trackpad`, `Screen`, or `Apps`.
- **PC Power Control Sheet (PC Mode Only):** Added `PcPowerDialog` (Liquid Glass `REGULAR` shell with 2x2 opaque grid) accessible from `Trackpad` and `Screen > More PC`, supporting instant `Lock Windows` (`rundll32.exe`) and `Turn off monitor`, plus mandatory 2nd-tap confirmation for `Sleep`, `Restart`, and `Shut down`.
- **Trackpad Multitouch Gestures & Left-Handed Mode (PC Mode):** Added 2-finger pinch-to-zoom (`Ctrl + Scroll` via `mouse.zoom`), 3-finger window gestures (up = `Win+Tab`, down = `Win+D`, left/right = `Alt+Tab`), and a `Left-handed scroll rail` toggle inside the cursor speed card.
- **Expanded PC Keyboard Shortcuts & Theme Override:** Added a `More` / `Less` toggle in `RemoteTextInputBar` exposing two fixed rows of PC shortcuts (`Win+D`, `Win+Tab`, `Ctrl+Z`, `Ctrl+W`, `F5`, `Ctrl+Shift+Esc`, `Alt+F4`) and an explicit `Appearance: System | Dark | Light` selector in `Settings`.

## [0.5.4] - 2026-11-24

### Security
- **DNS Rebinding & WebSocket Upgrade Hardening:** Required valid IPv6 syntax (`net.isIP === 6`) in `isPrivateOrLoopback()` to block domains starting with `fc`/`fd` from bypassing `Host` checks; enforced `Host` validation, browser cross-origin rejection, and a 16-connection cap for unauthenticated sockets during WebSocket `upgrade`.
- **Fail-Closed Local IPC & Path Validation:** Made `/api/v1/local/*` fail closed when `NUVIO_LOCAL_IPC_TOKEN` is unset, rejected UNC (`\\server\...`) and non-absolute paths in `validateNuvioExePath()`, and removed legacy `?auth=` query-string token authentication on WebSocket connections.
- **Scoped Windows Firewall Rules (NSIS):** Restricted Companion inbound firewall rules to `profile=private` and `program=node.exe` on explicit ports (`8787`, `8765`, `8766`, `8768`, and WebRTC UDP `60000-60100`), removing broad public-profile and all-ports rules.
- **Windows Auto-Updater & Android Cleanup:** Moved downloaded Windows installer staging from `%TEMP%` to `%LOCALAPPDATA%\NuvioDeskRemote\updates` (retaining pre-UAC SHA-256 re-verification) and removed dead legacy `pairedToken` acceptance in Android `welcome` messages.

## [0.5.3] - 2026-11-24

### Performance
- **Android Liquid Glass & Rendering:** Converted blur radii to density-independent `dp`, cached ambient backdrop gradients in `drawWithCache`, allocated `rememberGraphicsLayer()` only when live blur is active, and moved `NuvioGlassSwitch`, `NuvioGlassDock`, and `NuvioModeSegmentedControl` animations to GPU-only `graphicsLayer` / `Canvas` drawing (zero relayout).
- **Android Memory & Battery:** Unified poster/artwork loading into a single bounded 8 MiB `LruCache` (`RGB_565`, `inSampleSize` downsampling, 2 MiB download cap), added `@Immutable` annotations and binary search to lyrics/phase-3 models, and restricted `WifiLatencyLock` to PC Mode tabs (`Trackpad` and `Screen`).
- **Windows Companion Server & Desktop:** Made the 40Hz cursor polling timer demand-driven (active only during Screen Share), skipped frame buffer concatenation when no client is ready, added native Win32 clipboard read/write with PowerShell fallback, reused pre-allocated cursor buffers, tuned FFmpeg H.264 (`-threads 2`, `sliced-threads=1`) and MJPEG (`fast_bilinear`), and eliminated Desktop Companion startup window flash when launched `--minimized`.

### Accessibility & Visual Polish
- **Android Touch Targets (`>= 48dp`):** Expanded `AppsSubTabSegmentedControl` and `SpecialKeyPill` touch targets to `>= 48dp` (with horizontal scroll fallback below `352dp`), and refined `PrecisionDPadRing` inner padding and `centerOkSize` across all breakpoints to eliminate D-Pad touch overlap.
- **Android Light Theme & Contrast (`WCAG AA >= 4.5:1`):** Enforced high-contrast primary pills and `+35%` adaptive scrim with `ClearContentShadow` in `QrScannerDialog`, forwarded `isBackdropLight` to portrait `VideoStreamViewport` (`ZoomResetPill`), and separated `values/themes.xml` and `values-night/themes.xml` (`DayNight`) to eliminate the black startup flash in Light Theme.
- **Desktop Companion Contrast:** Updated `--text-muted`, `--status-online`, `--status-warn`, and `--status-danger` tokens in `desktop/ui/styles.css` for `>= 4.5:1` WCAG AA contrast in both Dark and Light themes.

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
