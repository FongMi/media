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
import androidx.media3.common.util.Util;
import androidx.media3.extractor.TrackOutput;

/**
 * Cook/ATRAC audio uses GENR interleaving: sub_packet_h consecutive DATA packets
 * form one decode group that is de-interleaved before emission.
 * FFmpeg formula: dst = sps * (h*x + ((h+1)/2)*(y&1) + (y>>1))
 */
final class CookAudioReader implements TrackReader {

  private final TrackOutput trackOutput;
  private final int frameSize;
  private final int subPacketH;
  private final int subPacketSize;
  private final int sampleRate;

  private int subPacketCnt = 0;
  private byte[] accumBuf;
  private long groupTimestampUs = C.TIME_UNSET;
  private boolean groupKeyFrame = false;

  CookAudioReader(TrackOutput trackOutput, int frameSize, int subPacketH, int subPacketSize, int sampleRate) {
    this.trackOutput = trackOutput;
    this.frameSize = frameSize;
    this.subPacketH = subPacketH;
    this.subPacketSize = subPacketSize;
    this.sampleRate = sampleRate;
    if (frameSize > 0 && subPacketH > 0) {
      accumBuf = new byte[frameSize * subPacketH];
    }
  }

  @Override
  public void seek() {
    subPacketCnt = 0;
    groupTimestampUs = C.TIME_UNSET;
    groupKeyFrame = false;
  }

  @Override
  public void consume(ParsableByteArray data, int dataSize, long timestampUs, boolean isKeyFrame) {
    if (subPacketSize <= 0 || frameSize <= 0) {
      RmUtil.emitSample(trackOutput, data, dataSize, timestampUs, isKeyFrame);
      return;
    }
    if (subPacketH <= 1) {
      emitRawFrames(data.getData(), data.getPosition(), dataSize, timestampUs, isKeyFrame);
      return;
    }
    if (accumBuf == null) {
      return;
    }

    // Reset mid-group only on keyframe; some encoders mark every packet as key, so
    // unconditional reset would prevent the group from ever completing.
    if (isKeyFrame && subPacketCnt != 0) {
      subPacketCnt = 0;
    }

    if (subPacketCnt == 0) {
      groupTimestampUs = timestampUs;
      groupKeyFrame = isKeyFrame;
    }

    deinterleaveIntoAccumBuf(data.getData(), data.getPosition(), dataSize, subPacketCnt);
    subPacketCnt++;

    if (subPacketCnt >= subPacketH) {
      emitAccumulatedFrames();
      subPacketCnt = 0;
    }
  }

  /**
   * Used when subPacketH <= 1: no GENR reorder; emit the whole packet as-is.
   */
  private void emitRawFrames(byte[] raw, int srcBase, int dataSize, long timestampUs, boolean isKeyFrame) {
    ParsableByteArray frameData = new ParsableByteArray(raw, srcBase + dataSize);
    frameData.setPosition(srcBase);
    RmUtil.emitSample(trackOutput, frameData, dataSize, timestampUs, isKeyFrame);
  }

  private void deinterleaveIntoAccumBuf(byte[] raw, int srcBase, int dataSize, int y) {
    int framesPerRow = frameSize / subPacketSize;
    for (int x = 0; x < framesPerRow; x++) {
      int srcOffset = srcBase + x * subPacketSize;
      int dstOffset = subPacketSize * (subPacketH * x + ((subPacketH + 1) / 2) * (y & 1) + (y >> 1));
      if (x * subPacketSize + subPacketSize <= dataSize && dstOffset + subPacketSize <= accumBuf.length) {
        System.arraycopy(raw, srcOffset, accumBuf, dstOffset, subPacketSize);
      }
    }
  }

  private void emitAccumulatedFrames() {
    int totalFrames = subPacketH * (frameSize / subPacketSize);
    long frameDurationUs = sampleRate > 0 ? Util.sampleCountToDurationUs(RmUtil.SAMPLES_PER_CODEC_FRAME, sampleRate) : 0L;
    for (int i = 0; i < totalFrames; i++) {
      int offset = i * subPacketSize;
      long frameTs = (groupTimestampUs != C.TIME_UNSET) ? groupTimestampUs + (long) i * frameDurationUs : 0L;
      ParsableByteArray frameData = new ParsableByteArray(accumBuf, offset + subPacketSize);
      frameData.setPosition(offset);
      trackOutput.sampleData(frameData, subPacketSize);
      trackOutput.sampleMetadata(frameTs, (i == 0 && groupKeyFrame) ? C.BUFFER_FLAG_KEY_FRAME : 0, subPacketSize, 0, null);
    }
    groupTimestampUs = C.TIME_UNSET;
    groupKeyFrame = false;
  }
}
