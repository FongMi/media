/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.rm;

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.TrackOutput;
import java.util.Arrays;

/**
 * Reassembles fragmented RealVideo packets into complete frames with FFmpeg-compatible
 * slice headers. Packet type is encoded in bits [7:6] of the first header byte:
 * 0 = middle fragment, 1 = complete frame, 2 = last fragment, 3 = complete frame in packet
 * (type 3: len2 = frame size, pos = timestamp ms; same output path as type 1)
 * <p>
 * Output format: (sliceCount-1)(1) + slice_table(8 * sliceCount) + frame_payload
 * Each slice table entry: valid_flag(4,LE) + byte_offset(4,LE)
 */
final class VideoReader implements TrackReader {

  private final TrackOutput trackOutput;

  private byte[] frameBuf = null;
  private int frameLen = 0;
  private int sliceCount = 0;
  private int[] sliceOffsets = null;
  private long frameTimestampUs;
  private boolean frameKeyFrame;
  private int lastPicNum = -1;

  VideoReader(TrackOutput trackOutput) {
    this.trackOutput = trackOutput;
  }

  @Override
  public void seek() {
    frameBuf = null;
    frameLen = 0;
    sliceCount = 0;
    lastPicNum = -1;
  }

  @Override
  public void consume(ParsableByteArray data, int dataSize, long timestampUs, boolean isKeyFrame) {
    if (dataSize < 2) {
      return;
    }
    byte[] raw = data.getData();
    int offset = 0;

    int hdr = raw[offset++] & 0xFF;
    int type = (hdr >> 6) & 3;
    int sliceHint = hdr & 0x3F;
    int seq = (type != 3) ? (raw[offset++] & 0xFF) : 0;

    int len2 = 0, pos = 0, picNum = 0;
    if (type != 1) {
      int[] r;
      r = readNum(raw, offset, dataSize - offset);
      len2 = r[0];
      offset = r[1];
      r = readNum(raw, offset, dataSize - offset);
      pos = r[0];
      offset = r[1];
      if (offset >= dataSize) {
        return;
      }
      picNum = raw[offset++] & 0xFF;
    }

    int remaining = dataSize - offset;
    int payloadLen = remaining;
    if (type == 2) {
      payloadLen = Math.min(remaining, pos);
    } else if (type == 3) {
      payloadLen = Math.min(remaining, len2);
    }

    if (type == 1) {
      emitSingleSliceFrame(raw, offset, payloadLen, timestampUs, isKeyFrame);
    } else if (type == 3) {
      emitSingleSliceFrame(raw, offset, payloadLen, pos * 1_000L, isKeyFrame);
    } else {
      handleFragmentedPacket(raw, offset, payloadLen, len2, type, seq, picNum, sliceHint, timestampUs, isKeyFrame);
    }
  }

  private void handleFragmentedPacket(byte[] raw, int offset, int payloadLen, int len2, int type, int seq, int picNum, int sliceHint, long timestampUs, boolean isKeyFrame) {
    boolean isFirstSlice = (seq & 0x7F) == 1 || lastPicNum != picNum;
    if (isFirstSlice) {
      emitAssembledFrame();
      int expectedSlices = Math.max((sliceHint << 1) + 1, 1);
      int bufSize = Math.max(len2, payloadLen * expectedSlices);
      if (frameBuf == null || frameBuf.length < bufSize) {
        frameBuf = new byte[bufSize];
      }
      frameLen = 0;
      sliceCount = 0;
      sliceOffsets = (sliceOffsets == null || sliceOffsets.length < expectedSlices + 16) ? new int[expectedSlices + 16] : sliceOffsets;
      frameTimestampUs = timestampUs;
      frameKeyFrame = isKeyFrame;
    }

    if (frameBuf == null) {
      return;
    }

    if (sliceCount >= sliceOffsets.length) {
      sliceOffsets = Arrays.copyOf(sliceOffsets, sliceOffsets.length * 2);
    }
    sliceOffsets[sliceCount] = frameLen;
    sliceCount++;

    if (frameLen + payloadLen > frameBuf.length) {
      byte[] grown = new byte[(frameLen + payloadLen) * 2];
      System.arraycopy(frameBuf, 0, grown, 0, frameLen);
      frameBuf = grown;
    }
    System.arraycopy(raw, offset, frameBuf, frameLen, payloadLen);
    frameLen += payloadLen;
    lastPicNum = picNum;

    if (type == 2) {
      emitAssembledFrame();
    }
  }

  private void emitSingleSliceFrame(byte[] raw, int offset, int payloadLen, long timestampUs, boolean isKeyFrame) {
    byte[] out = new byte[1 + 8 + payloadLen];
    out[0] = 0; // sliceCount - 1
    out[1] = 1; // valid flag; offset = 0 (bytes 2-8 remain zero)
    System.arraycopy(raw, offset, out, 9, payloadLen);
    ParsableByteArray outputData = new ParsableByteArray(out);
    trackOutput.sampleData(outputData, out.length);
    trackOutput.sampleMetadata(timestampUs, isKeyFrame ? C.BUFFER_FLAG_KEY_FRAME : 0, out.length, 0, null);
  }

  private void emitAssembledFrame() {
    if (frameBuf == null || sliceCount == 0) {
      return;
    }

    int headerSize = 1 + 8 * sliceCount;
    byte[] out = new byte[headerSize + frameLen];
    out[0] = (byte) (sliceCount - 1);
    for (int i = 0; i < sliceCount; i++) {
      int base = 1 + i * 8;
      int sliceOff = sliceOffsets[i];
      out[base] = 1; // valid flag
      out[base + 4] = (byte) (sliceOff & 0xFF);
      out[base + 5] = (byte) ((sliceOff >> 8) & 0xFF);
      out[base + 6] = (byte) ((sliceOff >> 16) & 0xFF);
      out[base + 7] = (byte) ((sliceOff >> 24) & 0xFF);
    }
    System.arraycopy(frameBuf, 0, out, headerSize, frameLen);

    ParsableByteArray outputData = new ParsableByteArray(out);
    trackOutput.sampleData(outputData, out.length);
    trackOutput.sampleMetadata(frameTimestampUs, frameKeyFrame ? C.BUFFER_FLAG_KEY_FRAME : 0, out.length, 0, null);

    sliceCount = 0;
    frameLen = 0;
  }

  /**
   * FFmpeg get_num(): reads 2 bytes masked to 15 bits.
   * If >= 0x4000: 15-bit value. Otherwise: 30-bit value (shift 16 | next 2 bytes).
   * Returns int[2] = { value, new_offset }.
   */
  static int[] readNum(byte[] data, int offset, int remaining) {
    if (remaining < 2) {
      return new int[]{0, offset};
    }
    int n = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    offset += 2;
    n &= 0x7FFF;
    if (n >= 0x4000) {
      return new int[]{n - 0x4000, offset};
    }
    if (remaining - 2 < 2) {
      return new int[]{n, offset};
    }
    int n1 = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    return new int[]{(n << 16) | n1, offset + 2};
  }
}
