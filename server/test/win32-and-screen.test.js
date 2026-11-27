const test = require('node:test');
const assert = require('node:assert/strict');

const {
  attachToInteractiveDesktop,
  ensureForeground,
  findNuvioWindow,
  isExcludedNuvioWindowTitle,
  INPUT_SIZE_X64,
  KEYEVENTF_KEYUP,
  KEYEVENTF_UNICODE,
  MOUSEEVENTF_LEFTUP,
} = require('../dist/injection/win32');
const { InputInjector } = require('../dist/injection/input');
const { H264AccessUnitParser } = require('../dist/screen/h264FrameParser');

function createMockWin32() {
  const calls = {
    closeDesktop: [],
    sendInputBatches: [],
    mouseEvents: [],
    sendMessageW: [],
    postMessageW: [],
  };

  const bind = {
    user32: { func: () => () => 0 },
    gdi32: { func: () => () => 0 },
    kernel32: { func: () => () => 0 },
    OpenDesktopW: () => 42n,
    OpenInputDesktop: () => 0n,
    SetThreadDesktop: () => 1,
    CloseDesktop: (h) => {
      calls.closeDesktop.push(h);
      return 1;
    },
    FindWindowW: () => 0n,
    GetForegroundWindow: () => 100n,
    GetTopWindow: () => 0n,
    GetWindow: () => 0n,
    GetWindowTextW: () => 0,
    GetWindowThreadProcessId: () => 200,
    GetCurrentThreadId: () => 100,
    AttachThreadInput: () => 1,
    GetFocus: () => 0n,
    SetFocus: () => 0n,
    SetActiveWindow: () => 0n,
    IsWindowVisible: () => 1,
    IsIconic: () => 0,
    IsWindow: () => 1,
    ShowWindow: () => 1,
    SetForegroundWindow: () => 0,
    BringWindowToTop: () => 1,
    SendInput: (count, buf) => {
      calls.sendInputBatches.push({ count, buf: Buffer.from(buf) });
      return count;
    },
    SetCursorPos: () => 1,
    GetCursorPos: () => 0,
    GetCursorInfo: () => 0,
    MapVirtualKeyW: () => 0,
    GetSystemMetrics: () => 1920,
    SendMessageW: (...args) => {
      calls.sendMessageW.push(args);
      return 0;
    },
    PostMessageW: (...args) => {
      calls.postMessageW.push(args);
      return 1;
    },
    MessageBeep: () => 1,
    mouse_event: (...args) => {
      calls.mouseEvents.push(args);
    },
  };

  return { bind, calls };
}

test('attachToInteractiveDesktop always closes HDESK handle via CloseDesktop', () => {
  const { bind, calls } = createMockWin32();
  const ok = attachToInteractiveDesktop(bind);
  assert.equal(ok, true);
  assert.deepEqual(calls.closeDesktop, [42n]);
});

test('ensureForeground sends Alt keydown before keyup (never leaves Alt stuck)', () => {
  const { bind, calls } = createMockWin32();
  ensureForeground(bind, 999n);

  // Expect 2 SendInput calls for VK_MENU (0x12): first keydown (flags=0), then keyup (KEYEVENTF_KEYUP)
  assert.equal(calls.sendInputBatches.length, 2);
  const firstVk = calls.sendInputBatches[0].buf.readUInt16LE(8);
  const firstFlags = calls.sendInputBatches[0].buf.readUInt32LE(12);
  const secondVk = calls.sendInputBatches[1].buf.readUInt16LE(8);
  const secondFlags = calls.sendInputBatches[1].buf.readUInt32LE(12);

  assert.equal(firstVk, 0x12);
  assert.equal(firstFlags & KEYEVENTF_KEYUP, 0);
  assert.equal(secondVk, 0x12);
  assert.equal(secondFlags & KEYEVENTF_KEYUP, KEYEVENTF_KEYUP);
});

test('InputInjector.typeText encodes emojis as UTF-16 surrogate pairs without RangeError', async () => {
  const { bind, calls } = createMockWin32();
  const injector = new InputInjector(bind, 'Nuvio');

  const res = await injector.typeText('A😀', true);
  assert.equal(res.ok, true);
  assert.equal(calls.sendInputBatches.length, 2);

  // 'A' -> 2 INPUT structs (down, up)
  assert.equal(calls.sendInputBatches[0].count, 2);
  assert.equal(calls.sendInputBatches[0].buf.readUInt16LE(10), 'A'.charCodeAt(0));

  // '😀' (U+1F600 -> 0xD83D 0xDE00) -> 4 INPUT structs (hi down, lo down, hi up, lo up)
  const emojiBatch = calls.sendInputBatches[1];
  assert.equal(emojiBatch.count, 4);
  assert.equal(emojiBatch.buf.readUInt16LE(10), 0xd83d);
  assert.equal(emojiBatch.buf.readUInt32LE(12), KEYEVENTF_UNICODE);
  assert.equal(emojiBatch.buf.readUInt16LE(INPUT_SIZE_X64 + 10), 0xde00);
  assert.equal(emojiBatch.buf.readUInt32LE(INPUT_SIZE_X64 + 12), KEYEVENTF_UNICODE);
  assert.equal(emojiBatch.buf.readUInt16LE(2 * INPUT_SIZE_X64 + 10), 0xd83d);
  assert.equal(emojiBatch.buf.readUInt32LE(2 * INPUT_SIZE_X64 + 12), KEYEVENTF_UNICODE | KEYEVENTF_KEYUP);
  assert.equal(emojiBatch.buf.readUInt16LE(3 * INPUT_SIZE_X64 + 10), 0xde00);
  assert.equal(emojiBatch.buf.readUInt32LE(3 * INPUT_SIZE_X64 + 12), KEYEVENTF_UNICODE | KEYEVENTF_KEYUP);
});

test('InputInjector.releaseHeldMouseButtons releases stuck buttons on disconnect', async () => {
  const { bind, calls } = createMockWin32();
  const injector = new InputInjector(bind, 'Nuvio');

  await injector.mouseHold('left', true);
  injector.releaseHeldMouseButtons();
  // Second call is a no-op
  injector.releaseHeldMouseButtons();

  assert.equal(calls.mouseEvents.length, 2);
  assert.equal(calls.mouseEvents[1][0], MOUSEEVENTF_LEFTUP);
});

test('InputInjector curtain_on and curtain_off use non-blocking PostMessageW', async () => {
  const { bind, calls } = createMockWin32();
  const injector = new InputInjector(bind, 'Nuvio');

  await injector.handleWindowAction('curtain_on');
  await injector.handleWindowAction('curtain_off');

  assert.equal(calls.sendMessageW.length, 0);
  assert.equal(calls.postMessageW.length, 2);
  assert.deepEqual(calls.postMessageW[0], [0xffff, 0x0112, 0xf170, 2]);
  assert.deepEqual(calls.postMessageW[1], [0xffff, 0x0112, 0xf170, -1]);
});

test('H264AccessUnitParser places cached PPS after SPS when IDR has SPS only', () => {
  const emitted = [];
  const parser = new H264AccessUnitParser((au) => emitted.push(au));

  // First keyframe establishes cached SPS (type 7) and PPS (type 8)
  const sps = Buffer.from([0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xe0, 0x1f]);
  const pps = Buffer.from([0x00, 0x00, 0x00, 0x01, 0x68, 0xce, 0x38, 0x80]);
  const idr = Buffer.from([0x00, 0x00, 0x00, 0x01, 0x65, 0x88, 0x84, 0x01]);
  parser.push(Buffer.concat([sps, pps, idr]));
  assert.equal(emitted.length, 1);

  // Second keyframe contains SPS + IDR without PPS -> cached PPS must appear AFTER SPS (7, 8, 5)
  parser.push(Buffer.concat([sps, idr]));
  assert.equal(emitted.length, 2);
  assert.deepEqual(emitted[1].nalTypes, [7, 8, 5]);
});

test('findNuvioWindow ignores Companion single-instance window (com.nuvio.deskremote.companion-siw)', () => {
  const windows = new Map([
    [10n, 'com.nuvio.deskremote.companion-siw'],
    [20n, 'Nuvio DeskRemote Companion'],
    [30n, 'Nuvio HTPC'],
  ]);
  const nextMap = new Map([
    [10n, 20n],
    [20n, 30n],
    [30n, 0n],
  ]);
  const { bind } = createMockWin32();
  bind.GetTopWindow = () => 10n;
  bind.GetWindow = (h) => nextMap.get(h) ?? 0n;
  bind.GetWindowTextW = (h, buf) => {
    const t = windows.get(h) || '';
    const raw = Buffer.from(t, 'utf16le');
    raw.copy(buf, 0);
    return t.length;
  };

  assert.equal(isExcludedNuvioWindowTitle('com.nuvio.deskremote.companion-siw'), true);
  assert.equal(isExcludedNuvioWindowTitle('Nuvio DeskRemote Companion'), true);
  assert.equal(isExcludedNuvioWindowTitle('Nuvio HTPC'), false);

  // When Nuvio HTPC is present alongside Companion windows, it must pick Nuvio HTPC (30n)
  assert.equal(findNuvioWindow(bind, 'Nuvio HTPC'), 30n);

  // When Nuvio HTPC is not open yet, it must return null (never cache Companion's SIW window)
  windows.delete(30n);
  nextMap.set(20n, 0n);
  assert.equal(findNuvioWindow(bind, 'Nuvio HTPC'), null);
});
