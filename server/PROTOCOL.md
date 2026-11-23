# Nuvio DeskRemote Companion — WebSocket Protocol

The Android app connects via WebSocket, exchanging JSON messages (one per text frame) and low-latency binary packets for screen frames, cursor telemetry, keyboard focus cues, audio loopback, and pointer fast-paths.

The server listens on LAN port `8787` (with a local debug loopback listener on `8788`, HTTP/WS pairing compatibility on `8765`, UDP discovery on `8766`, and UDP mouse fast-path on `8768`).

- Primary LAN URL: `ws://<PC-LAN-IP>:8787`
- Local debug URL: `ws://127.0.0.1:8788`

## 1. Handshake & Authentication

Client -> Server:

```json
{ "type": "hello", "name": "Nuvio DeskRemote Android", "auth": "<session-token>" }
```

Server -> Client:

```json
{
  "type": "welcome",
  "server": "nuvio-deskremote-companion",
  "version": "0.4.0",
  "state": { "screen": "home", "playerActive": false, "confirmed": false },
  "capabilities": ["nav", "key", "type", "mouse", "mouse_abs", "state", "screen_stream", "screen_audio", "multi_monitor", "log_tail", "window", "webrtc_candidate", "remote_tunnel", "clipboard", "nuvio_bridge"],
  "monitors": [{ "index": 1, "label": "Display 1 (1920x1080)", "x": 0, "y": 0, "width": 1920, "height": 1080, "primary": true }],
  "tunnelUrl": null
}
```

Authentication uses 6-digit PIN pairing over LAN (`POST /api/v1/pair`) followed by a persistent Bearer/session token (see `SECURITY.md`).

## 2. Server-Pushed Events

- `state` — tracked Nuvio screen changed:
  ```json
  {
    "type": "state",
    "screen": "home|detail|player|search|unknown",
    "playerActive": true,
    "confirmed": true,
    "source": "command|log",
    "ts": "2026-11-06T12:00:00.000Z"
  }
  ```
  - `source: "log"` + `confirmed: true`: real confirmation from `nuvio.log` tail (`PlaybackStartTrace` / `playerAttach`) or native `NuvioBridge`.
  - `source: "command"` + `confirmed: false`: immediate server prediction from an app command.

- `screen_status` — screen share lifecycle:
  ```json
  { "type": "screen_status", "status": "starting|running|stopped", "profile": "720p60", "monitor": 1, "detail": "webrtc-h264" }
  ```

- `webrtc_answer` — SDP answer to the client's H.264 offer on the `Screen` tab.
- `pong` — latency heartbeat reply (`clientTs` echoed).
- `clipboard_data` — Windows clipboard text response.
- `nuvio_bridge`, `nuvio_state`, `nuvio_sources`, `nuvio_subtitles`, `nuvio_audio`, `nuvio_queue`, `nuvio_library_video`, `nuvio_library_music`, `nuvio_continue`, `nuvio_episodes`, `nuvio_search_results`, `nuvio_album_tracks`, `nuvio_menu`, `nuvio_playlists`, `nuvio_recap`, `nuvio_action_done`, `nuvio_lyrics`, `nuvio_livetv`, `nuvio_tonight` — native `PhoneRemote` bridge updates.

## 3. Client Commands (App -> Server)

- `ping`: `{ "type": "ping", "clientTs": 1700000000000 }`
- `get_state`: `{ "type": "get_state" }`
- `nav` — context-aware Nuvio navigation:
  ```json
  { "type": "nav", "action": "up" }
  ```
  Actions: `up`, `down`, `left`, `right`, `ok`, `back`, `home`, `search`, `library`, `discover`, `calendar`, `live`, `game`, `fullscreen`, `youtube`, `music`.

  Context rule: inside `player`, module shortcuts that conflict with mpv bindings (`home`/`search`/`library`/`discover`/`calendar`/`live`/`game`/`youtube`/`music`) are refused with `NAV_REFUSED` — use `back` to exit the player first.
- `key` — raw key injection (optional `repeat` count):
  ```json
  { "type": "key", "name": "Space", "repeat": 1 }
  ```
- `type` — Unicode remote text injection (`KEYEVENTF_UNICODE`; `\n` injects `Enter`):
  ```json
  { "type": "type", "text": "severance" }
  ```
- `mouse` — relative/absolute pointer, clicks, drag, and scroll:
  ```json
  { "type": "mouse", "move": { "dx": 3, "dy": -2 } }
  { "type": "mouse", "abs": { "nx": 0.5, "ny": 0.5, "monitor": 1 }, "click": "left" }
  { "type": "mouse", "click": "left" }
  { "type": "mouse", "doubleClick": "left" }
  { "type": "mouse", "down": "left" }
  { "type": "mouse", "up": "left" }
  { "type": "mouse", "scroll": -2 }
  ```
- `window` — host window/display actions (`focus`, `nuvio`, `restart_nuvio`, `mute_toggle`, `beep`, `suspend_display`, `curtain_on`, `curtain_off`).
- `clipboard_set` / `clipboard_get` — bidirectional clipboard sync.
- `state` — manual screen state declaration (`home|detail|player|search|unknown`).
- `screen_start` / `screen_stop` / `screen_audio` / `screen_webrtc_state` / `webrtc_candidate` — on-demand screen share control and WebRTC signaling:
  ```json
  { "type": "screen_start", "profile": "720p60", "monitor": 1, "audio": true, "offer": "<H264 SDP offer>" }
  ```
  `screen_stop` (or socket disconnect) stops screen capture and audio immediately.
- `nuvio_*` — native `PhoneRemote` bridge commands (`nuvio_bridge_status`, `nuvio_bridge_pair`, `nuvio_seek`, `nuvio_volume`, `nuvio_control`, `nuvio_subtitles`, `nuvio_pick_subtitle`, `nuvio_audio`, `nuvio_pick_audio`, `nuvio_sources_open`, `nuvio_pick_source`, `nuvio_queue`, `nuvio_queue_action`, `nuvio_open`, `nuvio_library`, `nuvio_continue`, `nuvio_resume`, `nuvio_open_title`, `nuvio_episodes`, `nuvio_play_episode`, `nuvio_search`, `nuvio_album_tracks`, `nuvio_play_track`, `nuvio_play_album`, `nuvio_artist_action`, `nuvio_menu`, `nuvio_do`, `nuvio_lyrics`, `nuvio_livetv_do`, `nuvio_tonight_do`).

## 4. Binary Fast-Path Packets

- **Server -> Client:**
  - `0x01`: MJPEG fallback frame (`[0]=0x01, [1]=monitor, [2..3]=w, [4..5]=h, [6..7]=cx, [8..9]=cy, [10]=visible, [11]=needFallbackCursor, [12..]=JPEG`).
  - `0x04`: Cursor telemetry (`[0]=0x04, [1..2]=cx, [3..4]=cy, [5]=visible`).
  - `0x05`: Text-input focus hint (`[0]=0x05, [1]=1` when I-Beam cursor detected after click).
  - `0x06`: WASAPI loopback PCM audio (`[0]=0x06, [1]=0x01 (24kHz Stereo s16le), [2..3]=480 samples, [4..]=1920B PCM`).
- **Client -> Server (UDP port `8768` on LAN or binary WS over tunnel):**
  - `0x02`: Relative mouse delta (`[0]=0x02, [1..2]=dx int16 BE, [3..4]=dy int16 BE`).
  - `0x03`: Absolute mouse move (`[0]=0x03, [1]=monitor, [2..3]=nx*65535 uint16 BE, [4..5]=ny*65535 uint16 BE`).

## 5. Error Codes

```json
{ "type": "error", "code": "...", "message": "..." }
```

Codes: `BAD_JSON`, `UNKNOWN_TYPE`, `AUTH_REQUIRED`, `AUTH_FAILED`, `AUTH_TIMEOUT`, `NUVIO_NOT_RUNNING`, `NUVIO_NOT_FOUND`, `FOREGROUND_FAILED`, `SENDINPUT_FAILED`, `NAV_REFUSED`, `UNKNOWN_KEY`, `MOUSE_EMPTY`, `BAD_SCREEN`, `SCREEN_START_FAILED`, `HANDLER_ERROR`.

## 6. Local CLI Testing

- `npm run client` — interactive CLI WebSocket client.
- `npm run smoke` — automated server smoke validation.
- `node --test test/nuvio-bridge.test.js` — `PhoneRemote` bridge unit tests.
- `node --test test/webrtc-h264.test.js` — H.264 Annex-B parser & WebRTC unit tests.
