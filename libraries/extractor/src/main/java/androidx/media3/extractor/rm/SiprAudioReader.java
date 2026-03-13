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

/**
 * SIPR audio de-interleaver (DEINT_ID_SIPR).
 * <p>
 * FFmpeg ff_rm_parse_packet, DEINT_ID_SIPR:
 * accumBuf[y * w .. y*w+w-1] ← packet_y   (linear accumulation)
 * where w = audio_framesize, accumBuf size = w * sub_packet_h.
 * After h packets, ff_rm_reorder_sipr_data swaps nibble pairs per SIPR_SWAPS table
 * (from FFmpeg rmsipr.c), then the full buffer is emitted as one sample.
 * The decoder slices it into sub-frames using block_align = ff_sipr_subpk_size[flavor].
 */
final class SiprAudioReader implements TrackReader {

  /**
   * Nibble-swap reorder table from FFmpeg rmsipr.c.
   */
  private static final int[][] SIPR_SWAPS = {
      {0, 63}, {1, 22}, {2, 44}, {3, 90}, {5, 81}, {7, 31}, {8, 86}, {9, 58},
      {10, 36}, {12, 68}, {13, 39}, {14, 73}, {15, 53}, {16, 69}, {17, 57}, {19, 88},
      {20, 34}, {21, 71}, {24, 46}, {25, 94}, {26, 54}, {28, 75}, {29, 50}, {32, 70},
      {33, 92}, {35, 74}, {38, 85}, {40, 56}, {42, 87}, {43, 65}, {45, 59}, {48, 79},
      {49, 93}, {51, 89}, {55, 95}, {61, 76}, {67, 83}, {77, 80}
  };

  private final TrackOutput trackOutput;
  private final int subPacketH;
  private final int audioFrameSize;

  private final byte[] accumBuf;
  private int subPacketCnt = 0;
  private long groupTimestampUs = C.TIME_UNSET;
  private boolean groupKeyFrame = false;

  SiprAudioReader(TrackOutput trackOutput, int subPacketH, int audioFrameSize) {
    this.trackOutput = trackOutput;
    this.subPacketH = subPacketH;
    this.audioFrameSize = audioFrameSize;
    this.accumBuf = new byte[audioFrameSize * subPacketH];
  }

  @Override
  public void seek() {
    subPacketCnt = 0;
    groupTimestampUs = C.TIME_UNSET;
    groupKeyFrame = false;
  }

  @Override
  public void consume(ParsableByteArray data, int dataSize, long timestampUs, boolean isKeyFrame) {
    if (audioFrameSize <= 0 || subPacketH <= 0) {
      RmUtil.emitSample(trackOutput, data, dataSize, timestampUs, isKeyFrame);
      return;
    }

    if (subPacketCnt == 0) {
      groupTimestampUs = timestampUs;
      groupKeyFrame = isKeyFrame;
    }

    int dstOffset = subPacketCnt * audioFrameSize;
    int copyLen = Math.min(dataSize, accumBuf.length - dstOffset);
    if (copyLen > 0) {
      System.arraycopy(data.getData(), data.getPosition(), accumBuf, dstOffset, copyLen);
    }

    subPacketCnt++;
    if (subPacketCnt >= subPacketH) {
      reorderSiprData();
      emitAccumulatedData();
      subPacketCnt = 0;
    }
  }

  /**
   * ff_rm_reorder_sipr_data: bs = h*w*2/96 nibbles per swap-unit.
   */
  private void reorderSiprData() {
    int bs = subPacketH * audioFrameSize * 2 / 96;
    if (bs <= 0) {
      return;
    }
    for (int[] swap : SIPR_SWAPS) {
      int iBase = swap[0] * bs;
      int oBase = swap[1] * bs;
      for (int k = 0; k < bs; k++) {
        swapNibbles(iBase + k, oBase + k);
      }
    }
  }

  private void swapNibbles(int p, int q) {
    int pByte = p >> 1;
    int qByte = q >> 1;
    if (pByte >= accumBuf.length || qByte >= accumBuf.length) {
      return;
    }
    // nibble index n → (buf[n>>1] >> (4*(n&1))) & 0xF; n even = low nibble, n odd = high nibble
    int pShift = 4 * (p & 1);
    int qShift = 4 * (q & 1);
    int pNib = (accumBuf[pByte] >> pShift) & 0xF;
    int qNib = (accumBuf[qByte] >> qShift) & 0xF;
    accumBuf[pByte] = (byte) ((accumBuf[pByte] & ~(0xF << pShift)) | (qNib << pShift));
    accumBuf[qByte] = (byte) ((accumBuf[qByte] & ~(0xF << qShift)) | (pNib << qShift));
  }

  private void emitAccumulatedData() {
    int totalSize = subPacketH * audioFrameSize;
    ParsableByteArray frameData = new ParsableByteArray(accumBuf, totalSize);
    frameData.setPosition(0);
    long ts = (groupTimestampUs != C.TIME_UNSET) ? groupTimestampUs : 0L;
    RmUtil.emitSample(trackOutput, frameData, totalSize, ts, groupKeyFrame);
    groupTimestampUs = C.TIME_UNSET;
    groupKeyFrame = false;
  }
}
