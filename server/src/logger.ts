import * as fs from 'fs';
import * as path from 'path';

const MAX_LOG_BYTES = 2 * 1024 * 1024; // 2 MB per file (max ~4 MB including .old)

let logFile: string | null = null;
let stream: fs.WriteStream | null = null;
let approxBytes = 0;
let rotating = false;

function stamp(): string {
  return new Date().toISOString().replace('T', ' ').replace('Z', '');
}

function rotateSyncIfNeeded(file: string): void {
  try {
    if (!fs.existsSync(file)) return;
    const st = fs.statSync(file);
    approxBytes = st.size;
    if (st.size >= MAX_LOG_BYTES) {
      const oldFile = `${file}.old`;
      if (fs.existsSync(oldFile)) fs.unlinkSync(oldFile);
      fs.renameSync(file, oldFile);
      approxBytes = 0;
    }
  } catch {
    // best-effort
  }
}

function openStream(file: string): void {
  try {
    stream = fs.createWriteStream(file, { flags: 'a', encoding: 'utf8' });
    stream.on('error', () => {
      stream = null;
    });
  } catch {
    stream = null;
  }
}

export function initFileLog(dir: string): void {
  try {
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    const file = path.join(dir, 'server.log');
    logFile = file;
    rotateSyncIfNeeded(file);
    openStream(file);
    const header = `--- boot ${stamp()} ---\n`;
    approxBytes += Buffer.byteLength(header);
    if (stream) stream.write(header);
    else fs.appendFileSync(file, header);
  } catch {
    logFile = null;
  }
}

export function info(msg: string): void {
  const line = `${stamp()} INFO  ${msg}`;
  console.log(line);
  if (logFile) append(line);
}

export function warn(msg: string): void {
  const line = `${stamp()} WARN  ${msg}`;
  console.warn(line);
  if (logFile) append(line);
}

export function error(msg: string): void {
  const line = `${stamp()} ERROR ${msg}`;
  console.error(line);
  if (logFile) append(line);
}

function append(line: string): void {
  const file = logFile;
  if (!file) return;
  const payload = `${line}\n`;
  approxBytes += Buffer.byteLength(payload);
  try {
    if (stream && stream.writable) {
      stream.write(payload);
    } else {
      void fs.promises.appendFile(file, payload).catch(() => undefined);
    }
    if (!rotating && approxBytes >= MAX_LOG_BYTES) {
      rotating = true;
      const cur = stream;
      stream = null;
      const finishRotate = () => {
        rotateSyncIfNeeded(file);
        openStream(file);
        rotating = false;
      };
      if (cur) {
        cur.end(finishRotate);
      } else {
        finishRotate();
      }
    }
  } catch {
    // Disk logging is best-effort; never crash the server due to log I/O failure
  }
}
