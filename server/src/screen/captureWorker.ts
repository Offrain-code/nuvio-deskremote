import { parentPort, workerData } from 'node:worker_threads';
import { loadWin32, attachToInteractiveDesktop } from '../injection/win32';
import { GdiCapture } from './capture';

interface WorkerConfig {
  width: number;
  height: number;
  srcX: number;
  srcY: number;
  fps: number;
  frameBytes: number;
  sab: SharedArrayBuffer;
}

const cfg = workerData as WorkerConfig;
const port = parentPort;

if (port && cfg) {
  const bind = loadWin32();
  attachToInteractiveDesktop(bind);
  const capture = new GdiCapture(bind);
  const prep = capture.prepare(cfg.width, cfg.height, cfg.srcX, cfg.srcY);

  if (!prep.ok) {
    port.postMessage({ type: 'error', detail: prep.detail });
    process.exit(1);
  }

  const slots = [
    Buffer.from(cfg.sab, 0, cfg.frameBytes),
    Buffer.from(cfg.sab, cfg.frameBytes, cfg.frameBytes),
  ];
  let slot = 0;

  const intervalMs = Math.max(16, Math.round(1000 / Math.max(1, cfg.fps)));
  const timer = setInterval(() => {
    const buf = capture.captureFrame();
    if (!buf) return;
    const len = Math.min(buf.length, cfg.frameBytes);
    buf.copy(slots[slot], 0, 0, len);
    port.postMessage({ type: 'frame', slot });
    slot ^= 1;
  }, intervalMs);

  port.on('message', (msg: unknown) => {
    if (msg === 'stop') {
      clearInterval(timer);
      capture.release();
      process.exit(0);
    }
  });
}
