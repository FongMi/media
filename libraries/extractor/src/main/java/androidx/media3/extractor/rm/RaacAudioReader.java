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
 * Unpacks raac/racp VBR packets and emits each contained AAC frame as a separate sample.
 *
 * <p>Packet layout (DEINT_ID_VBRS / DEINT_ID_VBRF):
 * <pre>
 *   flags(2)        — bits 7-4: frame count N
 *   length[0..N-1]  — 2 bytes each
 *   frame[0..N-1]   — raw AAC frames
 * </pre>
 */
final class RaacAudioReader implements TrackReader {

  private final TrackOutput trackOutput;
  private final long frameDurationUs;

  RaacAudioReader(TrackOutput trackOutput, int sampleRate) {
    this.trackOutput = trackOutput;
    this.frameDurationUs = sampleRate > 0 ? Util.sampleCountToDurationUs(RmUtil.SAMPLES_PER_CODEC_FRAME, sampleRate) : 0L;
  }

  @Override
  public void seek() {
  }

  @Override
  public void consume(ParsableByteArray data, int dataSize, long timestampUs, boolean isKeyFrame) {
    if (dataSize < 2) {
      return;
    }

    byte[] buf = data.getData();
    int offset = data.getPosition();
    final int end = offset + dataSize;

    int flags = ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
    int frameCount = (flags & 0xF0) >> 4;
    offset += 2;

    if (frameCount <= 0 || offset + frameCount * 2 > end) {
      return;
    }

    int[] frameLengths = new int[frameCount];
    for (int i = 0; i < frameCount; i++) {
      frameLengths[i] = ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
      offset += 2;
    }

    for (int i = 0; i < frameCount; i++) {
      int frameLen = frameLengths[i];
      if (frameLen <= 0 || offset + frameLen > end) {
        break;
      }

      ParsableByteArray frameData = new ParsableByteArray(buf, offset + frameLen);
      frameData.setPosition(offset);
      trackOutput.sampleData(frameData, frameLen);
      trackOutput.sampleMetadata(
          timestampUs + i * frameDurationUs,
          i == 0 && isKeyFrame ? C.BUFFER_FLAG_KEY_FRAME : 0,
          frameLen,
          0,
          null);

      offset += frameLen;
    }
  }
}
