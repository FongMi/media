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

import androidx.annotation.Nullable;
import androidx.media3.common.util.ParsableBitArray;

final class Av3aUtil {

  static final int MAX_HEADER_SIZE = 9;
  static final int SAMPLES_PER_FRAME = 1024;

  static final int CHANNEL_CONFIG_MONO = 0;
  static final int CHANNEL_CONFIG_STEREO = 1;
  static final int CHANNEL_CONFIG_MC_5_1 = 2;
  static final int CHANNEL_CONFIG_MC_7_1 = 3;
  static final int CHANNEL_CONFIG_MC_10_2 = 4;
  static final int CHANNEL_CONFIG_MC_22_2 = 5;
  static final int CHANNEL_CONFIG_MC_4_0 = 6;
  static final int CHANNEL_CONFIG_MC_5_1_2 = 7;
  static final int CHANNEL_CONFIG_MC_5_1_4 = 8;
  static final int CHANNEL_CONFIG_MC_7_1_2 = 9;
  static final int CHANNEL_CONFIG_MC_7_1_4 = 10;
  static final int CHANNEL_CONFIG_HOA_ORDER1 = 11;
  static final int CHANNEL_CONFIG_HOA_ORDER2 = 12;
  static final int CHANNEL_CONFIG_HOA_ORDER3 = 13;
  static final int CHANNEL_CONFIG_UNKNOWN = 14;

  private static final int SYNC_WORD = 0xFFF;
  private static final int AUDIO_CODEC_ID = 2;
  private static final int CODING_PROFILE_CHANNEL = 0;
  private static final int CODING_PROFILE_MIX = 1;
  private static final int CODING_PROFILE_HOA = 2;

  private static final int[] SAMPLING_RATE_TABLE = {
      192000, 96000, 48000, 44100, 32000, 24000, 22050, 16000, 8000
  };

  private static final int[] CHANNEL_COUNT_TABLE = {
      /* MONO     */  1,
      /* STEREO   */  2,
      /* MC_5_1   */  6,
      /* MC_7_1   */  8,
      /* MC_10_2  */ 12,
      /* MC_22_2  */ 24,
      /* MC_4_0   */  4,
      /* MC_5_1_2 */  8,
      /* MC_5_1_4 */ 10,
      /* MC_7_1_2 */ 10,
      /* MC_7_1_4 */ 12,
      /* HOA_1    */ -1,
      /* HOA_2    */ -1,
      /* HOA_3    */ -1,
  };

  // avs3_rom_com.c bitrate tables, indexed by CHANNEL_CONFIG_* constant.
  private static final int[][] BITRATE_TABLES = {
      /* MONO     */ {16000, 32000, 44000, 56000, 64000, 72000, 80000, 96000, 128000, 144000, 164000, 192000},
      /* STEREO   */ {24000, 32000, 48000, 64000, 80000, 96000, 128000, 144000, 192000, 256000, 320000},
      /* MC_5_1   */ {192000, 256000, 320000, 384000, 448000, 512000, 640000, 720000, 144000, 96000, 128000, 160000},
      /* MC_7_1   */ {192000, 480000, 256000, 384000, 576000, 640000, 128000, 160000},
      /* MC_10_2  */ {},
      /* MC_22_2  */ {},
      /* MC_4_0   */ {48000, 96000, 128000, 192000, 256000},
      /* MC_5_1_2 */ {152000, 320000, 480000, 576000},
      /* MC_5_1_4 */ {176000, 384000, 576000, 704000, 256000, 448000},
      /* MC_7_1_2 */ {216000, 480000, 576000, 384000, 768000},
      /* MC_7_1_4 */ {240000, 608000, 384000, 512000, 832000},
      /* HOA_1    */ {48000, 96000, 128000, 192000, 256000},
      /* HOA_2    */ {192000, 256000, 320000, 384000, 480000, 512000, 640000},
      /* HOA_3    */ {256000, 320000, 384000, 512000, 640000, 896000},
  };

  @Nullable
  static FrameHeader parseFrameHeader(ParsableBitArray data) {
    if (data.readBits(12) != SYNC_WORD) {
      return null;
    }
    if (data.readBits(4) != AUDIO_CODEC_ID) {
      return null;
    }
    if (data.readBits(1) != 0) {
      return null;
    }
    data.skipBits(3);
    int codingProfile = data.readBits(3);
    int samplingRateIdx = data.readBits(4);
    if (samplingRateIdx >= SAMPLING_RATE_TABLE.length) {
      return null;
    }
    int samplingRate = SAMPLING_RATE_TABLE[samplingRateIdx];
    data.skipBits(8);
    int channelNumIdx = 0;
    int numObjs = 0;
    int hoaOrder = 0;
    int soundBedType = 0;
    int bitrateIdxPerObj = 0;
    int bitrateIdxBedMc = 0;
    switch (codingProfile) {
      case CODING_PROFILE_CHANNEL:
        channelNumIdx = data.readBits(7);
        break;
      case CODING_PROFILE_MIX:
        soundBedType = data.readBits(2);
        if (soundBedType == 0) {
          numObjs = data.readBits(7) + 1;
          bitrateIdxPerObj = data.readBits(4);
        } else if (soundBedType == 1) {
          channelNumIdx = data.readBits(7);
          bitrateIdxBedMc = data.readBits(4);
          numObjs = data.readBits(7) + 1;
          bitrateIdxPerObj = data.readBits(4);
        } else {
          return null;
        }
        break;
      case CODING_PROFILE_HOA:
        hoaOrder = data.readBits(4) + 1;
        break;
      default:
        return null;
    }
    data.skipBits(2);
    int bitrateIdx = (codingProfile != CODING_PROFILE_MIX) ? data.readBits(4) : 0;
    data.skipBits(8);
    return buildFrameHeader(samplingRate, codingProfile, channelNumIdx, numObjs, hoaOrder, soundBedType, bitrateIdx, bitrateIdxPerObj, bitrateIdxBedMc);
  }

  @Nullable
  private static FrameHeader buildFrameHeader(int samplingRate, int codingProfile, int channelNumIdx, int numObjs, int hoaOrder, int soundBedType, int bitrateIdx, int bitrateIdxPerObj, int bitrateIdxBedMc) {
    FrameHeader h = new FrameHeader();
    h.samplingRate = samplingRate;
    h.samplesPerFrame = SAMPLES_PER_FRAME;
    switch (codingProfile) {
      case CODING_PROFILE_CHANNEL:
        if (isInvalidChannelConfig(channelNumIdx) || isInvalidBitrateIdx(channelNumIdx, bitrateIdx)) {
          return null;
        }
        h.channelConfig = channelNumIdx;
        h.channelCount = CHANNEL_COUNT_TABLE[channelNumIdx];
        h.totalBitrate = BITRATE_TABLES[channelNumIdx][bitrateIdx];
        break;
      case CODING_PROFILE_MIX:
        if (!buildMixHeader(h, channelNumIdx, numObjs, soundBedType, bitrateIdxPerObj, bitrateIdxBedMc)) {
          return null;
        }
        break;
      case CODING_PROFILE_HOA:
        int hoaConfigIdx = hoaOrderToConfigIdx(hoaOrder);
        if (hoaConfigIdx == CHANNEL_CONFIG_UNKNOWN || isInvalidBitrateIdx(hoaConfigIdx, bitrateIdx)) {
          return null;
        }
        h.channelConfig = hoaConfigIdx;
        h.channelCount = (hoaOrder + 1) * (hoaOrder + 1);
        h.totalBitrate = BITRATE_TABLES[hoaConfigIdx][bitrateIdx];
        break;
      default:
        return null;
    }
    return h;
  }

  private static boolean buildMixHeader(FrameHeader h, int channelNumIdx, int numObjs, int soundBedType, int bitrateIdxPerObj, int bitrateIdxBedMc) {
    if (soundBedType == 0) {
      if (isInvalidBitrateIdx(CHANNEL_CONFIG_MONO, bitrateIdxPerObj)) {
        return false;
      }
      int bitratePerObj = BITRATE_TABLES[CHANNEL_CONFIG_MONO][bitrateIdxPerObj];
      h.channelConfig = CHANNEL_CONFIG_UNKNOWN;
      h.channelCount = numObjs;
      h.totalBitrate = numObjs * bitratePerObj;
    } else {
      if (isInvalidChannelConfig(channelNumIdx) || isInvalidBitrateIdx(channelNumIdx, bitrateIdxBedMc) || isInvalidBitrateIdx(CHANNEL_CONFIG_MONO, bitrateIdxPerObj)) {
        return false;
      }
      int bedBitrate = BITRATE_TABLES[channelNumIdx][bitrateIdxBedMc];
      int bitratePerObj = BITRATE_TABLES[CHANNEL_CONFIG_MONO][bitrateIdxPerObj];
      h.channelConfig = channelNumIdx;
      h.channelCount = CHANNEL_COUNT_TABLE[channelNumIdx] + numObjs;
      h.totalBitrate = bedBitrate + numObjs * bitratePerObj;
    }
    return true;
  }

  private static boolean isInvalidChannelConfig(int configIdx) {
    return configIdx < 0 || configIdx >= CHANNEL_COUNT_TABLE.length || CHANNEL_COUNT_TABLE[configIdx] <= 0 || BITRATE_TABLES[configIdx].length == 0;
  }

  private static boolean isInvalidBitrateIdx(int configIdx, int bitrateIdx) {
    return configIdx < 0 || configIdx >= BITRATE_TABLES.length || bitrateIdx < 0 || bitrateIdx >= BITRATE_TABLES[configIdx].length;
  }

  private static int hoaOrderToConfigIdx(int hoaOrder) {
    switch (hoaOrder) {
      case 1:
        return CHANNEL_CONFIG_HOA_ORDER1;
      case 2:
        return CHANNEL_CONFIG_HOA_ORDER2;
      case 3:
        return CHANNEL_CONFIG_HOA_ORDER3;
      default:
        return CHANNEL_CONFIG_UNKNOWN;
    }
  }

  static final class FrameHeader {

    int samplingRate;
    int channelCount;
    int totalBitrate;
    int samplesPerFrame;
    int channelConfig;
  }
}
