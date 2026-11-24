import * as path from 'path';
import { loadConfig, userConfigDir } from './config';
import { error, info, initFileLog, warn } from './logger';
import { Win32Bindings, loadWin32 } from './injection/win32';
import { InputInjector } from './injection/input';
import { ScreenStateMachine } from './state';
import { NuvioLogTail } from './logtail';
import { NuvioBridge } from './nuvioBridge';
import { ScreenServer } from './screen/screenServer';
import { CompanionServer, pickLanIp } from './wsServer';

function loadBindings(): Win32Bindings {
  try {
    return loadWin32();
  } catch (err) {
    error(`failed to load user32/gdi32: ${(err as Error).message}`);
    process.exit(1);
  }
}

function main(): void {
  if (process.platform !== 'win32') {
    error('Companion server is Windows-only (user32/koffi). Exiting.');
    process.exit(1);
  }

  const dir = __dirname; // dist/ — config.json lives in the server root directory
  const rootDir = path.resolve(dir, '..');
  initFileLog(userConfigDir());

  const cfg = loadConfig(rootDir);
  info(`config: port ${cfg.port} (debug loopback: ${cfg.loopback ? 'on' : 'off'})`);
  info(`config: Nuvio log = ${cfg.nuvioLogPath}`);

  const bind = loadBindings();

  const state = new ScreenStateMachine();
  const injector = new InputInjector(bind, cfg.nuvioWindowTitle, cfg.nuvioExePath);
  const screen = new ScreenServer(cfg, bind);

  // Startup diagnostics: check whether Nuvio window is open and log file exists
  const probe = injector.probeWindow();
  info(`nuvio: window ${probe.found ? `found "${probe.title}"` : 'NOT found (is Nuvio HTPC running?)'}`);
  info(`nuvio: target title = "${cfg.nuvioWindowTitle}"`);

  const logtail = new NuvioLogTail(cfg.nuvioLogPath ?? '', cfg.playerEnterMarkers, cfg.playerExitMarkers);
  const tail = logtail.start();
  if (tail.ok) info('nuvio: log tail active (PlaybackStartTrace = Player enter)');
  else warn(`nuvio: log tail inactive — ${tail.detail}`);

  const bridge = new NuvioBridge();
  bridge.start();

  const server = new CompanionServer({ cfg, state, injector, screen, logtail, bridge });
  const urls = server.start();
  info(`server ready: ${urls.main}`);
  if (urls.loopback) info(`local debug endpoint: ${urls.loopback}`);
  info(`detected LAN IP: ${pickLanIp()}`);

  let shuttingDown = false;
  const shutdown = (reason: string): void => {
    if (shuttingDown) return;
    shuttingDown = true;
    info(`shutting down (${reason})`);
    injector.releaseHeldMouseButtons();
    server.stop();
    bridge.stop();
    screen.stop('server shutting down');
    logtail.stop();
    process.exit(0);
  };

  const parentPid = Number(process.env.NUVIO_COMPANION_PARENT_PID ?? '0');
  if (Number.isInteger(parentPid) && parentPid > 0) {
    const parentWatch = setInterval(() => {
      try {
        process.kill(parentPid, 0);
      } catch (err) {
        const code = (err as NodeJS.ErrnoException)?.code;
        if (code === 'ESRCH') {
          clearInterval(parentWatch);
          shutdown(`parent Companion process ${parentPid} exited`);
        }
      }
    }, 1500);
    parentWatch.unref();
  }

  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));
  process.on('uncaughtException', (err) => {
    warn(`uncaughtException: ${(err as Error).stack ?? err}`);
  });
}

try {
  main();
} catch (err) {
  error(`bootstrap failed: ${(err as Error).stack ?? err}`);
  process.exit(1);
}
