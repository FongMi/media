/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.extractor.ts;

import static java.lang.Math.min;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public final class Av3aReader implements ElementaryStreamReader {

  private static final int STATE_FINDING_SYNC = 0;
  private static final int STATE_READING_HEADER = 1;
  private static final int STATE_READING_FRAME = 2;

  @Nullable
  private final String language;
  private final @C.RoleFlags int roleFlags;

  private final byte[] headerAccumulator = new byte[Av3aUtil.MAX_HEADER_SIZE];
  private final ParsableBitArray headerBits = new ParsableBitArray(headerAccumulator);

  private boolean lastByteWasFF;
  private int headerBytesRead;
  private int frameBytesRead;
  private int frameSize;
  private long frameDurationUs;

  private int state = STATE_FINDING_SYNC;
  private boolean hasOutputFormat;
  private long timeUs = C.TIME_UNSET;

  private @MonotonicNonNull String formatId;
  private @MonotonicNonNull TrackOutput output;

  public Av3aReader(@Nullable String language, @C.RoleFlags int roleFlags) {
    this.language = language;
    this.roleFlags = roleFlags;
  }

  @Override
  public void seek() {
    state = STATE_FINDING_SYNC;
    headerBytesRead = 0;
    frameBytesRead = 0;
    lastByteWasFF = false;
    timeUs = C.TIME_UNSET;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    timeUs = pesTimeUs;
  }

  @Override
  public void consume(ParsableByteArray source) {
    while (source.bytesLeft() > 0) {
      switch (state) {
        case STATE_FINDING_SYNC:
          findSync(source);
          break;
        case STATE_READING_HEADER:
          readHeader(source);
          break;
        case STATE_READING_FRAME:
          readFramePayload(source);
          break;
        default:
          throw new IllegalStateException("Unexpected state: " + state);
      }
    }
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
  }

  private void findSync(ParsableByteArray source) {
    byte[] data = source.getData();
    int start = source.getPosition();
    int end = source.limit();
    for (int i = start; i < end; i++) {
      if (lastByteWasFF && (data[i] & 0xF0) == 0xF0) {
        headerAccumulator[0] = (byte) 0xFF;
        headerAccumulator[1] = data[i];
        headerBytesRead = 2;
        lastByteWasFF = false;
        source.setPosition(i + 1);
        state = STATE_READING_HEADER;
        return;
      }
      lastByteWasFF = (data[i] == (byte) 0xFF);
    }
    source.setPosition(end);
  }

  private void readHeader(ParsableByteArray source) {
    int toCopy = min(Av3aUtil.MAX_HEADER_SIZE - headerBytesRead, source.bytesLeft());
    source.readBytes(headerAccumulator, headerBytesRead, toCopy);
    headerBytesRead += toCopy;
    if (headerBytesRead < Av3aUtil.MAX_HEADER_SIZE) {
      return;
    }
    headerBits.reset(headerAccumulator, Av3aUtil.MAX_HEADER_SIZE);
    @Nullable Av3aUtil.FrameHeader header = Av3aUtil.parseFrameHeader(headerBits);
    if (header == null) {
      resetToFindSync();
      return;
    }
    frameSize = (int) Math.ceil((double) header.totalBitrate * header.samplesPerFrame / header.samplingRate / 8);
    if (frameSize < Av3aUtil.MAX_HEADER_SIZE) {
      resetToFindSync();
      return;
    }
    frameDurationUs = (C.MICROS_PER_SECOND * (long) header.samplesPerFrame) / header.samplingRate;
    if (!hasOutputFormat) {
      outputFormat(header);
    }
    output.sampleData(new ParsableByteArray(headerAccumulator, Av3aUtil.MAX_HEADER_SIZE), Av3aUtil.MAX_HEADER_SIZE);
    frameBytesRead = Av3aUtil.MAX_HEADER_SIZE;
    state = STATE_READING_FRAME;
  }

  private void readFramePayload(ParsableByteArray source) {
    int bytesToRead = min(source.bytesLeft(), frameSize - frameBytesRead);
    output.sampleData(source, bytesToRead);
    frameBytesRead += bytesToRead;
    if (frameBytesRead < frameSize) {
      return;
    }
    output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, frameSize, 0, null);
    timeUs += frameDurationUs;
    frameBytesRead = 0;
    state = STATE_FINDING_SYNC;
    lastByteWasFF = false;
  }

  private void outputFormat(Av3aUtil.FrameHeader header) {
    output.format(
        new Format.Builder()
            .setId(formatId)
            .setSampleMimeType(MimeTypes.AUDIO_AV3A)
            .setMaxInputSize(4096 * 64)
            .setChannelCount(header.channelCount)
            .setSampleRate(header.samplingRate)
            .setLanguage(language)
            .setRoleFlags(roleFlags)
            .setPeakBitrate(header.totalBitrate)
            .setAverageBitrate(header.totalBitrate)
            .build());
    hasOutputFormat = true;
  }

  private void resetToFindSync() {
    headerBytesRead = 0;
    lastByteWasFF = false;
    state = STATE_FINDING_SYNC;
  }
}
