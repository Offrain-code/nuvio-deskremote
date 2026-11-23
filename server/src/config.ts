import * as fs from 'fs';
import * as path from 'path';

export type ScreenProfile = '720p30' | '720p60' | '1080p30' | '1080p60';

export interface Config {
  /** Primary WebSocket / HTTP TCP port. */
  port: number;
  /** Secondary loopback listener for local testing. */
  loopback: boolean;
  /** Path to the Nuvio log file. Null = %LOCALAPPDATA%\NuvioHTPC\logs\nuvio.log */
  nuvioLogPath: string | null;
  /** Optional path to Nuvio.exe (null = automatic detection). */
  nuvioExePath: string | null;
  /** Optional path to the APK served at /apk on LAN (null = relative lookup). */
  apkPath: string | null;
  /** Target window title for input injection. */
  nuvioWindowTitle: string;
  /** Regex patterns (strings) detecting player ENTER in the log. */
  playerEnterMarkers: string[];
  /** Regex patterns (strings) detecting player EXIT in the log. */
  playerExitMarkers: string[];
  /** Pairing PIN + session token authentication mode. */
  auth: { mode: 'open' };
  /** Cloudflare Quick Tunnel for off-LAN access (disabled by default). */
  tunnel: {
    enabled: boolean;
  };
  screen: {
    encoder: 'auto' | 'h264_mf' | 'libx264';
    bitrateKbps: number;
    gopSeconds: number;
    portRangeBegin: number;
    portRangeEnd: number;
  };
}

const DEFAULT_PORT = 8787;

export function defaultLogPath(): string {
  const base = process.env.LOCALAPPDATA ?? '';
  return path.join(base, 'NuvioHTPC', 'logs', 'nuvio.log');
}

export function userConfigDir(): string {
  const appData = process.env.APPDATA;
  return appData ? path.join(appData, 'NuvioDeskRemote') : path.resolve(__dirname, '..');
}

const defaults: Config = {
  port: DEFAULT_PORT,
  loopback: true,
  nuvioLogPath: null,
  nuvioExePath: null,
  apkPath: null,
  nuvioWindowTitle: 'Nuvio HTPC',
  playerEnterMarkers: [
    '\\(PlaybackStartTrace\\)\\s+id=\\d+\\s+(?:begin:\\s+)?playerAttach',
    '\\(PlaybackStartTrace\\)\\s+id=\\d+\\s+summary:\\s+playerAttach',
    '\\(PlaybackStartTrace\\)\\s+id=\\d+\\s+launchShield:hidden',
    '\\[nuvio-discord\\].*type=Playback\\s+playing=true',
    '\\(IptvPlayback\\)\\s+live\\s+buffer\\s+ahead=',
    '\\(IptvPlayback\\)\\s+channel\\s+change\\s+to',
  ],
  playerExitMarkers: [
    '\\(PlaybackStopTrace\\)',
    'playerDetach',
    '\\(PlaybackEnd\\)',
    'player closed from:',
    'player route left',
  ],
  auth: { mode: 'open' },
  tunnel: {
    enabled: false,
  },
  screen: {
    encoder: 'auto',
    bitrateKbps: 4000,
    gopSeconds: 2,
    portRangeBegin: 60000,
    portRangeEnd: 60100,
  },
};

function readJsonOverrides(file: string): Partial<Config> {
  if (!fs.existsSync(file)) return {};
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8')) as Partial<Config>;
  } catch (err) {
    throw new Error(`Invalid config.json at ${file}: ${(err as Error).message}`);
  }
}

/** Loads defaults + local config.json + user config.json in %APPDATA%\NuvioDeskRemote. */
export function loadConfig(dir: string): Config {
  const localOverrides = readJsonOverrides(path.join(dir, 'config.json'));
  const appDataFile = path.join(userConfigDir(), 'config.json');
  const userOverrides = appDataFile !== path.join(dir, 'config.json') ? readJsonOverrides(appDataFile) : {};

  const cfg: Config = {
    ...defaults,
    ...localOverrides,
    ...userOverrides,
    auth: { ...defaults.auth, ...(localOverrides.auth ?? {}), ...(userOverrides.auth ?? {}) },
    tunnel: { ...defaults.tunnel, ...(localOverrides.tunnel ?? {}), ...(userOverrides.tunnel ?? {}) },
    screen: { ...defaults.screen, ...(localOverrides.screen ?? {}), ...(userOverrides.screen ?? {}) },
  };

  if (cfg.nuvioLogPath === null) cfg.nuvioLogPath = defaultLogPath();
  return cfg;
}

/** Persists user preferences under %APPDATA%\NuvioDeskRemote\config.json (safe when installed under Program Files). */
export function saveUserConfig(patch: { nuvioExePath?: string | null; tunnel?: { enabled?: boolean } }): void {
  const dir = userConfigDir();
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
  const file = path.join(dir, 'config.json');
  const current = readJsonOverrides(file);
  const next: Partial<Config> = {
    ...current,
    ...(patch.nuvioExePath !== undefined ? { nuvioExePath: patch.nuvioExePath } : {}),
    ...(patch.tunnel !== undefined
      ? { tunnel: { enabled: Boolean(patch.tunnel.enabled ?? current.tunnel?.enabled ?? false) } }
      : {}),
  };
  fs.writeFileSync(file, JSON.stringify(next, null, 2), 'utf8');
}
