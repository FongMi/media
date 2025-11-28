package androidx.media3.exoplayer.hls.playlist;

import android.text.TextUtils;
import androidx.media3.common.util.Log;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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
  private static final double MIN_MAJORITY_GROUP_RATIO = 0.85;

  public static String process(String m3u8) {
    if (TextUtils.isEmpty(m3u8) || !m3u8.contains(TAG_ENDLIST)) {
      return m3u8;
    }
    Log.d(TAG, "Executing HlsAdsParser...");
    long startTime = System.currentTimeMillis();
    String[] lines = m3u8.split("\\r?\\n");
    Set<String> adSegments = findAds(lines);
    if (adSegments.isEmpty()) {
      Log.d(TAG, "No ad segments detected. Returning original content.");
      long endTime = System.currentTimeMillis();
      Log.d(TAG, "Total processing time: " + (endTime - startTime) + "ms");
      return m3u8;
    }
    Log.d(TAG, "Detected " + adSegments.size() + " ad segments to remove. Rebuilding playlist...");
    for (String adSegment : adSegments) {
      Log.d(TAG, "  -> Removing: " + adSegment);
    }
    String result = rebuildM3u8(lines, adSegments);
    long endTime = System.currentTimeMillis();
    Log.d(TAG, "Total processing time: " + (endTime - startTime) + "ms");
    return result;
  }

  private static Set<String> findAds(String[] lines) {
    Log.d(TAG, "Executing Primary Strategy: Filename/Path Analysis...");
    List<String> allSegments = new ArrayList<>();
    for (String line : lines) {
      String trimmedLine = line.trim();
      if (!trimmedLine.startsWith("#") && trimmedLine.endsWith(".ts")) {
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
      return new HashSet<>();
    }
    int minSize = Integer.MAX_VALUE;
    for (List<String> block : blocks) {
      if (block.size() < minSize) {
        minSize = block.size();
      }
    }
    if (minSize == 0) {
      return new HashSet<>();
    }
    int minorityBlockCount = 0;
    Set<String> adSegments = new HashSet<>();
    for (List<String> block : blocks) {
      if (block.size() == minSize) {
        minorityBlockCount++;
        adSegments.addAll(block);
      }
    }
    double totalDurationMinutes = getTotalDurationInMinutes(lines);
    int minorityCountThreshold = getMinorityCountThreshold(totalDurationMinutes);
    Log.d(TAG, "Total duration is " + String.format(Locale.getDefault(), "%.2f", totalDurationMinutes) + " minutes. Ad block threshold is " + minorityCountThreshold + ".");
    if (minorityBlockCount > 0 && minorityBlockCount <= minorityCountThreshold) {
      Log.d(TAG, "Discontinuity Analysis: Found " + minorityBlockCount + " minority block(s) with size " + minSize + ". This is within the threshold of " + minorityCountThreshold + ".");
      return adSegments;
    } else {
      Log.d(TAG, "Discontinuity Analysis: Found " + minorityBlockCount + " minority blocks. Count exceeds threshold of " + minorityCountThreshold + ". Result is ambiguous, ignoring.");
      return new HashSet<>();
    }
  }

  private static double getTotalDurationInMinutes(String[] lines) {
    BigDecimal totalSeconds = BigDecimal.ZERO;
    for (String line : lines) {
      if (line.startsWith(TAG_DURATION)) {
        Matcher matcher = REGEX_DURATION.matcher(line);
        if (matcher.find()) {
          try {
            totalSeconds = totalSeconds.add(new BigDecimal(matcher.group(1)));
          } catch (Exception ignored) {
          }
        }
      }
    }
    return totalSeconds.divide(new BigDecimal("60"), 2, RoundingMode.HALF_UP).doubleValue();
  }

  private static int getMinorityCountThreshold(double totalMinutes) {
    if (totalMinutes <= 30) {
      return 2;
    } else if (totalMinutes <= 60) {
      return 3;
    } else if (totalMinutes <= 90) {
      return 4;
    } else {
      return 5;
    }
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
      } else if (!trimmedLine.startsWith("#") && trimmedLine.endsWith(".ts")) {
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
      return new HashSet<>();
    }
    Map<String, List<String>> structuralGroups = new HashMap<>();
    for (String segment : allSegments) {
      String identifier = getStructuralIdentifier(segment);
      if (!structuralGroups.containsKey(identifier)) {
        structuralGroups.put(identifier, new ArrayList<>());
      }
      structuralGroups.get(identifier).add(segment);
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
    Map.Entry<String, List<String>> minEntry = null;
    for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
      if (minEntry == null || entry.getValue().size() < minEntry.getValue().size()) {
        minEntry = entry;
      }
    }
    if (minEntry != null && groups.size() > 1) {
      return new HashSet<>(minEntry.getValue());
    }
    return new HashSet<>();
  }

  private static Set<String> findAdsByPrefixAnalysis(List<String> segments) {
    int optimalPrefixLength = findOptimalPrefixLength(segments);
    if (optimalPrefixLength == -1) {
      return new HashSet<>();
    }
    Map<String, Integer> identifierCounts = groupSegmentsByIdentifier(segments, optimalPrefixLength);
    if (identifierCounts.size() <= 1 || identifierCounts.size() > REASONABLE_GROUP_LIMIT) {
      return new HashSet<>();
    }
    Map.Entry<String, Integer> maxEntry = null;
    for (Map.Entry<String, Integer> entry : identifierCounts.entrySet()) {
      if (maxEntry == null || entry.getValue().compareTo(maxEntry.getValue()) > 0) {
        maxEntry = entry;
      }
    }
    String mainContentIdentifier = (maxEntry != null) ? maxEntry.getKey() : "";
    Set<String> adSegments = new HashSet<>();
    for (String segment : segments) {
      if (!getPrefixIdentifier(segment, optimalPrefixLength).equals(mainContentIdentifier)) {
        adSegments.add(segment);
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
      Map<String, Integer> groups = groupSegmentsByIdentifier(segments, length);
      if (groups.size() > 1 && groups.size() <= REASONABLE_GROUP_LIMIT) {
        int maxGroupSize = 0;
        for (Integer count : groups.values()) {
          if (count > maxGroupSize) {
            maxGroupSize = count;
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

  private static Map<String, Integer> groupSegmentsByIdentifier(List<String> allSegments, int prefixLength) {
    Map<String, Integer> identifierCounts = new HashMap<>();
    for (String segment : allSegments) {
      String identifier = getPrefixIdentifier(segment, prefixLength);
      Integer count = identifierCounts.get(identifier);
      identifierCounts.put(identifier, (count == null) ? 1 : count + 1);
    }
    return identifierCounts;
  }

  private static String getPrefixIdentifier(String segmentUrl, int prefixLength) {
    return segmentUrl.length() > prefixLength ? segmentUrl.substring(0, prefixLength) : segmentUrl;
  }

  private static String rebuildM3u8(String[] lines, Set<String> adSegments) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty()) {
        continue;
      }
      if (line.startsWith(TAG_DURATION)) {
        if (i + 1 < lines.length) {
          String nextLine = lines[i + 1].trim();
          if (adSegments.contains(nextLine)) {
            i++;
            continue;
          }
        }
      } else if (adSegments.contains(line)) {
        continue;
      }
      builder.append(line).append("\n");
    }
    return builder.toString();
  }
}
