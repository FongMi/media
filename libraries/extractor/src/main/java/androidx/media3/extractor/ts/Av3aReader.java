package androidx.media3.extractor.ts;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import java.util.List;

public class Av3aReader implements ElementaryStreamReader {

  @Nullable
  private final String language;
  private final @C.RoleFlags int roleFlags;

  private int state = STATE_FINDING_HEADER;
  private boolean lastByteWasFF = false;
  private int frameBytesRead = 0;
  private boolean hasOutputFormat = false;

  private final ParsableByteArray headerScratch = new ParsableByteArray(HEADER_SIZE);
  private final Av3aAatfFrame header = new Av3aAatfFrame();

  private long timeUs = C.TIME_UNSET;
  private long frameDurationUs = 0L;
  private int frameSize = 0;

  private @MonotonicNonNull String formatId = null;
  private @MonotonicNonNull TrackOutput output = null;

  public Av3aReader(@Nullable String language, @C.RoleFlags int roleFlags) {
    this.language = language;
    this.roleFlags = roleFlags;
  }

  @Override
  public void seek() {
    state = STATE_FINDING_HEADER;
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
        case STATE_FINDING_HEADER:
          findHeader(source);
          break;
        case STATE_READING_HEADER:
          readHeaderRemainder(source);
          break;
        case STATE_READING_FRAME:
          readFrameRemainder(source);
          break;
      }
    }
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
  }

  private void findHeader(ParsableByteArray source) {
    byte[] data = source.getData();
    int startOffset = source.getPosition();
    int endOffset = source.limit();
    for (int i = startOffset; i < endOffset; i++) {
      boolean byteIsFF = data[i] == (byte) 0xFF;
      boolean found = lastByteWasFF && ((data[i] & 0xF0) == 0xF0);
      lastByteWasFF = byteIsFF;
      if (found) {
        source.setPosition(i + 1);
        lastByteWasFF = false;
        headerScratch.getData()[0] = (byte) 0xFF;
        headerScratch.getData()[1] = data[i];
        frameBytesRead = 2;
        state = STATE_READING_HEADER;
        return;
      }
    }
    source.setPosition(endOffset);
  }

  private void readHeaderRemainder(ParsableByteArray source) {
    int startPosition = source.getPosition() - 2;
    ByteArrayReader reader = new ByteArrayReader(source.getData(), startPosition);
    boolean success = header.fromData(reader);
    if (!success) {
      frameBytesRead = 0;
      state = STATE_READING_HEADER;
      return;
    }
    frameBytesRead = HEADER_SIZE;
    frameSize = (int) Math.ceil(header.totalBitrate / (float) header.samplingRate * header.frameLength / 8);
    if (!hasOutputFormat) {
      frameDurationUs = (C.MICROS_PER_SECOND * frameSize) / header.samplingRate;
      output.format(new Format.Builder()
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
    ParsableByteArray headerData = new ParsableByteArray(HEADER_SIZE);
    source.setPosition(startPosition);
    source.readBytes(headerData.getData(), 0, HEADER_SIZE);
    output.sampleData(headerData, HEADER_SIZE);
    state = STATE_READING_FRAME;
  }

  private void readFrameRemainder(ParsableByteArray source) {
    int bytesToRead = Math.min(source.bytesLeft(), frameSize - frameBytesRead);
    output.sampleData(source, bytesToRead);
    frameBytesRead += bytesToRead;
    if (frameBytesRead < frameSize) {
      return;
    }
    output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, frameSize, 0, null);
    timeUs += frameDurationUs;
    state = STATE_FINDING_HEADER;
    frameBytesRead = 0;
    lastByteWasFF = false;
  }

  private static final int STATE_FINDING_HEADER = 0;
  private static final int STATE_READING_HEADER = 1;
  private static final int STATE_READING_FRAME = 2;
  private static final int HEADER_SIZE = 9;
}

class ByteArrayReader {

  private final byte[] bytes;
  private int bitOffset;

  public ByteArrayReader(byte[] bytes, int startPosition) {
    if (startPosition < 0 || startPosition >= bytes.length) {
      throw new IllegalArgumentException("The start position is out of data range.");
    }
    this.bytes = bytes;
    this.bitOffset = startPosition * 8;
  }

  private boolean canReadBits(int bitCount) {
    return bitCount >= 0 && bitOffset + bitCount <= bytes.length * 8;
  }

  public int readBits(int bitCount) {
    if (!canReadBits(bitCount)) {
      throw new IllegalArgumentException("Reading exceeds data range.");
    }
    int byteIndex = bitOffset / 8;
    int bitInByte = bitOffset % 8;
    int result = 0;
    for (int i = 0; i < bitCount; i++) {
      if (bitInByte == 8) {
        byteIndex++;
        bitInByte = 0;
      }
      int bit = (bytes[byteIndex] >> (7 - bitInByte)) & 1;
      result = (result << 1) | bit;
      bitInByte++;
    }
    bitOffset += bitCount;
    return result;
  }
}

class Av3aAatfFrame {

  int audioCodecId = -1;
  int ancDataIndex = -1;
  int nnType = -1;
  int codingProfile = -1;
  int samplingFrequencyIndex = -1;
  int channelNumberIndex = -1;
  int soundBedType = -1;
  int objectChannelNumber = -1;
  int bitrateIndexPerChannel = -1;
  int bitrateIndex = -1;
  int hoaOrder = -1;
  int resolution = -1;
  int samplingRate = -1;
  int totalBitrate = -1;
  int channelCount = -1;
  int frameLength = -1;

  public boolean fromData(ByteArrayReader data) {
    data.readBits(12);
    audioCodecId = data.readBits(4);
    if (audioCodecId != 2) {
      return false;
    }
    ancDataIndex = data.readBits(1);
    if (ancDataIndex == 1) {
      return false;
    }
    parseHeader(data);
    return postHeader();
  }

  private void parseHeader(ByteArrayReader data) {
    nnType = data.readBits(3);
    codingProfile = data.readBits(3);
    samplingFrequencyIndex = data.readBits(4);
    data.readBits(8);
    if (codingProfile == 0) {
      channelNumberIndex = data.readBits(7);
    } else if (codingProfile == 1) {
      soundBedType = data.readBits(2);
      if (soundBedType == 0) {
        objectChannelNumber = data.readBits(7) + 1;
        bitrateIndexPerChannel = data.readBits(4);
      } else if (soundBedType == 1) {
        channelNumberIndex = data.readBits(7);
        bitrateIndex = data.readBits(4);
        objectChannelNumber = data.readBits(7) + 1;
        bitrateIndexPerChannel = data.readBits(4);
      }
    } else if (codingProfile == 2) {
      hoaOrder = data.readBits(4) + 1;
    }
    resolution = data.readBits(2);
    if (codingProfile != 1) {
      bitrateIndex = data.readBits(4);
    }
    data.readBits(8);
  }

  private boolean postHeader() {
    samplingRate = SAMPLING_FREQUENCY_TABLE.get(samplingFrequencyIndex);
    frameLength = 1024;
    if (codingProfile == 0) {
      if (channelNumberIndex <= ChannelNumConfig.CHANNEL_CONFIG_MC_7_1_4.idx) {
        channelCount = ChannelNumConfig.fromIdx(channelNumberIndex).channelCount;
      } else {
        return false;
      }
      totalBitrate = ChannelNumConfig.fromIdx(channelNumberIndex).bitrateTable.get(bitrateIndex);
    } else if (codingProfile == 1) {
      if (soundBedType == 0) {
        channelNumberIndex = -1;
        channelCount = objectChannelNumber;
        totalBitrate = objectChannelNumber * ChannelNumConfig.CHANNEL_CONFIG_MONO.bitrateTable.get(bitrateIndexPerChannel);
      } else if (soundBedType == 1) {
        channelCount = ChannelNumConfig.fromIdx(channelNumberIndex).channelCount + objectChannelNumber;
        totalBitrate = ChannelNumConfig.fromIdx(channelNumberIndex).bitrateTable.get(bitrateIndex) * objectChannelNumber * ChannelNumConfig.CHANNEL_CONFIG_MONO.bitrateTable.get(bitrateIndexPerChannel);
      }
    } else if (codingProfile == 2) {
      if (hoaOrder == 1) {
        channelNumberIndex = ChannelNumConfig.CHANNEL_CONFIG_HOA_ORDER1.idx;
      } else if (hoaOrder == 2) {
        channelNumberIndex = ChannelNumConfig.CHANNEL_CONFIG_HOA_ORDER2.idx;
      } else if (hoaOrder == 3) {
        channelNumberIndex = ChannelNumConfig.CHANNEL_CONFIG_HOA_ORDER3.idx;
      } else {
        channelNumberIndex = -1;
      }
      channelCount = (hoaOrder + 1) * (hoaOrder + 1);
      totalBitrate = ChannelNumConfig.fromIdx(channelNumberIndex).bitrateTable.get(bitrateIndex);
    }
    return true;
  }

  private static final List<Integer> SAMPLING_FREQUENCY_TABLE = List.of(
      192000, 96000, 48000, 44100, 32000, 24000, 22050, 16000, 8000
  );

  enum ChannelNumConfig {

    CHANNEL_CONFIG_MONO(0, 1, List.of(16000, 32000, 44000, 56000, 64000, 72000, 80000, 96000, 128000, 144000, 164000, 192000)),
    CHANNEL_CONFIG_STEREO(1, 2, List.of(24000, 32000, 48000, 64000, 80000, 96000, 128000, 144000, 192000, 256000, 320000)),
    CHANNEL_CONFIG_MC_5_1(2, 6, List.of(192000, 256000, 320000, 384000, 448000, 512000, 640000, 720000, 144000, 96000, 128000, 160000)),
    CHANNEL_CONFIG_MC_7_1(3, 8, List.of(192000, 480000, 256000, 384000, 576000, 640000, 128000, 160000)),
    CHANNEL_CONFIG_MC_10_2(4, 12, List.of()),
    CHANNEL_CONFIG_MC_22_2(5, 24, List.of()),
    CHANNEL_CONFIG_MC_4_0(6, 4, List.of(48000, 96000, 128000, 192000, 256000)),
    CHANNEL_CONFIG_MC_5_1_2(7, 8, List.of(152000, 320000, 480000, 576000)),
    CHANNEL_CONFIG_MC_5_1_4(8, 10, List.of(176000, 384000, 576000, 704000, 256000, 448000)),
    CHANNEL_CONFIG_MC_7_1_2(9, 10, List.of(216000, 480000, 576000, 384000, 768000)),
    CHANNEL_CONFIG_MC_7_1_4(10, 12, List.of(240000, 608000, 384000, 512000, 832000)),
    CHANNEL_CONFIG_HOA_ORDER1(11, 0, List.of(48000, 96000, 128000, 192000, 256000)),
    CHANNEL_CONFIG_HOA_ORDER2(12, 0, List.of(192000, 256000, 320000, 384000, 480000, 512000, 640000)),
    CHANNEL_CONFIG_HOA_ORDER3(13, 0, List.of(256000, 320000, 384000, 512000, 640000, 896000));

    private final int idx;
    private final int channelCount;
    private final List<Integer> bitrateTable;

    ChannelNumConfig(int idx, int channelCount, List<Integer> bitrateTable) {
      this.idx = idx;
      this.channelCount = channelCount;
      this.bitrateTable = bitrateTable;
    }

    public static ChannelNumConfig fromIdx(int idx) {
      for (ChannelNumConfig config : values()) {
        if (config.idx == idx) {
          return config;
        }
      }
      return CHANNEL_CONFIG_MONO;
    }
  }
}