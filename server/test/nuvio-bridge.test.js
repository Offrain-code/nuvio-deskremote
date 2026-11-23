const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');

const {
  NuvioBridge,
  encodeWire,
  decodeWire,
  parseStateLine,
  parseSourcesLine,
  parseTrackChoicesLine,
  parseQueueLine,
  parseWakeLine,
  parseLibraryVideoLine,
  parseLibraryMusicLine,
  parseContinueLine,
  parseEpisodesLine,
  parseSearchResultsLine,
  parseAlbumTracksLine,
  parseMenuLine,
  parsePlaylistsLine,
  parseRecapLine,
  parseDoneLine,
  parseLyricsLine,
  parseLiveTvLine,
  parseTonightLine,
} = require('../dist/nuvioBridge');

test('parseStateLine parses full film and music STATE lines', () => {
  const filmLine = [
    'STATE', 'film', '1', '125000', '3600000', '80', '100', '1',
    encodeWire('Duna: Parte Dois'),
    encodeWire('4K HDR'),
    encodeWire('https://img.example/poster.jpg'),
    encodeWire('Português (BR)'),
    encodeWire('English 5.1'),
    '125',
    encodeWire('Skip intro'),
    '1',
    '30',
    '-', '-', '-',
  ].join(' ');

  const film = parseStateLine(filmLine, 9999);
  assert.ok(film);
  assert.equal(film.kind, 'film');
  assert.equal(film.isPlaying, true);
  assert.equal(film.positionMs, 125000);
  assert.equal(film.durationMs, 3600000);
  assert.equal(film.volume, 80);
  assert.equal(film.volumeMax, 100);
  assert.equal(film.title, 'Duna: Parte Dois');
  assert.equal(film.subtitle, '4K HDR');
  assert.equal(film.subtitleTrack, 'Português (BR)');
  assert.equal(film.audioTrack, 'English 5.1');
  assert.equal(film.speedPercent, 125);
  assert.equal(film.skipLabel, 'Skip intro');
  assert.equal(film.canNextEpisode, true);
  assert.equal(film.sleepMinutes, 30);
  assert.equal(film.shuffle, null);

  const musicLine = [
    'STATE', 'music', '0', '42000', '210000', '65', '100', '1',
    encodeWire('Time'), encodeWire('Pink Floyd · Dark Side'), '-',
    '-', '-', '-', '-', '0', '0', '1', '2', '1',
  ].join(' ');
  const music = parseStateLine(musicLine, 1234);
  assert.ok(music);
  assert.equal(music.kind, 'music');
  assert.equal(music.isPlaying, false);
  assert.equal(music.shuffle, true);
  assert.equal(music.repeat, 2);
  assert.equal(music.liked, true);
});

test('parseSourcesLine, parseTrackChoicesLine, parseQueueLine, and parseWakeLine', () => {
  const closed = parseSourcesLine('SOURCES 0 0 - -');
  assert.deepEqual(closed, { open: false, list: null });

  const open = parseSourcesLine(
    `SOURCES 1 0 ${encodeWire('Oppenheimer')} ` +
    `${encodeWire('Torrentio 4K')},${encodeWire('2160p DV HEVC')},${encodeWire('Torrentio')},18000000000,1,1;` +
    `${encodeWire('1080p Backup')},-,${encodeWire('MediaFusion')},-,0,0`,
  );
  assert.equal(open.open, true);
  assert.equal(open.list.title, 'Oppenheimer');
  assert.equal(open.list.sources.length, 2);
  assert.equal(open.list.sources[0].number, 1);
  assert.equal(open.list.sources[0].instant, true);
  assert.equal(open.list.sources[0].sizeBytes, 18000000000);
  assert.equal(open.list.sources[1].playable, false);

  const subs = parseTrackChoicesLine(
    `SUBTITLES 1 ${encodeWire('Off')},-,${encodeWire('Off')},0;` +
    `${encodeWire('Portuguese')},${encodeWire('OpenSubtitles')},${encodeWire('Addons')},1`,
  );
  assert.equal(subs.loading, true);
  assert.equal(subs.choices.length, 2);
  assert.equal(subs.choices[1].number, 2);
  assert.equal(subs.choices[1].selected, true);

  const queue = parseQueueLine(
    `QUEUE 0 ${encodeWire('vid1')},${encodeWire('Track A')},${encodeWire('Artist 1')},200,-,0;` +
    `${encodeWire('vid2')},${encodeWire('Track B')},${encodeWire('Artist 2')},185,${encodeWire('https://c/1.jpg')},1`,
  );
  assert.equal(queue.playing, 0);
  assert.equal(queue.tracks.length, 2);
  assert.equal(queue.tracks[1].upcoming, true);

  const wake = parseWakeLine('WAKE 001122334455,INVALID 192.168.1.255');
  assert.deepEqual(wake, { macs: ['001122334455'], broadcasts: ['192.168.1.255'] });
  assert.equal(decodeWire(encodeWire('Olá Nuvio 100%!')), 'Olá Nuvio 100%!');
});

function createMockNuvioServer(validPin, issuedToken) {
  const received = [];
  let knownToken = null;
  const sockets = new Set();

  const server = net.createServer((socket) => {
    sockets.add(socket);
    let buf = '';
    socket.on('close', () => sockets.delete(socket));
    socket.on('data', (chunk) => {
      buf += chunk.toString('utf8');
      let nl = buf.indexOf('\n');
      while (nl !== -1) {
        const line = buf.slice(0, nl).replace(/\r$/, '');
        buf = buf.slice(nl + 1);
        if (line) {
          received.push(line);
          const parts = line.split(' ');
          if (parts[0] === 'HELLO') {
            if (knownToken && parts[2] === knownToken) {
              socket.write('WELCOME Demo-HTPC\n');
            } else {
              socket.write('PIN_REQUIRED\n');
            }
          } else if (parts[0] === 'PAIR') {
            if (parts[1] === validPin) {
              knownToken = issuedToken;
              socket.write(`PAIRED ${issuedToken} Demo-HTPC\n`);
            } else if (parts[1] === '999999') {
              socket.write('LOCKED\n');
            } else {
              socket.write('BAD_PIN\n');
            }
          } else if (parts[0] === 'SUBSCRIBE') {
            socket.write(`STATE film 1 10000 60000 75 100 1 ${encodeWire('Teste')} - - - - 100 - 0 0 - - -\n`);
          } else if (parts[0] === 'PING') {
            socket.write('PONG\n');
          } else if (parts[0] === 'SUBTITLES') {
            socket.write(`SUBTITLES 0 Off,-,Off,1;PT-BR,-,Built-in,0\n`);
          } else if (parts[0] === 'OPEN') {
            if (decodeWire(parts[1]).includes('youtu')) {
              socket.write(`OPENING ${encodeWire('a YouTube video')}\n`);
            } else {
              socket.write('CANT_OPEN\n');
            }
          }
        }
        nl = buf.indexOf('\n');
      }
    });
  });

  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address();
      resolve({
        port: addr.port,
        received,
        close: () => new Promise((done) => {
          for (const s of sockets) s.destroy();
          server.close(() => done());
        }),
      });
    });
  });
}

test('NuvioBridge completes handshake, PIN pairing, token persistence, STATE sync, and token reconnect', async () => {
  const mock = await createMockNuvioServer('654321', 'tok_abc123');
  const tmpFile = path.join(os.tmpdir(), `nuvio-bridge-test-${Date.now()}.json`);

  const bridge = new NuvioBridge({
    host: '127.0.0.1',
    port: mock.port,
    storagePath: tmpFile,
    pingIntervalMs: 5000,
    reconnectBaseMs: 200,
  });

  try {
    const statePromise = new Promise((resolve) => {
      bridge.once('state', (s) => {
        if (s) resolve(s);
      });
    });

    const bad = await bridge.pair('111111');
    assert.deepEqual(bad, { ok: false, error: 'BAD_PIN' });

    const good = await bridge.pair('654321');
    assert.deepEqual(good, { ok: true, pcName: 'Demo-HTPC' });
    assert.equal(bridge.isConnected(), true);

    const state = await statePromise;
    assert.equal(state.title, 'Teste');
    assert.equal(state.volume, 75);

    const subsPromise = new Promise((resolve) => bridge.once('subtitles', resolve));
    assert.equal(bridge.requestSubtitles(), true);
    const subs = await subsPromise;
    assert.equal(subs.choices.length, 2);

    assert.equal(bridge.seek(25000), true);
    assert.equal(bridge.setVolume(88), true);
    assert.equal(bridge.control('SKIP_INTRO'), true);
    assert.equal(bridge.control('SPEED', 150), true);

    const openOk = await bridge.openLink('https://youtu.be/dQw4w9WgXcQ');
    assert.deepEqual(openOk, { ok: true, what: 'a YouTube video' });
    const openFail = await bridge.openLink('https://example.com/nope');
    assert.deepEqual(openFail, { ok: false });

    bridge.stop();

    // Second instance loads the saved token and reconnects directly (WELCOME) without prompting for PIN
    const bridge2 = new NuvioBridge({
      host: '127.0.0.1',
      port: mock.port,
      storagePath: tmpFile,
      pingIntervalMs: 5000,
    });
    const connectedPromise = new Promise((resolve) => {
      bridge2.on('status', (snap) => {
        if (snap.status === 'connected') resolve(snap);
      });
    });
    bridge2.start();
    const snap2 = await connectedPromise;
    assert.equal(snap2.status, 'connected');
    assert.equal(snap2.pcName, 'Demo-HTPC');
    bridge2.stop();

    assert.ok(mock.received.includes('SEEK 25000'));
    assert.ok(mock.received.includes('VOLUME 88'));
    assert.ok(mock.received.includes('SKIP_INTRO'));
    assert.ok(mock.received.includes('SPEED 150'));
  } finally {
    bridge.stop();
    await mock.close();
    try { fs.unlinkSync(tmpFile); } catch { /* ignore */ }
  }
});

test('Phase 2 parsers (LIBRARY, CONTINUE, EPISODES, SEARCH_RESULTS, ALBUM_TRACKS, MENU, PLAYLISTS, RECAP, DONE)', () => {
  const libV = parseLibraryVideoLine(
    `LIBRARY video 1 ${encodeWire('Library')},series,tt0096697,${encodeWire('Os Simpsons')},1989,${encodeWire('https://p/1.jpg')},-,1`,
  );
  assert.ok(libV);
  assert.equal(libV.ready, true);
  assert.equal(libV.items.length, 1);
  assert.equal(libV.items[0].name, 'Os Simpsons');
  assert.equal(libV.items[0].watched, true);

  const libM = parseLibraryMusicLine(
    `LIBRARY music 1 a1,${encodeWire('Daft Punk')},${encodeWire('https://p/dp.jpg')},1 a1,MPREb_1,${encodeWire('Discovery')},Album,2001,${encodeWire('https://p/disc.jpg')},1`,
  );
  assert.ok(libM);
  assert.equal(libM.artists.length, 1);
  assert.equal(libM.artists[0].starred, true);
  assert.equal(libM.albums.length, 1);
  assert.equal(libM.albums[0].title, 'Discovery');

  const cont = parseContinueLine(
    `CONTINUE series,tt0096697,tt0096697:22:2,${encodeWire('Os Simpsons')},${encodeWire('S22 E2 · A Lisa Caridosa')},${encodeWire('https://p/s.jpg')},-,300000,1440000,${encodeWire('Torrentio')},208`,
  );
  assert.ok(cont);
  assert.equal(cont.length, 1);
  assert.equal(cont[0].progressPermille, 208);

  const eps = parseEpisodesLine(
    `EPISODES series tt0096697 1 22,2,${encodeWire('A Lisa Caridosa')},tt0096697:22:2,-,2010-10-03,500`,
  );
  assert.ok(eps);
  assert.equal(eps.found, true);
  assert.equal(eps.episodes.length, 1);
  assert.equal(eps.episodes[0].season, 22);
  assert.equal(eps.episodes[0].episode, 2);

  const search = parseSearchResultsLine(
    `SEARCH_RESULTS 0 ${encodeWire('andor')} ${encodeWire('Addons')},series,tt9253284,Andor,2022,-,-,0`,
  );
  assert.ok(search);
  assert.equal(search.loading, false);
  assert.equal(search.query, 'andor');
  assert.equal(search.items.length, 1);

  const album = parseAlbumTracksLine(
    `ALBUM_TRACKS MPREb_1 vid1,${encodeWire('One More Time')},${encodeWire('Daft Punk')},320,1`,
  );
  assert.ok(album);
  assert.equal(album.tracks.length, 1);
  assert.equal(album.tracks[0].playable, true);

  const menu = parseMenuLine(
    `MENU title series tt0096697 - ${encodeWire('Os Simpsons')} ${encodeWire('Série · 1989')} watched,${encodeWire('Marcar como assistido')};recap,${encodeWire('Resumo com IA')}`,
  );
  assert.ok(menu);
  assert.equal(menu.actions.length, 2);
  assert.equal(menu.actions[0].code, 'watched');

  const pls = parsePlaylistsLine(
    `PLAYLISTS tt0096697 series ${encodeWire('Os Simpsons')} 1,2,3 pl1,${encodeWire('Favoritos')},12`,
  );
  assert.ok(pls);
  assert.equal(pls.items.length, 1);
  assert.equal(pls.items[0].count, 12);

  const recap = parseRecapLine(`RECAP 1 ${encodeWire('Resumo S22')} ${encodeWire('Resumo gerado com IA.')}`);
  assert.ok(recap);
  assert.equal(recap.state, 1);
  assert.equal(recap.text, 'Resumo gerado com IA.');

  const done = parseDoneLine(`DONE 1 ${encodeWire('Marcado como assistido')} library`);
  assert.ok(done);
  assert.equal(done.ok, true);
  assert.equal(done.refresh, 'library');
});

test('NuvioBridge sends KEY sources on requestSources and Phase 2 commands', async () => {
  const mock = await createMockNuvioServer('654321', 'tok_phase2');
  const tmpFile = path.join(os.tmpdir(), `nuvio-bridge-p2-${Date.now()}.json`);
  const bridge = new NuvioBridge({ host: '127.0.0.1', port: mock.port, storagePath: tmpFile });

  try {
    const res = await bridge.pair('654321');
    assert.equal(res.ok, true);
    assert.equal(bridge.requestSources(false), true);
    assert.equal(bridge.requestLibrary('video'), true);
    assert.equal(bridge.requestLibrary('music'), true);
    assert.equal(bridge.requestContinue(), true);
    assert.equal(bridge.requestEpisodes('series', 'tt0096697'), true);
    assert.equal(bridge.search('Os Simpsons'), true);
    assert.equal(bridge.requestAlbumTracks('MPREb_1'), true);
    assert.equal(bridge.requestMenu('title', 'series', 'tt0096697'), true);
    assert.equal(bridge.doMenuAction('title', 'series', 'tt0096697', 'watched'), true);

    await new Promise((r) => setTimeout(r, 80));
    assert.ok(mock.received.includes('KEY sources'));
    assert.ok(mock.received.includes('LIBRARY video'));
    assert.ok(mock.received.includes('LIBRARY music'));
    assert.ok(mock.received.includes('CONTINUE'));
    assert.ok(mock.received.includes('EPISODES series tt0096697'));
    assert.ok(mock.received.includes(`SEARCH ${encodeWire('Os Simpsons')}`));
    assert.ok(mock.received.includes('ALBUM_TRACKS MPREb_1'));
    assert.ok(mock.received.includes('MENU title series tt0096697 -'));
    assert.ok(mock.received.includes('DO title series tt0096697 - watched'));
  } finally {
    bridge.stop();
    await mock.close();
    try { fs.unlinkSync(tmpFile); } catch { /* ignore */ }
  }
});

test('Phase 3 parsers (LYRICS, LIVETV, and TONIGHT)', () => {
  const lyrics = parseLyricsLine(
    `LYRICS synced ${encodeWire('Instant Crush')} 12000,${encodeWire('I didnt want to be the one')},${encodeWire('I:12000:12200|didnt:12200:12600')}`,
  );
  assert.ok(lyrics);
  assert.equal(lyrics.kind, 'synced');
  assert.equal(lyrics.title, 'Instant Crush');
  assert.equal(lyrics.lines.length, 1);
  assert.equal(lyrics.lines[0].words.length, 2);

  assert.deepEqual(parseLiveTvLine('LIVETV 0'), { open: false, screen: null });
  const live = parseLiveTvLine(
    `LIVETV 1 channels ${encodeWire('Esportes')} ${encodeWire('Ao vivo')} ` +
    `channels,${encodeWire('Canais')} ` +
    `main,0,1,${encodeWire('SporTV')},${encodeWire('Brasileirão')},-,1,450,watch ` +
    `top,0,${encodeWire('Guia')},1 -`,
  );
  assert.ok(live && live.open && live.screen);
  assert.equal(live.screen.items.length, 1);
  assert.equal(live.screen.items[0].live, true);
  assert.equal(live.screen.buttons.length, 1);

  assert.deepEqual(parseTonightLine('TONIGHT 0'), { open: false, screen: null });
  const tonight = parseTonightLine(
    `TONIGHT 1 ask solo 0 2 5 20 8 1 ${encodeWire('Qual o clima hoje?')} - - - - ` +
    `${encodeWire('Ação')};${encodeWire('Comédia')} ` +
    `a,${encodeWire('Duna')},${encodeWire('Épico sci-fi')},${encodeWire('2024 • 4K')},-,1 - 30 60 0 0`,
  );
  assert.ok(tonight && tonight.open && tonight.screen);
  assert.equal(tonight.screen.answers.length, 2);
  assert.equal(tonight.screen.cards.length, 1);
  assert.equal(tonight.screen.cards[0].name, 'Duna');
});
