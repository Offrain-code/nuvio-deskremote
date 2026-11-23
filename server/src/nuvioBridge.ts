import { EventEmitter } from 'events';
import * as fs from 'fs';
import * as net from 'net';
import * as path from 'path';
import { info, warn } from './logger';
import type {
  NuvioActionDone,
  NuvioAlbumTrack,
  NuvioBridgeStatus,
  NuvioContinueItem,
  NuvioControlAction,
  NuvioEpisodeItem,
  NuvioLiveTvButton,
  NuvioLiveTvItem,
  NuvioLiveTvScreen,
  NuvioLiveTvStage,
  NuvioLyricLine,
  NuvioLyricWord,
  NuvioLyricsSheet,
  NuvioMenuAction,
  NuvioMenuSheet,
  NuvioMusicAlbum,
  NuvioMusicArtist,
  NuvioNowPlaying,
  NuvioPlaylistChoice,
  NuvioPlaylistsSheet,
  NuvioQueueSnapshot,
  NuvioQueueTrack,
  NuvioRecapSheet,
  NuvioSourceItem,
  NuvioSourceList,
  NuvioTonightCard,
  NuvioTonightScreen,
  NuvioTrackChoice,
  NuvioTrackList,
  NuvioVideoTitle,
  NuvioWakeInfo,
} from './protocol';

export const NUVIO_REMOTE_PROTOCOL_VERSION = 1;
export const NUVIO_REMOTE_DEFAULT_HOST = '127.0.0.1';
export const NUVIO_REMOTE_DEFAULT_PORT = 47321;

export type { NuvioWakeInfo };

export interface NuvioBridgeSnapshot {
  status: NuvioBridgeStatus;
  pcName: string | null;
  port: number;
  nowPlaying: NuvioNowPlaying | null;
  sources: NuvioSourceList | null;
  wake: NuvioWakeInfo | null;
  liveTv: NuvioLiveTvScreen | null;
  tonight: NuvioTonightScreen | null;
}

export interface NuvioBridgeOptions {
  host?: string;
  port?: number;
  deviceName?: string;
  storagePath?: string;
  pingIntervalMs?: number;
  reconnectBaseMs?: number;
}

export type BridgePairResult =
  | { ok: true; pcName: string }
  | { ok: false; error: 'INVALID_PIN' | 'BAD_PIN' | 'LOCKED' | 'UNAVAILABLE' | 'TIMEOUT' };

export type BridgeOpenResult = { ok: boolean; what?: string };

export function encodeWire(text: string): string {
  return encodeURIComponent(text).replace(/[!'()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);
}

export function decodeWire(text: string): string {
  try {
    return decodeURIComponent(text.replace(/\+/g, ' '));
  } catch {
    return text;
  }
}

function optionalField(raw: string | undefined): string | null {
  if (!raw || raw === '-' || !raw.trim()) return null;
  return decodeWire(raw);
}

function parseOptionalInt(raw: string | undefined): number | null {
  if (!raw || raw === '-') return null;
  const n = Number.parseInt(raw, 10);
  return Number.isFinite(n) ? n : null;
}

function parseOptionalBool(raw: string | undefined): boolean | null {
  if (raw === '1') return true;
  if (raw === '0') return false;
  return null;
}

/** Parses a STATE line from PhoneRemoteProtocol.kt. */
export function parseStateLine(line: string, nowMs = Date.now()): NuvioNowPlaying | null {
  const parts = line.trim().split(' ');
  if (parts[0] !== 'STATE' || parts.length < 11) return null;
  const positionMs = Number.parseInt(parts[3], 10);
  const durationMs = Number.parseInt(parts[4], 10);
  const title = optionalField(parts[8]);
  if (!Number.isFinite(positionMs) || !Number.isFinite(durationMs) || !title) return null;

  const volume = parseOptionalInt(parts[5]);
  const volumeMaxRaw = parseOptionalInt(parts[6]);
  const volumeMax = volumeMaxRaw !== null && volumeMaxRaw >= 1 ? volumeMaxRaw : 100;

  return {
    kind: parts[1] === 'music' ? 'music' : 'film',
    isPlaying: parts[2] === '1',
    positionMs: Math.max(0, positionMs),
    durationMs: Math.max(0, durationMs),
    volume,
    volumeMax,
    canSkip: parts[7] === '1',
    title,
    subtitle: optionalField(parts[9]),
    imageUrl: optionalField(parts[10]),
    receivedAtMs: nowMs,
    subtitleTrack: optionalField(parts[11]),
    audioTrack: optionalField(parts[12]),
    speedPercent: parseOptionalInt(parts[13]),
    skipLabel: optionalField(parts[14]),
    canNextEpisode: parts[15] === '1',
    sleepMinutes: Math.max(0, parseOptionalInt(parts[16]) ?? 0),
    shuffle: parseOptionalBool(parts[17]),
    repeat: parseOptionalInt(parts[18]),
    liked: parseOptionalBool(parts[19]),
  };
}

/** Parses a SOURCES line from PhoneRemoteLibraryLines.kt. */
export function parseSourcesLine(line: string): { open: boolean; list: NuvioSourceList | null } | null {
  const parts = line.trim().split(' ');
  if (parts[0] !== 'SOURCES' || parts.length < 2) return null;
  if (parts[1] !== '1') return { open: false, list: null };
  const loading = parts[2] === '1';
  const title = optionalField(parts[3]) ?? '';
  const rawRecords = parts.length > 5 ? parts.slice(4).join(' ') : parts[4];
  const sources: NuvioSourceItem[] = [];
  if (rawRecords && rawRecords !== '-') {
    rawRecords.split(';').forEach((rec, idx) => {
      if (!rec.trim()) return;
      const f = rec.split(',');
      const name = optionalField(f[0]) ?? `Source ${idx + 1}`;
      const sizeVal = parseOptionalInt(f[3]);
      sources.push({
        number: idx + 1,
        name,
        detail: optionalField(f[1]),
        addon: optionalField(f[2]),
        sizeBytes: sizeVal !== null && sizeVal > 0 ? sizeVal : null,
        instant: f[4] === '1',
        playable: f.length >= 6 ? f[5] === '1' : true,
      });
    });
  }
  return { open: true, list: { title, loading, sources } };
}

/** Parses SUBTITLES and AUDIO lines from PhoneRemoteLibraryLines.kt. */
export function parseTrackChoicesLine(line: string): NuvioTrackList | null {
  const parts = line.trim().split(' ');
  if ((parts[0] !== 'SUBTITLES' && parts[0] !== 'AUDIO') || parts.length < 3) return null;
  const loading = parts[1] === '1';
  const rawRecords = parts[2];
  const choices: NuvioTrackChoice[] = [];
  if (rawRecords && rawRecords !== '-') {
    rawRecords.split(';').forEach((rec, idx) => {
      const f = rec.split(',');
      if (f.length < 4) return;
      choices.push({
        number: idx + 1,
        label: optionalField(f[0]) ?? `Track ${idx + 1}`,
        detail: optionalField(f[1]),
        group: optionalField(f[2]) ?? '',
        selected: f[3] === '1',
      });
    });
  }
  return { loading, choices };
}

/** Parses a QUEUE line from PhoneRemoteLibraryLines.kt. */
export function parseQueueLine(line: string): NuvioQueueSnapshot | null {
  const parts = line.trim().split(' ');
  if (parts[0] !== 'QUEUE' || parts.length < 3) return null;
  const playing = Number.parseInt(parts[1], 10);
  if (!Number.isFinite(playing)) return null;
  const rawRecords = parts[2];
  const tracks: NuvioQueueTrack[] = [];
  if (rawRecords && rawRecords !== '-') {
    rawRecords.split(';').forEach((rec) => {
      const f = rec.split(',');
      if (f.length < 6) return;
      const videoId = optionalField(f[0]);
      const title = optionalField(f[1]);
      if (!videoId || !title) return;
      tracks.push({
        videoId,
        title,
        artist: optionalField(f[2]) ?? '',
        seconds: Math.max(0, parseOptionalInt(f[3]) ?? 0),
        cover: optionalField(f[4]),
        upcoming: f[5] === '1',
      });
    });
  }
  return { playing, tracks };
}

/** Parses a WAKE line from PhoneRemoteProtocol.kt. */
export function parseWakeLine(line: string): NuvioWakeInfo | null {
  const parts = line.trim().split(' ');
  if (parts[0] !== 'WAKE') return null;
  const macs = (parts[1] && parts[1] !== '-' ? parts[1].split(',') : []).filter((m) => /^[0-9a-fA-F]{12}$/.test(m));
  const broadcasts = (parts[2] && parts[2] !== '-' ? parts[2].split(',') : []).filter((b) => b.trim().length > 0);
  return { macs, broadcasts };
}

function parseVideoTitlesRecordList(rawRecords: string | undefined): NuvioVideoTitle[] {
  const items: NuvioVideoTitle[] = [];
  if (!rawRecords || rawRecords === '-') return items;
  rawRecords.split(';').forEach((rec) => {
    const f = rec.split(',');
    if (f.length < 8) return;
    const id = optionalField(f[2]);
    const name = optionalField(f[3]);
    if (!id || !name) return;
    items.push({
      section: optionalField(f[0]) ?? '',
      contentType: optionalField(f[1]) ?? 'movie',
      id,
      name,
      year: optionalField(f[4]),
      poster: optionalField(f[5]),
      posterFallback: optionalField(f[6]),
      watched: f[7] === '1',
    });
  });
  return items;
}

/** Parses a LIBRARY video <ready> <titles> line. */
export function parseLibraryVideoLine(line: string): { ready: boolean; items: NuvioVideoTitle[] } | null {
  const parts = line.trim().split(' ');
  if (parts[0] !== 'LIBRARY' || parts[1] !== 'video' || parts.length < 4) return null;
  return {
    ready: parts[2] === '1',
    items: parseVideoTitlesRecordList(parts[3]),
  };
}

/** Parses a LIBRARY music <ready> <artists> <albums> line. */
export function parseLibraryMusicLine(
  line: string,
): { ready: boolean; artists: NuvioMusicArtist[]; albums: NuvioMusicAlbum[] } | null {
  const parts = line.trim().split(' ');
  if (parts[0] !== 'LIBRARY' || parts[1] !== 'music' || parts.length < 5) return null;
  const artists: NuvioMusicArtist[] = [];
  if (parts[3] && parts[3] !== '-') {
    parts[3].split(';').forEach((rec) => {
      const f = rec.split(',');
      if (f.length < 4) return;
      const id = optionalField(f[0]);
      const name = optionalField(f[1]);
      if (!id || !name) return;
      artists.push({
        id,
        name,
        photo: optionalField(f[2]),
        starred: f[3] === '1',
      });
    });
  }
  const albums: NuvioMusicAlbum[] = [];
  if (parts[4] && parts[4] !== '-') {
    parts[4].split(';').forEach((rec) => {
      const f = rec.split(',');
      if (f.length < 7) return;
      const browseId = optionalField(f[1]);
      const title = optionalField(f[2]);
      if (!browseId || !title) return;
      albums.push({
        artistId: optionalField(f[0]) ?? '',
        browseId,
        title,
        kind: optionalField(f[3]),
        year: optionalField(f[4]),
        cover: optionalField(f[5]),
        starred: f[6] === '1',
      });
    });
  }
  return { ready: parts[2] === '1', artists, albums };
}

/** Parses a CONTINUE <items> line. */
export function parseContinueLine(line: string): NuvioContinueItem[] | null {
  const parts = line.trim().split(' ');
  if (parts[0] !== 'CONTINUE' || parts.length < 2) return null;
  const items: NuvioContinueItem[] = [];
  if (parts[1] && parts[1] !== '-') {
    parts[1].split(';').forEach((rec) => {
      const f = rec.split(',');
      if (f.length < 11) return;
      const parentId = optionalField(f[1]);
      const videoId = optionalField(f[2]);
      const title = optionalField(f[3]);
      if (!parentId || !videoId || !title) return;
      items.push({
        contentType: optionalField(f[0]) ?? 'movie',
        parentId,
        videoId,
        title,
        episode: optionalField(f[4]),
        poster: optionalField(f[5]),
        still: optionalField(f[6]),
        positionMs: Math.max(0, parseOptionalInt(f[7]) ?? 0),
        durationMs: Math.max(0, parseOptionalInt(f[8]) ?? 0),
        lastSource: optionalField(f[9]),
        progressPermille: Math.max(0, Math.min(1000, parseOptionalInt(f[10]) ?? 0)),
      });
    });
  }
  return items;
}

/** Parses an EPISODES <type> <id> <found> <episodes> line. */
export function parseEpisodesLine(
  line: string,
): { contentType: string; id: string; found: boolean; episodes: NuvioEpisodeItem[] } | null {
  const parts = line.trim().split(' ');
  if (parts[0] !== 'EPISODES' || parts.length < 5) return null;
  const contentType = optionalField(parts[1]) ?? 'series';
  const id = optionalField(parts[2]);
  if (!id) return null;
  const found = parts[3] === '1';
  const episodes: NuvioEpisodeItem[] = [];
  if (parts[4] && parts[4] !== '-') {
    parts[4].split(';').forEach((rec) => {
      const f = rec.split(',');
      if (f.length < 7) return;
      const videoId = optionalField(f[3]);
      if (!videoId) return;
      episodes.push({
        season: Math.max(0, parseOptionalInt(f[0]) ?? 0),
        episode: Math.max(0, parseOptionalInt(f[1]) ?? 0),
        title: optionalField(f[2]) ?? '',
        videoId,
        thumbnail: optionalField(f[4]),
        released: optionalField(f[5]),
        progressPermille: Math.max(0, Math.min(1000, parseOptionalInt(f[6]) ?? 0)),
      });
    });
  }
  return { contentType, id, found, episodes };
}

/** Parses a SEARCH_RESULTS <loading> <query> <titles> line. */
export function parseSearchResultsLine(
  line: string,
): { loading: boolean; query: string; items: NuvioVideoTitle[] } | null {
  const parts = line.trim().split(' ');
  if (parts[0] !== 'SEARCH_RESULTS' || parts.length < 4) return null;
  return {
    loading: parts[1] === '1',
    query: optionalField(parts[2]) ?? '',
    items: parseVideoTitlesRecordList(parts[3]),
  };
}

/** Parses an ALBUM_TRACKS <browseId> <tracks> line. */
export function parseAlbumTracksLine(line: string): { browseId: string; tracks: NuvioAlbumTrack[] } | null {
  const parts = line.trim().split(' ');
  if (parts[0] !== 'ALBUM_TRACKS' || parts.length < 3) return null;
  const browseId = optionalField(parts[1]);
  if (!browseId) return null;
  const tracks: NuvioAlbumTrack[] = [];
  if (parts[2] && parts[2] !== '-') {
    parts[2].split(';').forEach((rec) => {
      const f = rec.split(',');
      if (f.length < 5) return;
      const videoId = optionalField(f[0]);
      const title = optionalField(f[1]);
      if (!videoId || !title) return;
      tracks.push({
        videoId,
        title,
        artist: optionalField(f[2]) ?? '',
        seconds: Math.max(0, parseOptionalInt(f[3]) ?? 0),
        playable: f[4] === '1',
      });
    });
  }
  return { browseId, tracks };
}

/** Parses a MENU <kind> <type> <id> <ref> <heading> <detail> <actions> line. */
export function parseMenuLine(line: string): NuvioMenuSheet | null {
  const parts = line.trim().split(' ');
  if (parts[0] !== 'MENU' || parts.length < 8) return null;
  const id = optionalField(parts[3]);
  if (!id) return null;
  const actions: NuvioMenuAction[] = [];
  if (parts[7] && parts[7] !== '-') {
    parts[7].split(';').forEach((rec) => {
      const f = rec.split(',');
      if (f.length < 2) return;
      const code = optionalField(f[0]);
      const label = optionalField(f[1]);
      if (code && label) actions.push({ code, label });
    });
  }
  return {
    kind: optionalField(parts[1]) ?? 'title',
    contentType: optionalField(parts[2]) ?? 'movie',
    id,
    ref: optionalField(parts[4]),
    heading: optionalField(parts[5]) ?? '',
    detail: optionalField(parts[6]),
    actions,
  };
}

/** Parses a PLAYLISTS <kind> <type> <id> <ref> <scope> <label> <seasons> <items> line (or short 6-field form). */
export function parsePlaylistsLine(line: string): NuvioPlaylistsSheet | null {
  const parts = line.trim().split(' ');
  if (parts[0] !== 'PLAYLISTS' || parts.length < 6) return null;
  const isFullTarget = parts.length >= 9;
  const target = isFullTarget ? (optionalField(parts[3]) ?? '') : (optionalField(parts[1]) ?? '');
  const scope = optionalField(isFullTarget ? parts[5] : parts[2]) ?? '';
  const label = optionalField(isFullTarget ? parts[6] : parts[3]) ?? '';
  const seasons = optionalField(isFullTarget ? parts[7] : parts[4]);
  const rawItems = isFullTarget ? parts[8] : parts[5];
  const items: NuvioPlaylistChoice[] = [];
  if (rawItems && rawItems !== '-') {
    rawItems.split(';').forEach((rec) => {
      const f = rec.split(',');
      if (f.length < 3) return;
      const id = optionalField(f[0]);
      const name = optionalField(f[1]);
      if (!id || !name) return;
      items.push({ id, name, count: Math.max(0, parseOptionalInt(f[2]) ?? 0) });
    });
  }
  return { target, scope, label, seasons, items };
}

/** Parses a RECAP <state> <heading> <text> line. */
export function parseRecapLine(line: string): NuvioRecapSheet | null {
  const parts = line.trim().split(' ');
  if (parts[0] !== 'RECAP' || parts.length < 4) return null;
  return {
    state: parseOptionalInt(parts[1]) ?? 0,
    heading: optionalField(parts[2]) ?? '',
    text: optionalField(parts[3]) ?? '',
  };
}

/** Parses a DONE <ok> <message> <refresh> line. */
export function parseDoneLine(line: string): NuvioActionDone | null {
  const parts = line.trim().split(' ');
  if (parts[0] !== 'DONE' || parts.length < 3) return null;
  return {
    ok: parts[1] === '1',
    message: optionalField(parts[2]) ?? '',
    refresh: optionalField(parts[3]),
  };
}

/** Parses a LYRICS <kind> <title> <lines> line. */
export function parseLyricsLine(line: string): NuvioLyricsSheet | null {
  const parts = line.trim().split(' ');
  if (parts[0] !== 'LYRICS' || parts.length < 4) return null;
  const rawKind = parts[1];
  const kind: 'synced' | 'plain' | 'none' = rawKind === 'synced' ? 'synced' : rawKind === 'plain' ? 'plain' : 'none';
  const title = optionalField(parts[2]);
  const lines: NuvioLyricLine[] = [];
  if (parts[3] && parts[3] !== '-') {
    parts[3].split(';').forEach((rec) => {
      const f = rec.split(',');
      if (f.length < 2) return;
      const text = optionalField(f[1]);
      if (text === null) return;
      const words: NuvioLyricWord[] = [];
      const rawWords = optionalField(f[2]);
      if (rawWords) {
        rawWords.split('|').forEach((w) => {
          const seg = w.split(':');
          if (seg.length < 3) return;
          const startMs = parseOptionalInt(seg[1]);
          const endMs = parseOptionalInt(seg[2]);
          if (seg[0] && startMs !== null && endMs !== null) {
            words.push({ word: seg[0], startMs, endMs });
          }
        });
      }
      lines.push({
        timeMs: parseOptionalInt(f[0]),
        text,
        words,
      });
    });
  }
  return { kind, title, lines };
}

/** Parses a LIVETV 0 | LIVETV 1 <stage> <heading> <sub> <stages> <items> <buttons> <empty> line. */
export function parseLiveTvLine(line: string): { open: boolean; screen: NuvioLiveTvScreen | null } | null {
  const parts = line.trim().split(' ');
  if (parts[0] !== 'LIVETV' || parts.length < 2) return null;
  if (parts[1] !== '1') return { open: false, screen: null };
  if (parts.length < 9) return null;
  const stages: NuvioLiveTvStage[] = [];
  if (parts[5] && parts[5] !== '-') {
    parts[5].split(';').forEach((rec) => {
      const f = rec.split(',');
      if (f.length < 2) return;
      const name = optionalField(f[0]);
      const label = optionalField(f[1]);
      if (name && label) stages.push({ name, label });
    });
  }
  const items: NuvioLiveTvItem[] = [];
  if (parts[6] && parts[6] !== '-') {
    parts[6].split(';').forEach((rec) => {
      const f = rec.split(',');
      if (f.length < 9) return;
      const title = optionalField(f[3]);
      if (!title) return;
      items.push({
        group: optionalField(f[0]) ?? '',
        index: parseOptionalInt(f[1]) ?? 0,
        slot: optionalField(f[2]) ?? '',
        title,
        sub: optionalField(f[4]),
        picture: optionalField(f[5]),
        live: f[6] === '1',
        progress: Math.max(0, Math.min(1000, parseOptionalInt(f[7]) ?? 0)),
        action: optionalField(f[8]) ?? '',
      });
    });
  }
  const buttons: NuvioLiveTvButton[] = [];
  if (parts[7] && parts[7] !== '-') {
    parts[7].split(';').forEach((rec) => {
      const f = rec.split(',');
      if (f.length < 4) return;
      const label = optionalField(f[2]);
      if (!label) return;
      buttons.push({
        group: optionalField(f[0]) ?? '',
        index: parseOptionalInt(f[1]) ?? 0,
        label,
        accent: f[3] === '1',
      });
    });
  }
  return {
    open: true,
    screen: {
      stage: optionalField(parts[2]) ?? '',
      heading: optionalField(parts[3]) ?? '',
      sub: optionalField(parts[4]),
      stages,
      items,
      buttons,
      empty: optionalField(parts[8]),
    },
  };
}

/** Parses a TONIGHT 0 | TONIGHT 1 <20 fields> line. */
export function parseTonightLine(line: string): { open: boolean; screen: NuvioTonightScreen | null } | null {
  const parts = line.trim().split(' ');
  if (parts[0] !== 'TONIGHT' || parts.length < 2) return null;
  if (parts[1] !== '1') return { open: false, screen: null };
  if (parts.length < 18) return null;
  const answers: string[] = [];
  if (parts[15] && parts[15] !== '-') {
    parts[15].split(';').forEach((rec) => {
      const val = optionalField(rec);
      if (val) answers.push(val);
    });
  }
  const cards: NuvioTonightCard[] = [];
  if (parts[16] && parts[16] !== '-') {
    parts[16].split(';').forEach((rec) => {
      const f = rec.split(',');
      if (f.length < 5) return;
      const name = optionalField(f[1]);
      if (!name) return;
      cards.push({
        slot: optionalField(f[0]) ?? '',
        name,
        why: optionalField(f[2]),
        meta: optionalField(f[3]),
        poster: optionalField(f[4]),
        flag: f[5] === '1',
      });
    });
  }
  return {
    open: true,
    screen: {
      view: optionalField(parts[2]) ?? '',
      mode: optionalField(parts[3]) ?? '',
      loading: parts[4] === '1',
      number: parseOptionalInt(parts[5]) ?? 0,
      total: parseOptionalInt(parts[6]) ?? 0,
      countFrom: parseOptionalInt(parts[7]) ?? 0,
      countLeft: parseOptionalInt(parts[8]) ?? 0,
      reshuffleLeft: parts[9] === '1',
      prompt: optionalField(parts[10]),
      leftLabel: optionalField(parts[11]),
      leftDetail: optionalField(parts[12]),
      rightLabel: optionalField(parts[13]),
      rightDetail: optionalField(parts[14]),
      answers,
      cards,
      extra: optionalField(parts[17]),
      seconds: parseOptionalInt(parts[18]) ?? 0,
      secondsTotal: parseOptionalInt(parts[19]) ?? 0,
      paused: parts[20] === '1',
      selected: parseOptionalInt(parts[21]) ?? -1,
    },
  };
}

/**
 * Local TCP bridge to the native PhoneRemote server in NuvioDesktop (127.0.0.1:47321).
 * Fails silently when Nuvio is closed or Phone Remote is disabled so the companion
 * keeps operating normally via Win32 input injection + logtail.
 */
export class NuvioBridge extends EventEmitter {
  private readonly host: string;
  private readonly port: number;
  private readonly deviceName: string;
  private readonly storagePath: string;
  private readonly pingIntervalMs: number;
  private readonly reconnectBaseMs: number;

  private token: string | null = null;
  private pcName: string | null = null;
  private status: NuvioBridgeStatus = 'disconnected';
  private nowPlaying: NuvioNowPlaying | null = null;
  private sources: NuvioSourceList | null = null;
  private wake: NuvioWakeInfo | null = null;
  private liveTv: NuvioLiveTvScreen | null = null;
  private tonight: NuvioTonightScreen | null = null;

  private socket: net.Socket | null = null;
  private lineBuffer = '';
  private pingTimer: NodeJS.Timeout | null = null;
  private reconnectTimer: NodeJS.Timeout | null = null;
  private reconnectAttempts = 0;
  private stopped = true;
  private handshakeDone = false;

  private pendingPair: {
    resolve: (res: BridgePairResult) => void;
    timer: NodeJS.Timeout;
  } | null = null;

  private pendingOpenQueue: Array<{
    resolve: (res: BridgeOpenResult) => void;
    timer: NodeJS.Timeout;
  }> = [];

  constructor(opts: NuvioBridgeOptions = {}) {
    super();
    this.host = opts.host ?? NUVIO_REMOTE_DEFAULT_HOST;
    this.port = opts.port ?? NUVIO_REMOTE_DEFAULT_PORT;
    this.deviceName = opts.deviceName ?? 'NuvioRemoteCompanion';
    const baseDir = process.env.APPDATA
      ? path.join(process.env.APPDATA, 'NuvioRemote')
      : path.resolve(__dirname, '..');
    this.storagePath = opts.storagePath ?? path.join(baseDir, 'nuvio-bridge.json');
    this.pingIntervalMs = opts.pingIntervalMs ?? 8000;
    this.reconnectBaseMs = opts.reconnectBaseMs ?? 3000;
    this.loadToken();
  }

  start(): void {
    if (!this.stopped) return;
    this.stopped = false;
    this.connect();
  }

  stop(): void {
    this.stopped = true;
    this.clearTimers();
    this.rejectPendingPair({ ok: false, error: 'UNAVAILABLE' });
    this.flushPendingOpens({ ok: false });
    this.destroySocket();
    this.setStatus('disconnected');
  }

  isConnected(): boolean {
    return this.status === 'connected' && this.socket !== null && !this.socket.destroyed;
  }

  snapshot(): NuvioBridgeSnapshot {
    return {
      status: this.status,
      pcName: this.pcName,
      port: this.port,
      nowPlaying: this.nowPlaying,
      sources: this.sources,
      wake: this.wake,
      liveTv: this.liveTv,
      tonight: this.tonight,
    };
  }

  /** Pairs the local bridge using the 6-digit PIN shown in Nuvio > Settings > Keyboard shortcuts > Phone remote. */
  async pair(rawPin: string): Promise<BridgePairResult> {
    const pin = String(rawPin ?? '').replace(/\D/g, '');
    if (pin.length !== 6) {
      return { ok: false, error: 'INVALID_PIN' };
    }
    if (this.pendingPair) {
      this.rejectPendingPair({ ok: false, error: 'UNAVAILABLE' });
    }

    // Already connected and authenticated; no need to pair again
    if (this.isConnected()) {
      return { ok: true, pcName: this.pcName ?? 'Nuvio' };
    }

    // Ensure there is an open socket waiting in pin_required state
    if (this.status !== 'pin_required' || !this.socket || this.socket.destroyed) {
      const ready = await this.connectAndWaitForPinPrompt();
      if (!ready) {
        return { ok: false, error: 'UNAVAILABLE' };
      }
      if (this.isConnected()) {
        return { ok: true, pcName: this.pcName ?? 'Nuvio' };
      }
    }

    return new Promise<BridgePairResult>((resolve) => {
      const timer = setTimeout(() => {
        if (this.pendingPair?.resolve === resolve) {
          this.pendingPair = null;
          resolve({ ok: false, error: 'TIMEOUT' });
        }
      }, 6000);
      timer.unref?.();
      this.pendingPair = { resolve, timer };
      this.sendRaw(`PAIR ${pin}`);
    });
  }

  sendKey(wireKey: string): boolean {
    if (!this.isConnected()) return false;
    return this.sendRaw(`KEY ${wireKey}`);
  }

  sendText(text: string): boolean {
    if (!this.isConnected() || !text) return false;
    return this.sendRaw(`TEXT ${encodeWire(text)}`);
  }

  seek(positionMs: number): boolean {
    if (!this.isConnected() || !Number.isFinite(positionMs)) return false;
    return this.sendRaw(`SEEK ${Math.max(0, Math.round(positionMs))}`);
  }

  setVolume(volume: number): boolean {
    if (!this.isConnected() || !Number.isFinite(volume)) return false;
    return this.sendRaw(`VOLUME ${Math.max(0, Math.round(volume))}`);
  }

  control(action: NuvioControlAction, value?: number): boolean {
    if (!this.isConnected()) return false;
    switch (action) {
      case 'SPEED':
      case 'SLEEP':
      case 'SHUFFLE':
      case 'REPEAT': {
        if (typeof value !== 'number' || !Number.isFinite(value)) return false;
        return this.sendRaw(`${action} ${Math.max(0, Math.round(value))}`);
      }
      case 'SKIP_INTRO':
      case 'NEXT_EPISODE':
      case 'LIKE':
      case 'RADIO':
        return this.sendRaw(action);
      default:
        return false;
    }
  }

  requestSubtitles(): boolean {
    if (!this.isConnected()) return false;
    return this.sendRaw('SUBTITLES');
  }

  pickSubtitle(number: number): boolean {
    if (!this.isConnected() || !Number.isInteger(number) || number < 1) return false;
    return this.sendRaw(`PICK_SUBTITLE ${number}`);
  }

  requestAudio(): boolean {
    if (!this.isConnected()) return false;
    return this.sendRaw('AUDIO');
  }

  pickAudio(number: number): boolean {
    if (!this.isConnected() || !Number.isInteger(number) || number < 1) return false;
    return this.sendRaw(`PICK_AUDIO ${number}`);
  }

  requestSources(force = false): boolean {
    if (!this.isConnected()) return false;
    if (!force && this.sources !== null) {
      this.emit('sources', this.sources);
      return true;
    }
    return this.sendKey('sources');
  }

  pickSource(number: number): Promise<BridgeOpenResult> {
    if (!this.isConnected() || !Number.isInteger(number) || number < 1) {
      return Promise.resolve({ ok: false });
    }
    return this.sendExpectingOpening(`PICK_SOURCE ${number}`);
  }

  requestQueue(): boolean {
    if (!this.isConnected()) return false;
    return this.sendRaw('QUEUE');
  }

  queueAction(action: 'play' | 'next' | 'remove', videoId: string): boolean {
    if (!this.isConnected() || !videoId) return false;
    const cmd = action === 'play' ? 'QUEUE_PLAY' : action === 'next' ? 'QUEUE_NEXT' : 'QUEUE_REMOVE';
    return this.sendRaw(`${cmd} ${encodeWire(videoId)}`);
  }

  openLink(url: string): Promise<BridgeOpenResult> {
    const trimmed = url.trim();
    if (!this.isConnected() || !trimmed) {
      return Promise.resolve({ ok: false });
    }
    return this.sendExpectingOpening(`OPEN ${encodeWire(trimmed)}`);
  }

  requestLibrary(kind: 'video' | 'music'): boolean {
    if (!this.isConnected()) return false;
    return this.sendRaw(`LIBRARY ${kind}`);
  }

  requestContinue(): boolean {
    if (!this.isConnected()) return false;
    return this.sendRaw('CONTINUE');
  }

  resume(parentId: string, videoId: string): Promise<BridgeOpenResult> {
    if (!this.isConnected() || !parentId || !videoId) {
      return Promise.resolve({ ok: false });
    }
    return this.sendExpectingOpening(`RESUME ${encodeWire(parentId)} ${encodeWire(videoId)}`);
  }

  openTitle(contentType: string, id: string): Promise<BridgeOpenResult> {
    if (!this.isConnected() || !contentType || !id) {
      return Promise.resolve({ ok: false });
    }
    return this.sendExpectingOpening(`OPEN_TITLE ${encodeWire(contentType)} ${encodeWire(id)}`);
  }

  requestEpisodes(contentType: string, id: string): boolean {
    if (!this.isConnected() || !contentType || !id) return false;
    return this.sendRaw(`EPISODES ${encodeWire(contentType)} ${encodeWire(id)}`);
  }

  playEpisode(contentType: string, id: string, videoId: string): Promise<BridgeOpenResult> {
    if (!this.isConnected() || !contentType || !id || !videoId) {
      return Promise.resolve({ ok: false });
    }
    return this.sendExpectingOpening(`PLAY_EPISODE ${encodeWire(contentType)} ${encodeWire(id)} ${encodeWire(videoId)}`);
  }

  search(query: string): boolean {
    if (!this.isConnected()) return false;
    const q = query.trim();
    return this.sendRaw(`SEARCH ${q ? encodeWire(q) : '-'}`);
  }

  requestAlbumTracks(browseId: string): boolean {
    if (!this.isConnected() || !browseId) return false;
    return this.sendRaw(`ALBUM_TRACKS ${encodeWire(browseId)}`);
  }

  playTrack(browseId: string, index: number): Promise<BridgeOpenResult> {
    if (!this.isConnected() || !browseId || !Number.isInteger(index) || index < 0) {
      return Promise.resolve({ ok: false });
    }
    return this.sendExpectingOpening(`PLAY_TRACK ${encodeWire(browseId)} ${index}`);
  }

  playAlbum(browseId: string, action: 'play' | 'shuffle' | 'queue'): Promise<BridgeOpenResult> {
    if (!this.isConnected() || !browseId) {
      return Promise.resolve({ ok: false });
    }
    return this.sendExpectingOpening(`PLAY_ALBUM ${encodeWire(browseId)} ${action}`);
  }

  artistAction(artistId: string, action: 'mix' | 'radio'): Promise<BridgeOpenResult> {
    if (!this.isConnected() || !artistId) {
      return Promise.resolve({ ok: false });
    }
    return this.sendExpectingOpening(`ARTIST ${encodeWire(artistId)} ${action}`);
  }

  requestMenu(kind: string, contentType: string, id: string, ref?: string): boolean {
    if (!this.isConnected() || !kind || !contentType || !id) return false;
    const refPart = ref && ref.trim() ? encodeWire(ref.trim()) : '-';
    return this.sendRaw(`MENU ${encodeWire(kind)} ${encodeWire(contentType)} ${encodeWire(id)} ${refPart}`);
  }

  doMenuAction(
    kind: string,
    contentType: string,
    id: string,
    code: string,
    ref?: string,
    args: string[] = [],
  ): boolean {
    if (!this.isConnected() || !kind || !contentType || !id || !code) return false;
    const refPart = ref && ref.trim() ? encodeWire(ref.trim()) : '-';
    const encodedArgs = args.map((a) => (a ? encodeWire(a) : '-'));
    const suffix = encodedArgs.length > 0 ? ` ${encodedArgs.join(' ')}` : '';
    return this.sendRaw(
      `DO ${encodeWire(kind)} ${encodeWire(contentType)} ${encodeWire(id)} ${refPart} ${encodeWire(code)}${suffix}`,
    );
  }

  requestLyrics(): boolean {
    if (!this.isConnected()) return false;
    return this.sendRaw('LYRICS');
  }

  liveTvDo(args: string[]): boolean {
    if (!this.isConnected() || args.length === 0) return false;
    const encoded = args.map((a) => encodeWire(a)).join(' ');
    return this.sendRaw(`LIVETV_DO ${encoded}`);
  }

  tonightDo(action: string, number?: number): boolean {
    if (!this.isConnected() || !action) return false;
    const numPart = typeof number === 'number' && Number.isFinite(number) ? ` ${Math.max(0, Math.round(number))}` : '';
    return this.sendRaw(`TONIGHT_DO ${encodeWire(action)}${numPart}`);
  }

  private sendExpectingOpening(line: string): Promise<BridgeOpenResult> {
    return new Promise<BridgeOpenResult>((resolve) => {
      const timer = setTimeout(() => {
        const idx = this.pendingOpenQueue.findIndex((p) => p.resolve === resolve);
        if (idx >= 0) this.pendingOpenQueue.splice(idx, 1);
        resolve({ ok: false });
      }, 6000);
      timer.unref?.();
      this.pendingOpenQueue.push({ resolve, timer });
      if (!this.sendRaw(line)) {
        const idx = this.pendingOpenQueue.findIndex((p) => p.resolve === resolve);
        if (idx >= 0) this.pendingOpenQueue.splice(idx, 1);
        clearTimeout(timer);
        resolve({ ok: false });
      }
    });
  }

  private connectAndWaitForPinPrompt(): Promise<boolean> {
    return new Promise<boolean>((resolve) => {
      let settled = false;
      const done = (ok: boolean) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        this.off('status', onStatus);
        resolve(ok);
      };
      const onStatus = (snap: NuvioBridgeSnapshot) => {
        if (snap.status === 'pin_required' || snap.status === 'connected') done(true);
        else if (snap.status === 'disconnected') done(false);
      };
      const timer = setTimeout(() => done(false), 4000);
      timer.unref?.();
      this.on('status', onStatus);
      this.stopped = false;
      this.connect(true);
    });
  }

  private connect(forceReset = false): void {
    if (this.stopped) return;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (forceReset) {
      this.destroySocket();
    } else if (this.socket && !this.socket.destroyed) {
      return;
    }

    this.handshakeDone = false;
    this.lineBuffer = '';
    this.setStatus('connecting');

    const sock = net.createConnection({ host: this.host, port: this.port });
    sock.setNoDelay(true);
    this.socket = sock;

    sock.on('connect', () => {
      if (this.socket !== sock) return;
      this.reconnectAttempts = 0;
      const tok = this.token ?? '-';
      this.sendRaw(`HELLO ${NUVIO_REMOTE_PROTOCOL_VERSION} ${tok} ${encodeWire(this.deviceName)}`);
    });

    sock.on('data', (chunk: Buffer) => {
      if (this.socket !== sock) return;
      this.lineBuffer += chunk.toString('utf8');
      let nl = this.lineBuffer.indexOf('\n');
      while (nl !== -1) {
        const line = this.lineBuffer.slice(0, nl).replace(/\r$/, '');
        this.lineBuffer = this.lineBuffer.slice(nl + 1);
        if (line.length > 0) this.onLine(line);
        nl = this.lineBuffer.indexOf('\n');
      }
    });

    sock.on('error', (err: NodeJS.ErrnoException) => {
      if (this.socket !== sock) return;
      if (err.code !== 'ECONNREFUSED' || this.reconnectAttempts === 0) {
        warn(`nuvio-bridge: TCP error ${this.host}:${this.port} (${err.code ?? err.message})`);
      }
    });

    sock.on('close', () => {
      if (this.socket !== sock) return;
      this.socket = null;
      this.handshakeDone = false;
      this.stopPing();
      this.rejectPendingPair({ ok: false, error: 'UNAVAILABLE' });
      this.flushPendingOpens({ ok: false });

      const hadPlaying = this.nowPlaying !== null;
      this.nowPlaying = null;
      this.sources = null;
      if (hadPlaying) this.emit('state', null);

      this.setStatus('disconnected');
      if (!this.stopped) this.scheduleReconnect();
    });
  }

  private onLine(line: string): void {
    const parts = line.trim().split(' ');
    const head = parts[0];

    if (!this.handshakeDone) {
      if (head === 'WELCOME') {
        this.handshakeDone = true;
        this.pcName = parts.length > 1 ? decodeWire(parts.slice(1).join(' ')) : 'Nuvio';
        info(`nuvio-bridge: connected to Nuvio "${this.pcName}" at ${this.host}:${this.port}`);
        this.onAuthenticated();
        return;
      }
      if (head === 'PIN_REQUIRED') {
        // If a saved token was rejected with PIN_REQUIRED, it was revoked in Nuvio
        if (this.token) {
          this.token = null;
          this.saveToken();
        }
        info(`nuvio-bridge: Nuvio at ${this.host}:${this.port} requires 6-digit PIN pairing`);
        this.setStatus('pin_required');
        return;
      }
      if (head === 'PAIRED') {
        const token = parts[1] ?? '';
        const pcName = parts.length > 2 ? decodeWire(parts.slice(2).join(' ')) : 'Nuvio';
        if (token) {
          this.token = token;
          this.pcName = pcName;
          this.saveToken();
          this.handshakeDone = true;
          info(`nuvio-bridge: paired successfully with Nuvio "${pcName}"`);
          this.resolvePendingPair({ ok: true, pcName });
          this.onAuthenticated();
        } else {
          this.resolvePendingPair({ ok: false, error: 'UNAVAILABLE' });
        }
        return;
      }
      if (head === 'BAD_PIN') {
        warn('nuvio-bridge: PIN rejected by Nuvio (BAD_PIN)');
        this.resolvePendingPair({ ok: false, error: 'BAD_PIN' });
        return;
      }
      if (head === 'LOCKED') {
        warn('nuvio-bridge: temporarily locked by Nuvio after incorrect attempts (LOCKED)');
        this.resolvePendingPair({ ok: false, error: 'LOCKED' });
        this.destroySocket();
        return;
      }
      if (head === 'UNSUPPORTED') {
        warn(`nuvio-bridge: incompatible protocol (${line})`);
        this.destroySocket();
        return;
      }
      return;
    }

    switch (head) {
      case 'PONG':
        return;
      case 'IDLE':
        this.nowPlaying = null;
        this.emit('state', null);
        return;
      case 'STATE': {
        const state = parseStateLine(line);
        if (state) {
          this.nowPlaying = state;
          this.emit('state', state);
        }
        return;
      }
      case 'SOURCES': {
        const parsed = parseSourcesLine(line);
        if (parsed) {
          this.sources = parsed.open ? parsed.list : null;
          if (parsed.open) {
            info(
              `nuvio-bridge: SOURCES open=1 loading=${parsed.list?.loading ? 1 : 0} count=${parsed.list?.sources.length ?? 0} title="${parsed.list?.title ?? ''}"`,
            );
          }
          this.emit('sources', this.sources);
        }
        return;
      }
      case 'SUBTITLES': {
        const list = parseTrackChoicesLine(line);
        if (list) this.emit('subtitles', list);
        return;
      }
      case 'AUDIO': {
        const list = parseTrackChoicesLine(line);
        if (list) this.emit('audio', list);
        return;
      }
      case 'QUEUE': {
        const queue = parseQueueLine(line);
        if (queue) this.emit('queue', queue);
        return;
      }
      case 'WAKE': {
        const wake = parseWakeLine(line);
        if (wake) {
          this.wake = wake;
          this.emit('wake', wake);
        }
        return;
      }
      case 'OPENING': {
        const what = parts[1] ? decodeWire(parts[1]) : '';
        this.resolveNextOpen({ ok: true, what });
        return;
      }
      case 'CANT_OPEN':
        this.resolveNextOpen({ ok: false });
        return;
      case 'LIBRARY': {
        if (parts[1] === 'video') {
          const parsed = parseLibraryVideoLine(line);
          if (parsed) this.emit('library_video', parsed);
        } else if (parts[1] === 'music') {
          const parsed = parseLibraryMusicLine(line);
          if (parsed) this.emit('library_music', parsed);
        }
        return;
      }
      case 'CONTINUE': {
        const items = parseContinueLine(line);
        if (items) this.emit('continue', items);
        return;
      }
      case 'EPISODES': {
        const parsed = parseEpisodesLine(line);
        if (parsed) this.emit('episodes', parsed);
        return;
      }
      case 'SEARCH_RESULTS': {
        const parsed = parseSearchResultsLine(line);
        if (parsed) this.emit('search_results', parsed);
        return;
      }
      case 'ALBUM_TRACKS': {
        const parsed = parseAlbumTracksLine(line);
        if (parsed) this.emit('album_tracks', parsed);
        return;
      }
      case 'MENU': {
        const menu = parseMenuLine(line);
        if (menu) this.emit('menu', menu);
        return;
      }
      case 'PLAYLISTS': {
        const playlists = parsePlaylistsLine(line);
        if (playlists) this.emit('playlists', playlists);
        return;
      }
      case 'RECAP': {
        const recap = parseRecapLine(line);
        if (recap) this.emit('recap', recap);
        return;
      }
      case 'DONE': {
        const done = parseDoneLine(line);
        if (done) this.emit('action_done', done);
        return;
      }
      case 'LYRICS': {
        const lyrics = parseLyricsLine(line);
        if (lyrics) this.emit('lyrics', lyrics);
        return;
      }
      case 'LIVETV': {
        const parsed = parseLiveTvLine(line);
        if (parsed) {
          this.liveTv = parsed.open ? parsed.screen : null;
          this.emit('livetv', this.liveTv);
        }
        return;
      }
      case 'TONIGHT': {
        const parsed = parseTonightLine(line);
        if (parsed) {
          this.tonight = parsed.open ? parsed.screen : null;
          this.emit('tonight', this.tonight);
        }
        return;
      }
      default:
        return;
    }
  }

  private onAuthenticated(): void {
    this.setStatus('connected');
    this.sendRaw('SUBSCRIBE');
    this.startPing();
  }

  private startPing(): void {
    this.stopPing();
    this.pingTimer = setInterval(() => {
      if (this.isConnected()) {
        this.sendRaw('PING');
      }
    }, this.pingIntervalMs);
    this.pingTimer.unref?.();
  }

  private stopPing(): void {
    if (this.pingTimer) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer || this.stopped) return;
    const delay = Math.min(15_000, this.reconnectBaseMs * Math.pow(1.6, Math.min(this.reconnectAttempts, 4)));
    this.reconnectAttempts += 1;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, Math.round(delay));
    this.reconnectTimer.unref?.();
  }

  private clearTimers(): void {
    this.stopPing();
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private destroySocket(): void {
    if (this.socket) {
      const s = this.socket;
      this.socket = null;
      try {
        s.destroy();
      } catch {
        // ignore
      }
    }
  }

  private sendRaw(line: string): boolean {
    if (!this.socket || this.socket.destroyed) return false;
    try {
      this.socket.write(`${line}\n`, 'utf8');
      return true;
    } catch {
      return false;
    }
  }

  private setStatus(next: NuvioBridgeStatus): void {
    if (this.status === next) return;
    this.status = next;
    this.emit('status', this.snapshot());
  }

  private resolvePendingPair(result: BridgePairResult): void {
    if (!this.pendingPair) return;
    const p = this.pendingPair;
    this.pendingPair = null;
    clearTimeout(p.timer);
    p.resolve(result);
  }

  private rejectPendingPair(result: BridgePairResult): void {
    this.resolvePendingPair(result);
  }

  private resolveNextOpen(result: BridgeOpenResult): void {
    const next = this.pendingOpenQueue.shift();
    if (!next) return;
    clearTimeout(next.timer);
    next.resolve(result);
  }

  private flushPendingOpens(result: BridgeOpenResult): void {
    while (this.pendingOpenQueue.length > 0) {
      this.resolveNextOpen(result);
    }
  }

  private loadToken(): void {
    try {
      if (!fs.existsSync(this.storagePath)) return;
      const raw = JSON.parse(fs.readFileSync(this.storagePath, 'utf8')) as { token?: string; pcName?: string };
      if (typeof raw.token === 'string' && raw.token.trim()) {
        this.token = raw.token.trim();
      }
      if (typeof raw.pcName === 'string' && raw.pcName.trim()) {
        this.pcName = raw.pcName.trim();
      }
    } catch (err) {
      warn(`nuvio-bridge: failed to read saved token: ${(err as Error).message}`);
    }
  }

  private saveToken(): void {
    try {
      const dir = path.dirname(this.storagePath);
      if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(
        this.storagePath,
        JSON.stringify({ token: this.token, pcName: this.pcName }, null, 2),
        'utf8',
      );
    } catch (err) {
      warn(`nuvio-bridge: failed to save token: ${(err as Error).message}`);
    }
  }
}
