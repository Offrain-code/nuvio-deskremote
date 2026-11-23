/**
 * Low-latency H.264 Annex-B parser (0ms overhead on complete frames):
 * - Groups NAL units by Access Unit (complete frame) while stripping AUD (type 9);
 * - Caches the latest SPS (7) and PPS (8) to re-inject before IDR keyframes and on WebRTC track open;
 * - Flushes immediately when a pipe read chunk completes a packet (< READ_CHUNK_SIZE) or a new picture starts.
 */

export interface H264AccessUnit {
  auBuffer: Buffer;
  isKeyFrame: boolean;
  nalTypes: number[];
}

const START3 = Buffer.from([0x00, 0x00, 0x01]);
const START4 = Buffer.from([0x00, 0x00, 0x00, 0x01]);

/** Default libuv pipe read chunk size on Windows (64 KB). */
export const PIPE_READ_CHUNK_SIZE = 65536;

export class H264AccessUnitParser {
  private readonly onAccessUnit: (au: H264AccessUnit) => void;
  private accum: Buffer = Buffer.alloc(0);
  private currentNals: Buffer[] = [];
  private currentTypes: number[] = [];
  private currentHasVcl = false;
  private currentIsKey = false;
  private cachedSps: Buffer | null = null;
  private cachedPps: Buffer | null = null;
  private flushTimer: NodeJS.Timeout | null = null;

  constructor(onAccessUnit: (au: H264AccessUnit) => void) {
    this.onAccessUnit = onAccessUnit;
  }

  /** Returns combined Annex-B SPS+PPS headers (if already seen) to warm up decoders after WebRTC connect. */
  getCachedHeaders(): Buffer | null {
    if (!this.cachedSps || !this.cachedPps) return null;
    return Buffer.concat([this.cachedSps, this.cachedPps]);
  }

  push(chunk: Buffer): void {
    if (chunk.length === 0) return;
    if (this.flushTimer) {
      clearTimeout(this.flushTimer);
      this.flushTimer = null;
    }

    this.accum = this.accum.length > 0 ? Buffer.concat([this.accum, chunk]) : chunk;

    const firstStart = findNextStartCode(this.accum, 0);
    if (!firstStart) {
      // Leading bytes without start code — discard while retaining up to 3 trailing bytes for boundary detection
      if (this.accum.length > 3) {
        this.accum = Buffer.from(this.accum.subarray(this.accum.length - 3));
      }
      return;
    }

    let pos = firstStart.index;
    while (true) {
      const sc = findNextStartCode(this.accum, pos);
      if (!sc) break;
      const next = findNextStartCode(this.accum, sc.dataOffset);
      if (!next) {
        // Final NAL begins at sc.index and runs through the end of this.accum
        if (chunk.length < PIPE_READ_CHUNK_SIZE) {
          // ffmpeg -flush_packets 1 delivered the entire frame in this WriteFile
          const finalNal = this.accum.subarray(sc.index);
          this.consumeNal(finalNal, sc.length);
          this.accum = Buffer.alloc(0);
          this.flushCurrentIfVcl();
          return;
        }
        // Full 64 KB chunk: large frame may continue on the next read
        this.accum = Buffer.from(this.accum.subarray(sc.index));
        this.scheduleSafetyFlush();
        return;
      }

      const completeNal = this.accum.subarray(sc.index, next.index);
      this.consumeNal(completeNal, sc.length);
      pos = next.index;
    }

    this.accum = pos < this.accum.length ? Buffer.from(this.accum.subarray(pos)) : Buffer.alloc(0);
    if (chunk.length < PIPE_READ_CHUNK_SIZE) {
      this.flushCurrentIfVcl();
    } else if (this.currentHasVcl) {
      this.scheduleSafetyFlush();
    }
  }

  flush(): void {
    if (this.flushTimer) {
      clearTimeout(this.flushTimer);
      this.flushTimer = null;
    }
    if (this.accum.length > 4) {
      const sc = findNextStartCode(this.accum, 0);
      if (sc && sc.dataOffset < this.accum.length) {
        this.consumeNal(this.accum.subarray(sc.index), sc.length);
      }
    }
    this.accum = Buffer.alloc(0);
    this.flushCurrentIfVcl();
  }

  reset(): void {
    if (this.flushTimer) {
      clearTimeout(this.flushTimer);
      this.flushTimer = null;
    }
    this.accum = Buffer.alloc(0);
    this.currentNals = [];
    this.currentTypes = [];
    this.currentHasVcl = false;
    this.currentIsKey = false;
    this.cachedSps = null;
    this.cachedPps = null;
  }

  private scheduleSafetyFlush(): void {
    if (this.flushTimer) return;
    this.flushTimer = setTimeout(() => {
      this.flushTimer = null;
      if (this.accum.length > 4) {
        const sc = findNextStartCode(this.accum, 0);
        if (sc && sc.dataOffset < this.accum.length) {
          this.consumeNal(this.accum.subarray(sc.index), sc.length);
        }
        this.accum = Buffer.alloc(0);
      }
      this.flushCurrentIfVcl();
    }, 3);
    this.flushTimer.unref?.();
  }

  private consumeNal(nalWithStartCode: Buffer, startCodeLen: number): void {
    if (nalWithStartCode.length <= startCodeLen) return;
    const nalType = nalWithStartCode[startCodeLen] & 0x1f;

    // AUD (type 9): new frame marker emitted by h264_amf — flush previous frame and drop AUD
    if (nalType === 9) {
      this.flushCurrentIfVcl();
      return;
    }

    const isNonVclHeader = nalType === 7 || nalType === 8 || nalType === 6;
    const isVcl = nalType === 1 || nalType === 5;
    const isNewPictureVcl = isVcl && isFirstSliceOfPicture(nalWithStartCode, startCodeLen);

    if (this.currentHasVcl && (isNonVclHeader || isNewPictureVcl)) {
      this.flushCurrentIfVcl();
    }

    // Normalize 3-byte start codes to 4-byte LongStartSequence
    const normalized =
      startCodeLen === 4
        ? Buffer.from(nalWithStartCode)
        : Buffer.concat([START4, nalWithStartCode.subarray(startCodeLen)]);

    if (nalType === 7) {
      this.cachedSps = normalized;
    } else if (nalType === 8) {
      this.cachedPps = normalized;
    }

    if (nalType === 5) {
      this.currentIsKey = true;
    }
    if (isVcl) {
      this.currentHasVcl = true;
    }

    this.currentNals.push(normalized);
    this.currentTypes.push(nalType);
  }

  private flushCurrentIfVcl(): void {
    if (!this.currentHasVcl || this.currentNals.length === 0) return;

    const nals = [...this.currentNals];
    const types = [...this.currentTypes];
    const isKeyFrame = this.currentIsKey;

    this.currentNals = [];
    this.currentTypes = [];
    this.currentHasVcl = false;
    this.currentIsKey = false;

    // Prepend cached SPS + PPS before every IDR keyframe if the encoder did not emit them
    if (isKeyFrame && this.cachedSps && this.cachedPps) {
      const hasSps = types.includes(7);
      const hasPps = types.includes(8);
      if (!hasSps && !hasPps) {
        nals.unshift(this.cachedSps, this.cachedPps);
        types.unshift(7, 8);
      } else if (!hasPps) {
        nals.unshift(this.cachedPps);
        types.unshift(8);
      } else if (!hasSps) {
        nals.unshift(this.cachedSps);
        types.unshift(7);
      }
    }

    this.onAccessUnit({
      auBuffer: nals.length === 1 ? nals[0] : Buffer.concat(nals),
      isKeyFrame,
      nalTypes: types,
    });
  }
}

/** Checks first_mb_in_slice == 0 in the slice header (ue(v): value 0 has MSB=1 after the 1-byte NAL header). */
function isFirstSliceOfPicture(nal: Buffer, startCodeLen: number): boolean {
  const sliceHdrByte = nal[startCodeLen + 1];
  if (sliceHdrByte === undefined) return true;
  return (sliceHdrByte & 0x80) !== 0;
}

function findNextStartCode(buf: Buffer, from: number): { index: number; length: number; dataOffset: number } | null {
  const idx = buf.indexOf(START3, from);
  if (idx < 0) return null;
  if (idx > from && buf[idx - 1] === 0x00) {
    return { index: idx - 1, length: 4, dataOffset: idx + 3 };
  }
  return { index: idx, length: 3, dataOffset: idx + 3 };
}
