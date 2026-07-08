package dev.talos.core.util;

import java.util.ArrayList;
import java.util.List;

/** Shared string-replacement matching with CRLF-aware raw offset mapping. */
public final class TextReplacement {
    private TextReplacement() {}

    public record Match(int count, int startRaw, int endRaw, String replacement) { }

    public static Match findUniqueMatch(String content, String oldString, String newString) {
        String source = content == null ? "" : content;
        String needle = oldString == null ? "" : oldString;
        String replacementText = newString == null ? "" : newString;
        if (source.isEmpty() || needle.isEmpty()) {
            return new Match(0, -1, -1, replacementText);
        }

        int rawCount = countOccurrences(source, needle);
        if (rawCount == 1) {
            int start = source.indexOf(needle);
            return new Match(1, start, start + needle.length(), replacementText);
        }
        if (rawCount > 1) {
            return new Match(rawCount, -1, -1, replacementText);
        }

        NormalizedText normalizedContent = normalizeLineEndingsWithRawMap(source);
        String normalizedOld = normalizeLineEndings(needle);
        int normalizedCount = countOccurrences(normalizedContent.text(), normalizedOld);
        if (normalizedCount != 1) {
            return new Match(normalizedCount, -1, -1, replacementText);
        }

        int startNormalized = normalizedContent.text().indexOf(normalizedOld);
        int endNormalized = startNormalized + normalizedOld.length();
        int startRaw = normalizedContent.rawIndexAt(startNormalized);
        int endRaw = normalizedContent.rawIndexAt(endNormalized);
        String matchedRaw = source.substring(startRaw, endRaw);
        String replacement = normalizeLineEndings(
                replacementText,
                preferredLineEnding(matchedRaw, source));
        return new Match(1, startRaw, endRaw, replacement);
    }

    public static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || haystack.isEmpty() || needle.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    public static boolean containsNormalized(String content, String needle) {
        if (content == null || needle == null) return false;
        return normalizeLineEndings(content).contains(normalizeLineEndings(needle));
    }

    public static boolean oldTextRemainsOutsideReplacement(String content, String oldText, String newText) {
        if (content == null || oldText == null || oldText.isEmpty()) return false;
        String normalizedContent = normalizeLineEndings(content);
        String normalizedOld = normalizeLineEndings(oldText);
        String normalizedNew = newText == null ? "" : normalizeLineEndings(newText);
        if (normalizedNew.isEmpty()) return normalizedContent.contains(normalizedOld);

        List<Range> replacementRanges = rangesOf(normalizedContent, normalizedNew);
        for (Range oldRange : rangesOf(normalizedContent, normalizedOld)) {
            if (!isCoveredByAny(oldRange, replacementRanges)) {
                return true;
            }
        }
        return false;
    }

    public static String normalizeLineEndings(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String normalizeLineEndings(String text, String lineEnding) {
        return normalizeLineEndings(text).replace("\n", lineEnding);
    }

    private static String preferredLineEnding(String primary, String fallback) {
        String primaryEnding = dominantLineEnding(primary);
        return primaryEnding.isEmpty() ? dominantLineEnding(fallback, "\n") : primaryEnding;
    }

    private static String dominantLineEnding(String text) {
        return dominantLineEnding(text, "");
    }

    private static String dominantLineEnding(String text, String defaultEnding) {
        int crlf = 0;
        int lf = 0;
        int cr = 0;
        String value = text == null ? "" : text;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\r') {
                if (i + 1 < value.length() && value.charAt(i + 1) == '\n') {
                    crlf++;
                    i++;
                } else {
                    cr++;
                }
            } else if (ch == '\n') {
                lf++;
            }
        }
        if (crlf >= lf && crlf >= cr && crlf > 0) return "\r\n";
        if (lf >= cr && lf > 0) return "\n";
        if (cr > 0) return "\r";
        return defaultEnding;
    }

    private static NormalizedText normalizeLineEndingsWithRawMap(String text) {
        String value = text == null ? "" : text;
        StringBuilder normalized = new StringBuilder(value.length());
        int[] rawByNormalizedOffset = new int[value.length() + 1];
        int normalizedOffset = 0;
        int rawOffset = 0;
        while (rawOffset < value.length()) {
            rawByNormalizedOffset[normalizedOffset] = rawOffset;
            char ch = value.charAt(rawOffset);
            if (ch == '\r') {
                normalized.append('\n');
                if (rawOffset + 1 < value.length() && value.charAt(rawOffset + 1) == '\n') {
                    rawOffset += 2;
                } else {
                    rawOffset++;
                }
            } else {
                normalized.append(ch);
                rawOffset++;
            }
            normalizedOffset++;
        }
        rawByNormalizedOffset[normalizedOffset] = value.length();
        int[] compactMap = java.util.Arrays.copyOf(rawByNormalizedOffset, normalizedOffset + 1);
        return new NormalizedText(normalized.toString(), compactMap);
    }

    private static List<Range> rangesOf(String haystack, String needle) {
        if (haystack == null || needle == null || haystack.isEmpty() || needle.isEmpty()) return List.of();
        List<Range> ranges = new ArrayList<>();
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            ranges.add(new Range(idx, idx + needle.length()));
            idx += needle.length();
        }
        return ranges;
    }

    private static boolean isCoveredByAny(Range candidate, List<Range> containers) {
        for (Range container : containers) {
            if (candidate.start() >= container.start() && candidate.end() <= container.end()) {
                return true;
            }
        }
        return false;
    }

    private record NormalizedText(String text, int[] rawByNormalizedOffset) {
        int rawIndexAt(int normalizedOffset) {
            return rawByNormalizedOffset[normalizedOffset];
        }
    }

    private record Range(int start, int end) { }
}
