package androidx.media3.extractor.ts;

import static androidx.media3.extractor.ts.TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR;
import static com.google.common.base.Preconditions.checkState;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class PgsReader implements ElementaryStreamReader {

  private static final int SIGNATURE_WORD = 0x5047;
  private static final int SIGNATURE_BYTE_1 = SIGNATURE_WORD >> 8 & 0xff;
  private static final int SIGNATURE_BYTE_2 = SIGNATURE_WORD & 0xff;
  private static final int SIGNATURE_LENGTH = 2;

  private static final int SECTION_NULL = -1;
  private static final int SECTION_PTS_DTS = 0;
  private static final int SECTION_PTS_DTS_SIZE = 8;
  private static final int SECTION_TYPE_PALETTE = 0x14;
  private static final int SECTION_TYPE_BITMAP_PICTURE = 0x15;
  private static final int SECTION_TYPE_IDENTIFIER = 0x16;
  private static final int SECTION_TYPE_WINDOW_DEF = 0x17;
  private static final int SECTION_TYPE_END = 0x80;

  @Nullable
  private final String language;
  private @MonotonicNonNull TrackOutput output;

  private static final int STATE_EXPECT_NEXT = -1;
  private static final int STATE_SECTION_TYPE_READ = 0;
  private static final int STATE_SECTION_SIZE_FIRST_BYTE_READ = 1;
  private static final int STATE_SECTION_BYTES_COUNTDOWN = 2;

  private int stateOfReading;
  private int sectionType;
  private int sectionBytesToRead;
  private int firstByteOfSectionSize;
  private int sigBytesToCheck;
  private int sampleBytesWritten;
  private long sampleTimeUs;
  private boolean packageGoodToGo;

  public PgsReader(@Nullable String language) {
    stateOfReading = -1;
    sectionType = SECTION_NULL;
    sectionBytesToRead = -1;
    this.language = language;
    sampleBytesWritten = 0;
    sampleTimeUs = C.TIME_UNSET;
  }

  @Override
  public void seek() {
    packageGoodToGo = false;
    sampleTimeUs = C.TIME_UNSET;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_TEXT);
    output.format(
        new Format.Builder()
            .setId(idGenerator.getFormatId())
            .setSampleMimeType(MimeTypes.APPLICATION_PGS)
            .setLanguage(language)
            .setCueReplacementBehavior(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE)
            .build());
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    if ((flags & FLAG_DATA_ALIGNMENT_INDICATOR) == 0) {
      return;
    }
    packageGoodToGo = true;
    if (sampleTimeUs == C.TIME_UNSET) {
      sampleTimeUs = pesTimeUs;
    }
    sigBytesToCheck = 0;
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    if (packageGoodToGo) {
      checkState(sampleTimeUs != C.TIME_UNSET);
      if (stateOfReading == STATE_EXPECT_NEXT && sectionType == SECTION_TYPE_END) {
        output.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, sampleBytesWritten, 0, null);
        sampleBytesWritten = 0;
        sampleTimeUs = C.TIME_UNSET;
      }
      packageGoodToGo = false;
    }
  }

  @Override
  public void consume(ParsableByteArray data) {
    if (packageGoodToGo) {
      if (sigBytesToCheck == SIGNATURE_LENGTH && !checkNextByte(data, SIGNATURE_BYTE_1)) {
        return;
      }
      if (sigBytesToCheck == SIGNATURE_LENGTH - 1) {
        if (checkNextByte(data, SIGNATURE_BYTE_2)) {
          stateOfReading = STATE_SECTION_BYTES_COUNTDOWN;
          sectionType = SECTION_PTS_DTS;
          sectionBytesToRead = SECTION_PTS_DTS_SIZE;
        } else {
          return;
        }
      }
      goThrough(data);
      int bytesAvailable = data.bytesLeft();
      output.sampleData(data, bytesAvailable);
      sampleBytesWritten += bytesAvailable;
    }
  }

  private boolean checkNextByte(ParsableByteArray data, int expectedValue) {
    if (data.bytesLeft() == 0) {
      return false;
    }
    if (data.readUnsignedByte() != expectedValue) {
      packageGoodToGo = false;
    }
    sigBytesToCheck--;
    return packageGoodToGo;
  }

  private void goThrough(ParsableByteArray array) {
    byte[] buffer = array.getData();
    int position = array.getPosition();
    int limit = array.limit();
    while (limit - position > 0) {
      int b = buffer[position++] & 0xff;
      switch (stateOfReading) {
        case STATE_EXPECT_NEXT:
          if (b == SECTION_TYPE_IDENTIFIER || b == SECTION_TYPE_WINDOW_DEF || b == SECTION_TYPE_PALETTE || b == SECTION_TYPE_BITMAP_PICTURE || b == SECTION_TYPE_END) {
            sectionType = b & 0xff;
            stateOfReading = STATE_SECTION_TYPE_READ;
          }
          break;
        case STATE_SECTION_TYPE_READ:
          firstByteOfSectionSize = b & 0xff;
          stateOfReading = STATE_SECTION_SIZE_FIRST_BYTE_READ;
          break;
        case STATE_SECTION_SIZE_FIRST_BYTE_READ:
          sectionBytesToRead = (firstByteOfSectionSize & 0xff) << 8 | (b & 0xff);
          stateOfReading = sectionBytesToRead == 0 ? STATE_EXPECT_NEXT : STATE_SECTION_BYTES_COUNTDOWN;
          break;
        case STATE_SECTION_BYTES_COUNTDOWN:
          sectionBytesToRead--;
          int bytesToRead = Math.min(sectionBytesToRead, limit - position);
          position += bytesToRead;
          sectionBytesToRead -= bytesToRead;
          if (sectionBytesToRead == 0) {
            stateOfReading = STATE_EXPECT_NEXT;
          }
          break;
      }
    }
  }
}
