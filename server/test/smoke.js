/**
 * Companion server smoke test (does not inject keys or steal focus):
 * 1. Loads user32/gdi32; checks whether the Nuvio window is open (both states are valid)
 * 2. Screen state machine (predicted transitions + log confirmation)
 * 3. WebSocket handshake + state broadcast (test server on high port)
 * 4. Log tail against a synthetic log file
 * 5. Single-frame GDI capture + H.264 encoder byte proof (no live streaming)
 */
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const os = require('os');
const WebSocket = require('ws');

const dist = path.join(__dirname, '..', 'dist');
const { loadWin32, findNuvioWindow } = require(path.join(dist, 'injection/win32.js'));
const { InputInjector } = require(path.join(dist, 'injection/input.js'));
const { ScreenStateMachine } = require(path.join(dist, 'state.js'));
const { NuvioLogTail } = require(path.join(dist, 'logtail.js'));
const { GdiCapture } = require(path.join(dist, 'screen/capture.js'));
const { CompanionServer } = require(path.join(dist, 'wsServer.js'));
const { ScreenServer } = require(path.join(dist, 'screen/screenServer.js'));
const { OpenAuth } = require(path.join(dist, 'auth.js'));
const cfgMod = require(path.join(dist, 'config.js'));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const bind = loadWin32();
  const cfg = cfgMod.loadConfig(path.join(__dirname, '..'));
  cfg.port = 8799;
  cfg.loopback = true;
  cfg.screen.encoder = 'libx264'; // deterministic test encoder

  // 1) bindings + probe
  const hwnd = findNuvioWindow(bind, cfg.nuvioWindowTitle);
  const probe = hwnd ? 'Nuvio window present (no injection in smoke test)' : 'Nuvio closed (NUVIO_NOT_RUNNING path expected)';
  console.log(`[1] user32 OK — ${probe}`);

  // 2) state machine
  const state = new ScreenStateMachine();
  const events = [];
  state.onChange((ev) => events.push(ev));
  state.applyNav('ok');
  state.forceState('home');
  state.applyNav('ok');                      // home -> detail
  assert.strictEqual(state.snapshot().screen, 'detail');
  state.applyNav('ok');                      // detail -> player (prediction)
  assert.strictEqual(state.snapshot().screen, 'player');
  assert.strictEqual(state.snapshot().confirmed, false);
  state.applyLogEnter('PlaybackStartTrace');
  assert.strictEqual(state.snapshot().confirmed, true);
  state.applyNav('back');                    // player -> detail (prediction)
  assert.strictEqual(state.snapshot().screen, 'detail');
  console.log('[2] state machine OK (home->detail->player->back->detail)');

  // 3) WS server + handshake
  const injector = new InputInjector(bind, cfg.nuvioWindowTitle);
  const screenServer = new ScreenServer(cfg, bind);
  const server = new CompanionServer({ cfg, state, injector, auth: new OpenAuth(), screen: screenServer, logtail: null });
  server.start();
  await sleep(400);
  const hello = await new Promise((resolve, reject) => {
    const ws = new WebSocket('ws://127.0.0.1:8800');
    const msgs = [];
    ws.on('message', (d) => {
      msgs.push(JSON.parse(d.toString()));
      if (msgs.length >= 2) { ws.close(); resolve(msgs); }
    });
    ws.on('error', reject);
    ws.on('open', () => {
      ws.send(JSON.stringify({ type: 'hello', name: 'smoke' }));
      ws.send(JSON.stringify({ type: 'get_state' }));
    });
    setTimeout(() => reject(new Error('handshake timeout')), 5000);
  });
  assert.strictEqual(hello[0].type, 'welcome');
  assert.strictEqual(hello[1].type, 'state');
  assert.strictEqual(hello[0].capabilities.length > 0, true);
  console.log(`[3] WS handshake OK (${hello.length} messages received)`);

  // 4) synthetic log tail
  const tmpLog = path.join(os.tmpdir(), `nuvio-smoke-${Date.now()}.log`);
  fs.writeFileSync(tmpLog, 'initial line\n');
  const tail = new NuvioLogTail(tmpLog, ['\\(PlaybackStartTrace\\).*playerAttach'], []);
  const tailHits = [];
  tail.on('player_enter', (line) => tailHits.push(line));
  tail.start();
  await sleep(300);
  fs.appendFileSync(tmpLog, '2026-11-06 12:00:00.000 Info: (PlaybackStartTrace) id=1 playerAttach +1ms\n');
  await sleep(900);
  tail.stop();
  assert.ok(tailHits.length >= 1, 'log tail did not capture marker');
  console.log('[4] log tail OK (marker detected in synthetic file)');

  // 5) GDI capture + encoder
  const capture = new GdiCapture(bind);
  const native = capture.nativeSize();
  const prep = capture.prepare(native.width, native.height);
  assert.ok(prep.ok, `capture prep failed: ${prep.detail}`);
  const frame = capture.captureFrame();
  assert.ok(frame && frame.length === native.width * native.height * 4, 'invalid GDI frame');
  console.log(`[5a] GDI capture OK (${native.width}x${native.height}, ${Math.round(frame.length / 1024)} KB)`);

  const { H264Encoder } = require(path.join(dist, 'screen/encoder.js'));
  const enc = new H264Encoder();
  const out = await new Promise((resolve) => {
    let got = 0;
    (async () => {
      await enc.start({
        width: native.width, height: native.height, fps: 30,
        scaleTo: { width: 320, height: 180 }, bitrateKbps: 500,
        gopSeconds: 1, preferred: 'libx264',
      }, (chunk) => {
        got += chunk.length;
        if (got > 2000) resolve(got);
      });
      // feed ~30 probe frames
      for (let i = 0; i < 30 && got <= 2000; i++) {
        enc.writeFrame(frame);
        await sleep(20);
      }
      await sleep(400);
      resolve(got);
      enc.stop();
    })();
  });
  capture.release();
  assert.ok(out > 2000, `encoder produced only ${out} bytes`);
  console.log(`[5b] H.264 encoder OK (libx264, ${out} Annex-B bytes)`);
  try { fs.unlinkSync(tmpLog); } catch { /* tmp */ }
  console.log('SMOKE OK');
  process.exit(0);
}

main().catch((err) => {
  console.error('SMOKE FAILED:', err);
  process.exit(1);
});
