# Nuvio DeskRemote — Windows Companion Server

Local LAN companion server in Node.js + TypeScript that receives commands from the **Nuvio DeskRemote (Android)** app and controls both **Nuvio HTPC** (via the native TCP `47321` `PhoneRemote` bridge + Win32/logtail fallback) and **Windows** (trackpad, keyboard, clipboard, and WebRTC H.264 screen sharing with WASAPI loopback audio).

## Requirements
- Windows 10 or Windows 11 (x64)
- Node.js 20+ (for development)
- Optional system `ffmpeg.exe` on `PATH` for D3D11 `ddagrab` GPU capture (otherwise falls back to bundled `@ffmpeg-installer/ffmpeg`)

## Setup & Run (Development)

```bash
cd server
npm install
npm run build
npm start
```

- Primary WebSocket/HTTP: `ws://<PC-LAN-IP>:8787`
- Pairing HTTP/WS (`LAN_ONLY`): `http://<PC-LAN-IP>:8765`
- UDP Discovery: port `8766` · UDP Mouse fast-path: port `8768`
- Local debug loopback listener: `ws://127.0.0.1:8788`
- Optional configuration: copy `config.example.json` to `config.json` (or `%APPDATA%\NuvioDeskRemote\config.json`). Cloudflare Quick Tunnel (`tunnel.enabled`) is **disabled by default**.

## Tests

```bash
npm run smoke
node --test test/nuvio-bridge.test.js
node --test test/webrtc-h264.test.js
npm run client
```
