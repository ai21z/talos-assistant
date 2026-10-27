package dev.talos.core.llm;

import dev.talos.core.tool.ToolProtocolText;
import dev.talos.core.util.Sanitize;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Bounded, append-only owner for decoded provider text.
 *
 * <p>The raw decoded source is retained until the generation reaches a
 * terminal condition. Output sanitation is a projection of that source; it is
 * never fed back into the accumulator. This keeps executable JSON arguments
 * independent of provider chunk partitioning.
 */
final class ProviderTextAssembly {
    private static final List<String> XML_OPENERS = List.of(
            "<tool_call>",
            "<function_call>",
            "<tool>",
            "<function>");
    private static final List<String> XML_CLOSERS = List.of(
            "</tool_call>",
            "</function_call>",
            "</tool>",
            "</function>");
    private static final int XML_TOKEN_TAIL = XML_CLOSERS.stream()
            .mapToInt(String::length)
            .max()
            .orElse(1) - 1;

    private final int cap;
    private final StringBuilder raw;

    private boolean truncated;
    private int emittedLength;
    private int stablePrefixLength;
    private int stableScanCursor;
    private int stableBlockedAt = -1;
    /** Earliest leading-adjacent value that remains provisional without EOS. */
    private int nonAuthoritativeCutAt = -1;
    private boolean linePrefixWhitespace = true;

    private int stableJsonCandidateStart = -1;
    private int stableJsonScanCursor;
    private int stableJsonDepth;
    private char stableJsonQuote;
    private boolean stableJsonEscaped;
    private boolean stableLeadingJsonSequenceEligible = true;
    private boolean stableLeadingJsonValueSeen;
    private int stableLeadingJsonThrough;

    private int eventCandidateStart = -1;
    private int eventScanCursor;
    private EventState eventState;
    private int eventIndependentBlockAt = -1;

    private int xmlCandidateStart = -1;
    private int xmlSearchCursor;
    private boolean settledLegacyXmlToolCall;

    ProviderTextAssembly(int cap) {
        this.cap = Math.max(1, cap);
        this.raw = new StringBuilder(Math.min(this.cap, 4_096));
    }

    /**
     * Appends the bounded prefix of {@code delta}.
     *
     * <p>Reaching the exact boundary is not truncation. A following nonempty
     * character proves that the response crossed the cap and returns
     * {@code true}. If the boundary would retain only the high half of a
     * surrogate pair, that half is removed once crossing is established.
     */
    boolean append(String delta) {
        String value = Objects.toString(delta, "");
        if (value.isEmpty() || truncated) return truncated;

        int remaining = cap - raw.length();
        int appendLength = Math.min(remaining, value.length());
        if (appendLength > 0) {
            raw.append(value, 0, appendLength);
            scanLegacyXml();
        }

        if (appendLength < value.length()) {
            truncated = true;
            removeTrailingHighSurrogate();
        }
        return truncated;
    }

    String rawText() {
        return raw.toString();
    }

    /**
     * Final provider-EOS projection. Canonical executable JSON spans remain
     * byte-for-byte exact; sanitation applies only to surrounding prose.
     */
    String finalText() {
        String source = rawText();
        if (ToolProtocolText.unsettledJsonProtocolTailStart(source) >= 0) {
            return nonAuthoritativeText();
        }
        String sanitized = Sanitize.sanitizeForOutputPreservingToolCalls(source);
        return Sanitize.hardTruncate(
                ProviderInlineHtmlLiteralProjection.preserveIfInert(source, sanitized),
                cap);
    }

    /**
     * Fail-closed projection for a response that did not reach an authority-
     * eligible provider terminal state. Complete and malformed Talos protocol
     * is removed before ordinary output sanitation.
     */
    String nonAuthoritativeText() {
        String source = rawText();
        int prefixLength = Math.min(stablePrefixLength, source.length());
        String emittedPrefix = source.substring(0, prefixLength);
        int unsettledAt = ToolProtocolText.unsettledJsonProtocolTailStart(source);
        if (stableJsonCandidateStart >= prefixLength
                && stableJsonDepth > 0
                && (unsettledAt < 0 || stableJsonCandidateStart < unsettledAt)) {
            unsettledAt = stableJsonCandidateStart;
        }
        if (nonAuthoritativeCutAt >= prefixLength
                && (unsettledAt < 0 || nonAuthoritativeCutAt < unsettledAt)) {
            unsettledAt = nonAuthoritativeCutAt;
        }
        String settledSource = unsettledAt < 0
                ? source
                : source.substring(0, unsettledAt);

        // Classify against the complete settled source so fenced priority and
        // the pinned leading-adjacent JSON-value pass retain their original
        // context. The stable-prefix scanner guarantees that any previously
        // emitted bytes are projection-invariant; the final prefix check below
        // fails closed if that invariant is ever violated.
        String sanitized = withoutUnsettledJsonTail(
                Sanitize.sanitizeForOutputPreservingToolCalls(settledSource));
        String withoutProtocol = withoutUnsettledJsonTail(
                ToolProtocolText.stripToolCalls(sanitized));
        String projected = withoutUnsettledJsonTail(
                Sanitize.sanitizeForOutputPreservingToolCalls(withoutProtocol));
        projected = Sanitize.hardTruncate(projected, cap);
        if (projected.length() < prefixLength
                || !projected.regionMatches(0, emittedPrefix, 0, prefixLength)) {
            return emittedPrefix;
        }
        return projected;
    }

    boolean normalized(String returnedText) {
        return !rawText().equals(Objects.toString(returnedText, ""));
    }

    boolean truncated() {
        return truncated;
    }

    /**
     * Legacy XML compatibility may retain its historical deterministic stop.
     * JSON proposals deliberately do not participate: their complete set is
     * known only at provider EOS.
     */
    boolean containsSettledLegacyXmlToolCall() {
        return settledLegacyXmlToolCall;
    }

    /**
     * Emits a raw prefix that no later chunk can cause the current sanitizer or
     * text-tool grammar to reinterpret.
     *
     * <p>Short ambiguous prefixes (for example {@code o}, a backslash, or one
     * or two backticks) are released once later input proves them ordinary.
     * A structurally open protocol/sanitizer candidate blocks further
     * provisional emission. The scanner is monotonic; raw chunk progress is
     * reported independently by {@link LlmClient}.
     */
    int emitStable(Consumer<String> sink) {
        if (stableBlockedAt >= 0) {
            return sink == null
                    ? 0
                    : emitPrefix(raw, stablePrefixLength, sink);
        }

        int cursor = stableScanCursor;
        while (cursor < raw.length()) {
            if (stableJsonCandidateStart >= 0) {
                StableJsonDisposition disposition = advanceStableJsonCandidate();
                if (disposition == StableJsonDisposition.PENDING) {
                    cursor = stableJsonCandidateStart;
                    stableScanCursor = cursor;
                    break;
                }
                if (disposition == StableJsonDisposition.BLOCK) {
                    stableBlockedAt = stableJsonCandidateStart;
                    cursor = stableJsonCandidateStart;
                    stableScanCursor = cursor;
                    break;
                }
                cursor = stableScanCursor;
                continue;
            }

            if (eventCandidateStart >= 0) {
                EventDisposition disposition = advanceEventCandidate();
                if (disposition == EventDisposition.PENDING) {
                    stableScanCursor = eventCandidateStart;
                    break;
                }
                if (disposition == EventDisposition.BLOCK) {
                    stableBlockedAt = eventCandidateStart;
                    stableScanCursor = eventCandidateStart;
                    break;
                }
                cursor = stableScanCursor;
                continue;
            }

            char value = raw.charAt(cursor);
            if (stableLeadingJsonSequenceEligible
                    && cursor == stableLeadingJsonThrough) {
                boolean permittedWhitespace = stableLeadingJsonValueSeen
                        ? isJsonInterValueWhitespace(value)
                        : Character.isWhitespace(value);
                if (permittedWhitespace) {
                    stableLeadingJsonThrough = cursor + 1;
                } else if (stableLeadingJsonValueSeen && value != '{') {
                    // The pinned leading-adjacent pass can traverse arbitrary
                    // JSON values. Once one leading object has settled, an
                    // adjacent non-object value is conservatively terminal-
                    // buffered rather than provisionally exposed.
                    stableBlockedAt = cursor;
                    nonAuthoritativeCutAt = cursor;
                    break;
                } else if (value != '{') {
                    stableLeadingJsonSequenceEligible = false;
                }
            }
            if (Character.isHighSurrogate(value)) {
                if (cursor + 1 >= raw.length()) {
                    break;
                }
                if (Character.isLowSurrogate(raw.charAt(cursor + 1))) {
                    updateLinePrefix(value);
                    updateLinePrefix(raw.charAt(cursor + 1));
                    cursor += 2;
                    stableScanCursor = cursor;
                    continue;
                }
            }

            if (value == 'o' || value == 'O') {
                eventCandidateStart = cursor;
                eventScanCursor = cursor + 1;
                eventState = EventState.EXPECT_N;
                eventIndependentBlockAt = -1;
                continue;
            }
            if (value == '\\') {
                PrefixDisposition escape = escapedAngleDisposition(cursor);
                if (escape == PrefixDisposition.PENDING) break;
                if (escape == PrefixDisposition.BLOCK) {
                    stableBlockedAt = cursor;
                    break;
                }
                updateLinePrefix(value);
                cursor++;
                stableScanCursor = cursor;
                continue;
            }
            if (value == '`') {
                int run = consecutiveBackticks(cursor);
                if (run >= 3) {
                    stableBlockedAt = cursor;
                    break;
                }
                if (cursor + run == raw.length()) break;
                if (!isGuaranteedStableBacktickSeparator(cursor + run)) {
                    stableBlockedAt = cursor;
                    break;
                }
                for (int i = 0; i < run; i++) updateLinePrefix('`');
                cursor += run;
                stableScanCursor = cursor;
                continue;
            }
            if (value == '{' && isStableJsonCandidateBoundary(cursor)) {
                beginStableJsonCandidate(cursor);
                continue;
            }
            if (isPermanentProjectionHazard(value)) {
                stableBlockedAt = cursor;
                break;
            }
            updateLinePrefix(value);
            cursor++;
            stableScanCursor = cursor;
        }
        stableScanCursor = cursor;
        stablePrefixLength = Math.max(stablePrefixLength, cursor);
        return sink == null ? 0 : emitPrefix(raw, cursor, sink);
    }

    /**
     * Emits the remainder of one terminal projection. The projection must
     * retain every prefix character already emitted by {@link #emitStable}.
     */
    int emitTerminal(String terminalText, Consumer<String> sink) {
        if (sink == null) return 0;
        String projection = Objects.toString(terminalText, "");
        if (projection.length() < emittedLength
                || !matchesRawPrefix(projection, emittedLength)) {
            throw new IllegalStateException(
                    "terminal provider projection changed an emitted prefix");
        }
        if (projection.length() == emittedLength) return 0;

        String delta = projection.substring(emittedLength);
        sink.accept(delta);
        emittedLength = projection.length();
        return delta.length();
    }

    private boolean matchesRawPrefix(String projection, int length) {
        for (int i = 0; i < length; i++) {
            if (projection.charAt(i) != raw.charAt(i)) return false;
        }
        return true;
    }

    private int emitPrefix(
            CharSequence source,
            int endExclusive,
            Consumer<String> sink) {
        if (endExclusive <= emittedLength) return 0;
        String delta = source.subSequence(emittedLength, endExclusive).toString();
        sink.accept(delta);
        emittedLength = endExclusive;
        return delta.length();
    }

    private void removeTrailingHighSurrogate() {
        if (!raw.isEmpty()
                && Character.isHighSurrogate(raw.charAt(raw.length() - 1))) {
            raw.setLength(raw.length() - 1);
            stableScanCursor = Math.min(stableScanCursor, raw.length());
            stablePrefixLength = Math.min(stablePrefixLength, raw.length());
            stableJsonScanCursor = Math.min(
                    stableJsonScanCursor, raw.length());
        }
    }

    private boolean isStableJsonCandidateBoundary(int at) {
        return at == 0
                || Character.isWhitespace(raw.charAt(at - 1))
                || stableLeadingJsonSequenceEligible
                && stableLeadingJsonValueSeen
                && at == stableLeadingJsonThrough;
    }

    private void beginStableJsonCandidate(int at) {
        stableJsonCandidateStart = at;
        stableJsonScanCursor = at;
        stableJsonDepth = 0;
        stableJsonQuote = 0;
        stableJsonEscaped = false;
    }

    private StableJsonDisposition advanceStableJsonCandidate() {
        while (stableJsonScanCursor < raw.length()) {
            char value = raw.charAt(stableJsonScanCursor++);
            if (stableJsonQuote != 0) {
                if (stableJsonEscaped) {
                    stableJsonEscaped = false;
                } else if (value == '\\') {
                    stableJsonEscaped = true;
                } else if (value == stableJsonQuote) {
                    stableJsonQuote = 0;
                }
                continue;
            }
            if (value == '"') {
                stableJsonQuote = value;
            } else if (value == '{') {
                stableJsonDepth++;
            } else if (value == '}') {
                stableJsonDepth--;
                if (stableJsonDepth == 0) {
                    return settleStableJsonCandidate();
                }
                if (stableJsonDepth < 0) {
                    return StableJsonDisposition.BLOCK;
                }
            }
        }
        return StableJsonDisposition.PENDING;
    }

    private StableJsonDisposition settleStableJsonCandidate() {
        int start = stableJsonCandidateStart;
        int end = stableJsonScanCursor;
        String candidate = raw.substring(start, end);
        boolean leadingJsonValue = stableLeadingJsonSequenceEligible
                && start == stableLeadingJsonThrough
                && ToolProtocolText.isCompleteJsonObjectValue(candidate);
        ToolProtocolText.JsonProposalSelection selection =
                ToolProtocolText.selectExecutableJsonProposals(candidate);
        String projected = Sanitize.sanitizeForOutputPreservingToolCalls(candidate);
        if (!selection.executionOrder().isEmpty()
                || !selection.suppressionOnlyEnvelopes().isEmpty()
                || !candidate.equals(projected)) {
            return StableJsonDisposition.BLOCK;
        }

        updateLinePrefix(start, end);
        if (leadingJsonValue) {
            stableLeadingJsonValueSeen = true;
            stableLeadingJsonThrough = end;
        } else if (stableLeadingJsonSequenceEligible
                && start == stableLeadingJsonThrough) {
            stableLeadingJsonSequenceEligible = false;
        }
        stableScanCursor = end;
        stableJsonCandidateStart = -1;
        stableJsonScanCursor = 0;
        stableJsonDepth = 0;
        stableJsonQuote = 0;
        stableJsonEscaped = false;
        return StableJsonDisposition.RELEASED;
    }

    private EventDisposition advanceEventCandidate() {
        while (eventScanCursor < raw.length()) {
            char value = raw.charAt(eventScanCursor);
            switch (eventState) {
                case EXPECT_N -> {
                    if (value == 'n' || value == 'N') {
                        eventState = EventState.WORD;
                        eventScanCursor++;
                    } else {
                        releaseFailedEventCandidate(eventCandidateStart + 1);
                        return EventDisposition.RELEASED;
                    }
                }
                case WORD -> {
                    if (isRegexWord(value)) {
                        eventScanCursor++;
                    } else if (isRegexSpace(value)) {
                        rememberIndependentControl(value, eventScanCursor);
                        eventState = EventState.SPACE;
                        eventScanCursor++;
                    } else if (value == '=') {
                        eventState = EventState.AFTER_EQUALS;
                        eventScanCursor++;
                    } else {
                        return failEventAtDelimiter();
                    }
                }
                case SPACE -> {
                    if (isRegexSpace(value)) {
                        rememberIndependentControl(value, eventScanCursor);
                        eventScanCursor++;
                    } else if (value == '=') {
                        eventState = EventState.AFTER_EQUALS;
                        eventScanCursor++;
                    } else {
                        return failEventAtDelimiter();
                    }
                }
                case AFTER_EQUALS -> {
                    if (isRegexSpace(value)) {
                        rememberIndependentControl(value, eventScanCursor);
                        eventScanCursor++;
                    } else if (value == '\'' || value == '"') {
                        eventState = EventState.QUOTED;
                        eventScanCursor++;
                    } else {
                        return failEventAtDelimiter();
                    }
                }
                case QUOTED -> {
                    if (value == '\'' || value == '"') {
                        return EventDisposition.BLOCK;
                    }
                    eventScanCursor++;
                }
            }
        }
        return EventDisposition.PENDING;
    }

    private EventDisposition failEventAtDelimiter() {
        if (eventIndependentBlockAt >= 0) {
            stableBlockedAt = eventIndependentBlockAt;
            return EventDisposition.BLOCK;
        }
        releaseFailedEventCandidate(eventScanCursor);
        return EventDisposition.RELEASED;
    }

    private void releaseFailedEventCandidate(int safeEndExclusive) {
        updateLinePrefix(eventCandidateStart, safeEndExclusive);
        stableScanCursor = safeEndExclusive;
        eventCandidateStart = -1;
        eventScanCursor = 0;
        eventState = null;
        eventIndependentBlockAt = -1;
    }

    private void rememberIndependentControl(char value, int at) {
        if (eventIndependentBlockAt < 0 && isStrippedControl(value)) {
            eventIndependentBlockAt = at;
        }
    }

    private PrefixDisposition escapedAngleDisposition(int at) {
        for (String candidate : List.of("\\u003c", "\\u003e")) {
            int availableLength = raw.length() - at;
            int comparedLength = Math.min(availableLength, candidate.length());
            if (rawSubstringMatches(at, candidate, comparedLength)) {
                return availableLength >= candidate.length()
                        ? PrefixDisposition.BLOCK
                        : PrefixDisposition.PENDING;
            }
        }
        return PrefixDisposition.RELEASE;
    }

    private boolean rawSubstringMatches(int at, String candidate, int length) {
        for (int i = 0; i < length; i++) {
            if (raw.charAt(at + i) != candidate.charAt(i)) return false;
        }
        return true;
    }

    private int consecutiveBackticks(int at) {
        int count = 0;
        while (at + count < raw.length()
                && raw.charAt(at + count) == '`'
                && count < 3) {
            count++;
        }
        return count;
    }

    private boolean isGuaranteedStableBacktickSeparator(int at) {
        char value = raw.charAt(at);
        if (Character.isHighSurrogate(value)) {
            return at + 1 < raw.length()
                    && Character.isLowSurrogate(raw.charAt(at + 1));
        }
        return value != 'o'
                && value != 'O'
                && value != '\\'
                && value != '`'
                && value != '<'
                && !(value == '{' && linePrefixWhitespace)
                && !isStrippedControl(value);
    }

    private static String withoutUnsettledJsonTail(String source) {
        String value = Objects.toString(source, "");
        int unsettledAt = ToolProtocolText.unsettledJsonProtocolTailStart(value);
        return unsettledAt < 0 ? value : value.substring(0, unsettledAt);
    }

    private void updateLinePrefix(int startInclusive, int endExclusive) {
        for (int i = startInclusive; i < endExclusive; i++) {
            updateLinePrefix(raw.charAt(i));
        }
    }

    private void updateLinePrefix(char value) {
        if (value == '\n'
                || value == '\r'
                || value == '\u0085'
                || value == '\u2028'
                || value == '\u2029') {
            linePrefixWhitespace = true;
        } else if (!Character.isWhitespace(value)) {
            linePrefixWhitespace = false;
        }
    }

    /**
     * Monotonic XML compatibility scanner. Candidate substrings are disjoint,
     * so even rejected foreign blocks are inspected at most once.
     */
    private void scanLegacyXml() {
        while (!settledLegacyXmlToolCall) {
            if (xmlCandidateStart < 0) {
                int opener = earliestToken(raw, XML_OPENERS, xmlSearchCursor);
                if (opener < 0) {
                    xmlSearchCursor = Math.max(0, raw.length() - XML_TOKEN_TAIL);
                    return;
                }
                xmlCandidateStart = opener;
                xmlSearchCursor = opener + 1;
            }

            int close = earliestToken(raw, XML_CLOSERS, xmlSearchCursor);
            if (close < 0) {
                xmlSearchCursor = Math.max(
                        xmlCandidateStart + 1,
                        raw.length() - XML_TOKEN_TAIL);
                return;
            }

            int closeEnd = close + tokenLengthAt(raw, XML_CLOSERS, close);
            String candidate = raw.substring(xmlCandidateStart, closeEnd);
            if (ToolProtocolText.containsRecognizedXmlToolCall(candidate)) {
                settledLegacyXmlToolCall = true;
                return;
            }
            xmlCandidateStart = -1;
            xmlSearchCursor = closeEnd;
        }
    }

    private static int earliestToken(
            StringBuilder source,
            List<String> tokens,
            int fromIndex) {
        int earliest = -1;
        for (String token : tokens) {
            int found = source.indexOf(token, Math.max(0, fromIndex));
            if (found >= 0 && (earliest < 0 || found < earliest)) {
                earliest = found;
            }
        }
        return earliest;
    }

    private static int tokenLengthAt(
            StringBuilder source,
            List<String> tokens,
            int at) {
        for (String token : tokens) {
            if (source.indexOf(token, at) == at) return token.length();
        }
        throw new IllegalStateException("XML close token disappeared");
    }

    private static boolean isPermanentProjectionHazard(char value) {
        return value == '<' || isStrippedControl(value);
    }

    private static boolean isStrippedControl(char value) {
        return value <= '\u0008'
                || value >= '\u000B' && value <= '\u001F'
                || value == '\u007F';
    }

    private static boolean isRegexWord(char value) {
        return value == '_'
                || value >= '0' && value <= '9'
                || value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z';
    }

    private static boolean isRegexSpace(char value) {
        return value == ' '
                || value == '\t'
                || value == '\n'
                || value == '\u000B'
                || value == '\f'
                || value == '\r';
    }

    private static boolean isJsonInterValueWhitespace(char value) {
        return value == ' '
                || value == '\t'
                || value == '\r'
                || value == '\n';
    }

    private enum EventState {
        EXPECT_N,
        WORD,
        SPACE,
        AFTER_EQUALS,
        QUOTED
    }

    private enum EventDisposition {
        PENDING,
        RELEASED,
        BLOCK
    }

    private enum PrefixDisposition {
        PENDING,
        RELEASE,
        BLOCK
    }

    private enum StableJsonDisposition {
        PENDING,
        RELEASED,
        BLOCK
    }
}
