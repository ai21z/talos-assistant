package dev.talos.core.llm;

import dev.talos.core.tool.ToolProtocolText;
import dev.talos.core.util.Sanitize;

/**
 * Bounded provider-EOS presentation exception for structurally closed Markdown
 * code. Code payloads are inert text, regardless of tag names, attributes or
 * fence language. This helper does not render Markdown, authenticate provider
 * receipts, or grant execution authority; callers retain those responsibilities.
 *
 * <p>The supported subset consists of single-line backtick spans with equal
 * delimiter runs, and LF-delimited backtick/tilde fences opening at column zero.
 * Fence closers allow zero to three leading spaces, use the same character,
 * at least the opening run length, and only spaces/tabs afterward. Runs are
 * limited to 32 characters;
 * fence info strings to 256 characters (without backticks in backtick fences).
 * Escaped opening backticks, unfinished boundaries, and other uncertain forms
 * retain the supplied fallback. Block containers and multiline inline spans
 * are not interpreted as a broader Markdown grammar.
 *
 * <p>At most 256 code regions and 65,536 UTF-16 source characters are examined.
 * Each payload is limited to 4,096 characters, excluding fence delimiters/info
 * and the required LF immediately before a fence closer. Other payload padding
 * counts. Controls, think-tag removal/escape decoding and tool-protocol checks
 * apply to the complete, unmasked source. Only HTML sanitation is exempted in
 * code. Outside code, sanitation must be a no-op and tag-like fragments are
 * rejected to avoid pairing across code boundaries or entering raw HTML blocks.
 * Any rejected condition returns the original fallback without partial repair.
 */
public final class ProviderInlineHtmlLiteralProjection {
    private static final int MAX_SOURCE_CHARS = 65_536;
    private static final int MAX_LITERAL_CHARS = 4_096;
    private static final int MAX_CODE_REGIONS = 256;
    private static final int MAX_DELIMITER_CHARS = 32;
    private static final int MAX_FENCE_INFO_CHARS = 256;

    private ProviderInlineHtmlLiteralProjection() {}

    public static String preserveIfInert(String source, String fallback) {
        if (source == null || source.equals(fallback) || source.length() > MAX_SOURCE_CHARS) {
            return fallback;
        }
        // These checks deliberately see every code character. Masking a whole
        // literal first would otherwise hide controls, hidden thought, or a
        // protocol-shaped proposal inside arbitrary markup or quoted strings.
        if (containsControl(source)
                || !Sanitize.stripControl(source).equals(source)
                || !Sanitize.stripThinkTags(source).equals(source)) return fallback;
        var proposals = ToolProtocolText.selectExecutableJsonProposals(source);
        if (!proposals.recognizedCandidates().isEmpty()
                || !proposals.suppressionOnlyEnvelopes().isEmpty()
                || !ToolProtocolText.stripToolCallsPreservingLayout(source).equals(source)) {
            return fallback;
        }

        StringBuilder outside = new StringBuilder(source);
        int regions = 0;
        for (int cursor = 0; cursor < source.length(); ) {
            char value = source.charAt(cursor);
            if (value != '`' && value != '~') {
                cursor++;
                continue;
            }
            int runEnd = runEnd(source, cursor, value);
            int runLength = runEnd - cursor;
            int indent = fenceIndent(source, cursor);
            // An indented opener may belong to a list whose continuation
            // indentation this deliberately small parser cannot establish.
            if (runLength >= 3 && indent > 0) return fallback;
            boolean fenceStart = runLength >= 3 && indent == 0;
            if (value == '~' && !fenceStart) {
                cursor = runEnd;
                continue;
            }
            if (runLength > MAX_DELIMITER_CHARS || escaped(source, cursor)) return fallback;
            CodeRegion region = fenceStart
                    ? closedFence(source, cursor, runEnd)
                    : closedInlineSpan(source, runEnd, runLength);
            if (region == null || ++regions > MAX_CODE_REGIONS) return fallback;
            for (int index = region.bodyStart(); index < region.bodyEnd(); index++) {
                // Keep line boundaries so the outside sanitizer cannot acquire
                // an accidental adjacency or lose structural fence framing.
                if (source.charAt(index) != '\n') outside.setCharAt(index, ' ');
            }
            cursor = region.endExclusive();
        }
        if (regions == 0) return fallback;
        String remaining = outside.toString();
        if (containsTagLikeFragment(remaining)
                || !Sanitize.sanitizeForOutputPreservingToolCalls(remaining).equals(remaining)) {
            return fallback;
        }
        return source;
    }

    private static CodeRegion closedInlineSpan(String source, int bodyStart, int delimiterLength) {
        for (int cursor = bodyStart; cursor < source.length(); ) {
            if (cursor - bodyStart > MAX_LITERAL_CHARS || source.charAt(cursor) == '\n') return null;
            if (source.charAt(cursor) != '`') {
                cursor++;
                continue;
            }
            int end = runEnd(source, cursor, '`');
            if (end - cursor == delimiterLength) {
                return new CodeRegion(bodyStart, cursor, end);
            }
            // Backslashes and differently sized backtick runs are literal
            // payload inside a Markdown code span, not escape/nesting syntax.
            cursor = end;
        }
        return null;
    }

    private static CodeRegion closedFence(String source, int start, int openingRunEnd) {
        int openingLineEnd = source.indexOf('\n', openingRunEnd);
        if (openingLineEnd < 0 || openingLineEnd - openingRunEnd > MAX_FENCE_INFO_CHARS) return null;
        char marker = source.charAt(start);
        if (marker == '`' && source.substring(openingRunEnd, openingLineEnd).indexOf('`') >= 0) {
            return null;
        }
        int bodyStart = openingLineEnd + 1;
        int openingLength = openingRunEnd - start;
        for (int lineStart = bodyStart; lineStart < source.length(); ) {
            // The LF separating the payload from its closer is framing.
            int bodyEnd = lineStart > bodyStart ? lineStart - 1 : lineStart;
            if (bodyEnd - bodyStart > MAX_LITERAL_CHARS) return null;
            int markerStart = lineStart;
            while (markerStart < source.length() && source.charAt(markerStart) == ' '
                    && markerStart - lineStart < 3) markerStart++;
            int lineEnd = source.indexOf('\n', markerStart);
            if (lineEnd < 0) lineEnd = source.length();
            if (markerStart < lineEnd && source.charAt(markerStart) == marker) {
                int closingRunEnd = runEnd(source, markerStart, marker);
                if (closingRunEnd - markerStart >= openingLength
                        && onlyHorizontalSpace(source, closingRunEnd, lineEnd)) {
                    if (closingRunEnd - markerStart > MAX_DELIMITER_CHARS) return null;
                    return new CodeRegion(bodyStart, bodyEnd,
                            lineEnd < source.length() ? lineEnd + 1 : lineEnd);
                }
            }
            lineStart = lineEnd + 1;
        }
        return null;
    }

    private static int runEnd(String source, int start, char marker) {
        int end = start;
        while (end < source.length() && source.charAt(end) == marker) end++;
        return end;
    }

    private static int fenceIndent(String source, int start) {
        int cursor = start;
        while (cursor > 0 && source.charAt(cursor - 1) == ' ' && start - cursor < 3) cursor--;
        return cursor == 0 || source.charAt(cursor - 1) == '\n' ? start - cursor : -1;
    }

    private static boolean escaped(String source, int start) {
        int cursor = start;
        while (cursor > 0 && source.charAt(cursor - 1) == '\\') cursor--;
        return (start - cursor) % 2 != 0;
    }

    private static boolean onlyHorizontalSpace(String source, int start, int end) {
        for (int index = start; index < end; index++) {
            if (source.charAt(index) != ' ' && source.charAt(index) != '\t') return false;
        }
        return true;
    }

    private static boolean containsControl(String source) {
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value != '\t' && value != '\n' && Character.isISOControl(value)) return true;
        }
        return false;
    }

    private static boolean containsTagLikeFragment(String source) {
        for (int cursor = 0; cursor < source.length(); cursor++) {
            if (source.charAt(cursor) != '<') continue;
            int next = cursor + 1;
            while (next < source.length() && Character.isWhitespace(source.charAt(next))) next++;
            if (next == source.length()) continue;
            char value = source.charAt(next);
            if (value == '/' || value == '!' || value == '?'
                    || value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z') return true;
        }
        return false;
    }

    private record CodeRegion(int bodyStart, int bodyEnd, int endExclusive) {}
}
