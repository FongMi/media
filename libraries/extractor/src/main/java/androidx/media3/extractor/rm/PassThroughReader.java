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

import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.TrackOutput;

/**
 * Forwards raw packets unchanged to the track output.
 */
final class PassThroughReader implements TrackReader {

  private final TrackOutput trackOutput;

  PassThroughReader(TrackOutput trackOutput) {
    this.trackOutput = trackOutput;
  }

  @Override
  public void consume(ParsableByteArray data, int dataSize, long timestampUs, boolean isKeyFrame) {
    RmUtil.emitSample(trackOutput, data, dataSize, timestampUs, isKeyFrame);
  }

  @Override
  public void seek() {
  }
}
