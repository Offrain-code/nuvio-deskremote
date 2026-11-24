# Nuvio DeskRemote — Security Specification & LAN Pairing Protocol
**Status:** Implemented and active

---

## 1. Overview & Attack Surface

The **Nuvio DeskRemote Companion** acts as a local Windows control bridge for Nuvio HTPC and the local desktop, with privileges to:
1. Inject keyboard and mouse events into the operating system (`SetForegroundWindow` + `SendInput` / Win32).
2. Capture and stream the desktop framebuffer in real time via WebRTC / MJPEG (`ddagrab` / GDI) and WASAPI audio loopback.

### Practical Risk Analysis & Mandatory Mitigations

| Risk Vector | Risk | Practical Impact Without Mitigation | Mandatory Mitigation |
| :--- | :---: | :--- | :--- |
| **Unauthorized LAN access** | **HIGH** | Another device on the same Wi-Fi/wired network could send arbitrary input or view the screen. | Initial 6-digit PIN pairing generated on the PC + persistent cryptographic session token. |
| **LAN PIN brute force** | **MEDIUM** | An attacker on the local network scans the 1,000,000 PIN space. | Strict rate limiting (3 failed attempts lock the IP for 5 minutes and immediately invalidate the PIN). |
| **Unauthorized remote pairing / APK exposure** | **HIGH** | Pairing or APK download exposed over an optional remote tunnel. | Strict `LAN_ONLY` enforcement rejecting non-LAN or tunnel requests on `/api/v1/pair` and `/apk` with HTTP 403. |
| **WAN / public network exposure** | **HIGH** | Server exposed outside the home network unintentionally. | Private RFC 1918 / loopback validation; optional Cloudflare Quick Tunnel disabled by default. |
| **Local desktop API abuse** | **MEDIUM** | Another LAN host attempts to query pairing PINs or modify local config. | `/api/v1/local/*` strictly restricted to loopback (`127.0.0.1` / `::1`) with `LOOPBACK_ONLY`. |
| **On-disk token disclosure** | **LOW** | Local malware reads companion configuration files. | Only SHA-256 token hashes are stored on the server; NTFS permissions remain restricted to the current user. |

---

## 2. Local Network Service Discovery

The Android app automatically discovers the Windows Companion on the local Wi-Fi subnet without requiring manual IP entry.

### 2.1. mDNS / DNS-SD (Primary Method)
- **Service Type:** `_nuvio-remote._tcp.local`
- **Default Ports:** `8787` (primary WebSocket/HTTP), `8765` (pairing compatibility HTTP/WebSocket)
- **TXT Records:**
  - `name=Nuvio-HTPC` (human-readable hostname)
  - `version=0.4.0`
  - `api_version=1`
  - `paired=false|true`

### 2.2. UDP Broadcast (Fallback)
For home Wi-Fi routers that block mDNS multicast (`224.0.0.251`):
- **Server UDP listen port:** `8766`
- **Android probe message (broadcast `255.255.255.255:8766`):**
  ```json
  {"action": "DISCOVER", "client": "NuvioAndroid", "version": "1.0.0"}
  ```
- **Server unicast reply to the Android IP:**
  ```json
  {"status": "FOUND", "server_name": "Nuvio-HTPC", "ip": "192.0.2.10", "port": 8787, "protocol": "ws"}
  ```

*Security Rule:* Discovery **never transmits secrets, PINs, tokens, or session data**. It only advertises service availability.

---

## 3. 6-Digit PIN Pairing Protocol

Pairing establishes trust between a specific mobile device and the Windows Companion.

```
+----------------+                                 +---------------------+
| Android App    |                                 | Windows Companion   |
+----------------+                                 +---------------------+
        |                                                     |
        | 1. mDNS / UDP Discovery                             |
        |---------------------------------------------------->|
        |<----------------------------------------------------|
        |                                                     |
        | [User taps "Pair with PC" or scans QR Code]         | [Generates CSPRNG PIN on PC]
        |                                                     | [Displays QR/dialog: 849 201]
        |                                                     |
        | 2. POST /api/v1/pair (device_id, name, pin)         |
        |---------------------------------------------------->|
        |                                                     | 3. Timing-safe validation
        |                                                     |    If valid: issue token
        |<----------------------------------------------------|    Persist SHA-256 hash
        | 4. 200 OK + { token, device_id }                    |
        |                                                     |
        | [Stores token in EncryptedSharedPreferences]        |
        |                                                     |
        | 5. WebSocket Connect (Authorization: Bearer <tok>)  |
        |====================================================>|
        |                                                     | 6. Validate token hash
        |<====================================================|    Session authorized
```

### 3.1. PIN Generation & Lifecycle
1. **Entropy:** 6 decimal digits (`100000` to `999999`), generated strictly with a CSPRNG (`crypto.randomInt(100000, 1000000)`).
2. **Expiration (TTL):** Strictly **120 seconds (2 minutes)** if unused.
3. **Single Use:** Immediately cleared from memory once validated.
4. **Visual Display:** Displayed inside the Windows Desktop Companion window (with QR Code) or a native Windows dialog. The PIN is never broadcast over the network.

### 3.2. HTTP Pairing Endpoint (`LAN_ONLY`)
- **Route:** `POST /api/v1/pair` (on ports `8787` and `8765`)
- **Header:** `Content-Type: application/json`
- **Request Body (Android -> PC):**
  ```json
  {
    "device_id": "c7a8b9e0-3124-4f89-8d9e-0123456789ab",
    "device_name": "Android Phone",
    "pin": "849201"
  }
  ```
- **Required Fields:**
  - `device_id`: Persistent UUIDv4 generated on app install (truncated to 64 chars).
  - `device_name`: Friendly device label (max 40 chars).
  - `pin`: Exactly 6 numeric digits (`^\d{6}$`).

### 3.3. Server Responses
- **Success (HTTP 200 OK):**
  ```json
  {
    "status": "paired",
    "server_id": "srv-98f12a34-01bc",
    "device_id": "c7a8b9e0-3124-4f89-8d9e-0123456789ab",
    "token": "nuvio_sec_7f9c3a1b8e4d2091c5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8",
    "expires_at": null
  }
  ```
- **Incorrect PIN (HTTP 401 Unauthorized):**
  ```json
  {
    "error": "INVALID_PIN",
    "message": "Incorrect PIN. Attempts remaining before lockout: 2",
    "attempts_remaining": 2
  }
  ```
- **Rate Limit Lockout (HTTP 429 Too Many Requests):**
  ```json
  {
    "error": "RATE_LIMIT_EXCEEDED",
    "message": "Too many incorrect attempts. Blocked for 300 seconds.",
    "retry_after_seconds": 300
  }
  ```

---

## 4. Session Token Management & Continuous Authentication

### 4.1. Token Structure
- **Format:** 256-bit CSPRNG hex token with a recognizable prefix:
  - `nuvio_sec_<64 hex characters>`
- **Persistence:** Valid until explicitly revoked from the Windows Companion or replaced by re-pairing the same `device_id`.

### 4.2. Secure Storage
1. **Windows Server:**
   - File: `%APPDATA%\NuvioRemote\paired-devices.json`
   - The server **never stores the raw token**, only its **SHA-256** hash:
     `tokenHash = SHA256(token)`
   - Structure:
     ```json
     {
       "devices": [
         {
           "deviceId": "c7a8b9e0-3124-4f89-8d9e-0123456789ab",
           "deviceName": "Android Phone",
           "tokenHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
           "pairedAt": "2026-11-06T14:20:00Z",
           "lastSeenAt": "2026-11-06T14:35:12Z",
           "lastIp": "192.0.2.15"
         }
       ]
     }
     ```
   - **NTFS Permissions:** Inherited per-user permissions under `%APPDATA%` (not readable by other unprivileged users).
2. **Android App:**
   - Raw token is stored in **`EncryptedSharedPreferences`** backed by the Android Keystore.
   - Never logged to Logcat or included in unencrypted cloud backups (`android:allowBackup="false"`).

### 4.3. WebSocket Authentication
- **Connection URL:** `ws://<server-ip>:8787` (or `wss://<tunnel-host>` when optional tunnel is enabled)
- **Supported Handshake Mechanisms:**
  1. *HTTP Header (Recommended):* `Authorization: Bearer nuvio_sec_...`
  2. *First-Message Auth (within 3 seconds):*
     ```json
     {
       "type": "hello",
       "name": "Nuvio DeskRemote Android",
       "auth": "nuvio_sec_..."
     }
     ```
     (or `{ "type": "AUTH", "device_id": "...", "token": "nuvio_sec_..." }`)
- **Disconnect Rule:**
  - If authentication fails or does not complete within **3 seconds**, the server closes the WebSocket with code **1008 (Policy Violation)**.

---

## 5. Network Restrictions & Attack Surface Reduction

### 5.1. Interface Binding & Subnet Validation
1. **Binding:** Primary LAN listener binds `0.0.0.0:8787` (plus pairing compatibility `0.0.0.0:8765`, UDP discovery `8766`, UDP mouse fast-path `8768`, and local debug loopback `127.0.0.1:8788`).
2. **LAN Subnet Filter (`LAN_ONLY`):**
   HTTP pairing (`/api/v1/pair`, `/api/v1/pair/request`), local management, and `/apk` inspect the client source address and tunnel headers (`cf-connecting-ip`, `cf-ray`, `*.trycloudflare.com`):
   - **Allowed for LAN endpoints:**
     - Loopback: `127.0.0.1`, `::1`
     - Private RFC 1918 ranges: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`
     - Link-local: `169.254.0.0/16`, `fe80::/10`
   - **Blocked immediately (HTTP 403 `LAN_ONLY`):**
     - Any public/routable IP or request arriving through a Cloudflare Tunnel.
3. **Loopback-Only Desktop API (`LOOPBACK_ONLY`):**
   `/api/v1/local/*` (live PIN/QR info, config updates, launch Nuvio) rejects any non-loopback IP with HTTP 403.

### 5.2. Rate Limiting & Brute-Force Protection
- **Per-IP PIN attempts:**
  - Maximum **3 consecutive failed attempts** per IP.
  - On the 3rd failure:
    - The source IP is locked out for **300 seconds (5 minutes)**.
    - The active PIN is **immediately invalidated and destroyed**.
- **UDP Mouse Fast-Path (`8768`):**
  - Silently drops all UDP packets whose source IP does not have an active authenticated WebSocket session (`authorizedClientIps`).

---

## 6. Device Revocation & Host Sovereignty

The PC owner retains full control over authorized mobile clients from the **Windows Desktop Companion** window:
- Real-time list of paired devices (`deviceName`, `lastIp`, `pairedAt`).
- **1-click Revoke:** calls `POST /api/v1/devices/revoke` (authorized via loopback or Bearer token), removing the `tokenHash` immediately.

---

## 7. On-Demand Screen Sharing Security (WebRTC)

1. **On-Demand Activation:** Screen capture (`ddagrab` / GDI) and WASAPI loopback audio start only when an authenticated client opens the `Screen` tab (`screen_start`) and stop immediately on tab switch, app background, `screen_stop`, or socket disconnect.
2. **Authenticated Signaling:** All WebRTC SDP offers, answers, and trickle ICE candidates travel strictly inside the authenticated WebSocket session.
3. **Zero External Relay by Default:** WebRTC uses direct LAN ICE candidates (`iceServers: []`) and Cloudflare Quick Tunnel (`tunnel.enabled`) ships **disabled by default**.

---

## 8. Implementation Checklist

- [x] CSPRNG 6-digit PIN generator with 120s TTL and single-use invalidation.
- [x] `POST /api/v1/pair` with `crypto.timingSafeEqual` and 3-attempt / 300s IP lockout.
- [x] SHA-256 hashed token storage in `%APPDATA%\NuvioRemote\paired-devices.json`.
- [x] Private LAN subnet validation and `LAN_ONLY` enforcement on pairing and `/apk`.
- [x] `LOOPBACK_ONLY` protection on `/api/v1/local/*` desktop companion routes.
- [x] WebSocket handshake authentication with 3-second timeout and code `1008` closure.
- [x] Authenticated IP whitelist on UDP mouse fast-path (port `8768`).
- [x] 1-click paired device revocation in the Windows Desktop Companion window.
