/**
 * CLI test client for Nuvio DeskRemote Companion.
 * Usage: node test/client.js [ws://127.0.0.1:8788]
 * Interactive commands:
 *   up|down|left|right|ok|back|home|search|library|discover|calendar|live|game|fullscreen
 *   key <Name>          (Space, Escape, ...)
 *   type <text>
 *   mouse dx dy | click left|right|middle | scroll <n>
 *   state               (get_state)
 *   set <home|detail|player|search>
 *   screen on|off       (starts/stops screen streaming over WS)
 *   quit
 */
const WebSocket = require('ws');
const readline = require('readline');

const url = process.argv[2] ?? 'ws://127.0.0.1:8788';
const ws = new WebSocket(url);
const rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt: 'nuvio> ' });

ws.on('open', () => {
  send({ type: 'hello', name: 'test-client' });
  console.log(`connected to ${url}`);
  rl.prompt();
});
ws.on('message', (data) => {
  console.log('<<', data.toString());
  rl.prompt();
});
ws.on('error', (err) => console.error('WS error:', err.message));
ws.on('close', () => process.exit(0));

function send(obj) {
  ws.send(JSON.stringify(obj));
}

rl.on('line', (line) => {
  const parts = line.trim().split(/\s+/);
  const cmd = parts[0] ?? '';
  const rest = parts.slice(1);
  switch (cmd) {
    case 'quit': ws.close(); return;
    case 'state': send({ type: 'get_state' }); break;
    case 'set': send({ type: 'state', screen: rest[0] ?? '' }); break;
    case 'key': send({ type: 'key', name: rest[0] ?? '' }); break;
    case 'type': send({ type: 'type', text: line.trim().slice(5) }); break;
    case 'mouse':
      if (rest[0] === 'click' || rest[0] === 'scroll') {
        if (rest[0] === 'click') send({ type: 'mouse', click: rest[1] ?? 'left' });
        else send({ type: 'mouse', scroll: Number(rest[1] ?? 0) });
      } else {
        send({ type: 'mouse', move: { dx: Number(rest[0] ?? 0), dy: Number(rest[1] ?? 0) } });
      }
      break;
    case 'screen': send({ type: rest[0] === 'off' ? 'screen_stop' : 'screen_start', profile: rest[1] ?? '720p30' }); break;
    case '': break;
    default: send({ type: 'nav', action: cmd }); break;
  }
  rl.prompt();
});
