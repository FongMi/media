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

import androidx.annotation.NonNull;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import java.util.List;

/**
 * Binary-search seek map built from the INDX chunk.
 */
final class IndexSeekMap implements SeekMap {

  private final long durationUs;
  private final List<SeekPoint> index;

  IndexSeekMap(long durationUs, List<SeekPoint> index) {
    this.durationUs = durationUs;
    this.index = index;
  }

  @Override
  public boolean isSeekable() {
    return !index.isEmpty();
  }

  @Override
  public long getDurationUs() {
    return durationUs;
  }

  @NonNull
  @Override
  public SeekPoints getSeekPoints(long timeUs) {
    if (index.isEmpty()) {
      return new SeekPoints(SeekPoint.START);
    }

    int lo = 0, hi = index.size() - 1;
    while (lo < hi) {
      int mid = (lo + hi + 1) / 2;
      if (index.get(mid).timeUs <= timeUs) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }
    SeekPoint before = index.get(lo);
    if (lo + 1 < index.size()) {
      return new SeekPoints(before, index.get(lo + 1));
    }
    return new SeekPoints(before);
  }
}
