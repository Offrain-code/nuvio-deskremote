import * as crypto from 'crypto';
import * as fs from 'fs';
import * as path from 'path';
import { spawn } from 'child_process';
import { info, warn } from './logger';

export interface PairedDevice {
  deviceId: string;
  deviceName: string;
  tokenHash: string;
  pairedAt: string;
  lastSeenAt: string;
  lastIp: string;
}

export interface PinValidationResult {
  ok: boolean;
  error?: 'INVALID_PIN' | 'RATE_LIMIT_EXCEEDED' | 'EXPIRED_OR_NO_PIN';
  message?: string;
  attemptsRemaining?: number;
  retryAfterSeconds?: number;
}

interface IpRateLimit {
  attempts: number;
  blockedUntil: number;
}

/**
 * Temporary 6-digit pairing PIN manager (SECURITY.md §3).
 * - TTL: 120 seconds
 * - 3 incorrect attempts block the client IP for 300 seconds and immediately invalidate the PIN
 * - Timing-safe comparison via crypto.timingSafeEqual
 */
export class PinManager {
  private currentPin: string | null = null;
  private pinExpiresAt = 0;
  private ipLimits: Map<string, IpRateLimit> = new Map();

  /** Generates a cryptographically random 6-digit PIN and optionally shows a Windows desktop dialog. */
  generatePin(silent = false): { pin: string; expiresInSeconds: number } {
    const pin = crypto.randomInt(100000, 1000000).toString();
    this.currentPin = pin;
    this.pinExpiresAt = Date.now() + 120_000;

    info('[AUTH] Generated new pairing PIN (valid for 120 seconds)');
    if (!silent) {
      this.notifyWindowsDesktop(pin);
    }

    return {
      pin,
      expiresInSeconds: 120,
    };
  }

  getActivePin(): { pin: string; expiresInSeconds: number } | null {
    if (!this.currentPin || Date.now() > this.pinExpiresAt) {
      this.currentPin = null;
      return null;
    }
    const rem = Math.max(1, Math.ceil((this.pinExpiresAt - Date.now()) / 1000));
    return { pin: this.currentPin, expiresInSeconds: rem };
  }

  verifyPin(ip: string, inputPin: string): PinValidationResult {
    const now = Date.now();
    const cleanIp = ip.replace(/^::ffff:/, '').trim() || 'unknown';
    const limit = this.ipLimits.get(cleanIp) ?? { attempts: 0, blockedUntil: 0 };

    // Check rate-limit block
    if (limit.blockedUntil > now) {
      const retryAfter = Math.ceil((limit.blockedUntil - now) / 1000);
      warn(`[AUTH] Pairing attempt blocked by rate limit for ${cleanIp} (${retryAfter}s remaining)`);
      return {
        ok: false,
        error: 'RATE_LIMIT_EXCEEDED',
        message: `Too many incorrect attempts. Blocked for ${retryAfter} seconds.`,
        retryAfterSeconds: retryAfter,
      };
    }

    // Check PIN expiration
    if (!this.currentPin || now > this.pinExpiresAt) {
      this.currentPin = null;
      return {
        ok: false,
        error: 'EXPIRED_OR_NO_PIN',
        message: 'No active PIN or the PIN has expired. Request a new PIN from the app.',
      };
    }

    // Timing-safe comparison
    const pinBuf = Buffer.from(this.currentPin, 'utf8');
    const inputBuf = Buffer.from(inputPin.trim(), 'utf8');
    const matches = pinBuf.length === inputBuf.length && crypto.timingSafeEqual(pinBuf, inputBuf);

    if (matches) {
      info(`[AUTH] PIN validated successfully for IP ${cleanIp}`);
      this.currentPin = null; // Single-use PIN
      this.ipLimits.delete(cleanIp);
      return { ok: true };
    }

    // Incorrect PIN: increment failure counter
    limit.attempts += 1;
    if (limit.attempts >= 3) {
      limit.blockedUntil = now + 300_000; // 5-minute block
      this.ipLimits.set(cleanIp, limit);
      this.currentPin = null; // Immediately destroy PIN against brute force (SECURITY §5.2)
      warn(`[AUTH] 3 PIN failures reached by ${cleanIp}. IP blocked for 300s and PIN invalidated.`);
      return {
        ok: false,
        error: 'RATE_LIMIT_EXCEEDED',
        message: 'Too many incorrect attempts. Blocked for 300 seconds.',
        retryAfterSeconds: 300,
      };
    }

    this.ipLimits.set(cleanIp, limit);
    const attemptsRemaining = 3 - limit.attempts;
    warn(`[AUTH] Incorrect PIN from ${cleanIp}. Remaining attempts: ${attemptsRemaining}`);
    return {
      ok: false,
      error: 'INVALID_PIN',
      message: `Incorrect PIN. Attempts remaining before lockout: ${attemptsRemaining}`,
      attemptsRemaining,
    };
  }

  private notifyWindowsDesktop(pin: string): void {
    try {
      // Native dialog shown above active desktop windows (SECURITY.md §3.1)
      const psScript = `
        Add-Type -AssemblyName System.Windows.Forms
        [System.Windows.Forms.MessageBox]::Show(
          'Pairing PIN: ${pin}' + [Environment]::NewLine + [Environment]::NewLine + 'Enter this 6-digit code on your phone to authorize access.',
          'Nuvio DeskRemote - Pairing',
          [System.Windows.Forms.MessageBoxButtons]::OK,
          [System.Windows.Forms.MessageBoxIcon]::Information,
          [System.Windows.Forms.MessageBoxDefaultButton]::Button1,
          [System.Windows.Forms.MessageBoxOptions]::DefaultDesktopOnly
        )
      `;
      const child = spawn('powershell.exe', ['-NoProfile', '-WindowStyle', 'Hidden', '-Command', psScript], {
        windowsHide: true,
        detached: true,
        stdio: 'ignore',
      });
      child.unref();
    } catch {
      // ignore
    }
  }
}

export interface CompanionAuth {
  check(req: { auth?: string }, remoteAddr?: string): { ok: boolean; code?: string; message?: string };
}

export class TokenAuth implements CompanionAuth {
  constructor(private tokenStore: TokenStore) {}

  check(req: { auth?: string }, remoteAddr?: string): { ok: boolean; code?: string; message?: string } {
    const token = (req.auth ?? '').trim();
    if (!token) return { ok: false, code: 'AUTH_REQUIRED', message: 'Token not provided' };
    const dev = this.tokenStore.validateToken(token, remoteAddr);
    if (!dev) return { ok: false, code: 'AUTH_FAILED', message: 'Invalid or unauthorized token' };
    return { ok: true };
  }
}

export class OpenAuth implements CompanionAuth {
  check(): { ok: boolean } {
    return { ok: true };
  }
}

/**
 * Persistent device token store (SECURITY.md §4).
 * Saves SHA-256 hashes on disk so raw tokens are never stored in plaintext files.
 */
export class TokenStore {
  private filePath: string;
  private devices: Map<string, PairedDevice> = new Map();
  private tokenHashMap: Map<string, PairedDevice> = new Map(); // tokenHash -> Device

  constructor(storageDir?: string) {
    const baseDir = storageDir || (process.env.APPDATA ? path.join(process.env.APPDATA, 'NuvioRemote') : path.resolve(__dirname, '..'));
    try {
      if (!fs.existsSync(baseDir)) {
        fs.mkdirSync(baseDir, { recursive: true });
      }
    } catch {
      // ignore
    }
    this.filePath = path.join(baseDir, 'paired-devices.json');
    this.load();
  }

  private hashToken(token: string): string {
    return crypto.createHash('sha256').update(token.trim()).digest('hex');
  }

  private load(): void {
    try {
      if (fs.existsSync(this.filePath)) {
        const raw = fs.readFileSync(this.filePath, 'utf8');
        const data = JSON.parse(raw) as { devices?: PairedDevice[] };
        if (Array.isArray(data.devices)) {
          for (const d of data.devices) {
            if (d.deviceId && d.tokenHash) {
              this.devices.set(d.deviceId, d);
              this.tokenHashMap.set(d.tokenHash, d);
            }
          }
        }
      }
    } catch (err) {
      warn(`[AUTH] Failed to load paired-devices.json: ${(err as Error).message}`);
    }
  }

  private save(): void {
    try {
      const data = {
        devices: Array.from(this.devices.values()),
      };
      fs.writeFileSync(this.filePath, JSON.stringify(data, null, 2), 'utf8');
    } catch (err) {
      warn(`[AUTH] Failed to save paired-devices.json: ${(err as Error).message}`);
    }
  }

  createDeviceToken(deviceId: string, deviceName: string, ip: string): string {
    const rawToken = `nuvio_sec_${crypto.randomBytes(32).toString('hex')}`;
    const tokenHash = this.hashToken(rawToken);
    const now = new Date().toISOString();

    const device: PairedDevice = {
      deviceId,
      deviceName: deviceName.slice(0, 40),
      tokenHash,
      pairedAt: now,
      lastSeenAt: now,
      lastIp: ip,
    };

    // Replace any previous token for this deviceId
    const old = this.devices.get(deviceId);
    if (old) {
      this.tokenHashMap.delete(old.tokenHash);
    }

    this.devices.set(deviceId, device);
    this.tokenHashMap.set(tokenHash, device);
    this.save();

    return rawToken;
  }

  validateToken(token: string, ip?: string): PairedDevice | null {
    if (!token || typeof token !== 'string' || !token.startsWith('nuvio_sec_') || token.length < 32) {
      return null;
    }
    const tokenHash = this.hashToken(token);
    const dev = this.tokenHashMap.get(tokenHash);
    if (dev) {
      dev.lastSeenAt = new Date().toISOString();
      if (ip) dev.lastIp = ip;
      return dev;
    }
    return null;
  }

  hasPairedDevices(): boolean {
    return this.devices.size > 0;
  }

  getDevices(): PairedDevice[] {
    return Array.from(this.devices.values());
  }

  revokeDevice(deviceId: string): boolean {
    const dev = this.devices.get(deviceId);
    if (!dev) return false;
    this.tokenHashMap.delete(dev.tokenHash);
    this.devices.delete(deviceId);
    this.save();
    return true;
  }
}
