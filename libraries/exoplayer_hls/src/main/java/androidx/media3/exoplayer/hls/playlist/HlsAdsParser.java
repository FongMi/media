package androidx.media3.exoplayer.hls.playlist;

import android.text.TextUtils;
import androidx.media3.common.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HlsAdsParser {

  private static final String TAG = HlsAdsParser.class.getSimpleName();

  private static final String TAG_DURATION = "#EXTINF";
  private static final String TAG_ENDLIST = "#EXT-X-ENDLIST";
  private static final String TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY";
  private static final String DEFAULT_GROUP_IDENTIFIER = "NO_PATH";
  private static final Pattern REGEX_DURATION = Pattern.compile(TAG_DURATION + ":([\\d\\.]+)\\b");

  private static final int REASONABLE_GROUP_LIMIT = 10;
  private static final int MIN_PREFIX_LENGTH_TO_TEST = 5;
  private static final int SEQUENCE_NUMBER_RESERVED_LENGTH = 4;

  private static final int AD_BREAK_THRESHOLD_SHORT = 2;
  private static final int AD_BREAK_THRESHOLD_MEDIUM = 3;
  private static final int AD_BREAK_THRESHOLD_LONG = 4;
  private static final int AD_BREAK_THRESHOLD_EXTRA = 5;

  private static final double MIN_MAJORITY_GROUP_RATIO = 0.85;
  private static final double AD_BLOCK_SIZE_RATIO = 0.75;
  private static final double DURATION_TIER_SHORT = 30.0;
  private static final double DURATION_TIER_MEDIUM = 60.0;
  private static final double DURATION_TIER_LONG = 90.0;

  public static String process(String m3u8) {
    if (TextUtils.isEmpty(m3u8) || !m3u8.contains(TAG_ENDLIST)) {
      return m3u8;
    }
    Log.d(TAG, "Executing HlsAdsParser...");
    String[] lines = m3u8.split("\\r?\\n");
    Set<String> adSegments = findAds(lines);
    if (adSegments.isEmpty()) {
      Log.d(TAG, "No ad segments detected. Returning original content.");
      return m3u8;
    } else {
      Log.d(TAG, "Detected " + adSegments.size() + " ad segments to remove. Rebuilding playlist...");
      for (String adSegment : adSegments) {
        Log.d(TAG, "  -> Removing: " + adSegment);
      }
      return rebuildM3u8(lines, adSegments);
    }
  }

  private static Set<String> findAds(String[] lines) {
    Log.d(TAG, "Executing Primary Strategy: Filename/Path Analysis...");
    List<String> allSegments = new ArrayList<>();
    for (String line : lines) {
      String trimmedLine = line.trim();
      if (isSegmentLine(trimmedLine)) {
        allSegments.add(trimmedLine);
      }
    }
    Set<String> adsFromFilename = findAdsByFilename(allSegments);
    if (!adsFromFilename.isEmpty()) {
      Log.d(TAG, "Primary strategy successful. Using its result.");
      return adsFromFilename;
    }
    Log.d(TAG, "Primary strategy did not find ads. Falling back to Discontinuity Analysis.");
    return findAdsByDiscontinuity(lines);
  }

  private static Set<String> findAdsByDiscontinuity(String[] lines) {
    List<List<String>> blocks = getDiscontinuityBlocks(lines);
    if (blocks.size() < 2) {
      Log.d(TAG, "Discontinuity Analysis: Only " + blocks.size() + " block(s) found. Strategy inconclusive.");
      return Collections.emptySet();
    }
    int modeSize = getModeSize(blocks.subList(0, blocks.size() - 1));
    if (modeSize <= 0) {
      return Collections.emptySet();
    }
    int minorityBlockCount = 0;
    Set<String> adSegments = new HashSet<>();
    double adSizeThreshold = modeSize * AD_BLOCK_SIZE_RATIO;
    for (int i = 0; i < blocks.size() - 1; i++) {
      List<String> block = blocks.get(i);
      if (block.size() < adSizeThreshold) {
        minorityBlockCount++;
        adSegments.addAll(block);
      }
    }
    double totalDurationMinutes = getTotalDurationInMinutes(lines);
    int minorityCountThreshold = getMinorityCountThreshold(totalDurationMinutes);
    Log.d(TAG, "Total duration is " + String.format(Locale.getDefault(), "%.2f", totalDurationMinutes) + " minutes. Ad block threshold is " + minorityCountThreshold + ".");
    if (minorityBlockCount > 0 && minorityBlockCount <= minorityCountThreshold) {
      Log.d(TAG, "Discontinuity Analysis: Found " + minorityBlockCount + " ad block(s) with size < " + adSizeThreshold + " (mode=" + modeSize + ", excluding last block). Identified as ads.");
      return adSegments;
    } else {
      Log.d(TAG, "Discontinuity Analysis: Found " + minorityBlockCount + " ad blocks. Count exceeds threshold of " + minorityCountThreshold + " (or is 0). Result is ambiguous, ignoring.");
      return Collections.emptySet();
    }
  }

  private static int getModeSize(List<List<String>> blocks) {
    Map<Integer, Integer> sizeFrequencies = new HashMap<>();
    for (List<String> block : blocks) {
      int size = block.size();
      Integer currentFreq = sizeFrequencies.get(size);
      sizeFrequencies.put(size, currentFreq == null ? 1 : currentFreq + 1);
    }
    int modeSize = -1;
    int maxFreq = -1;
    for (Map.Entry<Integer, Integer> entry : sizeFrequencies.entrySet()) {
      int freq = entry.getValue();
      int size = entry.getKey();
      if (freq > maxFreq || (freq == maxFreq && size > modeSize)) {
        maxFreq = freq;
        modeSize = size;
      }
    }
    return modeSize;
  }

  private static double getTotalDurationInMinutes(String[] lines) {
    double totalSeconds = 0.0;
    for (String line : lines) {
      if (line.startsWith(TAG_DURATION)) {
        Matcher matcher = REGEX_DURATION.matcher(line);
        if (matcher.find()) {
          try {
            totalSeconds += Double.parseDouble(matcher.group(1));
          } catch (NumberFormatException ignored) {
          }
        }
      }
    }
    return totalSeconds / 60.0;
  }

  private static int getMinorityCountThreshold(double totalMinutes) {
    if (totalMinutes <= DURATION_TIER_SHORT) {
      return AD_BREAK_THRESHOLD_SHORT;
    }
    if (totalMinutes <= DURATION_TIER_MEDIUM) {
      return AD_BREAK_THRESHOLD_MEDIUM;
    }
    if (totalMinutes <= DURATION_TIER_LONG) {
      return AD_BREAK_THRESHOLD_LONG;
    }
    return AD_BREAK_THRESHOLD_EXTRA;
  }

  private static List<List<String>> getDiscontinuityBlocks(String[] lines) {
    List<List<String>> blocks = new ArrayList<>();
    List<String> currentBlock = new ArrayList<>();
    for (String line : lines) {
      String trimmedLine = line.trim();
      if (trimmedLine.equals(TAG_DISCONTINUITY)) {
        if (!currentBlock.isEmpty()) {
          blocks.add(currentBlock);
        }
        currentBlock = new ArrayList<>();
      } else if (isSegmentLine(trimmedLine)) {
        currentBlock.add(trimmedLine);
      }
    }
    if (!currentBlock.isEmpty()) {
      blocks.add(currentBlock);
    }
    return blocks;
  }

  private static Set<String> findAdsByFilename(List<String> allSegments) {
    if (allSegments.size() < 2) {
      return Collections.emptySet();
    }
    Map<String, List<String>> structuralGroups = new HashMap<>();
    for (String segment : allSegments) {
      String identifier = getStructuralIdentifier(segment);
      List<String> group = structuralGroups.get(identifier);
      if (group == null) {
        group = new ArrayList<>();
        structuralGroups.put(identifier, group);
      }
      group.add(segment);
    }
    if (structuralGroups.size() > 1) {
      return findMinorityGroup(structuralGroups);
    }
    return findAdsByPrefixAnalysis(allSegments);
  }

  private static String getStructuralIdentifier(String segmentUrl) {
    int lastSlashIndex = segmentUrl.lastIndexOf('/');
    return lastSlashIndex != -1 ? segmentUrl.substring(0, lastSlashIndex) : DEFAULT_GROUP_IDENTIFIER;
  }

  private static Set<String> findMinorityGroup(Map<String, List<String>> groups) {
    int maxSize = 0;
    for (List<String> segments : groups.values()) {
      if (segments.size() > maxSize) {
        maxSize = segments.size();
      }
    }
    Set<String> adSegments = new HashSet<>();
    for (List<String> segments : groups.values()) {
      if (segments.size() < maxSize) {
        adSegments.addAll(segments);
      }
    }
    return adSegments;
  }

  private static Set<String> findAdsByPrefixAnalysis(List<String> segments) {
    int optimalPrefixLength = findOptimalPrefixLength(segments);
    if (optimalPrefixLength == -1) {
      return Collections.emptySet();
    }
    Map<String, List<String>> groups = groupSegmentsByIdentifier(segments, optimalPrefixLength);
    if (groups.size() <= 1 || groups.size() > REASONABLE_GROUP_LIMIT) {
      return Collections.emptySet();
    }
    List<String> mainContent = null;
    for (List<String> group : groups.values()) {
      if (mainContent == null || group.size() > mainContent.size()) {
        mainContent = group;
      }
    }
    Set<String> adSegments = new HashSet<>();
    for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
      if (entry.getValue() != mainContent) {
        adSegments.addAll(entry.getValue());
      }
    }
    return adSegments;
  }

  private static int findOptimalPrefixLength(List<String> segments) {
    if (segments.size() < 2) {
      return -1;
    }
    int shortestSegmentLength = Integer.MAX_VALUE;
    for (String segment : segments) {
      if (segment.length() < shortestSegmentLength) {
        shortestSegmentLength = segment.length();
      }
    }
    int bestLength = -1;
    double highestScore = 0.0;
    for (int length = MIN_PREFIX_LENGTH_TO_TEST; length < shortestSegmentLength - SEQUENCE_NUMBER_RESERVED_LENGTH; length++) {
      Map<String, List<String>> groups = groupSegmentsByIdentifier(segments, length);
      if (groups.size() > 1 && groups.size() <= REASONABLE_GROUP_LIMIT) {
        int maxGroupSize = 0;
        for (List<String> group : groups.values()) {
          if (group.size() > maxGroupSize) {
            maxGroupSize = group.size();
          }
        }
        double score = (double) maxGroupSize / segments.size();
        if (score > highestScore && score >= MIN_MAJORITY_GROUP_RATIO) {
          highestScore = score;
          bestLength = length;
        }
      }
    }
    return bestLength;
  }

  private static Map<String, List<String>> groupSegmentsByIdentifier(List<String> allSegments, int prefixLength) {
    Map<String, List<String>> groups = new HashMap<>();
    for (String segment : allSegments) {
      String identifier = getPrefixIdentifier(segment, prefixLength);
      List<String> group = groups.get(identifier);
      if (group == null) {
        group = new ArrayList<>();
        groups.put(identifier, group);
      }
      group.add(segment);
    }
    return groups;
  }

  private static String getPrefixIdentifier(String segmentUrl, int prefixLength) {
    return segmentUrl.length() > prefixLength ? segmentUrl.substring(0, prefixLength) : segmentUrl;
  }

  private static boolean isSegmentLine(String trimmedLine) {
    return !trimmedLine.isEmpty() && !trimmedLine.startsWith("#");
  }

  private static String rebuildM3u8(String[] lines, Set<String> adSegments) {
    List<String> stripped = removeAdSegments(lines, adSegments);
    List<String> cleaned = removeOrphanedDiscontinuityTags(stripped);
    StringBuilder builder = new StringBuilder();
    for (String line : cleaned) {
      builder.append(line).append("\n");
    }
    return builder.toString();
  }

  private static List<String> removeAdSegments(String[] lines, Set<String> adSegments) {
    List<String> result = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty()) {
        continue;
      }
      if (line.startsWith(TAG_DURATION)) {
        if (i + 1 < lines.length && adSegments.contains(lines[i + 1].trim())) {
          i++;
          continue;
        }
      } else if (adSegments.contains(line)) {
        continue;
      }
      result.add(line);
    }
    return result;
  }

  private static List<String> removeOrphanedDiscontinuityTags(List<String> lines) {
    List<String> result = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.equals(TAG_DISCONTINUITY)) {
        boolean prevIsDiscontinuityOrBoundary = (i == 0) || lines.get(i - 1).equals(TAG_DISCONTINUITY);
        boolean nextIsDiscontinuityOrBoundary = (i + 1 >= lines.size()) || lines.get(i + 1).equals(TAG_DISCONTINUITY);
        if (prevIsDiscontinuityOrBoundary || nextIsDiscontinuityOrBoundary) {
          continue;
        }
      }
      result.add(line);
    }
    return result;
  }
}
