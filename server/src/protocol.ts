/**
 * WebSocket JSON message protocol (integrated Phases 1-4).
 * Full specification for Android and desktop clients: server/PROTOCOL.md
 */

export type ScreenName = 'home' | 'detail' | 'player' | 'search' | 'unknown';

export type NavAction =
  | 'up' | 'down' | 'left' | 'right' | 'ok' | 'back'
  | 'home' | 'search' | 'library' | 'discover' | 'calendar' | 'live'
  | 'game' | 'fullscreen' | 'youtube' | 'music';

export type KeyName =
  | 'Up' | 'Down' | 'Left' | 'Right' | 'Enter' | 'Backspace' | 'Escape' | 'Space'
  | 'Tab' | 'PageUp' | 'PageDown' | 'F11' | 'Home' | 'End'
  | 'H' | 'S' | 'L' | 'D' | 'C' | 'V' | 'G' | 'M' | 'O' | 'E' | 'I' | 'A' | 'K' | 'Y' | 'U' | 'Z' | 'X' | 'W'
  | 'VolumeUp' | 'VolumeDown' | 'VolumeMute' | 'Delete' | 'LWin' | 'F5' | 'F4'
  | 'AltTab' | 'WinD' | 'WinTab' | 'WinL' | 'AltF4' | 'CtrlC' | 'CtrlV' | 'CtrlX' | 'CtrlZ' | 'CtrlA' | 'CtrlW' | 'CtrlShiftEsc';

export type MouseButton = 'left' | 'right' | 'middle';

export type ScreenProfile = '720p30' | '720p60' | '1080p30' | '1080p60';

export type WindowAction =
  | 'focus'
  | 'nuvio'
  | 'restart_nuvio'
  | 'mute_toggle'
  | 'beep'
  | 'suspend_display'
  | 'curtain_on'
  | 'curtain_off'
  | 'lock_pc'
  | 'sleep_pc'
  | 'restart_pc'
  | 'shutdown_pc';

export interface MonitorInfo {
  index: number;
  label: string;
  x: number;
  y: number;
  width: number;
  height: number;
  primary: boolean;
}

export type NuvioBridgeStatus = 'disconnected' | 'connecting' | 'pin_required' | 'connected';

export type NuvioControlAction =
  | 'SPEED'
  | 'SKIP_INTRO'
  | 'NEXT_EPISODE'
  | 'SLEEP'
  | 'SHUFFLE'
  | 'REPEAT'
  | 'LIKE'
  | 'RADIO';

export interface NuvioNowPlaying {
  kind: 'film' | 'music';
  isPlaying: boolean;
  positionMs: number;
  durationMs: number;
  volume: number | null;
  volumeMax: number;
  canSkip: boolean;
  title: string;
  subtitle: string | null;
  imageUrl: string | null;
  receivedAtMs: number;
  subtitleTrack: string | null;
  audioTrack: string | null;
  speedPercent: number | null;
  skipLabel: string | null;
  canNextEpisode: boolean;
  sleepMinutes: number;
  shuffle: boolean | null;
  repeat: number | null;
  liked: boolean | null;
}

export interface NuvioSourceItem {
  number: number;
  name: string;
  detail: string | null;
  addon: string | null;
  sizeBytes: number | null;
  instant: boolean;
  playable: boolean;
}

export interface NuvioSourceList {
  title: string;
  loading: boolean;
  sources: NuvioSourceItem[];
}

export interface NuvioTrackChoice {
  number: number;
  label: string;
  detail: string | null;
  group: string;
  selected: boolean;
}

export interface NuvioTrackList {
  loading: boolean;
  choices: NuvioTrackChoice[];
}

export interface NuvioQueueTrack {
  videoId: string;
  title: string;
  artist: string;
  seconds: number;
  cover: string | null;
  upcoming: boolean;
}

export interface NuvioQueueSnapshot {
  playing: number;
  tracks: NuvioQueueTrack[];
}

export interface NuvioWakeInfo {
  macs: string[];
  broadcasts: string[];
}

export interface NuvioVideoTitle {
  section: string;
  contentType: string;
  id: string;
  name: string;
  year: string | null;
  poster: string | null;
  posterFallback: string | null;
  watched: boolean;
}

export interface NuvioContinueItem {
  contentType: string;
  parentId: string;
  videoId: string;
  title: string;
  episode: string | null;
  poster: string | null;
  still: string | null;
  positionMs: number;
  durationMs: number;
  lastSource: string | null;
  progressPermille: number;
}

export interface NuvioEpisodeItem {
  season: number;
  episode: number;
  title: string;
  videoId: string;
  thumbnail: string | null;
  released: string | null;
  progressPermille: number;
}

export interface NuvioMusicArtist {
  id: string;
  name: string;
  photo: string | null;
  starred: boolean;
}

export interface NuvioMusicAlbum {
  artistId: string;
  browseId: string;
  title: string;
  kind: string | null;
  year: string | null;
  cover: string | null;
  starred: boolean;
}

export interface NuvioAlbumTrack {
  videoId: string;
  title: string;
  artist: string;
  seconds: number;
  playable: boolean;
}

export interface NuvioMenuAction {
  code: string;
  label: string;
}

export interface NuvioMenuSheet {
  kind: string;
  contentType: string;
  id: string;
  ref: string | null;
  heading: string;
  detail: string | null;
  actions: NuvioMenuAction[];
}

export interface NuvioPlaylistChoice {
  id: string;
  name: string;
  count: number;
}

export interface NuvioPlaylistsSheet {
  target: string;
  scope: string;
  label: string;
  seasons: string | null;
  items: NuvioPlaylistChoice[];
}

export interface NuvioRecapSheet {
  state: number;
  heading: string;
  text: string;
}

export interface NuvioActionDone {
  ok: boolean;
  message: string;
  refresh: string | null;
}

export interface NuvioLyricWord {
  word: string;
  startMs: number;
  endMs: number;
}

export interface NuvioLyricLine {
  timeMs: number | null;
  text: string;
  words: NuvioLyricWord[];
}

export interface NuvioLyricsSheet {
  kind: 'synced' | 'plain' | 'none';
  title: string | null;
  lines: NuvioLyricLine[];
}

export interface NuvioLiveTvStage {
  name: string;
  label: string;
}

export interface NuvioLiveTvItem {
  group: string;
  index: number;
  slot: string;
  title: string;
  sub: string | null;
  picture: string | null;
  live: boolean;
  progress: number;
  action: string;
}

export interface NuvioLiveTvButton {
  group: string;
  index: number;
  label: string;
  accent: boolean;
}

export interface NuvioLiveTvScreen {
  stage: string;
  heading: string;
  sub: string | null;
  stages: NuvioLiveTvStage[];
  items: NuvioLiveTvItem[];
  buttons: NuvioLiveTvButton[];
  empty: string | null;
}

export interface NuvioTonightCard {
  slot: string;
  name: string;
  why: string | null;
  meta: string | null;
  poster: string | null;
  flag: boolean;
}

export interface NuvioTonightScreen {
  view: string;
  mode: string;
  loading: boolean;
  number: number;
  total: number;
  countFrom: number;
  countLeft: number;
  reshuffleLeft: boolean;
  prompt: string | null;
  leftLabel: string | null;
  leftDetail: string | null;
  rightLabel: string | null;
  rightDetail: string | null;
  answers: string[];
  cards: NuvioTonightCard[];
  extra: string | null;
  seconds: number;
  secondsTotal: number;
  paused: boolean;
  selected: number;
}

export type ClientMessage =
  | { type: 'hello'; name?: string; auth?: string }
  | { type: 'AUTH'; device_id?: string; token: string }
  | { type: 'ping'; clientTs?: number }
  | { type: 'get_state' }
  | { type: 'nav'; action: NavAction }
  | { type: 'key'; name: KeyName; repeat?: number }
  | { type: 'type'; text: string }
  | { type: 'mouse'; move?: { dx: number; dy: number }; abs?: { nx: number; ny: number; monitor?: number }; click?: MouseButton; doubleClick?: MouseButton; down?: MouseButton; up?: MouseButton; scroll?: number; zoom?: number }
  | { type: 'state'; screen: ScreenName }
  | { type: 'screen_start'; profile: ScreenProfile; offer?: string; monitor?: number; audio?: boolean }
  | { type: 'screen_stop' }
  | { type: 'screen_audio'; enabled: boolean }
  | { type: 'private_audio_start' }
  | { type: 'private_audio_stop' }
  | { type: 'pc_open_url'; url: string }
  | { type: 'screen_webrtc_state'; live: boolean }
  | { type: 'webrtc_candidate'; candidate: string; mid?: string }
  | { type: 'window'; action: WindowAction }
  | { type: 'clipboard_set'; text: string }
  | { type: 'clipboard_get' }
  | { type: 'nuvio_bridge_status' }
  | { type: 'nuvio_bridge_pair'; pin: string }
  | { type: 'nuvio_seek'; positionMs: number }
  | { type: 'nuvio_volume'; volume: number }
  | { type: 'nuvio_control'; action: NuvioControlAction; value?: number }
  | { type: 'nuvio_subtitles' }
  | { type: 'nuvio_pick_subtitle'; number: number }
  | { type: 'nuvio_audio' }
  | { type: 'nuvio_pick_audio'; number: number }
  | { type: 'nuvio_sources_open'; force?: boolean }
  | { type: 'nuvio_pick_source'; number: number }
  | { type: 'nuvio_queue' }
  | { type: 'nuvio_queue_action'; action: 'play' | 'next' | 'remove'; videoId: string }
  | { type: 'nuvio_open'; url: string }
  | { type: 'nuvio_library'; kind: 'video' | 'music' }
  | { type: 'nuvio_continue' }
  | { type: 'nuvio_resume'; parentId: string; videoId: string }
  | { type: 'nuvio_open_title'; contentType: string; id: string }
  | { type: 'nuvio_episodes'; contentType: string; id: string }
  | { type: 'nuvio_play_episode'; contentType: string; id: string; videoId: string }
  | { type: 'nuvio_search'; query: string }
  | { type: 'nuvio_album_tracks'; browseId: string }
  | { type: 'nuvio_play_track'; browseId: string; index: number }
  | { type: 'nuvio_play_album'; browseId: string; action: 'play' | 'shuffle' | 'queue' }
  | { type: 'nuvio_artist_action'; artistId: string; action: 'mix' | 'radio' }
  | { type: 'nuvio_menu'; kind: string; contentType: string; id: string; ref?: string }
  | { type: 'nuvio_do'; kind: string; contentType: string; id: string; ref?: string; code: string; args?: string[] }
  | { type: 'nuvio_lyrics' }
  | { type: 'nuvio_livetv_do'; args: string[] }
  | { type: 'nuvio_tonight_do'; action: string; number?: number };

export type ServerMessage =
  | { type: 'welcome'; server: string; version: string; state: StateSnapshot; capabilities: string[]; monitors?: MonitorInfo[]; tunnelUrl?: string | null; pairedToken?: string }
  | { type: 'tunnel'; url: string }
  | { type: 'pong'; clientTs?: number }
  | { type: 'clipboard_data'; text: string }
  | { type: 'error'; code: string; message: string; reply_to?: string }
  | { type: 'state'; screen: ScreenName; playerActive: boolean; confirmed: boolean; source: 'command' | 'log'; ts: string }
  | { type: 'screen_status'; status: 'starting' | 'running' | 'stopped'; profile?: ScreenProfile; monitor?: number; detail?: string }
  | { type: 'private_audio_status'; active: boolean; detail?: string }
  | { type: 'pc_open_url_result'; ok: boolean; error?: string }
  | { type: 'screen_frame'; data: string; width: number; height: number; monitor?: number; ts: number }
  | { type: 'webrtc_answer'; sdp: string }
  | { type: 'log_event'; tag: string; line: string; ts: string }
  | { type: 'nuvio_bridge'; status: NuvioBridgeStatus; pcName: string | null; nowPlaying: NuvioNowPlaying | null; sources: NuvioSourceList | null; wake?: NuvioWakeInfo | null; liveTv?: NuvioLiveTvScreen | null; tonight?: NuvioTonightScreen | null }
  | { type: 'nuvio_bridge_pair_result'; ok: boolean; error?: string; pcName?: string }
  | { type: 'nuvio_state'; nowPlaying: NuvioNowPlaying | null }
  | { type: 'nuvio_sources'; list: NuvioSourceList | null }
  | { type: 'nuvio_subtitles'; loading: boolean; choices: NuvioTrackChoice[] }
  | { type: 'nuvio_audio'; choices: NuvioTrackChoice[] }
  | { type: 'nuvio_queue'; playing: number; tracks: NuvioQueueTrack[] }
  | { type: 'nuvio_open_result'; ok: boolean; what?: string }
  | { type: 'nuvio_library_video'; ready: boolean; items: NuvioVideoTitle[] }
  | { type: 'nuvio_library_music'; ready: boolean; artists: NuvioMusicArtist[]; albums: NuvioMusicAlbum[] }
  | { type: 'nuvio_continue'; items: NuvioContinueItem[] }
  | { type: 'nuvio_episodes'; contentType: string; id: string; found: boolean; episodes: NuvioEpisodeItem[] }
  | { type: 'nuvio_search_results'; loading: boolean; query: string; items: NuvioVideoTitle[] }
  | { type: 'nuvio_album_tracks'; browseId: string; tracks: NuvioAlbumTrack[] }
  | { type: 'nuvio_menu'; menu: NuvioMenuSheet }
  | { type: 'nuvio_playlists'; playlists: NuvioPlaylistsSheet }
  | { type: 'nuvio_recap'; recap: NuvioRecapSheet }
  | { type: 'nuvio_action_done'; done: NuvioActionDone }
  | { type: 'nuvio_lyrics'; lyrics: NuvioLyricsSheet }
  | { type: 'nuvio_livetv'; screen: NuvioLiveTvScreen | null }
  | { type: 'nuvio_tonight'; screen: NuvioTonightScreen | null };

export interface StateSnapshot {
  screen: ScreenName;
  playerActive: boolean;
  confirmed: boolean;
}

export function parseClientMessage(raw: string): ClientMessage | null {
  try {
    const obj = JSON.parse(raw);
    if (typeof obj !== 'object' || obj === null || typeof (obj as { type?: unknown }).type !== 'string') return null;
    return obj as ClientMessage;
  } catch {
    return null;
  }
}
