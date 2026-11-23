import * as crypto from 'crypto';
import * as dgram from 'dgram';
import * as fs from 'fs';
import * as http from 'http';
import * as os from 'os';
import * as path from 'path';
import { spawn } from 'child_process';
import * as ws from 'ws';
import { Config, saveUserConfig } from './config';
import { error, info, warn } from './logger';
import { ClientMessage, ServerMessage, parseClientMessage, ScreenName } from './protocol';
import { ScreenStateMachine } from './state';
import { InputInjector } from './injection/input';
import { PinManager, TokenStore } from './auth';
import { ScreenServer } from './screen/screenServer';
import { GdiCapture } from './screen/capture';
import { TunnelManager } from './tunnel';
import type { NuvioLogTail } from './logtail';
import type { NuvioBridge, NuvioBridgeSnapshot } from './nuvioBridge';
import type {
  NuvioActionDone,
  NuvioAlbumTrack,
  NuvioContinueItem,
  NuvioEpisodeItem,
  NuvioLiveTvScreen,
  NuvioLyricsSheet,
  NuvioMenuSheet,
  NuvioMusicAlbum,
  NuvioMusicArtist,
  NuvioNowPlaying,
  NuvioPlaylistsSheet,
  NuvioQueueSnapshot,
  NuvioRecapSheet,
  NuvioSourceList,
  NuvioTonightScreen,
  NuvioTrackList,
  NuvioVideoTitle,
} from './protocol';

const SERVER_NAME = 'nuvio-deskremote-companion';
const SERVER_VERSION = '0.4.0';
const DISCOVERY_UDP_PORT = 8766;
const PAIRING_HTTP_PORT = 8765;
const MOUSE_UDP_PORT = 8768;

interface SocketWithMeta extends ws.WebSocket {
  _id?: string;
  _isAlive?: boolean;
  _isTunnel?: boolean;
  _authed?: boolean;
  _wantsScreen?: boolean;
  _mjpegInFlight?: boolean;
  _lastCursorPushMs?: number;
  _remoteAddr?: string;
  _authTimer?: NodeJS.Timeout;
}

/** WebSocket + UDP Discovery + HTTP Pairing server: LAN listener (+ optional loopback + Cloudflare Tunnel) and message routing. */
export class CompanionServer {
  private cfg: Config;
  private state: ScreenStateMachine;
  private injector: InputInjector;
  private pinManager: PinManager;
  private tokenStore: TokenStore;
  private authorizedClientIps: Set<string> = new Set();
  private screen: ScreenServer;
  private logtail: NuvioLogTail | null;
  private bridge: NuvioBridge | null;
  private tunnel: TunnelManager | null = null;
  private tunnelEnabled = false;
  private sockets: Set<SocketWithMeta> = new Set();
  private servers: ws.WebSocketServer[] = [];
  private httpServers: http.Server[] = [];
  private udpDiscovery: dgram.Socket | null = null;
  private udpMouse: dgram.Socket | null = null;
  private pingTimer: NodeJS.Timeout | null = null;
  private cursorWatchTimer: NodeJS.Timeout | null = null;
  private lastBroadcastCursor = { ix: -1, iy: -1, vis: -1 };
  private nextSocketId = 0;

  constructor(deps: {
    cfg: Config;
    state: ScreenStateMachine;
    injector: InputInjector;
    screen: ScreenServer;
    logtail: NuvioLogTail | null;
    bridge?: NuvioBridge | null;
  }) {
    this.cfg = deps.cfg;
    this.state = deps.state;
    this.injector = deps.injector;
    this.pinManager = new PinManager();
    this.tokenStore = new TokenStore();
    this.screen = deps.screen;
    this.logtail = deps.logtail;
    this.bridge = deps.bridge ?? null;
  }

  /** Starts WebSocket, UDP Discovery, optional Tunnel, and HTTP Pairing listeners; returns URLs for clients. */
  start(): { main: string; loopback: string | null } {
    try {
      const oldTokensFile = path.resolve(__dirname, '..', 'paired-tokens.json');
      if (fs.existsSync(oldTokensFile)) {
        fs.unlinkSync(oldTokensFile);
      }
    } catch {
      // ignore
    }
    this.screen.setStatusCb((status, profile, detail, monitor) => {
      const msg: ServerMessage = {
        type: 'screen_status',
        status: status === 'error' ? 'stopped' : status,
        profile,
        monitor,
        detail,
      };
      this.broadcast(msg);
    });
    this.screen.setFrameCb((ownerId, jpg, width, height, monitor, directCursorEmbedded) => {
      // Binary frame: [0]=0x01 [1]=monitor [2..3]=w [4..5]=h [6..7]=cx*65535 [8..9]=cy*65535 [10]=visible [11]=needFallbackCursor + JPEG
      const cur = this.screen.cursorNorm();
      const header = Buffer.allocUnsafe(12);
      header.writeUInt8(1, 0);
      header.writeUInt8(Math.max(0, Math.min(255, monitor)), 1);
      header.writeUInt16BE(width, 2);
      header.writeUInt16BE(height, 4);
      header.writeUInt16BE(Math.round(Math.max(0, Math.min(1, cur.nx)) * 65535), 6);
      header.writeUInt16BE(Math.round(Math.max(0, Math.min(1, cur.ny)) * 65535), 8);
      header.writeUInt8(cur.visible ? 1 : 0, 10);
      header.writeUInt8(directCursorEmbedded ? 0 : 1, 11);
      const packet = Buffer.concat([header, jpg]);
      for (const s of this.sockets) {
        if (s.readyState === ws.WebSocket.OPEN && s._authed && s._wantsScreen && ownerId && socketId(s) === ownerId) {
          // Strict 1 MJPEG frame in flight per TCP socket: avoids Windows SO_SNDBUF queuing
          // and prevents head-of-line blocking over ping/pong, audio, and mouse clicks.
          const maxBuffered = s._isTunnel ? 48 * 1024 : 64 * 1024;
          if (!s._mjpegInFlight && s.bufferedAmount < maxBuffered) {
            s._mjpegInFlight = true;
            s.send(packet, { binary: true }, () => {
              s._mjpegInFlight = false;
            });
          }
        }
      }
    });
    this.screen.setAudioCb((ownerId, pcmPacket) => {
      for (const s of this.sockets) {
        if (s.readyState === ws.WebSocket.OPEN && s._authed && s._wantsScreen && ownerId && socketId(s) === ownerId) {
          // Keep higher send-buffer headroom for audio so playback never stutters during 1080p streaming.
          const maxAudioBuffered = s._isTunnel ? 448 * 1024 : 1024 * 1024;
          if (s.bufferedAmount < maxAudioBuffered) {
            s.send(pcmPacket, { binary: true });
          }
        }
      }
    });

    const lanIp = pickLanIp();
    // Bind 0.0.0.0 so the server survives Windows adapter changes and multi-interface setups
    const srv = this.serve('0.0.0.0', this.cfg.port, 'lan');
    this.servers.push(srv);
    const mainUrl = `ws://${lanIp}:${this.cfg.port}`;

    let loopbackUrl: string | null = null;
    if (this.cfg.loopback) {
      const s2 = this.serve('127.0.0.1', this.cfg.port + 1, 'loopback');
      this.servers.push(s2);
      loopbackUrl = `ws://127.0.0.1:${this.cfg.port + 1}`;
    }

    // Start UDP Discovery (8766), Mouse UDP Fast-Path (8768), and secondary HTTP Pairing/WS (8765).
    // Cloudflare Quick Tunnel only starts when explicitly enabled (disabled by default).
    if (this.cfg.port === 8787) {
      this.startUdpDiscovery();
      this.startPairingAndCompatServer();
      this.startUdpMouse();
      if (this.cfg.tunnel?.enabled) {
        this.setTunnelEnabled(true);
      } else {
        info('tunnel: Remote Access (Cloudflare Quick Tunnel) is disabled by default (enable via config or Desktop Companion)');
      }
    }

    // Active server keepalive every 15s (prevents mobile NAT/Wi-Fi sleep disconnects)
    this.pingTimer = setInterval(() => {
      for (const s of this.sockets) {
        if (s.readyState !== ws.WebSocket.OPEN) {
          this.sockets.delete(s);
          continue;
        }
        if (s._isAlive === false) {
          warn(`ws: client missed ping (${s._id ?? '?'}); terminating stale socket`);
          try { s.terminate(); } catch { /* ignore */ }
          this.sockets.delete(s);
          this.screen.stopIfOwner(socketId(s));
          continue;
        }
        s._isAlive = false;
        try { s.ping(); } catch { /* ignore */ }
      }
    }, 15000);
    this.pingTimer.unref?.();

    // Sync Windows cursor coordinates at 40Hz when the physical mouse moves during Screen Share (draw_mouse=0)
    this.cursorWatchTimer = setInterval(() => {
      if (!this.screen.isRunning()) return;
      const cur = this.screen.cursorNorm();
      const ix = Math.round(Math.max(0, Math.min(1, cur.nx)) * 65535);
      const iy = Math.round(Math.max(0, Math.min(1, cur.ny)) * 65535);
      const vis = cur.visible ? 1 : 0;
      if (
        ix === this.lastBroadcastCursor.ix &&
        iy === this.lastBroadcastCursor.iy &&
        vis === this.lastBroadcastCursor.vis
      ) {
        return;
      }
      this.lastBroadcastCursor = { ix, iy, vis };
      for (const s of this.sockets) {
        if (s.readyState === ws.WebSocket.OPEN && s._authed && s._wantsScreen) {
          this.sendCursorPacket(s);
        }
      }
    }, 25);
    this.cursorWatchTimer.unref?.();

    this.state.onChange((ev) => {
      const msg: ServerMessage = {
        type: 'state',
        screen: ev.snapshot.screen,
        playerActive: ev.snapshot.playerActive,
        confirmed: ev.snapshot.confirmed,
        source: ev.source,
        ts: new Date().toISOString(),
      };
      this.broadcast(msg);
    });
    this.logtail?.on('player_enter', (line: string) => this.state.applyLogEnter(truncate(line)));
    this.logtail?.on('player_exit', (line: string) => this.state.applyLogExit(truncate(line)));
    this.logtail?.on('screen_detected', (scr: ScreenName, line: string) => this.state.applyLogScreen(scr, truncate(line)));
    this.logtail?.on('nav_edge_dismissed', () => {
      info('ws: HomeHeroTrailer consumed the first Left arrow at edge; injecting extra Left to open sidebar');
      setTimeout(() => {
        void this.injector.injectKey('Left');
      }, 25);
    });
    if (this.bridge) {
      this.bridge.on('status', (snap: NuvioBridgeSnapshot) => {
        this.broadcastAuthed({
          type: 'nuvio_bridge',
          status: snap.status,
          pcName: snap.pcName,
          nowPlaying: snap.nowPlaying,
          sources: snap.sources,
          wake: snap.wake,
        });
      });
      this.bridge.on('wake', () => {
        const snap = this.bridge?.snapshot();
        if (!snap) return;
        this.broadcastAuthed({
          type: 'nuvio_bridge',
          status: snap.status,
          pcName: snap.pcName,
          nowPlaying: snap.nowPlaying,
          sources: snap.sources,
          wake: snap.wake,
        });
      });
      this.bridge.on('state', (nowPlaying: NuvioNowPlaying | null) => {
        this.broadcastAuthed({ type: 'nuvio_state', nowPlaying });
        if (nowPlaying) {
          this.state.applyLogEnter(`nuvio-bridge: ${nowPlaying.kind} ${nowPlaying.title}`);
        } else {
          this.state.applyLogExit('nuvio-bridge: IDLE');
        }
      });
      this.bridge.on('sources', (list: NuvioSourceList | null) => {
        this.broadcastAuthed({ type: 'nuvio_sources', list });
      });
      this.bridge.on('subtitles', (list: NuvioTrackList) => {
        this.broadcastAuthed({ type: 'nuvio_subtitles', loading: list.loading, choices: list.choices });
      });
      this.bridge.on('audio', (list: NuvioTrackList) => {
        this.broadcastAuthed({ type: 'nuvio_audio', choices: list.choices });
      });
      this.bridge.on('queue', (queue: NuvioQueueSnapshot) => {
        this.broadcastAuthed({ type: 'nuvio_queue', playing: queue.playing, tracks: queue.tracks });
      });
      this.bridge.on('library_video', (payload: { ready: boolean; items: NuvioVideoTitle[] }) => {
        this.broadcastAuthed({ type: 'nuvio_library_video', ready: payload.ready, items: payload.items });
      });
      this.bridge.on(
        'library_music',
        (payload: { ready: boolean; artists: NuvioMusicArtist[]; albums: NuvioMusicAlbum[] }) => {
          this.broadcastAuthed({
            type: 'nuvio_library_music',
            ready: payload.ready,
            artists: payload.artists,
            albums: payload.albums,
          });
        },
      );
      this.bridge.on('continue', (items: NuvioContinueItem[]) => {
        this.broadcastAuthed({ type: 'nuvio_continue', items });
      });
      this.bridge.on(
        'episodes',
        (payload: { contentType: string; id: string; found: boolean; episodes: NuvioEpisodeItem[] }) => {
          this.broadcastAuthed({
            type: 'nuvio_episodes',
            contentType: payload.contentType,
            id: payload.id,
            found: payload.found,
            episodes: payload.episodes,
          });
        },
      );
      this.bridge.on(
        'search_results',
        (payload: { loading: boolean; query: string; items: NuvioVideoTitle[] }) => {
          this.broadcastAuthed({
            type: 'nuvio_search_results',
            loading: payload.loading,
            query: payload.query,
            items: payload.items,
          });
        },
      );
      this.bridge.on('album_tracks', (payload: { browseId: string; tracks: NuvioAlbumTrack[] }) => {
        this.broadcastAuthed({ type: 'nuvio_album_tracks', browseId: payload.browseId, tracks: payload.tracks });
      });
      this.bridge.on('menu', (menu: NuvioMenuSheet) => {
        this.broadcastAuthed({ type: 'nuvio_menu', menu });
      });
      this.bridge.on('playlists', (playlists: NuvioPlaylistsSheet) => {
        this.broadcastAuthed({ type: 'nuvio_playlists', playlists });
      });
      this.bridge.on('recap', (recap: NuvioRecapSheet) => {
        this.broadcastAuthed({ type: 'nuvio_recap', recap });
      });
      this.bridge.on('action_done', (done: NuvioActionDone) => {
        this.broadcastAuthed({ type: 'nuvio_action_done', done });
      });
      this.bridge.on('lyrics', (lyrics: NuvioLyricsSheet) => {
        this.broadcastAuthed({ type: 'nuvio_lyrics', lyrics });
      });
      this.bridge.on('livetv', (screen: NuvioLiveTvScreen | null) => {
        this.broadcastAuthed({ type: 'nuvio_livetv', screen });
      });
      this.bridge.on('tonight', (screen: NuvioTonightScreen | null) => {
        this.broadcastAuthed({ type: 'nuvio_tonight', screen });
      });
    }
    return { main: mainUrl, loopback: loopbackUrl };
  }

  private serve(host: string, port: number, tag: string): ws.WebSocketServer {
    const httpSrv = http.createServer((req, res) => this.handleHttpRequest(req, res));
    const wss = new ws.WebSocketServer({ server: httpSrv, perMessageDeflate: false });
    wss.on('connection', (socket, req) => this.onConnection(socket as SocketWithMeta, req));
    wss.on('error', (err) => error(`ws[${tag}]: ${(err as Error).message}`));
    httpSrv.on('error', (err) => {
      const e = err as NodeJS.ErrnoException;
      error(`http[${tag}]: ${e.message}`);
      if (tag === 'lan' && e.code === 'EADDRINUSE') {
        error(`primary port ${port} is already in use by another instance; terminating duplicate process.`);
        this.shutdownOnConflict();
      }
    });
    httpSrv.listen(port, host, () => {
      info(`ws[${tag}]: listening on ${host}:${port}`);
    });
    this.httpServers.push(httpSrv);
    return wss;
  }

  /** Starts secondary HTTP + WS listener on port 8765 for pairing compatibility (SECURITY.md §3.2). */
  private startPairingAndCompatServer(): void {
    try {
      const httpSrv = http.createServer((req, res) => this.handleHttpRequest(req, res));
      const wss = new ws.WebSocketServer({ server: httpSrv, perMessageDeflate: false });
      wss.on('connection', (socket, req) => this.onConnection(socket as SocketWithMeta, req));
      wss.on('error', (err) => warn(`ws[compat-8765]: ${(err as Error).message}`));
      httpSrv.on('error', (err) => warn(`http[compat-8765]: ${(err as Error).message}`));
      httpSrv.listen(PAIRING_HTTP_PORT, '0.0.0.0', () => {
        info(`http/ws[pair]: listening on 0.0.0.0:${PAIRING_HTTP_PORT} (/api/v1/pair + WS)`);
      });
      this.servers.push(wss);
      this.httpServers.push(httpSrv);
    } catch (err) {
      warn(`http[pair]: could not open port ${PAIRING_HTTP_PORT}: ${(err as Error).message}`);
    }
  }

  private isRequestFromTunnel(req: http.IncomingMessage): boolean {
    return Boolean(
      req.headers['cf-connecting-ip'] ||
      req.headers['cf-ray'] ||
      (req.headers.host && req.headers.host.includes('.trycloudflare.com'))
    );
  }

  private handleHttpRequest(req: http.IncomingMessage, res: http.ServerResponse): void {
    const remote = (req.headers['cf-connecting-ip'] as string) ?? req.socket.remoteAddress ?? '';
    const fromTunnel = this.isRequestFromTunnel(req);
    const isLan = !fromTunnel && isPrivateOrLoopback(remote);

    // SECURITY: APK download and PIN pairing are NEVER exposed over the internet/tunnel — LAN Wi-Fi only!
    if (!isLan) {
      res.writeHead(403, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'LAN_ONLY', message: 'APK download and PIN pairing are allowed on the local network (LAN) only.' }));
      return;
    }

    // SECURITY: Block cross-origin browser requests (CSRF / DNS rebinding) to local HTTP APIs.
    const originHeader = (req.headers['origin'] ?? '').toString().trim();
    if (originHeader && !/^https?:\/\/(127\.0\.0\.1|localhost|\[::1\])(?::\d+)?$/i.test(originHeader)) {
      res.writeHead(403, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'FORBIDDEN_ORIGIN', message: 'Cross-origin browser requests are not allowed.' }));
      return;
    }

    if ((req.method === 'GET' || req.method === 'HEAD') && (req.url === '/apk' || req.url === '/nuvio-remote-debug.apk' || req.url === '/nuvio-deskremote.apk')) {
      const apkCandidates = [
        this.cfg.apkPath ?? '',
        path.resolve(__dirname, '..', '..', 'android', 'nuvio-deskremote-release.apk'),
        path.resolve(__dirname, '..', '..', 'android', 'nuvio-remote-release.apk'),
        path.resolve(__dirname, '..', '..', 'android', 'nuvio-remote-debug.apk'),
        path.resolve(__dirname, '..', 'bin', 'nuvio-deskremote.apk'),
      ].filter((p) => p.length > 0);
      const apkPath = apkCandidates.find((p) => fs.existsSync(p));
      if (apkPath) {
        const stat = fs.statSync(apkPath);
        res.writeHead(200, {
          'Content-Type': 'application/vnd.android.package-archive',
          'Content-Disposition': 'attachment; filename="nuvio-deskremote.apk"',
          'Content-Length': stat.size,
        });
        if (req.method === 'HEAD') {
          res.end();
          return;
        }
        fs.createReadStream(apkPath).pipe(res);
        return;
      }
    }

    if (req.method === 'GET' && (req.url === '/health' || req.url === '/api/v1/status')) {
      const bridgeSnap = this.bridge?.snapshot() ?? null;
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        status: 'ok',
        server: SERVER_NAME,
        version: SERVER_VERSION,
        ip: pickLanIp(),
        ws_port: this.cfg.port,
        tunnel_enabled: this.tunnelEnabled,
        tunnel_url: this.tunnel?.getUrl() ?? null,
        state: this.state.snapshot(),
        nuvio_bridge: bridgeSnap
          ? {
            status: bridgeSnap.status,
            pc_name: bridgeSnap.pcName,
            playing: bridgeSnap.nowPlaying ? bridgeSnap.nowPlaying.title : null,
          }
          : null,
      }));
      return;
    }

    if (req.method === 'POST' && req.url === '/api/v1/tunnel') {
      if (!this.isHttpAdminAuthorized(req, remote)) {
        res.writeHead(401, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'AUTH_REQUIRED', message: 'Bearer token or local loopback access required.' }));
        return;
      }
      let body = '';
      req.on('data', (chunk: Buffer) => {
        if (body.length < 4096) body += chunk.toString('utf8');
      });
      req.on('end', () => {
        try {
          const parsed = JSON.parse(body || '{}') as { enabled?: boolean };
          const enabled = Boolean(parsed.enabled);
          this.setTunnelEnabled(enabled);
          try {
            saveUserConfig({ tunnel: { enabled } });
          } catch (err) {
            warn(`config: failed to save tunnel.enabled: ${(err as Error).message}`);
          }
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({
            tunnel_enabled: this.tunnelEnabled,
            tunnel_url: this.tunnel?.getUrl() ?? null,
          }));
        } catch {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'BAD_JSON' }));
        }
      });
      return;
    }

    // Local endpoints exclusively for the native desktop UI (STRICTLY loopback 127.0.0.1 / ::1)
    if (req.url?.startsWith('/api/v1/local/')) {
      if (!isLoopbackAddress(remote)) {
        res.writeHead(403, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'LOOPBACK_ONLY', message: 'Access restricted to the local machine.' }));
        return;
      }
      if (req.method === 'GET' && req.url.startsWith('/api/v1/local/pair-info')) {
        const forceRefresh = req.url.includes('refresh=1');
        const active = !forceRefresh ? this.pinManager.getActivePin() : null;
        const pinInfo = active ?? this.pinManager.generatePin(true);
        const lanIp = pickLanIp();
        const qrPayload = `nuvio://pair?host=${encodeURIComponent(lanIp)}&port=${this.cfg.port}&pin=${pinInfo.pin}`;
        let connectedClients = 0;
        for (const s of this.sockets) {
          if (s.readyState === ws.WebSocket.OPEN && s._authed) connectedClients += 1;
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          pin: pinInfo.pin,
          expires_in_seconds: pinInfo.expiresInSeconds,
          lan_ip: lanIp,
          ws_port: this.cfg.port,
          qr_payload: qrPayload,
          connected_clients: connectedClients,
          paired_devices: this.tokenStore.getDevices(),
        }));
        return;
      }
      if (req.method === 'GET' && req.url === '/api/v1/local/config') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          nuvio_exe_path: this.cfg.nuvioExePath,
          detected_nuvio_exe_path: this.injector.resolveNuvioExePath(),
          tunnel_enabled: this.tunnelEnabled,
          tunnel_url: this.tunnel?.getUrl() ?? null,
        }));
        return;
      }
      if (req.method === 'POST' && req.url === '/api/v1/local/config') {
        let body = '';
        req.on('data', (chunk: Buffer) => {
          if (body.length < 8192) body += chunk.toString('utf8');
        });
        req.on('end', () => {
          try {
            const parsed = JSON.parse(body || '{}') as { nuvio_exe_path?: string | null; tunnel_enabled?: boolean };
            const patch: { nuvioExePath?: string | null; tunnel?: { enabled?: boolean } } = {};
            if (parsed.nuvio_exe_path !== undefined) {
              const cleanPath = parsed.nuvio_exe_path ? String(parsed.nuvio_exe_path).trim() : null;
              this.cfg.nuvioExePath = cleanPath || null;
              this.injector.setNuvioExePath(this.cfg.nuvioExePath);
              patch.nuvioExePath = this.cfg.nuvioExePath;
            }
            if (typeof parsed.tunnel_enabled === 'boolean') {
              this.setTunnelEnabled(parsed.tunnel_enabled);
              patch.tunnel = { enabled: parsed.tunnel_enabled };
            }
            saveUserConfig(patch);
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({
              nuvio_exe_path: this.cfg.nuvioExePath,
              detected_nuvio_exe_path: this.injector.resolveNuvioExePath(),
              tunnel_enabled: this.tunnelEnabled,
              tunnel_url: this.tunnel?.getUrl() ?? null,
            }));
          } catch {
            res.writeHead(400, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'BAD_JSON' }));
          }
        });
        return;
      }
      if (req.method === 'POST' && req.url === '/api/v1/local/open-nuvio') {
        void this.injector.handleWindowAction('nuvio').then((outcome) => {
          res.writeHead(outcome.ok ? 200 : 404, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify(outcome));
        });
        return;
      }
    }

    if (req.method === 'GET' && req.url === '/api/v1/pair/status') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        has_active_pin: this.pinManager.getActivePin() !== null,
        has_paired_devices: this.tokenStore.hasPairedDevices(),
      }));
      return;
    }

    if (req.method === 'GET' && req.url === '/api/v1/devices') {
      if (!this.isHttpAdminAuthorized(req, remote)) {
        res.writeHead(401, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'AUTH_REQUIRED', message: 'Bearer token or local loopback access required.' }));
        return;
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        devices: this.tokenStore.getDevices(),
      }));
      return;
    }

    if (req.method === 'POST' && req.url === '/api/v1/pair/request') {
      const pinInfo = this.pinManager.generatePin();
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        status: 'pin_displayed',
        expires_in_seconds: pinInfo.expiresInSeconds,
      }));
      return;
    }

    if (req.method === 'POST' && req.url === '/api/v1/pair') {
      let body = '';
      req.on('data', (chunk: Buffer) => {
        if (body.length < 8192) body += chunk.toString('utf8');
      });
      req.on('end', () => {
        try {
          const parsed = JSON.parse(body || '{}') as { device_id?: string; device_name?: string; pin?: string };
          const pin = String(parsed.pin ?? '').trim();
          if (!/^\d{6}$/.test(pin)) {
            res.writeHead(401, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'INVALID_PIN', message: 'PIN must contain exactly 6 numeric digits.' }));
            return;
          }
          const v = this.pinManager.verifyPin(remote, pin);
          if (!v.ok) {
            if (v.error === 'RATE_LIMIT_EXCEEDED') {
              res.writeHead(429, { 'Content-Type': 'application/json' });
              res.end(JSON.stringify({
                error: 'RATE_LIMIT_EXCEEDED',
                message: v.message,
                retry_after_seconds: v.retryAfterSeconds ?? 300,
              }));
              return;
            }
            if (v.error === 'INVALID_PIN') {
              res.writeHead(401, { 'Content-Type': 'application/json' });
              res.end(JSON.stringify({
                error: 'INVALID_PIN',
                message: v.message,
                attempts_remaining: v.attemptsRemaining ?? 0,
              }));
              return;
            }
            res.writeHead(400, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: v.error ?? 'PAIR_FAILED', message: v.message }));
            return;
          }

          const devId = (parsed.device_id || crypto.randomUUID()).slice(0, 64);
          const devName = (parsed.device_name || 'Android Device').slice(0, 40);
          const token = this.tokenStore.createDeviceToken(devId, devName, remote);
          const cleanIp = remote.replace(/^::ffff:/, '');
          this.authorizedClientIps.add(cleanIp);
          info(`[AUTH] Device paired successfully: "${devName}" (${devId}) from ${remote}`);

          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({
            status: 'paired',
            server_id: `srv-${os.hostname()}`,
            device_id: devId,
            token,
            expires_at: null,
          }));
        } catch {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'BAD_JSON' }));
        }
      });
      return;
    }

    if (req.method === 'POST' && req.url === '/api/v1/devices/revoke') {
      if (!this.isHttpAdminAuthorized(req, remote)) {
        res.writeHead(401, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'AUTH_REQUIRED', message: 'Bearer token or local loopback access required.' }));
        return;
      }
      let body = '';
      req.on('data', (chunk: Buffer) => {
        if (body.length < 8192) body += chunk.toString('utf8');
      });
      req.on('end', () => {
        try {
          const parsed = JSON.parse(body || '{}') as { device_id?: string };
          if (parsed.device_id) {
            this.tokenStore.revokeDevice(parsed.device_id);
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ status: 'revoked', device_id: parsed.device_id }));
            return;
          }
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'MISSING_DEVICE_ID' }));
        } catch {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'BAD_JSON' }));
        }
      });
      return;
    }

    if (req.method === 'POST' && req.url === '/api/v1/nuvio-bridge/pair') {
      if (!this.isHttpAdminAuthorized(req, remote)) {
        res.writeHead(401, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'AUTH_REQUIRED', message: 'Bearer token or local loopback access required.' }));
        return;
      }
      if (!this.bridge) {
        res.writeHead(503, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'BRIDGE_DISABLED', message: 'Native Nuvio bridge is unavailable.' }));
        return;
      }
      let body = '';
      req.on('data', (chunk: Buffer) => {
        if (body.length < 4096) body += chunk.toString('utf8');
      });
      req.on('end', () => {
        void (async () => {
          try {
            const parsed = JSON.parse(body || '{}') as { pin?: string };
            const result = await this.bridge!.pair(String(parsed.pin ?? ''));
            if (result.ok) {
              res.writeHead(200, { 'Content-Type': 'application/json' });
              res.end(JSON.stringify({ status: 'paired', pc_name: result.pcName }));
              return;
            }
            const statusCode = result.error === 'LOCKED' ? 429 : result.error === 'UNAVAILABLE' ? 503 : 400;
            res.writeHead(statusCode, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: result.error }));
          } catch {
            res.writeHead(400, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'BAD_JSON' }));
          }
        })();
      });
      return;
    }

    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'NOT_FOUND' }));
  }

  /** Responds to Android UDP broadcast discovery probes on port 8766 (SECURITY.md §2.2). */
  private startUdpDiscovery(): void {
    try {
      const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
      sock.on('error', (err) => warn(`udp[discovery]: ${err.message}`));
      sock.on('message', (msg, rinfo) => {
        if (!isPrivateOrLoopback(rinfo.address)) return;
        const text = msg.toString('utf8');
        if (!text.includes('DISCOVER')) return;
        const payload = JSON.stringify({
          status: 'FOUND',
          server_name: os.hostname() || 'Nuvio-PC',
          ip: pickLanIp(),
          port: this.cfg.port,
          protocol: 'ws',
        });
        sock.send(Buffer.from(payload, 'utf8'), rinfo.port, rinfo.address);
      });
      sock.bind(DISCOVERY_UDP_PORT, '0.0.0.0', () => {
        info(`udp[discovery]: listening for broadcasts on 0.0.0.0:${DISCOVERY_UDP_PORT} (advertising WS port ${this.cfg.port})`);
      });
      this.udpDiscovery = sock;
      this.udpDiscovery.unref();
    } catch (err) {
      warn(`udp[discovery]: failed to start: ${(err as Error).message}`);
    }
  }

  /** Enables or disables Cloudflare Quick Tunnel on demand (disabled by default). */
  setTunnelEnabled(enabled: boolean): void {
    this.tunnelEnabled = enabled;
    if (enabled) {
      if (!this.tunnel) {
        this.tunnel = new TunnelManager(this.cfg.port, (url) => {
          info(`tunnel: active remote URL = ${url}`);
          this.broadcast({ type: 'tunnel', url });
        });
        this.tunnel.start();
      }
    } else if (this.tunnel) {
      this.tunnel.stop();
      this.tunnel = null;
      info('tunnel: Remote Access disabled');
      this.broadcast({ type: 'tunnel', url: '' });
    }
  }

  /** Releases resources and exits when the primary port is already owned by another instance. */
  private shutdownOnConflict(): void {
    if (this.pingTimer) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
    if (this.cursorWatchTimer) {
      clearInterval(this.cursorWatchTimer);
      this.cursorWatchTimer = null;
    }
    this.tunnel?.stop();
    this.bridge?.stop();
    this.logtail?.stop();
    this.screen.stop('EADDRINUSE on primary port');
    try { this.udpDiscovery?.close(); } catch { /* ignore */ }
    try { this.udpMouse?.close(); } catch { /* ignore */ }
    for (const srv of this.httpServers) {
      try { srv.close(); } catch { /* ignore */ }
    }
    process.exit(1);
  }

  /** Handles ultra-low-latency relative (0x02) and absolute (0x03) UDP mouse packets on port 8768. */
  private startUdpMouse(): void {
    try {
      const sock = dgram.createSocket('udp4');
      sock.on('error', (err) => warn(`udp[mouse]: ${err.message}`));
      sock.on('message', (msg, rinfo) => {
        if (!isPrivateOrLoopback(rinfo.address)) return;
        const senderIp = rinfo.address.replace(/^::ffff:/, '');
        if (!this.authorizedClientIps.has(senderIp) && !this.authorizedClientIps.has(rinfo.address)) {
          return; // SECURITY: Drop UDP packets from unauthenticated IP addresses
        }
        // Relative mouse packet: [0]=0x02, [1..2]=dx (int16 BE), [3..4]=dy (int16 BE)
        if (msg.length >= 5 && msg[0] === 0x02) {
          const dx = msg.readInt16BE(1);
          const dy = msg.readInt16BE(3);
          void this.injector.mouseMove(dx, dy);
          for (const s of this.sockets) {
            if (s.readyState === ws.WebSocket.OPEN && s._authed && s.bufferedAmount < 32 * 1024) {
              this.sendCursorPacket(s);
            }
          }
          return;
        }
        // Absolute mouse packet: [0]=0x03, [1]=monitor, [2..3]=nx*65535, [4..5]=ny*65535
        if (msg.length >= 6 && msg[0] === 0x03) {
          const monIdx = msg[1] || this.screen.activeMonitor();
          const nx = msg.readUInt16BE(2) / 65535;
          const ny = msg.readUInt16BE(4) / 65535;
          const rect = this.getMonitorRect(monIdx);
          void this.injector.mouseMoveAbs(nx, ny, rect);
          for (const s of this.sockets) {
            if (s.readyState === ws.WebSocket.OPEN && s._authed && s._wantsScreen && s.bufferedAmount < 32 * 1024) {
              this.sendCursorPacket(s);
            }
          }
          return;
        }
      });
      sock.bind(MOUSE_UDP_PORT, '0.0.0.0', () => {
        info(`udp[mouse]: fast-path listening on 0.0.0.0:${MOUSE_UDP_PORT}`);
      });
      this.udpMouse = sock;
      this.udpMouse.unref();
    } catch (err) {
      warn(`udp[mouse]: failed to start: ${(err as Error).message}`);
    }
  }

  private cachedMonitors: Array<{ index: number; x: number; y: number; width: number; height: number }> = [];
  private cachedMonitorsAt = 0;

  private getMonitorRect(monIdx: number): { x: number; y: number; width: number; height: number } {
    const now = Date.now();
    if (this.cachedMonitors.length === 0 || now - this.cachedMonitorsAt > 5000) {
      this.cachedMonitors = GdiCapture.listMonitors(this.injector.getBind());
      this.cachedMonitorsAt = now;
    }
    return (
      this.cachedMonitors.find((x) => x.index === monIdx) ??
      this.cachedMonitors[0] ?? { x: 0, y: 0, width: 1280, height: 1024 }
    );
  }

  private isHttpAdminAuthorized(req: http.IncomingMessage, remote: string): boolean {
    const clean = remote.replace(/^::ffff:/, '');
    if (clean === '127.0.0.1' || clean === '::1') return true;
    const auth = (req.headers['authorization'] ?? '').toString().replace(/^Bearer\s+/i, '').trim();
    if (!auth) return false;
    return this.tokenStore.validateToken(auth, remote) !== null;
  }

  private sendCursorPacket(socket: SocketWithMeta, force = false): void {
    if (!this.screen.isRunning() || socket.readyState !== ws.WebSocket.OPEN) return;
    if (!force) {
      const now = Date.now();
      const minInterval = socket._isTunnel ? 35 : 16;
      if (socket._lastCursorPushMs && now - socket._lastCursorPushMs < minInterval) return;
      if (socket.bufferedAmount >= (socket._isTunnel ? 64 * 1024 : 32 * 1024)) return;
      socket._lastCursorPushMs = now;
    }
    const cur = this.screen.cursorNorm();
    const pkt = Buffer.allocUnsafe(6);
    pkt[0] = 4;
    pkt.writeUInt16BE(Math.round(Math.max(0, Math.min(1, cur.nx)) * 65535), 1);
    pkt.writeUInt16BE(Math.round(Math.max(0, Math.min(1, cur.ny)) * 65535), 3);
    pkt[5] = cur.visible ? 1 : 0;
    socket.send(pkt, { binary: true });
  }

  private checkTextFocusAfterClick(socket: ws.WebSocket): void {
    if (socket.readyState !== ws.WebSocket.OPEN) return;
    // Check immediately and 45ms after a click whether the Windows cursor switched to I-Beam (text input)
    const sendIfIBeam = () => {
      if (socket.readyState === ws.WebSocket.OPEN && this.injector.isTextCursorActive()) {
        // Binary packet 0x05: [0x05, 0x01, 0, 0, 0, 0] -> request auto-opening the mobile keyboard
        const pkt = Buffer.from([5, 1, 0, 0, 0, 0]);
        socket.send(pkt, { binary: true });
        return true;
      }
      return false;
    };
    if (!sendIfIBeam()) {
      setTimeout(() => {
        sendIfIBeam();
      }, 45);
    }
  }

  private onConnection(socket: SocketWithMeta, req: http.IncomingMessage): void {
    const remoteAddr = (req.headers['cf-connecting-ip'] as string) ?? req.socket.remoteAddress ?? '?';
    const fromTunnel = this.isRequestFromTunnel(req);
    const isLan = !fromTunnel && isPrivateOrLoopback(remoteAddr);
    const cleanIp = remoteAddr.replace(/^::ffff:/, '');

    let queryAuth = '';
    try {
      const parsedUrl = new URL(req.url ?? '/', 'http://localhost');
      queryAuth = (parsedUrl.searchParams.get('auth') ?? '').trim();
    } catch {
      // ignore
    }

    const bearerHeader = (req.headers['authorization'] ?? '').toString().replace(/^Bearer\s+/i, '').trim();
    const tokenCandidate = bearerHeader || queryAuth;
    const pairedDev = tokenCandidate ? this.tokenStore.validateToken(tokenCandidate, remoteAddr) : null;
    const isAuthed = pairedDev !== null;

    this.nextSocketId += 1;
    const id = `ws-${Date.now()}-${this.nextSocketId}`;
    socket._id = id;
    socket._isAlive = true;
    socket._isTunnel = !isLan;
    socket._authed = isAuthed;
    socket._remoteAddr = remoteAddr;

    if (isAuthed) {
      this.authorizedClientIps.add(cleanIp);
    } else {
      // SECURITY §4.3: Unauthenticated sockets have 3 seconds to authenticate via hello or AUTH
      socket._authTimer = setTimeout(() => {
        if (!socket._authed && socket.readyState === ws.WebSocket.OPEN) {
          warn(`ws: client ${remoteAddr} (${id}) disconnected for not authenticating within 3s`);
          this.reply(socket, { type: 'error', code: 'AUTH_TIMEOUT', message: 'Authentication deadline expired (SECURITY.md §4.3).' });
          try { socket.close(1008, 'Auth timeout'); } catch { /* ignore */ }
        }
      }, 3000);
      socket._authTimer.unref?.();
    }

    // Enable TCP keep-alive and TCP_NODELAY on the underlying socket for minimal latency
    const rawSock = (socket as unknown as { _socket?: { setKeepAlive?: (enable: boolean, delay: number) => void; setNoDelay?: (noDelay: boolean) => void } })._socket;
    rawSock?.setKeepAlive?.(true, 10000);
    rawSock?.setNoDelay?.(true);

    info(`ws: connection from ${remoteAddr} (${id}) tunnel=${!isLan} preAuthed=${isAuthed}`);
    this.sockets.add(socket);

    socket.on('pong', () => {
      socket._isAlive = true;
    });

    const cleanupSocket = () => {
      if (socket._authTimer) {
        clearTimeout(socket._authTimer);
        socket._authTimer = undefined;
      }
      socket._wantsScreen = false;
      this.sockets.delete(socket);
      // Remove IP from authorizedClientIps if no other authenticated socket from this IP remains open
      let hasOther = false;
      for (const s of this.sockets) {
        if (s._remoteAddr?.replace(/^::ffff:/, '') === cleanIp && s._authed) {
          hasOther = true;
          break;
        }
      }
      if (!hasOther) {
        this.authorizedClientIps.delete(cleanIp);
      }
      this.screen.stopIfOwner(id);
    };

    socket.on('close', (code, reason) => {
      info(`ws: disconnected ${remoteAddr} (${id}) code=${code} reason=${reason?.toString() || '-'}`);
      cleanupSocket();
    });

    socket.on('error', (err) => {
      warn(`ws: error on ${remoteAddr} (${id}): ${(err as Error).message}`);
      cleanupSocket();
    });

    socket.on('message', (raw: ws.RawData, isBinary: boolean) => {
      socket._isAlive = true;
      if (isBinary && Buffer.isBuffer(raw)) {
        if (!socket._authed) return;
        // Binary fast-path 0x02: [0x02, dx_hi, dx_lo, dy_hi, dy_lo] (relative mouse move without JSON.parse)
        if (raw.length >= 5 && raw[0] === 0x02) {
          const dx = raw.readInt16BE(1);
          const dy = raw.readInt16BE(3);
          void this.injector.mouseMove(dx, dy);
          this.sendCursorPacket(socket);
          return;
        }
        // Binary fast-path 0x03: [0x03, monitor, nx_hi, nx_lo, ny_hi, ny_lo] (absolute mouse move without JSON.parse)
        if (raw.length >= 6 && raw[0] === 0x03) {
          const monIdx = raw[1] || this.screen.activeMonitor();
          const nx = raw.readUInt16BE(2) / 65535;
          const ny = raw.readUInt16BE(4) / 65535;
          const rect = this.getMonitorRect(monIdx);
          void this.injector.mouseMoveAbs(nx, ny, rect);
          this.sendCursorPacket(socket);
          return;
        }
      }
      const text = raw.toString();
      if (!text) return;
      const msg = parseClientMessage(text);
      if (!msg) {
        this.reply(socket, { type: 'error', code: 'BAD_JSON', message: 'message is not valid JSON with a type field' });
        return;
      }
      void this.handle(socket, msg).catch((err) => {
        warn(`ws: handler failed (${msg.type}): ${(err as Error).stack ?? err}`);
        this.reply(socket, { type: 'error', code: 'HANDLER_ERROR', message: (err as Error).message });
      });
    });
  }

  private async handle(socket: SocketWithMeta, msg: ClientMessage): Promise<void> {
    if (msg.type !== 'hello' && msg.type !== 'AUTH' && msg.type !== 'ping' && !socket._authed) {
      this.reply(socket, { type: 'error', code: 'AUTH_REQUIRED', message: 'Access requires a previously paired device with a valid token.' });
      socket.close(1008, 'Auth required');
      return;
    }
    switch (msg.type) {
      case 'AUTH':
      case 'hello': {
        const clientToken = (msg.type === 'AUTH' ? msg.token : msg.auth ?? '').trim();
        const remote = socket._remoteAddr ?? socketAddr(socket);
        const cleanIp = remote.replace(/^::ffff:/, '');

        let dev = socket._authed ? { deviceId: 'session', deviceName: 'Paired Client' } : null;
        if (!dev && clientToken) {
          dev = this.tokenStore.validateToken(clientToken, remote);
        }

        if (!dev) {
          warn(`ws: authentication rejected for ${remote} (${socket._id}) - invalid or missing token`);
          this.reply(socket, { type: 'error', code: 'AUTH_FAILED', message: 'Access denied: unpaired device or invalid token. Pair using a 6-digit PIN first.' });
          socket.close(1008, 'Unauthorized');
          return;
        }

        socket._authed = true;
        if (socket._authTimer) {
          clearTimeout(socket._authTimer);
          socket._authTimer = undefined;
        }
        this.authorizedClientIps.add(cleanIp);
        info(`ws: client authenticated successfully: "${dev.deviceName}" (${dev.deviceId}) from ${remote}`);

        const caps = ['nav', 'key', 'type', 'mouse', 'mouse_abs', 'state', 'screen_stream', 'screen_audio', 'multi_monitor', 'log_tail', 'window', 'webrtc_candidate', 'remote_tunnel', 'clipboard', 'nuvio_bridge'];
        const monitors = GdiCapture.listMonitors(this.injector.getBind());
        this.cachedMonitors = monitors;
        this.cachedMonitorsAt = Date.now();
        const msgOut: ServerMessage = {
          type: 'welcome',
          server: SERVER_NAME,
          version: SERVER_VERSION,
          state: this.state.snapshot(),
          capabilities: caps,
          monitors,
          tunnelUrl: this.tunnel?.getUrl() ?? null,
          pairedToken: clientToken || undefined,
        };
        this.reply(socket, msgOut);
        if (this.bridge) {
          const snap = this.bridge.snapshot();
          this.reply(socket, {
            type: 'nuvio_bridge',
            status: snap.status,
            pcName: snap.pcName,
            nowPlaying: snap.nowPlaying,
            sources: snap.sources,
            wake: snap.wake,
            liveTv: snap.liveTv,
            tonight: snap.tonight,
          });
        }
        return;
      }
      case 'ping':
        this.reply(socket, { type: 'pong', clientTs: msg.clientTs });
        return;
      case 'get_state':
        this.reply(socket, stateMsg(this.state));
        return;
      case 'nav': {
        const plan = this.injector.planNav(msg.action, this.state.snapshot().screen);
        if (!plan.key) {
          this.reply(socket, { type: 'error', code: 'NAV_REFUSED', message: plan.refused ?? 'navigation action refused' });
          return;
        }
        const outcome = await this.injector.injectKey(plan.key as never);
        if (!outcome.ok) {
          this.reply(socket, { type: 'error', code: outcome.code, message: outcome.message });
          return;
        }
        this.state.applyNav(msg.action);
        return;
      }
      case 'key': {
        const isRemoteTypingKey = msg.name === 'Backspace' || msg.name === 'Enter';
        const repeat = typeof msg.repeat === 'number' ? msg.repeat : 1;
        const outcome = await this.injector.injectKey(msg.name, this.screen.isRunning() || isRemoteTypingKey, repeat);
        if (!outcome.ok) {
          this.reply(socket, { type: 'error', code: outcome.code, message: outcome.message });
          return;
        }
        if (!this.screen.isRunning()) {
          this.state.applyRawKey(msg.name);
        }
        return;
      }
      case 'type': {
        const outcome = await this.injector.typeText(msg.text, true);
        if (!outcome.ok) this.reply(socket, { type: 'error', code: outcome.code, message: outcome.message });
        return;
      }
      case 'mouse': {
        const m = msg;
        if (m.abs) {
          const monIdx = typeof m.abs.monitor === 'number' ? m.abs.monitor : this.screen.activeMonitor();
          const rect = this.getMonitorRect(monIdx);
          await this.injector.mouseMoveAbs(m.abs.nx, m.abs.ny, rect);
          if (m.click || m.doubleClick || m.down || m.up) {
            await new Promise<void>((r) => setTimeout(r, 10));
          }
          if (m.click) {
            await this.injector.click(m.click, false);
            this.checkTextFocusAfterClick(socket);
            this.sendCursorPacket(socket, true);
          } else if (m.doubleClick) {
            await this.injector.click(m.doubleClick, true);
            this.checkTextFocusAfterClick(socket);
            this.sendCursorPacket(socket, true);
          } else if (m.down) {
            await this.injector.mouseHold(m.down, true);
          } else if (m.up) {
            await this.injector.mouseHold(m.up, false);
          } else {
            this.sendCursorPacket(socket);
          }
          return;
        }
        if (m.move) {
          const o = await this.injector.mouseMove(clampDelta(m.move.dx), clampDelta(m.move.dy));
          if (!o.ok) this.reply(socket, { type: 'error', code: o.code, message: o.message });
          this.sendCursorPacket(socket);
          return;
        }
        if (m.doubleClick) {
          const o = await this.injector.click(m.doubleClick, true);
          if (!o.ok) this.reply(socket, { type: 'error', code: o.code, message: o.message });
          this.checkTextFocusAfterClick(socket);
          return;
        }
        if (m.click) {
          const o = await this.injector.click(m.click, false);
          if (!o.ok) this.reply(socket, { type: 'error', code: o.code, message: o.message });
          this.checkTextFocusAfterClick(socket);
          return;
        }
        if (m.down) {
          const o = await this.injector.mouseHold(m.down, true);
          if (!o.ok) this.reply(socket, { type: 'error', code: o.code, message: o.message });
          return;
        }
        if (m.up) {
          const o = await this.injector.mouseHold(m.up, false);
          if (!o.ok) this.reply(socket, { type: 'error', code: o.code, message: o.message });
          return;
        }
        if (typeof m.scroll === 'number') {
          const o = await this.injector.scroll(Math.max(-10, Math.min(10, m.scroll)));
          if (!o.ok) this.reply(socket, { type: 'error', code: o.code, message: o.message });
          return;
        }
        this.reply(socket, { type: 'error', code: 'MOUSE_EMPTY', message: 'mouse payload missing move/abs/click/down/up/scroll' });
        return;
      }
      case 'state': {
        const allowed: ScreenName[] = ['home', 'detail', 'player', 'search', 'unknown'];
        if (!allowed.includes(msg.screen)) {
          this.reply(socket, { type: 'error', code: 'BAD_SCREEN', message: `screen must be ${allowed.join('|')}` });
          return;
        }
        this.state.forceState(msg.screen);
        return;
      }
      case 'screen_start': {
        for (const s of this.sockets) s._wantsScreen = false;
        socket._wantsScreen = true;
        if (typeof msg.audio === 'boolean') {
          this.screen.setAudioEnabled(msg.audio);
        }
        const result = await this.screen.start(
          msg.profile,
          msg.offer ?? '',
          socketId(socket),
          msg.monitor,
          socket._isTunnel,
        );
        if (!result.ok) {
          socket._wantsScreen = false;
          this.reply(socket, { type: 'error', code: 'SCREEN_START_FAILED', message: result.detail });
          return;
        }
        this.sendCursorPacket(socket, true);
        if (result.answerSdp) {
          this.reply(socket, { type: 'webrtc_answer', sdp: result.answerSdp });
        }
        return;
      }
      case 'screen_stop':
        for (const s of this.sockets) s._wantsScreen = false;
        this.screen.stop('client screen_stop');
        return;
      case 'screen_audio':
        this.screen.setAudioEnabled(Boolean(msg.enabled));
        return;
      case 'screen_webrtc_state':
        this.screen.setWebRtcLive(socketId(socket), Boolean(msg.live));
        return;
      case 'webrtc_candidate':
        this.screen.addRemoteCandidate(msg.candidate, msg.mid ?? '0');
        return;
      case 'window': {
        const outcome = await this.injector.handleWindowAction(msg.action);
        if (!outcome.ok) {
          this.reply(socket, { type: 'error', code: outcome.code, message: outcome.message });
        }
        return;
      }
      case 'clipboard_set': {
        const text = typeof msg.text === 'string' ? msg.text : '';
        await setWindowsClipboard(text);
        return;
      }
      case 'clipboard_get': {
        const text = await getWindowsClipboard();
        this.reply(socket, { type: 'clipboard_data', text });
        return;
      }
      case 'nuvio_bridge_status': {
        if (!this.bridge) {
          this.reply(socket, { type: 'nuvio_bridge', status: 'disconnected', pcName: null, nowPlaying: null, sources: null, wake: null });
          return;
        }
        const snap = this.bridge.snapshot();
        this.reply(socket, {
          type: 'nuvio_bridge',
          status: snap.status,
          pcName: snap.pcName,
          nowPlaying: snap.nowPlaying,
          sources: snap.sources,
          wake: snap.wake,
        });
        return;
      }
      case 'nuvio_bridge_pair': {
        if (socket._isTunnel) {
          this.reply(socket, { type: 'nuvio_bridge_pair_result', ok: false, error: 'LAN_ONLY' });
          return;
        }
        if (!this.bridge) {
          this.reply(socket, { type: 'nuvio_bridge_pair_result', ok: false, error: 'UNAVAILABLE' });
          return;
        }
        const res = await this.bridge.pair(String(msg.pin ?? ''));
        this.reply(socket, {
          type: 'nuvio_bridge_pair_result',
          ok: res.ok,
          error: res.ok ? undefined : res.error,
          pcName: res.ok ? res.pcName : undefined,
        });
        return;
      }
      case 'nuvio_seek':
        this.bridge?.seek(msg.positionMs);
        return;
      case 'nuvio_volume':
        this.bridge?.setVolume(msg.volume);
        return;
      case 'nuvio_control':
        this.bridge?.control(msg.action, msg.value);
        return;
      case 'nuvio_subtitles':
        this.bridge?.requestSubtitles();
        return;
      case 'nuvio_pick_subtitle':
        this.bridge?.pickSubtitle(msg.number);
        return;
      case 'nuvio_audio':
        this.bridge?.requestAudio();
        return;
      case 'nuvio_pick_audio':
        this.bridge?.pickAudio(msg.number);
        return;
      case 'nuvio_sources_open': {
        if (!this.bridge) return;
        const snap = this.bridge.snapshot();
        if (!msg.force && snap.sources !== null) {
          this.reply(socket, { type: 'nuvio_sources', list: snap.sources });
          return;
        }
        this.bridge.requestSources(Boolean(msg.force));
        return;
      }
      case 'nuvio_pick_source': {
        const res = this.bridge ? await this.bridge.pickSource(msg.number) : { ok: false };
        this.reply(socket, { type: 'nuvio_open_result', ok: res.ok, what: res.what });
        return;
      }
      case 'nuvio_queue':
        this.bridge?.requestQueue();
        return;
      case 'nuvio_queue_action':
        this.bridge?.queueAction(msg.action, msg.videoId);
        return;
      case 'nuvio_open': {
        const res = this.bridge ? await this.bridge.openLink(String(msg.url ?? '')) : { ok: false };
        this.reply(socket, { type: 'nuvio_open_result', ok: res.ok, what: res.what });
        return;
      }
      case 'nuvio_library':
        this.bridge?.requestLibrary(msg.kind === 'music' ? 'music' : 'video');
        return;
      case 'nuvio_continue':
        this.bridge?.requestContinue();
        return;
      case 'nuvio_resume': {
        const res = this.bridge ? await this.bridge.resume(String(msg.parentId ?? ''), String(msg.videoId ?? '')) : { ok: false };
        this.reply(socket, { type: 'nuvio_open_result', ok: res.ok, what: res.what });
        return;
      }
      case 'nuvio_open_title': {
        const res = this.bridge ? await this.bridge.openTitle(String(msg.contentType ?? ''), String(msg.id ?? '')) : { ok: false };
        this.reply(socket, { type: 'nuvio_open_result', ok: res.ok, what: res.what });
        return;
      }
      case 'nuvio_episodes':
        this.bridge?.requestEpisodes(String(msg.contentType ?? ''), String(msg.id ?? ''));
        return;
      case 'nuvio_play_episode': {
        const res = this.bridge
          ? await this.bridge.playEpisode(String(msg.contentType ?? ''), String(msg.id ?? ''), String(msg.videoId ?? ''))
          : { ok: false };
        this.reply(socket, { type: 'nuvio_open_result', ok: res.ok, what: res.what });
        return;
      }
      case 'nuvio_search':
        this.bridge?.search(String(msg.query ?? ''));
        return;
      case 'nuvio_album_tracks':
        this.bridge?.requestAlbumTracks(String(msg.browseId ?? ''));
        return;
      case 'nuvio_play_track': {
        const res = this.bridge ? await this.bridge.playTrack(String(msg.browseId ?? ''), Number(msg.index)) : { ok: false };
        this.reply(socket, { type: 'nuvio_open_result', ok: res.ok, what: res.what });
        return;
      }
      case 'nuvio_play_album': {
        const action = msg.action === 'shuffle' || msg.action === 'queue' ? msg.action : 'play';
        const res = this.bridge ? await this.bridge.playAlbum(String(msg.browseId ?? ''), action) : { ok: false };
        this.reply(socket, { type: 'nuvio_open_result', ok: res.ok, what: res.what });
        return;
      }
      case 'nuvio_artist_action': {
        const action = msg.action === 'radio' ? 'radio' : 'mix';
        const res = this.bridge ? await this.bridge.artistAction(String(msg.artistId ?? ''), action) : { ok: false };
        this.reply(socket, { type: 'nuvio_open_result', ok: res.ok, what: res.what });
        return;
      }
      case 'nuvio_menu':
        this.bridge?.requestMenu(String(msg.kind ?? ''), String(msg.contentType ?? ''), String(msg.id ?? ''), msg.ref);
        return;
      case 'nuvio_do':
        this.bridge?.doMenuAction(
          String(msg.kind ?? ''),
          String(msg.contentType ?? ''),
          String(msg.id ?? ''),
          String(msg.code ?? ''),
          msg.ref,
          Array.isArray(msg.args) ? msg.args.map(String) : [],
        );
        return;
      case 'nuvio_lyrics':
        this.bridge?.requestLyrics();
        return;
      case 'nuvio_livetv_do':
        this.bridge?.liveTvDo(Array.isArray(msg.args) ? msg.args.map(String) : []);
        return;
      case 'nuvio_tonight_do':
        this.bridge?.tonightDo(String(msg.action ?? ''), typeof msg.number === 'number' ? msg.number : undefined);
        return;
      default:
        this.reply(socket, { type: 'error', code: 'UNKNOWN_TYPE', message: `unsupported message type: ${(msg as { type: string }).type}` });
    }
  }

  private reply(socket: ws.WebSocket, msg: ServerMessage): void {
    if (socket.readyState === ws.WebSocket.OPEN) socket.send(JSON.stringify(msg));
  }

  private broadcast(msg: ServerMessage): void {
    const data = JSON.stringify(msg);
    for (const s of this.sockets) {
      if (s.readyState === ws.WebSocket.OPEN) s.send(data);
    }
  }

  private broadcastAuthed(msg: ServerMessage): void {
    const data = JSON.stringify(msg);
    for (const s of this.sockets) {
      if (s.readyState === ws.WebSocket.OPEN && s._authed) s.send(data);
    }
  }
}

function socketId(socket: SocketWithMeta): string {
  return socket._id ?? '';
}

function stateMsg(state: ScreenStateMachine): ServerMessage {
  const s = state.snapshot();
  return { type: 'state', screen: s.screen, playerActive: s.playerActive, confirmed: s.confirmed, source: 'command', ts: new Date().toISOString() };
}

function clampDelta(v: number): number {
  if (!Number.isFinite(v)) return 0;
  return Math.max(-2000, Math.min(2000, Math.round(v)));
}

function truncate(line: string): string {
  return line.length > 200 ? line.slice(0, 200) + '…' : line;
}

export function pickLanIp(): string {
  const ifaces = os.networkInterfaces();
  let fallback = '127.0.0.1';
  for (const name of Object.keys(ifaces)) {
    for (const addr of ifaces[name] ?? []) {
      if (addr.family === 'IPv4' && !addr.internal && !addr.address.startsWith('169.254.')) {
        if (addr.address.startsWith('192.168.') || addr.address.startsWith('10.')) {
          return addr.address;
        }
        fallback = addr.address;
      }
    }
  }
  return fallback;
}

function isLoopbackAddress(addr: string): boolean {
  const clean = addr.replace(/^::ffff:/, '').trim();
  return clean === '127.0.0.1' || clean === '::1';
}

function isPrivateOrLoopback(addr: string): boolean {
  if (!addr || addr === '?' || addr === '127.0.0.1' || addr === '::1' || addr === '::ffff:127.0.0.1') return true;
  const clean = addr.replace(/^::ffff:/, '');
  if (clean.startsWith('192.168.') || clean.startsWith('10.') || clean.startsWith('169.254.')) return true;
  if (/^172\.(1[6-9]|2\d|3[0-1])\./.test(clean)) return true;
  if (clean.toLowerCase().startsWith('fe80:')) return true;
  return false;
}

function socketAddr(socket: ws.WebSocket): string {
  return (socket as unknown as { _socket?: { remoteAddress?: string } })._socket?.remoteAddress ?? '?';
}

function setWindowsClipboard(text: string): Promise<void> {
  return new Promise((resolve) => {
    try {
      const ps = spawn('powershell.exe', [
        '-NoProfile', '-NonInteractive', '-Command',
        '[Console]::InputEncoding = [System.Text.Encoding]::UTF8; $v = [Console]::In.ReadToEnd(); Set-Clipboard -Value $v',
      ], { windowsHide: true });
      const timer = setTimeout(() => {
        try { ps.kill(); } catch { /* ignore */ }
        resolve();
      }, 2500);
      ps.on('close', () => {
        clearTimeout(timer);
        resolve();
      });
      ps.on('error', () => {
        clearTimeout(timer);
        resolve();
      });
      ps.stdin?.write(text, 'utf8');
      ps.stdin?.end();
    } catch {
      resolve();
    }
  });
}

function getWindowsClipboard(): Promise<string> {
  return new Promise((resolve) => {
    try {
      const ps = spawn('powershell.exe', [
        '-NoProfile', '-NonInteractive', '-Command',
        '[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; Get-Clipboard -Raw',
      ], { windowsHide: true });
      let out = '';
      const timer = setTimeout(() => {
        try { ps.kill(); } catch { /* ignore */ }
        resolve(out);
      }, 2500);
      ps.stdout?.on('data', (chunk: Buffer) => {
        out += chunk.toString('utf8');
      });
      ps.on('close', () => {
        clearTimeout(timer);
        resolve(out.replace(/\r?\n$/, ''));
      });
      ps.on('error', () => {
        clearTimeout(timer);
        resolve('');
      });
    } catch {
      resolve('');
    }
  });
}
