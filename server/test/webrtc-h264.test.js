const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const ndc = require('node-datachannel');

const dist = path.resolve(__dirname, '..', 'dist');
const { H264AccessUnitParser, PIPE_READ_CHUNK_SIZE } = require(path.join(dist, 'screen', 'h264FrameParser.js'));
const { resolveH264StreamCodec } = require(path.join(dist, 'screen', 'encoder.js'));
const { pickH264OfferCodec, pickPlayoutDelayExtId, WebRtcScreenSession } = require(
  path.join(dist, 'screen', 'webrtc.js'),
);

test('H264AccessUnitParser strips AUD(9), groups SPS+PPS+IDR, and reinjects headers before later IDRs', () => {
  const units = [];
  const parser = new H264AccessUnitParser((au) => units.push(au));

  const aud = Buffer.from([0x00, 0x00, 0x00, 0x01, 0x09, 0xf0]);
  const sps = Buffer.from([0x00, 0x00, 0x01, 0x67, 0x42, 0xe0, 0x1f, 0xda]);
  const pps = Buffer.from([0x00, 0x00, 0x00, 0x01, 0x68, 0xce, 0x3c, 0x80]);
  const idr1 = Buffer.from([0x00, 0x00, 0x00, 0x01, 0x65, 0x88, 0x84, 0x21]);
  const pFrame = Buffer.from([0x00, 0x00, 0x00, 0x01, 0x41, 0x9a, 0x22, 0x11]);
  const idrNoHeaders = Buffer.from([0x00, 0x00, 0x00, 0x01, 0x65, 0x88, 0x99, 0x01]);

  // Chunks < 64KB should flush immediately without waiting for the next frame
  parser.push(Buffer.concat([aud, sps, pps, idr1]));
  assert.equal(units.length, 1);
  assert.equal(units[0].isKeyFrame, true);
  assert.deepEqual(units[0].nalTypes, [7, 8, 5]);
  // 3-byte SPS start code normalized to 4 bytes
  assert.deepEqual(units[0].auBuffer.subarray(0, 4), Buffer.from([0, 0, 0, 1]));

  parser.push(Buffer.concat([aud, pFrame]));
  assert.equal(units.length, 2);
  assert.equal(units[1].isKeyFrame, false);
  assert.deepEqual(units[1].nalTypes, [1]);

  // Second IDR without SPS/PPS in chunk must prepend cached SPS+PPS before the slice
  parser.push(idrNoHeaders);
  assert.equal(units.length, 3);
  assert.equal(units[2].isKeyFrame, true);
  assert.deepEqual(units[2].nalTypes, [7, 8, 5]);
});

test('H264AccessUnitParser reassembles chunks split exactly on the 64KB boundary', () => {
  const units = [];
  const parser = new H264AccessUnitParser((au) => units.push(au));

  const bigIdrHeader = Buffer.from([0x00, 0x00, 0x00, 0x01, 0x65, 0x88, 0x84]);
  const bigIdr = Buffer.concat([bigIdrHeader, Buffer.alloc(PIPE_READ_CHUNK_SIZE + 500, 0x5a)]);
  const sps = Buffer.from([0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xe0, 0x1f]);
  const pps = Buffer.from([0x00, 0x00, 0x00, 0x01, 0x68, 0xce, 0x3c, 0x80]);
  const fullPacket = Buffer.concat([sps, pps, bigIdr]);

  const part1 = fullPacket.subarray(0, PIPE_READ_CHUNK_SIZE);
  const part2 = fullPacket.subarray(PIPE_READ_CHUNK_SIZE);

  parser.push(part1);
  assert.equal(units.length, 0, 'exact 64KB chunk must not prematurely truncate a large frame');
  parser.push(part2);
  assert.equal(units.length, 1);
  assert.equal(units[0].isKeyFrame, true);
  assert.equal(units[0].auBuffer.length, fullPacket.length);
});

test('pickH264OfferCodec chooses PT 102 Constrained Baseline even when VP8 uses PT 96', () => {
  const androidOffer = [
    'v=0',
    'o=- 1 2 IN IP4 127.0.0.1',
    's=-',
    't=0 0',
    'm=video 9 UDP/TLS/RTP/SAVPF 96 98 102 104',
    'a=mid:0',
    'a=rtpmap:96 VP8/90000',
    'a=rtpmap:98 VP9/90000',
    'a=rtpmap:102 H264/90000',
    'a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f',
    'a=rtpmap:104 H264/90000',
    'a=fmtp:104 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=640c1f',
    '',
  ].join('\r\n');

  const codec = pickH264OfferCodec(androidOffer);
  assert.equal(codec.payloadType, 102);
  assert.match(codec.fmtp, /42e01f/);
});

test('WebRtcScreenSession sends H.264 Access Units with negotiated PT and marker only on final packet', async () => {
  const clientPc = new ndc.PeerConnection('android-sim', { iceServers: [] });
  const recvVideo = new ndc.Video('0', 'RecvOnly');
  recvVideo.addH264Codec(102, 'level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f');
  const recvTrack = clientPc.addTrack(recvVideo);
  recvTrack.setMediaHandler(new ndc.RtcpReceivingSession());

  const rxPackets = [];
  recvTrack.onMessage((pkt) => {
    rxPackets.push({
      pt: pkt[1] & 0x7f,
      marker: (pkt[1] & 0x80) !== 0,
      len: pkt.length,
    });
  });

  clientPc.setLocalDescription('offer');
  await new Promise((r) => setTimeout(r, 60));
  const offer = clientPc.localDescription();
  assert.ok(offer && offer.sdp);

  let trackOpened = false;
  const session = await WebRtcScreenSession.create('test-webrtc-loopback', {
    offerSdp: offer.sdp,
    fps: 60,
    width: 1280,
    height: 720,
    portRangeBegin: 40000,
    portRangeEnd: 40100,
    onTrackOpen: () => { trackOpened = true; },
  });
  assert.match(session.answer.sdp, /a=rtpmap:102 H264\/90000/);

  clientPc.onLocalCandidate((cand, mid) => session.addRemoteCandidate(cand, mid));
  clientPc.setRemoteDescription(session.answer.sdp, 'answer');

  for (let i = 0; i < 40 && !session.isOpen(); i++) {
    await new Promise((r) => setTimeout(r, 25));
  }
  assert.ok(session.isOpen(), 'WebRTC track did not open over loopback');
  assert.ok(trackOpened);

  const sps = Buffer.from([0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xe0, 0x1f, 0xda, 0x01]);
  const pps = Buffer.from([0x00, 0x00, 0x00, 0x01, 0x68, 0xce, 0x3c, 0x80]);
  const bigIdr = Buffer.concat([Buffer.from([0x00, 0x00, 0x00, 0x01, 0x65]), Buffer.alloc(1400, 0xab)]);
  session.feedAnnexBChunk(Buffer.concat([sps, pps, bigIdr]));

  await new Promise((r) => setTimeout(r, 120));
  session.close();
  clientPc.close();

  assert.ok(rxPackets.length >= 3, `expected >= 3 RTP packets, got ${rxPackets.length}`);
  assert.ok(rxPackets.every((p) => p.pt === 102), 'all RTP packets must use negotiated PT 102');
  const markers = rxPackets.map((p) => p.marker);
  assert.equal(markers.slice(0, -1).every((m) => !m), true, 'only the last packet of a frame may have marker=true');
  assert.equal(markers[markers.length - 1], true, 'last packet of a frame must have marker=true');
});

test('pickPlayoutDelayExtId extracts playout-delay ID in m=video and answer preserves extmap', async () => {
  const offerWithExt = [
    'v=0',
    'o=- 1 2 IN IP4 127.0.0.1',
    's=-',
    't=0 0',
    'm=video 9 UDP/TLS/RTP/SAVPF 102',
    'c=IN IP4 0.0.0.0',
    'a=ice-ufrag:ufrag1234',
    'a=ice-pwd:password1234567890123456',
    'a=fingerprint:sha-256 00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF',
    'a=setup:actpass',
    'a=mid:0',
    'a=recvonly',
    'a=rtcp-mux',
    'a=extmap:6/sendrecv http://www.webrtc.org/experiments/rtp-hdrext/playout-delay',
    'a=rtpmap:102 H264/90000',
    'a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f',
    '',
  ].join('\r\n');

  assert.equal(pickPlayoutDelayExtId(offerWithExt), 6);
  assert.equal(pickPlayoutDelayExtId('v=0\r\nm=video 9 RTP/AVPF 96\r\n'), null);

  const session = await WebRtcScreenSession.create('test-playout-delay', {
    offerSdp: offerWithExt,
    fps: 60,
    width: 1280,
    height: 720,
    portRangeBegin: 40100,
    portRangeEnd: 40200,
  });
  const answerSdp = session.answer.sdp;
  session.close();

  assert.match(
    answerSdp,
    /^a=extmap:6(?:\S+)?\s+http:\/\/www\.webrtc\.org\/experiments\/rtp-hdrext\/playout-delay\b/im,
  );
});

test('H264AccessUnitParser splits consecutive frames without AUD via first_mb_in_slice=0', () => {
  const units = [];
  const parser = new H264AccessUnitParser((au) => units.push(au));

  const sps = Buffer.from([0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xe0, 0x1f]);
  const pps = Buffer.from([0x00, 0x00, 0x00, 0x01, 0x68, 0xce, 0x3c, 0x80]);
  const idr = Buffer.from([0x00, 0x00, 0x00, 0x01, 0x65, 0x88, 0x84, 0x21]);
  const p1 = Buffer.from([0x00, 0x00, 0x00, 0x01, 0x41, 0x9a, 0x22, 0x11]);
  const p2 = Buffer.from([0x00, 0x00, 0x00, 0x01, 0x41, 0x9e, 0x33, 0x44]);

  parser.push(Buffer.concat([sps, pps, idr, p1, p2]));
  assert.equal(units.length, 3);
  assert.deepEqual(units.map((u) => u.nalTypes), [[7, 8, 5], [1], [1]]);
  assert.deepEqual(units.map((u) => u.isKeyFrame), [true, false, false]);
});

test('resolveH264StreamCodec selects zerolatency libx264 without GPU frame queue', async () => {
  const codec = await resolveH264StreamCodec();
  assert.equal(codec, 'libx264');
});
