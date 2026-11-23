# Nuvio DeskRemote — Project Specification

## 1. Overview
**Nuvio DeskRemote** is a native Android application (Jetpack Compose, Liquid Glass) paired with a Windows Companion server and native Tauri v2 desktop wrapper for all-in-one control: **Nuvio HTPC (v1.16.1)** over LAN + **full Windows PC control** (trackpad, remote keyboard, clipboard, system shortcuts, and live screen sharing with audio).

Distributed under the **GPL-3.0** license, compatible with the `PhoneRemote` protocol of [UmbraProjects/NuvioDesktop](https://github.com/UmbraProjects/NuvioDesktop). Both the Android app and Windows Desktop Companion include a built-in **English (default) / Português (BR)** language switcher.

## 2. Two-Mode Architecture (Strict Separation)
- **Nuvio Mode (`Remote` and `Apps` tabs):** exclusively for controlling Nuvio HTPC (D-pad, context bar `Navigate` / `Player` / `Keyboard`, video and music libraries, stream sources, subtitles, audio tracks, music queue, synchronized lyrics, Live TV mirror, Tonight mirror, and Cinema OLED Mode).
- **PC Mode (`Trackpad` and `Screen` tabs):** Windows desktop control with a low-latency mouse/trackpad, remote keyboard, bidirectional clipboard, `Alt+Tab`, display `Curtain`, and WebRTC H.264 screen sharing with WASAPI loopback audio.
- **LAN Security:** 6-digit PIN pairing (120s TTL, IP lockout after 3 failed attempts), SHA-256 hashed persistent session tokens, and strict `LAN_ONLY` enforcement on `/api/v1/pair` and `/apk`.

## 3. Nuvio HTPC Keymap (v1.16.1)
- **Navigation (Home / Detail):**
  - Arrow keys: Grid and carousel navigation.
  - `Enter`: Select / Open.
  - `Backspace` / `Escape`: Back / Close modal.
  - `H`: Go to Home.
  - `S`: Open Search.
  - `L`: Library.
  - `D`: Discover.
  - `Y`: YouTube.
  - `U`: Music.
  - `C`: Calendar.
  - `V`: Live TV / IPTV.
  - `G`: Toggle Game Mode.
  - `F11`: Fullscreen.
- **Search:**
  - Remote text entry injected into the active search box (`KEYEVENTF_UNICODE`).
  - `Escape`: Clear / close search.
- **Player (mpv):**
  - `Space` / `K`: Play / Pause.
  - `Left` / `Right`: Seek -5s / +5s.
  - `Tab`: Skip intro / segment.
  - `A`: Cycle audio track.
  - `S`: Cycle subtitle track.
  - `Up` / `Down`: mpv volume.
  - `M`: Mute.
  - `O`: Sources / Streams.
  - `E`: Episodes.
  - `C`: Cycle aspect ratio / Zoom.
  - `I`: mpv statistics.
  - `Page Up` / `Page Down`: Previous / next channel.
  - `Escape`: Exit player.

## 4. Native Integration & State Fallback
- **Native Bridge (`PhoneRemote` TCP `127.0.0.1:47321`):** real-time synchronization for *Now Playing*, catalog, music, queue, lyrics, Live TV, Tonight, and Wake-on-LAN.
- **Win32 + Log Tail Fallback:** screen state machine (`Home`, `Detail`, `Player`, `Search`), real-time tail of `%LOCALAPPDATA%\NuvioHTPC\logs\nuvio.log`, and foreground input injection via `SetForegroundWindow` + `SendInput`.

## 5. Screen Sharing (`Screen` Tab)
- Active only while the `Screen` tab is selected on Android; automatically stopped when switching tabs, backgrounding the app, or disconnecting.
- Hardware-assisted capture (`ddagrab` / GDI fallback) + low-latency WebRTC H.264 + binary MJPEG fallback + 0ms local cursor overlay + WASAPI loopback audio.
