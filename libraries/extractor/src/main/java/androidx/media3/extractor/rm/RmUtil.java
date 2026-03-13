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

final class RmUtil {

  /**
   * Number of PCM samples per codec frame for RealAudio codecs that use 1024-sample windows
   * (Cook, ATRAC, raac/racp AAC).
   */
  static final int SAMPLES_PER_CODEC_FRAME = 1024;

  /**
   * Writes {@code size} bytes from {@code data} to {@code output} and immediately appends the
   * corresponding sample metadata.
   *
   * <p>This is the canonical one-shot emission path used by all {@link TrackReader} implementations
   * that emit one complete sample per call.
   */
  static void emitSample(TrackOutput output, ParsableByteArray data, int size, long timestampUs, boolean isKeyFrame) {
    output.sampleData(data, size);
    output.sampleMetadata(timestampUs, isKeyFrame ? C.BUFFER_FLAG_KEY_FRAME : 0, size, 0, null);
  }
}
