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

import android.util.Log;
import android.util.SparseArray;
import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackOutput;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RmExtractor implements Extractor {

  private static final String TAG = RmExtractor.class.getSimpleName();

  private static final int[] SIPR_SUBPK_SIZE = {29, 19, 37, 20};

  private static final int CHUNK_PROP = 0x50524f50; // "PROP"
  private static final int CHUNK_MDPR = 0x4d445052; // "MDPR"
  private static final int CHUNK_DATA = 0x44415441; // "DATA"
  private static final int CHUNK_INDX = 0x494e4458; // "INDX"

  private static final int RA_SIG = 0x2e7261fd; // ".ra\xfd"

  private static final int STATE_READ_FILE_HEADER = 0;
  private static final int STATE_READ_CHUNK = 1;
  private static final int STATE_READ_DATA = 2;
  private static final int STATE_END = 3;
  private static final int STATE_SEEK_TO_INDX = 4;
  private static final int STATE_READ_INDX_THEN_SEEK_BACK = 5;

  private final SparseArray<TrackReader> trackReaders = new SparseArray<>();
  private final ParsableByteArray scratch = new ParsableByteArray(256);
  private final ParsableByteArray packetBuffer = new ParsableByteArray();
  private final List<SeekPoint> seekIndex = new ArrayList<>();

  private ExtractorOutput extractorOutput;

  private int state = STATE_READ_FILE_HEADER;
  private long durationUs = C.TIME_UNSET;
  private boolean tracksEnded = false;
  private int totalPackets = 0;
  private int packetCount = 0;
  private long indxOffsetInFile = 0;
  private long dataBodyStart = 0;

  @Override
  public void init(@NonNull ExtractorOutput output) {
    this.extractorOutput = output;
  }

  @Override
  public boolean sniff(@NonNull ExtractorInput input) throws IOException {
    byte[] header = new byte[4];
    try {
      input.peekFully(header, 0, 4, false);
      return header[0] == 0x2e && header[1] == 0x52
          && header[2] == 0x4d && header[3] == 0x46; // ".RMF"
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  public int read(@NonNull ExtractorInput input, @NonNull PositionHolder seekPosition) throws IOException {
    switch (state) {
      case STATE_READ_FILE_HEADER:
        return readFileHeader(input);
      case STATE_READ_CHUNK:
        return readChunk(input);
      case STATE_READ_DATA:
        return readDataPacket(input);
      case STATE_SEEK_TO_INDX:
        seekPosition.position = indxOffsetInFile;
        state = STATE_READ_INDX_THEN_SEEK_BACK;
        return RESULT_SEEK;
      case STATE_READ_INDX_THEN_SEEK_BACK:
        readIndxThenSeekBack(input, seekPosition);
        return RESULT_SEEK;
      default:
        return RESULT_END_OF_INPUT;
    }
  }

  @Override
  public void seek(long position, long timeUs) {
    packetCount = 0;
    state = (position == 0L) ? STATE_READ_FILE_HEADER : STATE_READ_DATA;
    if (position == 0L) {
      trackReaders.clear();
      seekIndex.clear();
      totalPackets = 0;
      tracksEnded = false;
      durationUs = C.TIME_UNSET;
      indxOffsetInFile = 0;
      dataBodyStart = 0;
    } else {
      for (int i = 0; i < trackReaders.size(); i++) {
        trackReaders.valueAt(i).seek();
      }
    }
  }

  @Override
  public void release() {
    trackReaders.clear();
    seekIndex.clear();
  }

  private int readFileHeader(ExtractorInput input) throws IOException {
    scratch.reset(8);
    input.readFully(scratch.getData(), 0, 8);
    scratch.skipBytes(4); // ".RMF" tag
    int remaining = scratch.readInt() - 8;
    if (remaining > 0) {
      input.skipFully(remaining);
    }
    state = STATE_READ_CHUNK;
    return RESULT_CONTINUE;
  }

  private int readChunk(ExtractorInput input) throws IOException {
    scratch.reset(8);
    if (!input.readFully(scratch.getData(), 0, 8, true)) {
      return RESULT_END_OF_INPUT;
    }
    int chunkType = scratch.readInt();
    int payloadSize = Math.max(scratch.readInt() - 8, 0);
    switch (chunkType) {
      case CHUNK_PROP:
        readPropChunk(input, payloadSize);
        break;
      case CHUNK_MDPR:
        readMdprChunk(input, payloadSize);
        break;
      case CHUNK_DATA:
        readDataChunkHeader(input);
        break;
      case CHUNK_INDX:
        readIndxChunk(input, payloadSize);
        break;
      default:
        input.skipFully(payloadSize);
        break;
    }
    return RESULT_CONTINUE;
  }

  private void readPropChunk(ExtractorInput input, int size) throws IOException {
    packetBuffer.reset(size);
    input.readFully(packetBuffer.getData(), 0, size);
    int version = packetBuffer.readShort() & 0xFFFF;
    packetBuffer.skipBytes(20);
    long durationMs = packetBuffer.readInt() & 0xFFFFFFFFL;
    durationUs = durationMs * 1_000L;
    packetBuffer.skipBytes(4);
    if (version == 2) {
      indxOffsetInFile = packetBuffer.readLong();
    } else {
      indxOffsetInFile = packetBuffer.readInt() & 0xFFFFFFFFL;
    }
    extractorOutput.seekMap(new androidx.media3.extractor.SeekMap.Unseekable(durationUs));
  }

  /**
   * MDPR payload layout (after 8-byte chunk header):
   * version(2) stream_id(2) max_bit_rate(4) avg_bit_rate(4) max_pkt_size(4)
   * avg_pkt_size(4) start_time(4) preroll(4) duration(4) desc_len(1) desc(n)
   * mime_len(1) mime(n) extra_data_size(4) extra_data(n)
   */
  private void readMdprChunk(ExtractorInput input, int size) throws IOException {
    packetBuffer.reset(size);
    input.readFully(packetBuffer.getData(), 0, size);

    packetBuffer.skipBytes(2);
    int streamId = packetBuffer.readShort() & 0xFFFF;
    int maxBitRate = packetBuffer.readInt();
    int avgBitRate = packetBuffer.readInt();
    int maxPktSize = packetBuffer.readInt();
    packetBuffer.skipBytes(16);

    packetBuffer.skipBytes(packetBuffer.readUnsignedByte()); // desc

    int mimeLen = packetBuffer.readUnsignedByte();
    String mime = new String(packetBuffer.getData(), packetBuffer.getPosition(), mimeLen, StandardCharsets.US_ASCII);
    packetBuffer.skipBytes(mimeLen);

    int extraDataSize = packetBuffer.readInt();
    int extraDataAvail = packetBuffer.limit() - packetBuffer.getPosition();
    if (extraDataSize < 0 || extraDataSize > extraDataAvail) {
      Log.w(TAG, "stream " + streamId + ": bad extraDataSize=" + extraDataSize + " avail=" + extraDataAvail);
      return;
    }
    byte[] extraData = new byte[extraDataSize];
    System.arraycopy(packetBuffer.getData(), packetBuffer.getPosition(), extraData, 0, extraDataSize);

    if (mime.contains("video")) {
      parseVideoMdpr(streamId, extraData, avgBitRate, maxBitRate, maxPktSize);
    } else if (mime.contains("audio")) {
      parseAudioMdpr(streamId, extraData, avgBitRate, maxBitRate);
    }
  }

  /**
   * Video extra-data layout (FFmpeg ff_rm_read_mdpr_codecdata):
   * sig(4) "VIDO"(4) codec_tag(4,LE) width(2) height(2)
   * bits_per_sample(2) zero(4) fps_16_16(4) decoder_config(...)
   */
  private static VideoCodecInfo parseVideoExtraData(byte[] extraData) {
    if (extraData.length < 26) {
      return null;
    }
    ParsableByteArray buf = new ParsableByteArray(extraData);
    buf.skipBytes(8); // sig + "VIDO"

    String codecTag = new String(extraData, buf.getPosition(), 4, StandardCharsets.US_ASCII);
    buf.skipBytes(4);

    int width = buf.readShort() & 0xFFFF;
    int height = buf.readShort() & 0xFFFF;
    buf.skipBytes(6); // bits_per_sample + zero
    float fps = buf.readInt() / 65536.0f; // 16.16 fixed-point

    byte[] initData = new byte[extraData.length - 26];
    System.arraycopy(extraData, 26, initData, 0, initData.length);

    String[] resolved = resolveVideoCodec(initData, codecTag);
    return new VideoCodecInfo(resolved[0], resolved[1], width, height, fps, initData);
  }

  private static String[] resolveVideoCodec(byte[] initData, String codecTag) {
    if (initData.length >= 5) {
      switch ((initData[4] & 0xFF) >> 4) {
        case 1:
          return new String[]{MimeTypes.VIDEO_RV10, "rv10"};
        case 2:
          return new String[]{MimeTypes.VIDEO_RV20, "rv20"};
        case 3:
          return new String[]{MimeTypes.VIDEO_RV30, "rv30"};
        case 4:
          return new String[]{MimeTypes.VIDEO_RV40, "rv40"};
      }
    }
    switch (codecTag) {
      case "RV10":
      case "RV13":
        return new String[]{MimeTypes.VIDEO_RV10, "rv10"};
      case "RV20":
        return new String[]{MimeTypes.VIDEO_RV20, "rv20"};
      case "RV30":
        return new String[]{MimeTypes.VIDEO_RV30, "rv30"};
      default:
        return new String[]{MimeTypes.VIDEO_RV40, "rv40"};
    }
  }

  private void parseVideoMdpr(int streamId, byte[] extraData, int avgBitRate, int maxBitRate, int maxPktSize) {
    VideoCodecInfo info = parseVideoExtraData(extraData);
    if (info == null) {
      return;
    }
    TrackOutput trackOutput = extractorOutput.track(streamId, C.TRACK_TYPE_VIDEO);
    trackOutput.format(buildVideoFormat(streamId, info, avgBitRate, maxBitRate, maxPktSize));
    trackReaders.put(streamId, new VideoReader(trackOutput));
  }

  private static Format buildVideoFormat(int streamId, VideoCodecInfo info, int avgBitRate, int maxBitRate, int maxPktSize) {
    Format.Builder builder = new Format.Builder()
        .setId(String.valueOf(streamId))
        .setWidth(info.width)
        .setHeight(info.height)
        .setCodecs(info.codecs)
        .setSampleMimeType(info.mimeType)
        .setContainerMimeType(MimeTypes.APPLICATION_RM)
        .setInitializationData(Collections.singletonList(info.initData));
    if (info.fps > 0) {
      builder.setFrameRate(info.fps);
    }
    if (avgBitRate > 0) {
      builder.setAverageBitrate(avgBitRate);
    }
    if (maxBitRate > 0) {
      builder.setPeakBitrate(maxBitRate);
    }
    if (maxPktSize > 0) {
      builder.setMaxInputSize(maxPktSize);
    }
    return builder.build();
  }

  private void parseAudioMdpr(int streamId, byte[] extraData, int avgBitRate, int maxBitRate) {
    if (extraData.length < 4) {
      Log.w(TAG, "stream " + streamId + ": audio extradata too short");
      return;
    }

    if (extraData[0] == 'L' && extraData[1] == 'S' && extraData[2] == 'D' && extraData[3] == ':') {
      if (extraData.length < 24) {
        Log.w(TAG, "stream " + streamId + ": RALF extradata too short (" + extraData.length + ")");
        return;
      }
      int channels = ((extraData[8] & 0xFF) << 8) | (extraData[9] & 0xFF);
      int sampleRate = ((extraData[12] & 0xFF) << 24) | ((extraData[13] & 0xFF) << 16) | ((extraData[14] & 0xFF) << 8) | (extraData[15] & 0xFF);
      TrackOutput trackOutput = extractorOutput.track(streamId, C.TRACK_TYPE_AUDIO);
      trackOutput.format(buildAudioFormat(streamId, MimeTypes.AUDIO_RALF, "ralf", channels, sampleRate, avgBitRate, maxBitRate, 0, Collections.singletonList(extraData)));
      trackReaders.put(streamId, new PassThroughReader(trackOutput));
      return;
    }

    parseRealAudio(streamId, extraData, avgBitRate, maxBitRate);
  }

  private void parseRealAudio(int streamId, byte[] extraData, int avgBitRate, int maxBitRate) {
    int raOffset = findAudioSig(extraData);
    if (raOffset < 0) {
      Log.w(TAG, "stream " + streamId + ": no .ra\\xfd signature found");
      return;
    }

    ParsableByteArray buf = new ParsableByteArray(extraData);
    buf.setPosition(raOffset);
    if (buf.readInt() != RA_SIG) {
      Log.w(TAG, "stream " + streamId + ": bad RA signature");
      return;
    }

    try {
      int version = buf.readShort() & 0xFFFF;

      if (version == 3) {
        // RA v3 (14_4 / ra_144) is not supported; skip track.
        Log.w(TAG, "stream " + streamId + ": RA v3 (14_4) not supported, skipping");
        return;
      }

      AudioHeader ra = parseAudioHeader(buf, version);
      String mimeType = fourCCToMime(ra.codecFourCC);
      if (mimeType == null) {
        Log.w(TAG, "stream " + streamId + ": unsupported audio fourCC=" + ra.codecFourCC);
        return;
      }

      int maxInputSize = calcAudioMaxInputSize(ra.codecFourCC, ra.flavor, ra.subPacketSize);
      int blockAlign = calcBlockAlign(ra.codecFourCC, ra.flavor, ra.subPacketSize);
      List<byte[]> initData = MimeTypes.AUDIO_AAC.equals(mimeType) ? Collections.singletonList(ra.codecExtraData) : buildAudioInitData(ra.codecExtraData, blockAlign);

      TrackOutput trackOutput = extractorOutput.track(streamId, C.TRACK_TYPE_AUDIO);
      trackOutput.format(buildAudioFormat(streamId, mimeType, ra.codecFourCC.toLowerCase(), ra.channels, ra.sampleRate, avgBitRate, maxBitRate, maxInputSize, initData));
      trackReaders.put(streamId, createAudioReader(trackOutput, ra));
    } catch (Exception e) {
      Log.w(TAG, "stream " + streamId + ": audio MDPR parse error", e);
    }
  }

  private static Format buildAudioFormat(int streamId, String mimeType, String codecs, int channels, int sampleRate, int avgBitRate, int maxBitRate, int maxInputSize, List<byte[]> initData) {
    Format.Builder builder = new Format.Builder()
        .setId(String.valueOf(streamId))
        .setCodecs(codecs)
        .setChannelCount(channels)
        .setSampleRate(sampleRate)
        .setSampleMimeType(mimeType)
        .setContainerMimeType(MimeTypes.APPLICATION_RM)
        .setInitializationData(initData);
    if (avgBitRate > 0) {
      builder.setAverageBitrate(avgBitRate);
    }
    if (maxBitRate > 0) {
      builder.setPeakBitrate(maxBitRate);
    }
    if (maxInputSize > 0) {
      builder.setMaxInputSize(maxInputSize);
    }
    return builder.build();
  }

  private static TrackReader createAudioReader(TrackOutput trackOutput, AudioHeader ra) {
    String lowerFourCC = ra.codecFourCC.toLowerCase();
    switch (lowerFourCC) {
      case "cook":
      case "atrc":
      case "atrc+":
        return new CookAudioReader(trackOutput, ra.frameSize, ra.subPacketH, ra.subPacketSize, ra.sampleRate);
      case "sipr":
        return new SiprAudioReader(trackOutput, ra.subPacketH, ra.frameSize);
      case "dnet":
        return new Ac3AudioReader(trackOutput);
      case "raac":
      case "racp":
        return new RaacAudioReader(trackOutput, ra.sampleRate);
      default:
        return new PassThroughReader(trackOutput);
    }
  }

  private static int findAudioSig(byte[] data) {
    for (int i = 0; i <= data.length - 4; i++) {
      if (data[i] == 0x2e && data[i + 1] == 0x72 && data[i + 2] == 0x61 && (data[i + 3] & 0xFF) == 0xfd) {
        return i;
      }
    }
    return -1;
  }

  private static String fourCCToMime(String fourCC) {
    switch (fourCC.toLowerCase()) {
      case "cook":
        return MimeTypes.AUDIO_COOK;
      case "atrc":
        return MimeTypes.AUDIO_ATRAC3;
      case "atrc+":
        return MimeTypes.AUDIO_ATRAC3P;
      case "sipr":
        return MimeTypes.AUDIO_SIPR;
      case "raac":
      case "racp":
        return MimeTypes.AUDIO_AAC;
      case "dnet":
        return MimeTypes.AUDIO_AC3;
      default:
        return null;
    }
  }

  /**
   * RealAudio header layout after version (v4 and v5 only; v3 is handled at call site):
   * [skipped 16] flavor(2) coded_framesize(4) [skipped 12]
   * sub_packet_h(2) frame_size(2) sub_packet_size(2) [skipped 2]
   * [v5: skipped 6] sample_rate(2) [skipped 4] channels(2)
   * v4: interleaver(len-prefixed) codec(len-prefixed)
   * v5: deint_id(4) codec_tag(4)
   * cook/atrc/atrc+/sipr/raac/racp: [skipped 3+v5:1] csd_len(4) [raac:skip 1] csd
   */
  private static AudioHeader parseAudioHeader(ParsableByteArray buf, int version) {
    buf.skipBytes(16);
    int flavor = buf.readShort() & 0xFFFF;
    buf.skipBytes(4); // coded_framesize (not needed)
    buf.skipBytes(12);
    int subPacketH = buf.readShort() & 0xFFFF;
    int frameSize = buf.readShort() & 0xFFFF;
    int subPacketSize = buf.readShort() & 0xFFFF;
    buf.skipBytes(2);
    if (version == 5) {
      buf.skipBytes(6);
    }
    int sampleRate = buf.readShort() & 0xFFFF;
    buf.skipBytes(4);
    int channels = buf.readShort() & 0xFFFF;

    String codecFourCC;
    if (version == 4) {
      buf.skipBytes(buf.readUnsignedByte()); // interleaver id string
      int codecIdLen = buf.readUnsignedByte();
      codecFourCC = new String(buf.getData(), buf.getPosition(), codecIdLen, StandardCharsets.US_ASCII).trim();
      buf.skipBytes(codecIdLen);
    } else {
      buf.skipBytes(4); // deint_id
      codecFourCC = new String(buf.getData(), buf.getPosition(), 4, StandardCharsets.US_ASCII).trim();
      buf.skipBytes(4);
    }

    boolean isAac = "raac".equalsIgnoreCase(codecFourCC) || "racp".equalsIgnoreCase(codecFourCC);
    boolean hasCsd = isAac || "cook".equalsIgnoreCase(codecFourCC) || "atrc".equalsIgnoreCase(codecFourCC) || "atrc+".equalsIgnoreCase(codecFourCC) || "sipr".equalsIgnoreCase(codecFourCC);

    byte[] codecExtraData = new byte[0];
    if (hasCsd) {
      buf.skipBytes(version == 5 ? 4 : 3);
      if (buf.getPosition() + 4 <= buf.limit()) {
        int csdLen = buf.readInt();
        if (isAac && csdLen >= 1 && buf.getPosition() + 1 <= buf.limit()) {
          buf.skipBytes(1); // skip RealNetworks object-type prefix byte
          csdLen--;
        }
        if (csdLen > 0 && buf.getPosition() + csdLen <= buf.limit()) {
          codecExtraData = new byte[csdLen];
          System.arraycopy(buf.getData(), buf.getPosition(), codecExtraData, 0, csdLen);
        }
      }
    }

    return new AudioHeader(flavor, subPacketH, frameSize, subPacketSize, sampleRate, channels, codecFourCC, codecExtraData);
  }

  private static int calcAudioMaxInputSize(String fourCC, int flavor, int subPacketSize) {
    if ("sipr".equalsIgnoreCase(fourCC)) {
      return (flavor < SIPR_SUBPK_SIZE.length) ? SIPR_SUBPK_SIZE[flavor] : 0;
    }
    return subPacketSize;
  }

  private static int calcBlockAlign(String fourCC, int flavor, int subPacketSize) {
    if ("sipr".equalsIgnoreCase(fourCC)) {
      return (flavor < SIPR_SUBPK_SIZE.length) ? SIPR_SUBPK_SIZE[flavor] : subPacketSize;
    }
    return subPacketSize;
  }

  private static List<byte[]> buildAudioInitData(byte[] codecExtraData, int blockAlign) {
    List<byte[]> initData = new ArrayList<>();
    initData.add(codecExtraData);
    if (blockAlign > 0) {
      initData.add(new byte[]{(byte) (blockAlign >> 24), (byte) (blockAlign >> 16), (byte) (blockAlign >> 8), (byte) blockAlign});
    }
    return initData;
  }

  private void readDataChunkHeader(ExtractorInput input) throws IOException {
    scratch.reset(10);
    input.readFully(scratch.getData(), 0, 10);
    scratch.skipBytes(2); // version
    totalPackets = scratch.readInt();
    ensureTracksEnded();
    dataBodyStart = input.getPosition();

    long fileLength = input.getLength();
    boolean indxReachable = indxOffsetInFile > 0 && indxOffsetInFile > dataBodyStart && fileLength != C.LENGTH_UNSET && indxOffsetInFile + 20 <= fileLength;

    if (indxReachable) {
      state = STATE_SEEK_TO_INDX;
    } else {
      if (indxOffsetInFile > 0) {
        Log.w(TAG, "INDX offset " + indxOffsetInFile + " out of range (fileLen=" + fileLength + ")");
      }
      state = STATE_READ_DATA;
    }
  }

  private int readDataPacket(ExtractorInput input) throws IOException {
    scratch.reset(12);
    if (!input.readFully(scratch.getData(), 0, 12, true)) {
      return RESULT_END_OF_INPUT;
    }

    int version = scratch.readShort() & 0xFFFF;
    int packetSize = scratch.readShort() & 0xFFFF;
    int streamId = scratch.readShort() & 0xFFFF;
    long timestampMs = scratch.readInt() & 0xFFFFFFFFL;
    scratch.skipBytes(1); // reserved
    int flags = scratch.readUnsignedByte();

    if (version == 1) {
      input.skipFully(1); // extra byte in v1 header
    }
    int headerSize = (version == 1) ? 13 : 12;

    int dataSize = packetSize - headerSize;
    if (dataSize <= 0) {
      if (dataSize < 0) {
        Log.w(TAG, "Invalid packet size=" + packetSize);
      }
      return RESULT_CONTINUE;
    }

    TrackReader reader = trackReaders.get(streamId);
    try {
      if (reader == null) {
        input.skipFully(dataSize);
      } else {
        packetBuffer.reset(dataSize);
        input.readFully(packetBuffer.getData(), 0, dataSize);
        reader.consume(packetBuffer, dataSize, timestampMs * 1_000L, (flags & 0x02) != 0);
      }
    } catch (EOFException e) {
      return RESULT_END_OF_INPUT;
    }

    packetCount++;
    if (totalPackets > 0 && packetCount >= totalPackets) {
      state = STATE_END;
      return RESULT_END_OF_INPUT;
    }
    return RESULT_CONTINUE;
  }

  private void readIndxChunk(ExtractorInput input, int size) throws IOException {
    if (size < 12) {
      input.skipFully(size);
      return;
    }
    packetBuffer.reset(size);
    input.readFully(packetBuffer.getData(), 0, size);

    int version = packetBuffer.readShort() & 0xFFFF;
    int numEntries = packetBuffer.readInt();
    packetBuffer.skipBytes(6); // str_id (2) + next_off (4)
    if (version == 2) {
      packetBuffer.skipBytes(4); // extra field present in ver==2 header
    }

    // entry: entry_ver(2) timestamp_ms(4) byte_offset(4|8) packet_no(4)
    int headerConsumed = (version == 2) ? 16 : 12;
    int entrySize = (version == 2) ? 18 : 14;
    int count = Math.min(numEntries, (size - headerConsumed) / entrySize);
    for (int i = 0; i < count; i++) {
      packetBuffer.skipBytes(2); // entry version
      long timestampMs = packetBuffer.readInt() & 0xFFFFFFFFL;
      long byteOffset = (version == 2) ? packetBuffer.readLong() : (packetBuffer.readInt() & 0xFFFFFFFFL);
      packetBuffer.skipBytes(4); // frame_index (packet no.)
      seekIndex.add(new SeekPoint(timestampMs * 1_000L, byteOffset));
    }

    if (!seekIndex.isEmpty()) {
      extractorOutput.seekMap(new IndexSeekMap(durationUs, new ArrayList<>(seekIndex)));
    }
  }

  private void readIndxThenSeekBack(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    scratch.reset(8);
    if (!input.readFully(scratch.getData(), 0, 8, true)) {
      Log.w(TAG, "Could not read INDX header at offset " + indxOffsetInFile);
      seekPosition.position = dataBodyStart;
      state = STATE_READ_DATA;
      return;
    }
    int chunkType = scratch.readInt();
    int payloadSize = Math.max(scratch.readInt() - 8, 0);

    if (chunkType == CHUNK_INDX) {
      readIndxChunk(input, payloadSize);
    } else {
      Log.w(TAG, "Expected INDX at " + indxOffsetInFile + " but found 0x" + Integer.toHexString(chunkType));
      input.skipFully(payloadSize);
    }

    seekPosition.position = dataBodyStart;
    state = STATE_READ_DATA;
  }

  private void ensureTracksEnded() {
    if (!tracksEnded) {
      tracksEnded = true;
      extractorOutput.endTracks();
    }
  }
}
