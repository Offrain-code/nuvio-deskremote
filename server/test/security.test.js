'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const ws = require('ws');

const { PinManager, TokenStore } = require('../dist/auth.js');
const {
  CompanionServer,
  isAllowedLanHostHeader,
  isAllowedLocalHostHeader,
  isPrivateOrLoopback,
  isRequestFromTunnel,
  resolveEffectiveRemote,
  validateNuvioExePath,
} = require('../dist/wsServer.js');
const { ScreenStateMachine } = require('../dist/state.js');

function httpJson(port, method, urlPath, headers = {}, body = null) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        host: '127.0.0.1',
        port,
        method,
        path: urlPath,
        headers,
      },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          let json = null;
          try {
            json = JSON.parse(text);
          } catch {
            // keep null
          }
          resolve({ status: res.statusCode, text, json });
        });
      },
    );
    req.on('error', reject);
    if (body !== null) {
      req.write(body);
    }
    req.end();
  });
}

test('isPrivateOrLoopback is fail-closed and validates IPv4 ranges', () => {
  assert.equal(isPrivateOrLoopback(''), false);
  assert.equal(isPrivateOrLoopback('?'), false);
  assert.equal(isPrivateOrLoopback('8.8.8.8'), false);
  assert.equal(isPrivateOrLoopback('192.168.1.999'), false);
  assert.equal(isPrivateOrLoopback('172.32.0.1'), false);

  assert.equal(isPrivateOrLoopback('127.0.0.1'), true);
  assert.equal(isPrivateOrLoopback('::1'), true);
  assert.equal(isPrivateOrLoopback('::ffff:192.168.1.25'), true);
  assert.equal(isPrivateOrLoopback('10.0.0.42'), true);
  assert.equal(isPrivateOrLoopback('172.16.5.4'), true);
  assert.equal(isPrivateOrLoopback('fe80::1'), true);
  assert.equal(isPrivateOrLoopback('fd12:3456::1'), true);
  assert.equal(isPrivateOrLoopback('fc-evil.com'), false);
  assert.equal(isPrivateOrLoopback('fd.attacker.org'), false);
  assert.equal(isPrivateOrLoopback('2001:db8::1'), false);
});

test('isRequestFromTunnel detects empty cf-connecting-ip header and resolveEffectiveRemote prevents spoofing', () => {
  const spoofedEmptyCf = {
    headers: { 'cf-connecting-ip': '', host: '127.0.0.1:8787' },
    socket: { remoteAddress: '127.0.0.1' },
  };
  assert.equal(isRequestFromTunnel(spoofedEmptyCf), true);

  const lanSpoof = {
    headers: { 'cf-connecting-ip': '192.168.1.99', host: '192.168.1.10:8787' },
    socket: { remoteAddress: '192.168.1.50' },
  };
  assert.equal(resolveEffectiveRemote(lanSpoof, true), '192.168.1.50');

  const realTunnel = {
    headers: { 'cf-connecting-ip': '203.0.113.10', host: 'abc.trycloudflare.com' },
    socket: { remoteAddress: '127.0.0.1' },
  };
  assert.equal(isRequestFromTunnel(realTunnel), true);
  assert.equal(resolveEffectiveRemote(realTunnel, true), '203.0.113.10');
});

test('DNS rebinding host header validators block external domains', () => {
  assert.equal(isAllowedLocalHostHeader('127.0.0.1:8787'), true);
  assert.equal(isAllowedLocalHostHeader('localhost:8787'), true);
  assert.equal(isAllowedLocalHostHeader('[::1]:8787'), true);
  assert.equal(isAllowedLocalHostHeader('192.168.1.10:8787'), false);
  assert.equal(isAllowedLocalHostHeader('evil.example.com:8787'), false);
  assert.equal(isAllowedLocalHostHeader(undefined), false);

  assert.equal(isAllowedLanHostHeader('192.168.1.10:8787'), true);
  assert.equal(isAllowedLanHostHeader('127.0.0.1:8787'), true);
  assert.equal(isAllowedLanHostHeader('htpc.local:8787'), true);
  assert.equal(isAllowedLanHostHeader('evil.example.com:8787'), false);
  assert.equal(isAllowedLanHostHeader('fc-evil.com:8787'), false);
  assert.equal(isAllowedLanHostHeader('fd.attacker.org:8787'), false);
});

test('validateNuvioExePath blocks arbitrary binaries and allows Nuvio .exe files', () => {
  assert.equal(validateNuvioExePath(null).ok, true);
  assert.equal(validateNuvioExePath('').ok, true);
  assert.equal(validateNuvioExePath('C:\\Windows\\System32\\cmd.exe').ok, false);
  assert.equal(validateNuvioExePath('C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe').ok, false);
  assert.equal(validateNuvioExePath('\\\\attacker\\share\\Nuvio.exe').ok, false);
  assert.equal(validateNuvioExePath('Nuvio.exe').ok, false);

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'nuvio-exe-test-'));
  try {
    const fakeNuvio = path.join(tmpDir, 'Nuvio.exe');
    fs.writeFileSync(fakeNuvio, 'stub');
    const res = validateNuvioExePath(fakeNuvio);
    assert.equal(res.ok, true);
    assert.equal(res.normalized, fakeNuvio);
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('PinManager resets attempts after lockout expires and rate-limits requestPin', () => {
  const pm = new PinManager();
  pm.generatePin(true);
  assert.equal(pm.verifyPin('192.168.1.20', '000001').attemptsRemaining, 2);
  assert.equal(pm.verifyPin('192.168.1.20', '000002').attemptsRemaining, 1);
  const locked = pm.verifyPin('192.168.1.20', '000003');
  assert.equal(locked.error, 'RATE_LIMIT_EXCEEDED');

  // Simulate lockout expiration
  pm.ipLimits.get('192.168.1.20').blockedUntil = Date.now() - 1000;
  pm.generatePin(true);
  const afterExpiry = pm.verifyPin('192.168.1.20', '000004');
  assert.equal(afterExpiry.error, 'INVALID_PIN');
  assert.equal(afterExpiry.attemptsRemaining, 2);

  // Per-IP PIN request rate-limit (max 3 in 60s)
  const r1 = pm.requestPin('192.168.1.21', true);
  const r2 = pm.requestPin('192.168.1.21', true);
  const r3 = pm.requestPin('192.168.1.21', true);
  const r4 = pm.requestPin('192.168.1.21', true);
  assert.equal(r1.ok, true);
  assert.equal(r2.ok, true);
  assert.equal(r2.reused, true);
  assert.equal(r3.ok, true);
  assert.equal(r4.ok, false);
  assert.equal(r4.error, 'RATE_LIMIT_EXCEEDED');
});

test('TokenStore.getPublicDevices strips tokenHash', () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'nuvio-store-test-'));
  try {
    const store = new TokenStore(tmpDir);
    store.createDeviceToken('dev-1', 'Phone', '192.168.1.50');
    const pub = store.getPublicDevices();
    assert.equal(pub.length, 1);
    assert.equal(pub[0].deviceId, 'dev-1');
    assert.equal('tokenHash' in pub[0], false);
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('CompanionServer enforces IPC token, LAN_ONLY, 413 body cap, auth broadcasts, and live WS revocation', async () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'nuvio-srv-sec-'));
  const port = 18787 + Math.floor(Math.random() * 1000);
  const ipcToken = 'local_ipc_secret_test_token_1234567890';
  const state = new ScreenStateMachine();
  const fakeInjector = {
    resolveNuvioExePath: () => null,
    setNuvioExePath: () => undefined,
    getBind: () => ({
      GetSystemMetrics: (idx) => (idx === 0 || idx === 78 ? 1920 : idx === 1 || idx === 79 ? 1080 : 0),
    }),
    handleWindowAction: async () => ({ ok: false, code: 'NUVIO_NOT_FOUND', message: 'not found' }),
  };
  const fakeScreen = {
    setStatusCb: () => undefined,
    setFrameCb: () => undefined,
    setAudioCb: () => undefined,
    isRunning: () => false,
    stopIfOwner: () => false,
    stop: () => false,
    cursorNorm: () => ({ nx: 0.5, ny: 0.5, visible: false }),
    activeMonitor: () => 1,
  };
  const tokenStore = new TokenStore(tmpDir);
  const srv = new CompanionServer({
    cfg: {
      port,
      loopback: false,
      nuvioExePath: null,
      nuvioTitle: 'Nuvio',
      logPath: null,
      apkPath: null,
      tunnel: { enabled: false },
      screen: { encoder: 'libx264', bitrateKbps: 2500, gopSeconds: 2, portRangeBegin: 60000, portRangeEnd: 60100 },
    },
    state,
    injector: fakeInjector,
    screen: fakeScreen,
    logtail: null,
    tokenStore,
    localIpcToken: ipcToken,
  });

  srv.start();
  await new Promise((r) => setTimeout(r, 120));

  try {
    // 1. Empty cf-connecting-ip must be rejected with 403 LAN_ONLY (no bypass)
    const bypassAttempt = await httpJson(port, 'GET', '/api/v1/pair/status', {
      Host: `127.0.0.1:${port}`,
      'cf-connecting-ip': '',
    });
    assert.equal(bypassAttempt.status, 403);
    assert.equal(bypassAttempt.json.error, 'LAN_ONLY');

    // 2. DNS rebinding Host header must be rejected with 403 FORBIDDEN_HOST
    const rebindAttempt = await httpJson(port, 'GET', '/api/v1/local/pair-info', {
      Host: `evil.com:${port}`,
      'X-Nuvio-Local-Token': ipcToken,
    });
    assert.equal(rebindAttempt.status, 403);
    assert.equal(rebindAttempt.json.error, 'FORBIDDEN_HOST');

    // 3. Missing IPC token on /api/v1/local/pair-info must be rejected
    const noIpc = await httpJson(port, 'GET', '/api/v1/local/pair-info', {
      Host: `127.0.0.1:${port}`,
    });
    assert.equal(noIpc.status, 403);
    assert.equal(noIpc.json.error, 'LOOPBACK_ONLY');

    // 4. Valid IPC token obtains PIN without tokenHash exposure
    const pairInfo = await httpJson(port, 'GET', '/api/v1/local/pair-info', {
      Host: `127.0.0.1:${port}`,
      'X-Nuvio-Local-Token': ipcToken,
    });
    assert.equal(pairInfo.status, 200);
    assert.match(pairInfo.json.pin, /^\d{6}$/);

    // 5. Oversized body (> 8192 bytes) returns 413 PAYLOAD_TOO_LARGE
    const hugeBody = JSON.stringify({ pin: '123456', pad: 'x'.repeat(9000) });
    const tooLarge = await httpJson(
      port,
      'POST',
      '/api/v1/pair',
      { Host: `127.0.0.1:${port}`, 'Content-Type': 'application/json' },
      hugeBody,
    );
    assert.equal(tooLarge.status, 413);

    // 6. Pair two devices and open authenticated WebSocket for dev-A
    const pairA = await httpJson(
      port,
      'POST',
      '/api/v1/pair',
      { Host: `127.0.0.1:${port}`, 'Content-Type': 'application/json' },
      JSON.stringify({ device_id: 'dev-A', device_name: 'Phone A', pin: pairInfo.json.pin }),
    );
    assert.equal(pairA.status, 200);
    assert.ok(pairA.json.token.startsWith('nuvio_sec_'));

    const tokenB = tokenStore.createDeviceToken('dev-B', 'Phone B', '127.0.0.1');

    // 7. Phone B cannot revoke Phone A (403 FORBIDDEN)
    const crossRevoke = await httpJson(
      port,
      'POST',
      '/api/v1/devices/revoke',
      {
        Host: `127.0.0.1:${port}`,
        'Content-Type': 'application/json',
        Authorization: `Bearer ${tokenB}`,
      },
      JSON.stringify({ device_id: 'dev-A' }),
    );
    assert.equal(crossRevoke.status, 403);

    // 8. Connect authenticated WS for dev-A and unauthenticated WS
    const unauthedReceived = [];
    const unauthedSock = new ws.WebSocket(`ws://127.0.0.1:${port}`);
    await new Promise((r) => unauthedSock.once('open', r));
    unauthedSock.on('message', (data) => unauthedReceived.push(JSON.parse(data.toString('utf8'))));

    const authedSock = new ws.WebSocket(`ws://127.0.0.1:${port}`, {
      headers: { Authorization: `Bearer ${pairA.json.token}` },
    });
    await new Promise((r) => authedSock.once('open', r));

    const welcomePromise = new Promise((resolve) => {
      authedSock.once('message', (data) => resolve(JSON.parse(data.toString('utf8'))));
    });
    authedSock.send(JSON.stringify({ type: 'hello', name: 'Test Phone A' }));
    const welcome = await welcomePromise;
    assert.equal(welcome.type, 'welcome');
    assert.equal(welcome.pairedToken, undefined);

    // Trigger state broadcast -> unauthed socket must NOT receive it
    state.forceState('detail');
    await new Promise((r) => setTimeout(r, 80));
    assert.equal(unauthedReceived.some((m) => m.type === 'state'), false);

    // 9. Revoking dev-A via IPC admin must immediately close dev-A's active WS with 1008
    const closedPromise = new Promise((resolve) => {
      authedSock.once('close', (code) => resolve(code));
    });
    const revokeRes = await httpJson(
      port,
      'POST',
      '/api/v1/devices/revoke',
      {
        Host: `127.0.0.1:${port}`,
        'Content-Type': 'application/json',
        'X-Nuvio-Local-Token': ipcToken,
      },
      JSON.stringify({ device_id: 'dev-A' }),
    );
    assert.equal(revokeRes.status, 200);
    assert.equal(revokeRes.json.disconnected_sessions, 1);
    const closeCode = await closedPromise;
    assert.equal(closeCode, 1008);

    // 10. WebSocket upgrade rejects cross-origin browser requests and untrusted Host headers
    const badOriginStatus = await new Promise((resolve) => {
      const s = new ws.WebSocket(`ws://127.0.0.1:${port}`, {
        headers: { Origin: 'https://evil.example' },
      });
      s.once('unexpected-response', (_req, res) => resolve(res.statusCode));
      s.once('open', () => { s.close(); resolve(200); });
      s.once('error', () => resolve(-1));
    });
    assert.equal(badOriginStatus, 403);

    const badHostStatus = await new Promise((resolve) => {
      const s = new ws.WebSocket(`ws://127.0.0.1:${port}`, {
        headers: { Host: `fc-evil.com:${port}` },
      });
      s.once('unexpected-response', (_req, res) => resolve(res.statusCode));
      s.once('open', () => { s.close(); resolve(200); });
      s.once('error', () => resolve(-1));
    });
    assert.equal(badHostStatus, 403);

    // 11. Query-string ?auth= is no longer accepted for WebSocket pre-authentication
    const queryAuthSock = new ws.WebSocket(`ws://127.0.0.1:${port}/?auth=${tokenB}`);
    await new Promise((r) => queryAuthSock.once('open', r));
    const queryAuthReply = await new Promise((resolve) => {
      queryAuthSock.once('message', (data) => resolve(JSON.parse(data.toString('utf8'))));
      queryAuthSock.send(JSON.stringify({ type: 'get_state' }));
    });
    assert.equal(queryAuthReply.type, 'error');
    assert.equal(queryAuthReply.code, 'AUTH_REQUIRED');
    queryAuthSock.close();

    unauthedSock.close();
  } finally {
    srv.stop();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('CompanionServer fails closed on /api/v1/local/* when no localIpcToken is configured', async () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'nuvio-srv-noipc-'));
  const port = 19787 + Math.floor(Math.random() * 1000);
  const srv = new CompanionServer({
    cfg: {
      port,
      loopback: false,
      nuvioExePath: null,
      nuvioTitle: 'Nuvio',
      logPath: null,
      apkPath: null,
      tunnel: { enabled: false },
      screen: { encoder: 'libx264', bitrateKbps: 2500, gopSeconds: 2, portRangeBegin: 60000, portRangeEnd: 60100 },
    },
    state: new ScreenStateMachine(),
    injector: { resolveNuvioExePath: () => null, setNuvioExePath: () => undefined, getBind: () => ({}) },
    screen: {
      setStatusCb: () => undefined,
      setFrameCb: () => undefined,
      setAudioCb: () => undefined,
      isRunning: () => false,
      stopIfOwner: () => false,
      stop: () => false,
      cursorNorm: () => ({ nx: 0.5, ny: 0.5, visible: false }),
      activeMonitor: () => 1,
    },
    logtail: null,
    tokenStore: new TokenStore(tmpDir),
    localIpcToken: '',
  });
  srv.start();
  await new Promise((r) => setTimeout(r, 120));
  try {
    const res = await httpJson(port, 'GET', '/api/v1/local/pair-info', { Host: `127.0.0.1:${port}` });
    assert.equal(res.status, 403);
    assert.equal(res.json.error, 'LOOPBACK_ONLY');
  } finally {
    srv.stop();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});
