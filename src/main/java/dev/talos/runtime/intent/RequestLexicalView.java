package dev.talos.runtime.intent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;

/**
 * Quote-aware, offset-preserving view of one canonical stored request.
 *
 * <p>The view never rewrites request bytes. Consumers may inspect a masked
 * copy for matching, but every returned offset is an index into
 * {@link #request()}.
 */
final class RequestLexicalView {

    private static final Set<String> NON_TERMINAL_ABBREVIATIONS = Set.of(
            "approx.",
            "cf.",
            "dept.",
            "dr.",
            "e.g.",
            "etc.",
            "fig.",
            "i.e.",
            "incl.",
            "jr.",
            "misc.",
            "mr.",
            "mrs.",
            "ms.",
            "no.",
            "prof.",
            "sr.",
            "st.",
            "vs.");

    private static final Map<Character, Character> PAIRED_QUOTES = Map.ofEntries(
            Map.entry('"', '"'),
            Map.entry('`', '`'),
            Map.entry('\u201c', '\u201d'),
            Map.entry('\u2018', '\u2019'),
            Map.entry('\u00ab', '\u00bb'),
            Map.entry('\u2039', '\u203a'),
            Map.entry('\u300c', '\u300d'),
            Map.entry('\u300e', '\u300f'),
            Map.entry('\u3010', '\u3011'),
            Map.entry('\u301d', '\u301e'),
            Map.entry('\u275d', '\u275e'),
            Map.entry('\uff02', '\uff02'));

    private final String request;
    private final boolean[] quoted;
    private final boolean[] providerRemovedControls;
    private final int[] quoteOwners;
    private final String masked;
    private final String maskedLower;

    private RequestLexicalView(String request) {
        this.request = request == null ? "" : request;
        QuoteAnalysis quoteAnalysis = quoteAnalysis(this.request);
        this.quoted = quoteAnalysis.mask();
        this.providerRemovedControls = new boolean[this.request.length()];
        this.quoteOwners = quoteAnalysis.owners();
        StringBuilder masked = new StringBuilder(this.request.length());
        for (int i = 0; i < this.request.length(); i++) {
            char value = this.request.charAt(i);
            boolean providerRemovedControl = providerRemovesControlAt(
                    this.request,
                    i);
            providerRemovedControls[i] = providerRemovedControl;
            masked.append(quoted[i] || providerRemovedControl ? ' ' : value);
        }
        this.masked = masked.toString();
        this.maskedLower = foldCasePreservingLength(this.masked);
    }

    static RequestLexicalView of(String canonicalRequest) {
        return new RequestLexicalView(canonicalRequest);
    }

    String request() {
        return request;
    }

    String maskedLower() {
        return maskedLower;
    }

    String masked() {
        return masked;
    }

    boolean isQuoted(int offset) {
        return offset >= 0 && offset < quoted.length && quoted[offset];
    }

    boolean isUnquotedRange(int start, int end) {
        int safeStart = Math.max(0, start);
        int safeEnd = Math.min(request.length(), Math.max(safeStart, end));
        for (int i = safeStart; i < safeEnd; i++) {
            if (quoted[i]) return false;
        }
        return true;
    }

    boolean containsProviderRemovedControl(int start, int end) {
        int safeStart = Math.max(0, start);
        int safeEnd = Math.min(request.length(), Math.max(safeStart, end));
        for (int i = safeStart; i < safeEnd; i++) {
            if (providerRemovedControls[i]) return true;
        }
        return false;
    }

    boolean containsLoneCarriageReturn(int start, int end) {
        int safeStart = Math.max(0, start);
        int safeEnd = Math.min(request.length(), Math.max(safeStart, end));
        for (int i = safeStart; i < safeEnd; i++) {
            if (request.charAt(i) == '\r'
                    && (i + 1 >= request.length()
                            || request.charAt(i + 1) != '\n')) {
                return true;
            }
        }
        return false;
    }

    private static boolean providerRemovesControlAt(String value, int index) {
        char current = value.charAt(index);
        if (current == '\r'
                && index + 1 < value.length()
                && value.charAt(index + 1) == '\n') {
            return false;
        }
        return current <= '\u0008'
                || current >= '\u000B' && current <= '\u001F'
                || current == '\u007F';
    }

    int firstUnquotedIgnoreCase(String needle, int from, int to) {
        if (needle == null || needle.isEmpty()) return -1;
        String lowerNeedle = foldCasePreservingLength(needle);
        int safeFrom = Math.max(0, from);
        int safeTo = Math.min(request.length(), Math.max(safeFrom, to));
        int candidate = maskedLower.indexOf(lowerNeedle, safeFrom);
        while (candidate >= 0 && candidate + lowerNeedle.length() <= safeTo) {
            if (isUnquotedRange(candidate, candidate + lowerNeedle.length())) {
                return candidate;
            }
            candidate = maskedLower.indexOf(lowerNeedle, candidate + 1);
        }
        return -1;
    }

    int sentenceEndAfter(int start) {
        int safeStart = Math.max(0, start);
        for (int i = safeStart; i < request.length(); i++) {
            if (quoted[i]) continue;
            char value = request.charAt(i);
            if ((value == '.' || value == '!' || value == '?' || value == '\u2026')
                    && isSentenceBoundaryAt(i)) {
                return i + 1;
            }
        }
        return request.length();
    }

    boolean isSentenceBoundaryAt(int index) {
        if (index < 0 || index >= request.length() || quoted[index]) {
            return false;
        }
        char value = request.charAt(index);
        if (value != '.' && value != '!' && value != '?' && value != '\u2026') {
            return false;
        }
        if (index + 1 < request.length()
                && !Character.isWhitespace(request.charAt(index + 1))) {
            return false;
        }
        return value != '.' || !isNonTerminalAbbreviationEndingAt(index);
    }

    String substring(Span span) {
        return request.substring(span.start(), span.end());
    }

    Span trimmedSpan(int start, int end) {
        int safeStart = Math.max(0, Math.min(start, request.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, request.length()));
        while (safeStart < safeEnd && Character.isWhitespace(request.charAt(safeStart))) {
            safeStart++;
        }
        while (safeEnd > safeStart && Character.isWhitespace(request.charAt(safeEnd - 1))) {
            safeEnd--;
        }
        return new Span(safeStart, safeEnd);
    }

    boolean isExactQuotedLiteral(int start, int end) {
        return exactQuotedEnvelope(start, end) != null;
    }

    Span exactQuotedEnvelope(int start, int end) {
        if (start <= 0 || end <= start || end >= request.length()) return null;
        int owner = quoteOwners[start];
        if (owner >= 0 && request.charAt(owner) == '`') {
            int delimiterLength = repeatedRunLength(request, owner, '`');
            if (delimiterLength <= 0
                    || start != owner + delimiterLength
                    || end + delimiterLength > request.length()
                    || repeatedRunLength(request, end, '`') < delimiterLength) {
                return null;
            }
            for (int i = owner; i < end + delimiterLength; i++) {
                if (!quoted[i] || quoteOwners[i] != owner) return null;
            }
            return new Span(owner, end + delimiterLength);
        }
        for (int i = start; i < end; i++) {
            if (!quoted[i]) return null;
        }
        char open = request.charAt(start - 1);
        char close = request.charAt(end);
        Character expectedClose = open == '\''
                ? Character.valueOf('\'')
                : PAIRED_QUOTES.get(open);
        return expectedClose != null
                && close == expectedClose
                && quoted[start - 1]
                && quoted[end]
                && quoteOwners[start - 1] == start - 1
                && quoteOwners[start] == start - 1
                && quoteOwners[end] == start - 1
                ? new Span(start - 1, end + 1)
                : null;
    }

    Span exactQuotedContentStartingAt(int envelopeStart, int endLimit) {
        if (envelopeStart < 0
                || envelopeStart >= request.length()
                || !quoted[envelopeStart]) {
            return null;
        }
        int delimiterLength = request.charAt(envelopeStart) == '`'
                ? repeatedRunLength(request, envelopeStart, '`')
                : 1;
        int contentStart = envelopeStart + delimiterLength;
        int safeLimit = Math.min(
                request.length(),
                Math.max(contentStart, endLimit));
        for (int contentEnd = contentStart + 1;
                contentEnd < safeLimit;
                contentEnd++) {
            Span envelope = exactQuotedEnvelope(contentStart, contentEnd);
            if (envelope != null && envelope.start() == envelopeStart) {
                return new Span(contentStart, contentEnd);
            }
        }
        return null;
    }

    Span completedQuotedEnvelopeImmediatelyBefore(
            int delimiterStart,
            int lowerBound
    ) {
        int safeLower = Math.max(0, Math.min(lowerBound, request.length()));
        int cursor = Math.min(request.length(), Math.max(0, delimiterStart)) - 1;
        while (cursor >= safeLower && Character.isWhitespace(request.charAt(cursor))) {
            cursor--;
        }
        if (cursor < safeLower || !quoted[cursor]) return null;
        int owner = quoteOwners[cursor];
        if (owner < safeLower || owner >= cursor) return null;

        int delimiterLength = request.charAt(owner) == '`'
                ? repeatedRunLength(request, owner, '`')
                : 1;
        int envelopeEnd = cursor + 1;
        int contentStart = owner + delimiterLength;
        int contentEnd = envelopeEnd - delimiterLength;
        if (contentEnd <= contentStart) return null;
        Span envelope = exactQuotedEnvelope(contentStart, contentEnd);
        return envelope != null
                        && envelope.start() == owner
                        && envelope.end() == envelopeEnd
                ? envelope
                : null;
    }

    static String foldCasePreservingLength(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        StringBuilder folded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            folded.append(Character.toLowerCase(value.charAt(i)));
        }
        return folded.toString();
    }

    private boolean isNonTerminalAbbreviationEndingAt(int index) {
        int next = index + 1;
        while (next < request.length()
                && Character.isWhitespace(request.charAt(next))) {
            next++;
        }
        if (next >= request.length()) return false;

        int start = index;
        while (start > 0) {
            char prior = request.charAt(start - 1);
            if (prior != '.' && !Character.isLetter(prior)) break;
            start--;
        }
        if (start >= index || index - start + 1 > 24) return false;
        String token = foldCasePreservingLength(
                request.substring(start, index + 1));
        return NON_TERMINAL_ABBREVIATIONS.contains(token)
                || isMultiInitialAbbreviation(token);
    }

    private static boolean isMultiInitialAbbreviation(String token) {
        if (token == null || token.length() < 4 || token.length() > 24) {
            return false;
        }
        int components = 0;
        int letters = 0;
        for (int i = 0; i < token.length(); i++) {
            char value = token.charAt(i);
            if (Character.isLetter(value)) {
                letters++;
                if (letters > 1) return false;
                continue;
            }
            if (value != '.' || letters != 1) return false;
            components++;
            letters = 0;
        }
        return components >= 2 && letters == 0;
    }

    private static QuoteAnalysis quoteAnalysis(String request) {
        boolean[] mask = new boolean[request.length()];
        int[] owners = new int[request.length()];
        java.util.Arrays.fill(owners, -1);
        Deque<QuoteFrame> stack = new ArrayDeque<>();
        boolean escaped = false;
        for (int i = 0; i < request.length(); i++) {
            char value = request.charAt(i);
            if (!stack.isEmpty()) {
                QuoteFrame frame = stack.peek();
                if (frame.delimiterLength() > 1 || frame.close() == '`') {
                    int runLength = value == '`'
                            ? repeatedRunLength(request, i, '`')
                            : 0;
                    int consumed = runLength >= frame.delimiterLength()
                            ? frame.delimiterLength()
                            : Math.max(1, runLength);
                    for (int j = i;
                            j < request.length() && j < i + consumed;
                            j++) {
                        mask[j] = true;
                        owners[j] = frame.start();
                    }
                    if (runLength >= frame.delimiterLength()) {
                        stack.pop();
                    }
                    i += consumed - 1;
                    continue;
                }
                mask[i] = true;
                owners[i] = frame.start();
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (value == '\\' && frame.escapeAware()) {
                    escaped = true;
                    continue;
                }
                if (apostropheLike(value)
                        && frame.close() == value
                        && apostropheInsideWord(request, i)) {
                    continue;
                }
                if (value == frame.close()) {
                    stack.pop();
                }
                continue;
            }

            if (value == '\'' && apostropheInWordOrPossessive(request, i)) {
                continue;
            }
            if (value == '`') {
                int delimiterLength = repeatedRunLength(request, i, '`');
                for (int j = i; j < i + delimiterLength; j++) {
                    mask[j] = true;
                    owners[j] = i;
                }
                stack.push(new QuoteFrame('`', false, i, delimiterLength));
                i += delimiterLength - 1;
                continue;
            }
            Character close = value == '\''
                    ? Character.valueOf('\'')
                    : PAIRED_QUOTES.get(value);
            if (close == null) continue;
            mask[i] = true;
            owners[i] = i;
            stack.push(new QuoteFrame(
                    close,
                    value == '"' || value == '\'',
                    i,
                    1));
        }
        if (!stack.isEmpty()) {
            int firstMasked = stack.stream()
                    .mapToInt(QuoteFrame::start)
                    .min()
                    .orElse(mask.length);
            for (int i = firstMasked; i < mask.length; i++) {
                mask[i] = true;
                if (owners[i] < 0) owners[i] = firstMasked;
            }
        }
        return new QuoteAnalysis(mask, owners);
    }

    private static boolean apostropheInWordOrPossessive(String request, int index) {
        return index > 0
                && Character.isLetterOrDigit(request.charAt(index - 1));
    }

    private static boolean apostropheInsideWord(String request, int index) {
        return index > 0
                && index + 1 < request.length()
                && Character.isLetterOrDigit(request.charAt(index - 1))
                && Character.isLetterOrDigit(request.charAt(index + 1));
    }

    private static boolean apostropheLike(char value) {
        return value == '\'' || value == '\u2019';
    }

    private static int repeatedRunLength(String value, int start, char expected) {
        if (value == null || start < 0 || start >= value.length()) return 0;
        int end = start;
        while (end < value.length() && value.charAt(end) == expected) end++;
        return end - start;
    }

    record Span(int start, int end) {
        Span {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("invalid source span");
            }
        }
    }

    private record QuoteFrame(
            char close,
            boolean escapeAware,
            int start,
            int delimiterLength
    ) {}

    private record QuoteAnalysis(boolean[] mask, int[] owners) {}
}
