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
    private final int[] quotedPrefix;
    private final boolean[] providerRemovedControls;
    private final int[] providerRemovedControlPrefix;
    private final int[] loneCarriageReturnPrefix;
    private final int[] malformedDelimiterPrefix;
    private final int[] delimiterDepth;
    private final int[] delimiterOwners;
    private final int[] quoteOwners;
    private final int[] quoteContentStarts;
    private final int[] quoteContentEnds;
    private final int[] quoteEnvelopeEnds;
    private final int[] sentenceEnds;
    private final int[] sentenceStarts;
    private final String masked;
    private final String maskedLower;

    private RequestLexicalView(String request) {
        this.request = request == null ? "" : request;
        QuoteAnalysis quoteAnalysis = quoteAnalysis(this.request);
        this.quoted = quoteAnalysis.mask();
        this.quotedPrefix = prefixCounts(this.quoted);
        this.providerRemovedControls = new boolean[this.request.length()];
        this.quoteOwners = quoteAnalysis.owners();
        this.quoteContentStarts = quoteAnalysis.contentStarts();
        this.quoteContentEnds = quoteAnalysis.contentEnds();
        this.quoteEnvelopeEnds = quoteAnalysis.envelopeEnds();
        boolean[] loneCarriageReturns = new boolean[this.request.length()];
        StringBuilder masked = new StringBuilder(this.request.length());
        for (int i = 0; i < this.request.length(); i++) {
            char value = this.request.charAt(i);
            boolean providerRemovedControl = providerRemovesControlAt(
                    this.request,
                    i);
            providerRemovedControls[i] = providerRemovedControl;
            loneCarriageReturns[i] = value == '\r'
                    && (i + 1 >= this.request.length()
                            || this.request.charAt(i + 1) != '\n');
            masked.append(quoted[i] || providerRemovedControl ? ' ' : value);
        }
        this.providerRemovedControlPrefix = prefixCounts(
                this.providerRemovedControls);
        this.loneCarriageReturnPrefix = prefixCounts(loneCarriageReturns);
        DelimiterAnalysis delimiterAnalysis = delimiterAnalysis(
                this.request,
                this.quoted,
                this.providerRemovedControls);
        this.delimiterDepth = delimiterAnalysis.depth();
        this.delimiterOwners = delimiterAnalysis.owners();
        this.malformedDelimiterPrefix = prefixCounts(
                delimiterAnalysis.malformed());
        this.masked = masked.toString();
        this.maskedLower = foldCasePreservingLength(this.masked);
        this.sentenceEnds = sentenceEnds();
        this.sentenceStarts = sentenceStarts();
    }

    static RequestLexicalView of(String canonicalRequest) {
        return new RequestLexicalView(canonicalRequest);
    }

    String request() {
        return request;
    }

    boolean containsIncompleteQuotedEnvelope() {
        for (int owner : quoteOwners) {
            if (owner >= 0 && (quoteContentStarts[owner] < 0 || quoteContentEnds[owner] < 0
                    || quoteEnvelopeEnds[owner] < 0)) return true;
        }
        return false;
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
        return quotedPrefix[safeEnd] == quotedPrefix[safeStart];
    }

    /**
     * Returns true only for bytes outside paired (), [], and {} regions.
     * Quote bytes are ignored while depth is calculated, so punctuation in a
     * quoted literal cannot change structural ownership.
     */
    boolean isTopLevel(int offset) {
        return offset >= 0
                && offset < delimiterDepth.length
                && delimiterDepth[offset] == 0;
    }

    /**
     * Returns the exact opening-delimiter offset of the innermost structural
     * container at {@code offset}, or {@code -1} at top level. Depth alone is
     * insufficient because adjacent sibling containers have the same depth
     * but do not share lexical authority.
     */
    int delimiterOwner(int offset) {
        return offset >= 0 && offset < delimiterOwners.length
                ? delimiterOwners[offset]
                : -1;
    }

    boolean containsProviderRemovedControl(int start, int end) {
        int safeStart = Math.max(0, start);
        int safeEnd = Math.min(request.length(), Math.max(safeStart, end));
        return providerRemovedControlPrefix[safeEnd]
                > providerRemovedControlPrefix[safeStart];
    }

    boolean containsLoneCarriageReturn(int start, int end) {
        int safeStart = Math.max(0, start);
        int safeEnd = Math.min(request.length(), Math.max(safeStart, end));
        return loneCarriageReturnPrefix[safeEnd]
                > loneCarriageReturnPrefix[safeStart];
    }

    boolean containsMalformedDelimiter(int start, int end) {
        int safeStart = Math.max(0, start);
        int safeEnd = Math.min(request.length(), Math.max(safeStart, end));
        return malformedDelimiterPrefix[safeEnd]
                > malformedDelimiterPrefix[safeStart];
    }

    private static int[] prefixCounts(boolean[] values) {
        int[] prefix = new int[values.length + 1];
        for (int i = 0; i < values.length; i++) {
            prefix[i + 1] = prefix[i] + (values[i] ? 1 : 0);
        }
        return prefix;
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
        if (request.isEmpty()) return 0;
        int safeStart = Math.max(0, Math.min(start, request.length()));
        return safeStart == request.length()
                ? request.length()
                : sentenceEnds[safeStart];
    }

    private int[] sentenceEnds() {
        int[] indexed = new int[request.length()];
        int nextEnd = request.length();
        for (int index = request.length() - 1; index >= 0; index--) {
            if (isSentenceBoundaryAt(index)) nextEnd = index + 1;
            indexed[index] = nextEnd;
        }
        return indexed;
    }

    private int[] sentenceStarts() {
        int[] indexed = new int[request.length() + 1];
        int lastEnd = 0;
        for (int index = 0; index < request.length(); index++) {
            if (isSentenceBoundaryAt(index)) lastEnd = index + 1;
            indexed[index + 1] = lastEnd;
        }
        return indexed;
    }

    int sentenceStartBefore(int lowerBound, int end) {
        int safeLower = Math.max(0, Math.min(lowerBound, request.length()));
        int safeEnd = Math.min(
                request.length(),
                Math.max(safeLower, end));
        return Math.max(safeLower, sentenceStarts[safeEnd]);
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
        if (start <= 0 || end <= start || start >= request.length()) return null;
        int owner = quoteOwners[start];
        return owner >= 0
                        && quoteContentStarts[owner] == start
                        && quoteContentEnds[owner] == end
                        && quoteEnvelopeEnds[owner] > end
                ? new Span(owner, quoteEnvelopeEnds[owner])
                : null;
    }

    Span exactQuotedContentStartingAt(int envelopeStart, int endLimit) {
        if (envelopeStart < 0
                || envelopeStart >= request.length()
                || quoteOwners[envelopeStart] != envelopeStart) {
            return null;
        }
        int contentStart = quoteContentStarts[envelopeStart];
        int contentEnd = quoteContentEnds[envelopeStart];
        if (contentStart < 0 || contentEnd <= contentStart) return null;
        int safeLimit = Math.min(
                request.length(),
                Math.max(contentStart, endLimit));
        return contentEnd < safeLimit
                ? new Span(contentStart, contentEnd)
                : null;
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
        int envelopeEnd = cursor + 1;
        int contentStart = quoteContentStarts[owner];
        int contentEnd = quoteContentEnds[owner];
        if (contentEnd <= contentStart) return null;
        return quoteEnvelopeEnds[owner] == envelopeEnd
                ? new Span(owner, envelopeEnd)
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
        int[] contentStarts = new int[request.length()];
        int[] contentEnds = new int[request.length()];
        int[] envelopeEnds = new int[request.length()];
        java.util.Arrays.fill(owners, -1);
        java.util.Arrays.fill(contentStarts, -1);
        java.util.Arrays.fill(contentEnds, -1);
        java.util.Arrays.fill(envelopeEnds, -1);
        Deque<QuoteFrame> stack = new ArrayDeque<>();
        boolean escaped = false;
        for (int i = 0; i < request.length(); i++) {
            char value = request.charAt(i);
            if (!stack.isEmpty()) {
                QuoteFrame frame = stack.peek();
                if (frame.delimiterLength() > 1 || frame.close() == '`') {
                    int runLength = value == frame.close()
                            ? repeatedRunLength(request, i, frame.close())
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
                        contentStarts[frame.start()] = frame.start()
                                + frame.delimiterLength();
                        contentEnds[frame.start()] = i;
                        envelopeEnds[frame.start()] = i
                                + frame.delimiterLength();
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
                    contentStarts[frame.start()] = frame.start() + 1;
                    contentEnds[frame.start()] = i;
                    envelopeEnds[frame.start()] = i + 1;
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
            if (value == '~'
                    && repeatedRunLength(request, i, '~') >= 3) {
                int delimiterLength = repeatedRunLength(request, i, '~');
                for (int j = i; j < i + delimiterLength; j++) {
                    mask[j] = true;
                    owners[j] = i;
                }
                stack.push(new QuoteFrame('~', false, i, delimiterLength));
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
        return new QuoteAnalysis(
                mask,
                owners,
                contentStarts,
                contentEnds,
                envelopeEnds);
    }

    private static DelimiterAnalysis delimiterAnalysis(
            String request,
            boolean[] quoted,
            boolean[] providerRemovedControls
    ) {
        int[] depthAt = new int[request.length()];
        int[] ownerAt = new int[request.length()];
        java.util.Arrays.fill(ownerAt, -1);
        boolean[] malformed = new boolean[request.length()];
        Deque<DelimiterFrame> delimiters = new ArrayDeque<>();
        for (int index = 0; index < request.length(); index++) {
            char value = request.charAt(index);
            if (quoted[index] || providerRemovedControls[index]) {
                depthAt[index] = delimiters.size();
                ownerAt[index] = delimiters.isEmpty()
                        ? -1
                        : delimiters.peek().start();
                continue;
            }
            if (matchesDelimiter(delimiters.peek(), value)) {
                delimiters.pop();
            } else if (closingDelimiter(value)) {
                malformed[index] = true;
            }
            depthAt[index] = delimiters.size();
            ownerAt[index] = delimiters.isEmpty()
                    ? -1
                    : delimiters.peek().start();
            if (value == '(' || value == '[' || value == '{') {
                delimiters.push(new DelimiterFrame(value, index));
            }
        }
        for (DelimiterFrame unclosed : delimiters) {
            malformed[unclosed.start()] = true;
        }
        return new DelimiterAnalysis(depthAt, ownerAt, malformed);
    }

    private static boolean matchesDelimiter(DelimiterFrame frame, char close) {
        Character open = frame == null ? null : frame.open();
        return open != null
                && (open == '(' && close == ')'
                        || open == '[' && close == ']'
                        || open == '{' && close == '}');
    }

    private static boolean closingDelimiter(char value) {
        return value == ')' || value == ']' || value == '}';
    }

    private record DelimiterFrame(char open, int start) {}

    private record DelimiterAnalysis(
            int[] depth,
            int[] owners,
            boolean[] malformed
    ) {}

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

    private record QuoteAnalysis(
            boolean[] mask,
            int[] owners,
            int[] contentStarts,
            int[] contentEnds,
            int[] envelopeEnds
    ) {}
}
