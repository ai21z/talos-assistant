package dev.talos.runtime;

import dev.talos.core.tool.ToolProtocolText;

import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stream filter that suppresses tool-call protocol blocks from user-visible output.
 *
 * <p>Wraps a {@code Consumer<String>} display sink. Chunks that contain or partially
 * overlap tool-call blocks are buffered and suppressed. Natural-language text
 * before/after tool-call blocks passes through to the delegate.
 *
 * <p><b>Architecture (native-first pipeline):</b>
 * <ul>
 *   <li><b>Native tool calls (primary path)</b> never appear in the text stream -
 *       they are emitted as {@link dev.talos.spi.types.TokenChunk#ofToolCalls} chunks
 *       and captured directly by {@link dev.talos.core.llm.LlmClient#chatStreamFull},
 *       bypassing this filter entirely.</li>
 *   <li><b>JSON code fences (active text fallback)</b> - suppressed when the content
 *       matches a tool-call signature ({@code "name": "talos."}).</li>
 *   <li><b>Bare standalone JSON (compat fallback)</b> - buffered until a complete
 *       top-level object is available, then suppressed only if it parses as a
 *       Talos tool call.</li>
 *   <li><b>XML tags (deprecated compatibility)</b> - {@code <tool_call>},
 *       {@code <function_call>}, {@code <tool>}, {@code <function>} - retained
 *       temporarily for models that emit XML from training habits. Not actively
 *       instructed. Scheduled for removal once native calling is stable.</li>
 * </ul>
 *
 * <p>The tool-call loop ({@link ToolCallLoop}) receives the full raw text from
 * {@link dev.talos.core.llm.LlmClient#chatStream}'s return value, so filtering
 * the display sink does NOT break tool execution.
 *
 * <p>Usage:
 * <pre>
 *   Consumer&lt;String&gt; rawSink = chunk -&gt; System.out.print(chunk);
 *   ToolCallStreamFilter filter = new ToolCallStreamFilter(rawSink);
 *   // pass filter as the onChunk callback
 *   String full = llm.chatStream(messages, filter);
 *   filter.flush(); // emit any pending non-tool text
 * </pre>
 *
 * <p>Thread-safety: not thread-safe. Intended for single-threaded streaming use.
 */
public final class ToolCallStreamFilter implements Consumer<String> {

    private final Consumer<String> delegate;
    private final StringBuilder buffer = new StringBuilder();
    private final StringBuilder leadingJsonBuffer = new StringBuilder();
    private boolean probingLeadingJson = true;
    private boolean bufferingLeadingJson;
    private boolean leadingJsonOverflow;
    /** Monotonic cursor after the opening brace while its first member settles. */
    private int leadingJsonMemberScanCursor;
    /** Saved opening fence text (e.g. "```json\n") for re-emission of non-tool fences. */
    private String fenceOpening = "";
    /** Backtick count of the currently buffered Markdown fence. */
    private int fenceMarkerLength;
    /** Whether the opening syntax is eligible for executable JSON classification. */
    private boolean executableFenceOpening;
    /** Text immediately before a JSON protocol candidate, held until the candidate is classified. */
    private String pendingProtocolPrefix = "";
    /** Boundary truth retained when PASSTHROUGH keeps only a suffix of its raw buffer. */
    private boolean passthroughBufferStartsAtStandaloneBoundary = true;
    /** Line-boundary truth retained independently of single-byte whitespace. */
    private boolean passthroughBufferStartsAtLineBoundary = true;
    /** Blank suffix after suppressed array debris, pending terminal/prose settlement. */
    private boolean pendingProtocolOnlyBlankSuffix;

    /** Current suppression state.
     *  SUPPRESSING_XML is DEPRECATED compatibility-only (for models that emit XML from training).
     *  Scheduled for removal once native tool calling is stable across model versions. */
    private enum State {
        PASSTHROUGH,
        SUPPRESSING_XML,
        BUFFERING_FENCE_OPENING,
        BUFFERING_FENCE,
        SUPPRESSING_FENCE,
        BUFFERING_BARE_JSON,
        SUPPRESSING_BARE_JSON,
        BUFFERING_PROTOCOL_ARRAY
    }
    private State state = State.PASSTHROUGH;
    private int suppressedJsonDepth;
    private char suppressedJsonQuote;
    private boolean suppressedJsonEscaped;

    /** Monotonic structural cursor for the currently buffered bare JSON object. */
    private int bareJsonScanCursor;
    private int bareJsonDepth;
    private char bareJsonQuote;
    private boolean bareJsonEscaped;
    private boolean bareJsonFirstMemberSeen;

    /** Monotonic close-delimiter cursor for the currently buffered code fence. */
    private int fenceScanCursor;
    /** Start of an unsettled delimiter run retained across provider chunks. */
    private int pendingFenceBacktickRunStart = -1;
    /** First unscanned offset after the retained delimiter run. */
    private int pendingFenceBacktickRunEnd = -1;
    private int fenceOpeningScanCursor;
    private boolean fenceOpeningMarkerConfirmed;
    private char fenceQuote;
    private boolean fenceQuoteEscaped;
    private boolean fenceContentKindSettled;
    private boolean fenceQuoteAwareContent;
    private boolean fenceLinePrefixWhitespace;

    /** Opening XML tags that start suppression.
     *  DEPRECATED COMPATIBILITY ONLY - retained for models that emit XML from training habits.
     *  Scheduled for removal. */
    private static final Pattern OPEN_TAG = Pattern.compile(
            "<(tool_call|function_call|tool|function)>"
    );

    /** Closing XML tags that end suppression.
     *  DEPRECATED COMPATIBILITY ONLY - retained alongside OPEN_TAG.
     *  Scheduled for removal. */
    private static final Pattern CLOSE_TAG = Pattern.compile(
            "</(tool_call|function_call|tool|function)>"
    );

    /** All possible opening XML tag strings (for prefix matching at chunk boundaries).
     *  DEPRECATED COMPATIBILITY ONLY - retained alongside OPEN_TAG.
     *  Scheduled for removal. */
    private static final String[] OPEN_TAG_STRINGS = {
            "<tool_call>", "<function_call>", "<tool>", "<function>"
    };

    /** Parent-compatible executable JSON-fence opening. */
    private static final Pattern EXECUTABLE_CODE_FENCE_OPEN =
            Pattern.compile("```(?:json)?[ \\t]*\\R");

    /** Any Markdown code-fence opening; executable eligibility is classified separately. */
    private static final Pattern CODE_FENCE_OPEN = Pattern.compile(
            "(?<!`)(`{3,})([^\\r\\n\\u000B\\f\\u0085\\u2028\\u2029]*)\\R");

    /** Closing delimiter shared by the canonical executable JSON-fence contract. */
    private static final String CODE_FENCE_CLOSE = "```";

    /** All possible code fence opening prefixes (for chunk boundary detection). */
    private static final String CODE_FENCE_PREFIX = "```";

    /**
     * Maximum unresolved JSON envelope retained by the display path.
     *
     * <p>This matches the product's default provider-response ceiling. A
     * complete ordinary or foreign object below this ceiling is emitted
     * unchanged. An envelope still structurally unresolved at the ceiling is
     * discarded until its end rather than exposing a possible late-name tool
     * proposal.
     */
    private static final int MAX_JSON_ENVELOPE_BUFFER_CHARS = 10 * 1024 * 1024;

    /** Upper bound for narrow malformed-array protocol-debris buffering. */
    private static final int MAX_PROTOCOL_ARRAY_BUFFER_CHARS = 512;

    /** Narrow phrases that are misleading if printed immediately before a suppressed tool protocol block. */
    private static final Pattern SPECULATIVE_PRE_TOOL_PROSE = Pattern.compile(
            "(?is)\\b("
                    + "let's\\s+assume|"
                    + "assume\\s+the\\s+relevant|"
                    + "assuming\\s+the\\s+relevant|"
                    + "suppose\\s+the\\s+relevant|"
                    + "the\\s+relevant\\s+section\\s+looks\\s+like|"
                    + "here'?s\\s+a\\s+possible"
                    + ")\\b"
    );

    public ToolCallStreamFilter(Consumer<String> delegate) {
        this.delegate = (delegate != null) ? delegate : s -> {};
    }

    @Override
    public void accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        if (acceptLeadingJson(chunk)) return;
        if (pendingProtocolOnlyBlankSuffix && !chunk.isBlank()) {
            pendingProtocolOnlyBlankSuffix = false;
        }
        buffer.append(chunk);
        drain();
    }

    /**
     * Flush any remaining buffered content to the delegate.
     *
     * <p>Call this after the stream completes (e.g., after {@code chatStream()} returns).
     * If currently inside a suppressed block, the partial block is discarded (it was
     * tool-call content that never closed - safe to drop). If buffering a code fence
     * that never completed, the buffered content is emitted (it was not a tool call).
     */
    public void flush() {
        if (leadingJsonOverflow) {
            resetState();
            return;
        }
        if (bufferingLeadingJson) {
            flushLeadingJson();
            resetState();
            return;
        }
        releaseLeadingProbe();
        if (!buffer.isEmpty() || !fenceOpening.isEmpty()) {
            switch (state) {
                case PASSTHROUGH:
                    emitPendingProtocolPrefix(false);
                    if (!pendingProtocolOnlyBlankSuffix || !buffer.toString().isBlank()) {
                        delegate.accept(buffer.toString());
                    }
                    break;
                case BUFFERING_FENCE_OPENING:
                    emitPendingProtocolPrefix(false);
                    delegate.accept(buffer.toString());
                    break;
                case BUFFERING_FENCE: {
                    if (drainBufferingFence(true)) {
                        if (!buffer.isEmpty()) drain();
                        if (state == State.PASSTHROUGH && !buffer.isEmpty()) {
                            emitPendingProtocolPrefix(false);
                            if (!pendingProtocolOnlyBlankSuffix
                                    || !buffer.toString().isBlank()) {
                                delegate.accept(buffer.toString());
                            }
                            buffer.setLength(0);
                        }
                        break;
                    }
                    String incompleteFenceContent = buffer.toString();
                    if ((executableFenceOpening
                            && isProtocolLikeIncompleteFence(incompleteFenceContent))
                            || (executableFenceOpening
                            && isJsonFenceOpening(fenceOpening)
                            && incompleteFenceContent.isBlank())) {
                        // Blank, incomplete JSON fence - protocol debris.
                        emitPendingProtocolPrefix(true);
                    } else {
                        // Never completed. Apply the same canonical display
                        // projection as a settled generic fence so a nested
                        // incomplete executable envelope cannot leak at EOS.
                        emitPendingProtocolPrefix(false);
                        String displayProjection =
                                ToolProtocolText.stripToolCallsPreservingLayout(
                                        fenceOpening + buffer);
                        if (!displayProjection.isEmpty()) {
                            delegate.accept(displayProjection);
                        }
                    }
                    break;
                }
                case BUFFERING_BARE_JSON: {
                    String incompleteBareJson = buffer.toString();
                    if (isPotentialExecutableStandaloneJson(incompleteBareJson)) {
                        // Incomplete protocol debris - discard
                        emitPendingProtocolPrefix(true);
                    } else {
                        emitPendingProtocolPrefix(false);
                        delegate.accept(buffer.toString());
                    }
                    break;
                }
                case BUFFERING_PROTOCOL_ARRAY:
                    if (ToolCallParser.looksLikeMalformedProtocolArrayDebris(buffer.toString())) {
                        emitPendingProtocolPrefix(true);
                    } else {
                        emitPendingProtocolPrefix(false);
                        delegate.accept(buffer.toString());
                    }
                    break;
                case SUPPRESSING_XML:
                case SUPPRESSING_FENCE:
                case SUPPRESSING_BARE_JSON:
                    // Incomplete tool-call block - discard
                    emitPendingProtocolPrefix(true);
                    break;
            }
        }
        resetState();
    }

    /**
     * Reset state without flushing (e.g., between turns).
     */
    public void reset() {
        resetState();
    }

    private void resetState() {
        buffer.setLength(0);
        leadingJsonBuffer.setLength(0);
        probingLeadingJson = true;
        bufferingLeadingJson = false;
        leadingJsonOverflow = false;
        leadingJsonMemberScanCursor = 0;
        fenceOpening = "";
        fenceMarkerLength = 0;
        executableFenceOpening = false;
        resetSuppressedJsonState();
        resetBareJsonScanner();
        resetFenceScanner();
        pendingProtocolPrefix = "";
        passthroughBufferStartsAtStandaloneBoundary = true;
        passthroughBufferStartsAtLineBoundary = true;
        pendingProtocolOnlyBlankSuffix = false;
        fenceOpeningScanCursor = 0;
        fenceOpeningMarkerConfirmed = false;
        state = State.PASSTHROUGH;
    }

    private boolean acceptLeadingJson(String chunk) {
        if (leadingJsonOverflow) return true;
        if (bufferingLeadingJson) {
            int appended = appendLeadingJson(chunk);
            releaseDisprovedLeadingJson();
            appendReleasedLeadingJsonOverflow(chunk, appended);
            return true;
        }
        if (!probingLeadingJson) return false;

        int first = 0;
        while (first < chunk.length()
                && Character.isWhitespace(chunk.charAt(first))) {
            first++;
        }
        if (first == chunk.length()) {
            appendLeadingJson(chunk);
            return true;
        }

        probingLeadingJson = false;
        if (chunk.charAt(first) == '{') {
            bufferingLeadingJson = true;
            int priorLength = leadingJsonBuffer.length();
            int appended = appendLeadingJson(chunk);
            leadingJsonMemberScanCursor = priorLength + first + 1;
            releaseDisprovedLeadingJson();
            appendReleasedLeadingJsonOverflow(chunk, appended);
            return true;
        }

        buffer.append(leadingJsonBuffer);
        leadingJsonBuffer.setLength(0);
        buffer.append(chunk);
        drain();
        return true;
    }

    private int appendLeadingJson(String chunk) {
        if (leadingJsonOverflow) return 0;
        int remaining = MAX_JSON_ENVELOPE_BUFFER_CHARS - leadingJsonBuffer.length();
        int appended = Math.min(chunk.length(), Math.max(0, remaining));
        if (appended > 0) {
            leadingJsonBuffer.append(chunk, 0, appended);
        }
        if (appended < chunk.length()) {
            leadingJsonOverflow = true;
        }
        return appended;
    }

    private void releaseDisprovedLeadingJson() {
        if (!bufferingLeadingJson) return;
        while (leadingJsonMemberScanCursor < leadingJsonBuffer.length()
                && Character.isWhitespace(
                        leadingJsonBuffer.charAt(leadingJsonMemberScanCursor))) {
            leadingJsonMemberScanCursor++;
        }
        if (leadingJsonMemberScanCursor >= leadingJsonBuffer.length()) return;
        char firstMember = leadingJsonBuffer.charAt(leadingJsonMemberScanCursor);
        if (firstMember == '"' || firstMember == '}') return;

        bufferingLeadingJson = false;
        leadingJsonOverflow = false;
        buffer.append(leadingJsonBuffer);
        leadingJsonBuffer.setLength(0);
        leadingJsonMemberScanCursor = 0;
        drain();
    }

    private void appendReleasedLeadingJsonOverflow(String chunk, int appended) {
        if (bufferingLeadingJson || appended >= chunk.length()) return;
        buffer.append(chunk, appended, chunk.length());
        drain();
    }

    private void releaseLeadingProbe() {
        if (leadingJsonBuffer.isEmpty()) return;
        buffer.append(leadingJsonBuffer);
        leadingJsonBuffer.setLength(0);
        probingLeadingJson = false;
    }

    private void flushLeadingJson() {
        if (leadingJsonOverflow) return;
        String source = leadingJsonBuffer.toString();
        int unsettledAt = ToolProtocolText.unsettledJsonProtocolTailStart(source);
        String settled = source;
        if (unsettledAt >= 0) {
            String tail = source.substring(unsettledAt);
            int firstNonWhitespace = 0;
            while (firstNonWhitespace < source.length()
                    && Character.isWhitespace(source.charAt(firstNonWhitespace))) {
                firstNonWhitespace++;
            }
            boolean protocolLike = unsettledAt > firstNonWhitespace
                    || !ToolProtocolText
                    .potentialExecutableStandaloneJsonSpans(tail)
                    .isEmpty()
                    || ToolProtocolText.containsPotentialExecutableToolNameField(tail)
                    || ToolProtocolText.containsPotentialExecutableToolNamePrefix(tail);
            if (protocolLike) {
                settled = source.substring(0, unsettledAt);
            }
        }
        String projected = ToolProtocolText.stripToolCallsPreservingLayout(settled);
        if (!projected.isEmpty()) {
            delegate.accept(projected);
        }
    }

    // ── Internal drain loop ──────────────────────────────────────────────

    private void drain() {
        // Process buffer until no more progress can be made
        while (!buffer.isEmpty()) {
            boolean progress = switch (state) {
                case SUPPRESSING_XML -> drainSuppressingXml();
                case BUFFERING_FENCE_OPENING -> drainBufferingFenceOpening();
                case SUPPRESSING_FENCE -> drainSuppressingFence();
                case BUFFERING_FENCE -> drainBufferingFence();
                case BUFFERING_BARE_JSON -> drainBufferingBareJson();
                case SUPPRESSING_BARE_JSON -> drainSuppressingBareJson();
                case BUFFERING_PROTOCOL_ARRAY -> drainBufferingProtocolArray();
                case PASSTHROUGH -> drainPassthrough();
            };
            if (!progress) break;
        }
    }

    /**
     * DEPRECATED COMPATIBILITY ONLY: In XML suppressing mode - look for closing tag.
     * Retained temporarily for models that emit XML tool-call tags from training habits.
     * Not actively instructed. Scheduled for removal.
     * Returns true if progress was made (should loop again).
     */
    private boolean drainSuppressingXml() {
        Matcher cm = CLOSE_TAG.matcher(buffer);
        if (cm.find()) {
            // Found closing tag - discard everything up to and including it
            XmlCompatTelemetry.recordStreamSuppressedXmlBlock();
            String remainder = buffer.substring(cm.end());
            buffer.setLength(0);
            buffer.append(remainder);
            state = State.PASSTHROUGH;
            return true; // made progress
        }
        // Still inside block, wait for more chunks
        return false;
    }

    /** Waits for the line break that settles one possible fence opening. */
    private boolean drainBufferingFenceOpening() {
        if (!fenceOpeningMarkerConfirmed) {
            while (fenceOpeningScanCursor < buffer.length()
                    && buffer.charAt(fenceOpeningScanCursor) == '`') {
                fenceOpeningScanCursor++;
                if (fenceOpeningScanCursor >= CODE_FENCE_CLOSE.length()) {
                    fenceOpeningMarkerConfirmed = true;
                    break;
                }
            }
            if (!fenceOpeningMarkerConfirmed) {
                if (fenceOpeningScanCursor < buffer.length()) {
                    fenceOpeningScanCursor = 0;
                    state = State.PASSTHROUGH;
                    return true;
                }
                return false;
            }
        }
        while (fenceOpeningScanCursor < buffer.length()) {
            char value = buffer.charAt(fenceOpeningScanCursor++);
            if (value == '\r'
                    || value == '\n'
                    || value == '\u000B'
                    || value == '\f'
                    || value == '\u0085'
                    || value == '\u2028'
                    || value == '\u2029') {
                fenceOpeningScanCursor = 0;
                fenceOpeningMarkerConfirmed = false;
                state = State.PASSTHROUGH;
                return true;
            }
        }
        if (buffer.length() >= MAX_JSON_ENVELOPE_BUFFER_CHARS) {
            emitPendingProtocolPrefix(true);
            String overflow = buffer.substring(MAX_JSON_ENVELOPE_BUFFER_CHARS);
            buffer.setLength(0);
            buffer.append(overflow);
            fenceMarkerLength = CODE_FENCE_CLOSE.length();
            executableFenceOpening = true;
            resetFenceScanner();
            fenceOpeningScanCursor = 0;
            fenceOpeningMarkerConfirmed = false;
            state = State.SUPPRESSING_FENCE;
            return true;
        }
        return false;
    }

    /**
     * In malformed protocol-array buffering mode: suppress only the observed
     * invalid empty-array debris shape ({@code [ , ]}). Ordinary arrays are
     * emitted unchanged as soon as they no longer match that narrow prefix.
     */
    private boolean drainBufferingProtocolArray() {
        String text = buffer.toString();
        if (text.isEmpty()) return false;

        ProtocolArrayDecision decision = classifyProtocolArrayPrefix(text);
        if (decision.kind() == ProtocolArrayDecision.Kind.WAIT) {
            if (buffer.length() > MAX_PROTOCOL_ARRAY_BUFFER_CHARS) {
                emitPendingProtocolPrefix(false);
                delegate.accept(buffer.toString());
                buffer.setLength(0);
                state = State.PASSTHROUGH;
                return true;
            }
            return false;
        }

        if (decision.kind() == ProtocolArrayDecision.Kind.NOT_PROTOCOL) {
            int releaseEnd = Math.max(
                    1, Math.min(decision.endExclusive(), text.length()));
            emitPendingProtocolPrefix(false);
            delegate.accept(text.substring(0, releaseEnd));
            String remainder = text.substring(releaseEnd);
            passthroughBufferStartsAtStandaloneBoundary =
                    Character.isWhitespace(text.charAt(releaseEnd - 1));
            passthroughBufferStartsAtLineBoundary =
                    isStandaloneLineBoundary(text, releaseEnd, true);
            buffer.setLength(0);
            buffer.append(remainder);
            state = State.PASSTHROUGH;
            return true;
        }

        emitPendingProtocolPrefix(true);
        String remainder = text.substring(decision.endExclusive());
        buffer.setLength(0);
        buffer.append(remainder);
        pendingProtocolOnlyBlankSuffix = remainder.isBlank();
        state = State.PASSTHROUGH;
        return true;
    }

    /**
     * In bare-JSON buffering mode: wait until a complete top-level JSON object
     * is available, then suppress only Talos tool-call objects.
     */
    private boolean drainBufferingBareJson() {
        if (buffer.isEmpty()) return false;

        BareJsonScanResult scan = advanceBareJsonScanner(
                Math.min(buffer.length(), MAX_JSON_ENVELOPE_BUFFER_CHARS));
        if (scan.kind() == BareJsonScanResult.Kind.NOT_JSON_OBJECT) {
            int releaseEnd = Math.max(
                    1, Math.min(scan.endExclusive(), buffer.length()));
            emitPendingProtocolPrefix(false);
            delegate.accept(buffer.substring(0, releaseEnd));
            String remainder = buffer.substring(releaseEnd);
            passthroughBufferStartsAtStandaloneBoundary =
                    Character.isWhitespace(buffer.charAt(releaseEnd - 1));
            passthroughBufferStartsAtLineBoundary =
                    isStandaloneLineBoundary(buffer.toString(), releaseEnd, true);
            buffer.setLength(0);
            buffer.append(remainder);
            resetBareJsonScanner();
            state = State.PASSTHROUGH;
            return true;
        }

        if (scan.kind() == BareJsonScanResult.Kind.COMPLETE) {
            int objectEndExclusive = scan.endExclusive();
            String candidate = buffer.substring(0, objectEndExclusive);
            String remainder = buffer.substring(objectEndExclusive);
            // Classification is deliberately envelope-local. A later Talos
            // object may not retroactively poison this completed object.
            boolean toolProtocol = ToolCallParser.looksLikeStandaloneToolJson(candidate)
                    || isPotentialExecutableStandaloneJson(candidate);
            if (!toolProtocol) {
                emitPendingProtocolPrefix(false);
                delegate.accept(candidate);
            } else {
                emitPendingProtocolPrefix(true);
            }
            buffer.setLength(0);
            buffer.append(remainder);
            resetBareJsonScanner();
            state = State.PASSTHROUGH;
            return true;
        }

        if (bareJsonScanCursor >= MAX_JSON_ENVELOPE_BUFFER_CHARS) {
            // The provider ceiling was reached before structural settlement.
            // Preserve only scanner state and any already-arrived overflow;
            // the unresolved candidate itself is never displayed.
            emitPendingProtocolPrefix(true);
            String overflow = buffer.length() > MAX_JSON_ENVELOPE_BUFFER_CHARS
                    ? buffer.substring(MAX_JSON_ENVELOPE_BUFFER_CHARS)
                    : "";
            beginSuppressingBareJson(overflow);
            return true;
        }
        return false;
    }

    /**
     * In fence-suppressing mode: look for closing ```.
     * Returns true if progress was made.
     */
    private boolean drainSuppressingFence() {
        ClosingFence close = advanceFenceScanner(buffer.length());
        if (close != null) {
            if (close.nestedExecutableOpening()) {
                String remainder = buffer.substring(close.startInclusive());
                emitPendingProtocolPrefix(true);
                finishFenceBuffer(remainder);
                return true;
            }
            String rawRemainder = buffer.substring(close.endExclusive());
            if (suppressedFenceLineSuffixNeedsMore(rawRemainder)) {
                return false;
            }
            String remainder = stripSuppressedFenceLineSuffix(rawRemainder);
            finishFenceBuffer(remainder);
            return true;
        }
        if (fenceScanCursor > 0) {
            int removed = fenceScanCursor;
            buffer.delete(0, removed);
            rebasePendingFenceBacktickRun(removed);
            fenceScanCursor = 0;
        }
        return false;
    }

    /**
     * In fence-buffering mode: we've seen the opening ``` and the buffer
     * contains only the content AFTER the opening fence. Look for the
     * closing ``` to decide whether to suppress (tool call) or emit (regular code).
     */
    private boolean drainBufferingFence() {
        return drainBufferingFence(false);
    }

    private boolean drainBufferingFence(boolean terminal) {
        ClosingFence close = advanceFenceScanner(
                Math.min(buffer.length(), MAX_JSON_ENVELOPE_BUFFER_CHARS),
                terminal);
        if (close != null) {
            if (close.nestedExecutableOpening()) {
                String genericPrefix = fenceOpening
                        + buffer.substring(0, close.startInclusive());
                String remainder = buffer.substring(close.startInclusive());
                emitPendingProtocolPrefix(false);
                delegate.accept(genericPrefix);
                finishFenceBuffer(remainder);
                return true;
            }
            String fenceContent = buffer.substring(0, close.startInclusive());
            String fullFence = fenceOpening
                    + buffer.substring(0, close.endExclusive());
            // Classification is deliberately envelope-local. A name in a
            // later fence may not change the disposition of this fence.
            boolean potentialToolFence = executableFenceOpening
                    && isPotentialExecutableJsonFence(fullFence);
            boolean protocolLike = executableFenceOpening
                    && isProtocolLikeIncompleteFence(fenceContent);
            boolean toolCallFence = isExactExecutableJsonFence(fullFence)
                    || potentialToolFence
                    || protocolLike;
            boolean emptyJsonFence = executableFenceOpening
                    && isJsonFenceOpening(fenceOpening)
                    && fenceContent.isBlank();
            boolean suppressFence = toolCallFence || emptyJsonFence;
            String rawRemainder = buffer.substring(close.endExclusive());
            if (suppressFence
                    && !terminal
                    && suppressedFenceLineSuffixNeedsMore(rawRemainder)) {
                return false;
            }
            if (!toolCallFence && !emptyJsonFence) {
                // Not a tool call - emit the opening fence + content + closing fence.
                emitPendingProtocolPrefix(false);
                String displayProjection =
                        ToolProtocolText.stripToolCallsPreservingLayout(fullFence);
                if (!displayProjection.isEmpty()) {
                    delegate.accept(displayProjection);
                }
            } else {
                // Tool-call or empty JSON protocol debris - suppress the fence.
                emitPendingProtocolPrefix(true);
            }
            finishFenceBuffer(suppressFence
                    ? stripSuppressedFenceLineSuffix(rawRemainder)
                    : rawRemainder);
            return true;
        }

        if (buffer.length() >= MAX_JSON_ENVELOPE_BUFFER_CHARS) {
            emitPendingProtocolPrefix(true);
            // Preserve a one/two-backtick run held at the scan cursor so a
            // closing delimiter split exactly at the ceiling still settles.
            String overflow = buffer.substring(fenceScanCursor);
            int removed = fenceScanCursor;
            buffer.setLength(0);
            buffer.append(overflow);
            rebasePendingFenceBacktickRun(removed);
            fenceScanCursor = 0;
            state = State.SUPPRESSING_FENCE;
            return true;
        }
        // Still waiting for closing fence
        return false;
    }

    private void finishFenceBuffer(String remainder) {
        buffer.setLength(0);
        buffer.append(remainder);
        fenceOpening = "";
        fenceMarkerLength = 0;
        executableFenceOpening = false;
        resetFenceScanner();
        state = State.PASSTHROUGH;
    }

    /**
     * In passthrough mode: look for opening XML tag or code fence.
     * Returns true if progress was made (should loop again).
     */
    private boolean drainPassthrough() {
        String text = buffer.toString();
        if (pendingProtocolOnlyBlankSuffix && text.isBlank()) return false;

        // Check for XML opening tag
        Matcher om = OPEN_TAG.matcher(text);
        int xmlStart = om.find() ? om.start() : -1;

        // Check for code fence opening
        Matcher fm = CODE_FENCE_OPEN.matcher(text);
        int genericFenceStart = fm.find() ? fm.start() : -1;
        Matcher executableFence = EXECUTABLE_CODE_FENCE_OPEN.matcher(text);
        int executableFenceStart = executableFence.find() ? executableFence.start() : -1;
        boolean executableSharesGenericEnvelope = executableFenceStart >= 0
                && genericFenceStart >= 0
                && executableFenceStart >= genericFenceStart
                && executableFenceStart < genericFenceStart + fm.group(1).length();
        boolean useExecutableFence = executableFenceStart >= 0
                && !executableSharesGenericEnvelope
                && (genericFenceStart < 0
                || executableFenceStart <= genericFenceStart);
        int fenceStart = useExecutableFence ? executableFenceStart : genericFenceStart;

        // Check for bare standalone JSON object opening
        int jsonStart = findBareJsonStart(
                text, passthroughBufferStartsAtStandaloneBoundary);

        // Check for narrow malformed JSON-array protocol debris.
        int arrayStart = findProtocolArrayStart(
                text, passthroughBufferStartsAtLineBoundary);

        // An opening fence owns its still-unsettled info line. JSON, array,
        // or XML-shaped bytes inside that line cannot be reclassified before
        // the provider supplies the line break that settles the opening.
        int incompleteFenceStart = findIncompleteCodeFenceOpeningStart(text);
        int firstSettledCandidate = earliestNonNegative(
                xmlStart, fenceStart, jsonStart, arrayStart);
        if (incompleteFenceStart >= 0
                && (firstSettledCandidate < 0
                || incompleteFenceStart < firstSettledCandidate)) {
            if (incompleteFenceStart > 0) {
                delegate.accept(text.substring(0, incompleteFenceStart));
                String remainder = text.substring(incompleteFenceStart);
                buffer.setLength(0);
                buffer.append(remainder);
                passthroughBufferStartsAtStandaloneBoundary = false;
                passthroughBufferStartsAtLineBoundary = false;
            }
            fenceOpeningScanCursor = 0;
            fenceOpeningMarkerConfirmed = false;
            state = State.BUFFERING_FENCE_OPENING;
            return false;
        }

        // None found - try to emit safe prefix
        if (xmlStart < 0 && fenceStart < 0 && jsonStart < 0 && arrayStart < 0) {
            int safeEnd = findSafeEmitEnd(
                    text, passthroughBufferStartsAtLineBoundary);
            if (safeEnd == text.length() && !text.isEmpty()) {
                // Retain the terminal code point and its predecessor as raw
                // look-behind. If the terminal code point is itself a
                // protocol starter, the next chunk must still see the byte
                // that proved whether it was inline or standalone.
                safeEnd = text.offsetByCodePoints(text.length(), -1);
                if (safeEnd > 0) {
                    safeEnd = text.offsetByCodePoints(safeEnd, -1);
                }
            }
            if (safeEnd > 0) {
                delegate.accept(text.substring(0, safeEnd));
                String remainder = text.substring(safeEnd);
                boolean remainderStartsAtLineBoundary =
                        isStandaloneLineBoundary(
                                text,
                                safeEnd,
                                passthroughBufferStartsAtLineBoundary);
                passthroughBufferStartsAtStandaloneBoundary =
                        Character.isWhitespace(text.charAt(safeEnd - 1));
                passthroughBufferStartsAtLineBoundary =
                        remainderStartsAtLineBoundary;
                buffer.setLength(0);
                buffer.append(remainder);
            }
            return false;
        }

        // Determine which comes first
        int firstPos;
        MatchKind kind;
        if (xmlStart >= 0 && (fenceStart < 0 || xmlStart <= fenceStart)
                && (jsonStart < 0 || xmlStart <= jsonStart)
                && (arrayStart < 0 || xmlStart <= arrayStart)) {
            firstPos = xmlStart;
            kind = MatchKind.XML;
        } else if (fenceStart >= 0
                && (jsonStart < 0 || fenceStart <= jsonStart)
                && (arrayStart < 0 || fenceStart <= arrayStart)) {
            firstPos = fenceStart;
            kind = MatchKind.FENCE;
        } else if (jsonStart >= 0 && (arrayStart < 0 || jsonStart <= arrayStart)) {
            firstPos = jsonStart;
            kind = MatchKind.BARE_JSON;
        } else {
            firstPos = arrayStart;
            kind = MatchKind.PROTOCOL_ARRAY;
        }

        // Emit everything before the first match
        if (firstPos > 0 && kind == MatchKind.XML) {
            delegate.accept(text.substring(0, firstPos));
        } else if (firstPos > 0) {
            pendingProtocolPrefix += text.substring(0, firstPos);
        }

        switch (kind) {
            case XML -> {
                // XML tag - enter XML suppression
                String remainder = text.substring(om.end());
                buffer.setLength(0);
                buffer.append(remainder);
                state = State.SUPPRESSING_XML;
            }
            case FENCE -> {
                // Code fence - enter fence buffering.
                // Store only the content AFTER the opening fence (```json\n)
                // so the close-fence pattern doesn't match the opening fence.
                int fenceEnd = useExecutableFence ? executableFence.end() : fm.end();
                String remainder = text.substring(fenceEnd);
                buffer.setLength(0);
                buffer.append(remainder);
                boolean executableOpening = useExecutableFence
                        || executableSharesGenericEnvelope;
                if (fenceEnd - fenceStart
                                >= MAX_JSON_ENVELOPE_BUFFER_CHARS) {
                    emitPendingProtocolPrefix(true);
                    fenceOpening = "";
                    fenceMarkerLength = useExecutableFence
                            ? CODE_FENCE_CLOSE.length()
                            : fm.group(1).length();
                    executableFenceOpening = true;
                    resetFenceScanner();
                    state = State.SUPPRESSING_FENCE;
                    break;
                }
                // Remember the opening fence text for re-emission if it turns out
                // to be a non-tool-call code fence.
                fenceOpening = text.substring(fenceStart, fenceEnd);
                fenceMarkerLength = useExecutableFence ? 3 : fm.group(1).length();
                executableFenceOpening = executableOpening;
                resetFenceScanner();
                state = State.BUFFERING_FENCE;
            }
            case BARE_JSON -> {
                String remainder = text.substring(firstPos);
                buffer.setLength(0);
                buffer.append(remainder);
                resetBareJsonScanner();
                state = State.BUFFERING_BARE_JSON;
            }
            case PROTOCOL_ARRAY -> {
                String remainder = text.substring(firstPos);
                buffer.setLength(0);
                buffer.append(remainder);
                state = State.BUFFERING_PROTOCOL_ARRAY;
            }
        }
        passthroughBufferStartsAtStandaloneBoundary = true;
        passthroughBufferStartsAtLineBoundary = true;
        return true;
    }

    /**
     * Find the safe-to-emit boundary: everything before a potential partial
     * opening tag or code fence at the end of the buffer.
     *
     * <p>Scans backward from the end looking for {@code <} that could be
     * the start of an opening tag prefix, or {@code `} that could be the
     * start of a code fence. Returns the index up to which content can
     * safely be emitted, or the full length if no partial match.
     */
    private static int findSafeEmitEnd(
            String text,
            boolean bufferStartsAtLineBoundary) {
        int len = text.length();
        int safeEnd = len;
        // Scan from end for the bounded XML prefixes.
        int scanFrom = Math.max(0, len - 16);

        for (int i = len - 1; i >= scanFrom; i--) {
            char c = text.charAt(i);
            if (c == '<') {
                String tail = text.substring(i);
                if (couldBeOpenTagPrefix(tail)) {
                    safeEnd = Math.min(safeEnd, i);
                }
            }
        }

        // Fence info strings may contain an arbitrary number of horizontal
        // spaces. Search the complete unfinished line rather than applying a
        // fixed tail bound that can emit the start of a split opener.
        int unfinishedLineStart = unfinishedLineStart(text);
        for (int i = unfinishedLineStart; i < len; i++) {
            if (text.charAt(i) != '`') continue;
            if (couldBeCodeFenceOpenPrefix(text, i, len)) {
                safeEnd = Math.min(safeEnd, i);
                break;
            }
        }

        for (int i = unfinishedLineStart; i < len; i++) {
            if (text.charAt(i) != '[') continue;
            if (!isStandaloneLineBoundary(
                    text, i, bufferStartsAtLineBoundary)) continue;
            if (couldBeginProtocolArray(text, i)) {
                safeEnd = Math.min(safeEnd, i);
                break;
            }
        }

        return safeEnd;
    }

    private static int findIncompleteCodeFenceOpeningStart(String text) {
        int lineStart = unfinishedLineStart(text);
        for (int index = lineStart; index < text.length(); index++) {
            if (text.charAt(index) != '`') continue;
            if (couldBeCodeFenceOpenPrefix(
                    text, index, text.length())) {
                return index;
            }
        }
        return -1;
    }

    private static int earliestNonNegative(int... candidates) {
        int first = -1;
        for (int candidate : candidates) {
            if (candidate >= 0 && (first < 0 || candidate < first)) {
                first = candidate;
            }
        }
        return first;
    }

    private static int unfinishedLineStart(String text) {
        for (int index = text.length() - 1; index >= 0; index--) {
            char c = text.charAt(index);
            if (c == '\r' || c == '\n' || c == '\u000B' || c == '\f'
                    || c == '\u0085' || c == '\u2028' || c == '\u2029') {
                return index + 1;
            }
        }
        return 0;
    }

    private enum MatchKind { XML, FENCE, BARE_JSON, PROTOCOL_ARRAY }

    private static int findBareJsonStart(
            String text,
            boolean bufferStartsAtStandaloneBoundary) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '{') continue;
            if (!isStandaloneBoundary(
                    text, i, bufferStartsAtStandaloneBoundary)) continue;
            if (couldBeginJsonObject(text, i)) return i;
        }
        return -1;
    }

    private static int findProtocolArrayStart(
            String text,
            boolean bufferStartsAtLineBoundary) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '[') continue;
            if (!isStandaloneLineBoundary(
                    text, i, bufferStartsAtLineBoundary)) continue;
            if (couldBeginProtocolArray(text, i)) return i;
        }
        return -1;
    }

    private static boolean isStandaloneBoundary(
            String text,
            int braceIndex,
            boolean bufferStartsAtStandaloneBoundary) {
        if (braceIndex <= 0) return bufferStartsAtStandaloneBoundary;
        char prev = text.charAt(braceIndex - 1);
        return Character.isWhitespace(prev);
    }

    private static boolean isStandaloneLineBoundary(
            String text,
            int index,
            boolean bufferStartsAtLineBoundary) {
        if (index <= 0) return bufferStartsAtLineBoundary;
        for (int i = index - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') return true;
            if (!Character.isWhitespace(c)) return false;
        }
        return bufferStartsAtLineBoundary;
    }

    private static boolean couldBeginJsonObject(String text, int braceIndex) {
        int i = braceIndex + 1;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= text.length()) return true;
        char c = text.charAt(i);
        return c == '"' || c == '}';
    }

    private static boolean couldBeginProtocolArray(String text, int bracketIndex) {
        int i = bracketIndex + 1;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= text.length()) return true;
        char c = text.charAt(i);
        return c == ',' || c == ']';
    }

    /** Advance each newly appended bare-JSON character exactly once. */
    private BareJsonScanResult advanceBareJsonScanner(int scanLimitExclusive) {
        int limit = Math.min(scanLimitExclusive, buffer.length());
        while (bareJsonScanCursor < limit) {
            int index = bareJsonScanCursor;
            char c = buffer.charAt(index);

            if (index == 0) {
                bareJsonScanCursor++;
                if (c != '{') {
                    return BareJsonScanResult.notJsonObject(bareJsonScanCursor);
                }
                bareJsonDepth = 1;
                continue;
            }

            if (bareJsonQuote != 0) {
                bareJsonScanCursor++;
                if (bareJsonEscaped) {
                    bareJsonEscaped = false;
                } else if (c == '\\') {
                    bareJsonEscaped = true;
                } else if (c == bareJsonQuote) {
                    bareJsonQuote = 0;
                }
                continue;
            }

            if (!bareJsonFirstMemberSeen && bareJsonDepth == 1) {
                if (Character.isWhitespace(c)) {
                    bareJsonScanCursor++;
                    continue;
                }
                if (c == '}') {
                    bareJsonDepth = 0;
                    bareJsonScanCursor++;
                    return BareJsonScanResult.complete(bareJsonScanCursor);
                }
                if (c != '"') {
                    return BareJsonScanResult.notJsonObject(index);
                }
                bareJsonFirstMemberSeen = true;
            }

            bareJsonScanCursor++;
            if (c == '"') {
                bareJsonQuote = c;
            } else if (c == '{') {
                bareJsonDepth++;
            } else if (c == '}') {
                bareJsonDepth--;
                if (bareJsonDepth == 0) {
                    return BareJsonScanResult.complete(bareJsonScanCursor);
                }
                if (bareJsonDepth < 0) {
                    return BareJsonScanResult.notJsonObject(bareJsonScanCursor);
                }
            }
        }
        return BareJsonScanResult.incomplete();
    }

    private record BareJsonScanResult(Kind kind, int endExclusive) {
        enum Kind { INCOMPLETE, NOT_JSON_OBJECT, COMPLETE }

        static BareJsonScanResult incomplete() {
            return new BareJsonScanResult(Kind.INCOMPLETE, -1);
        }

        static BareJsonScanResult notJsonObject(int releaseEndExclusive) {
            return new BareJsonScanResult(
                    Kind.NOT_JSON_OBJECT, releaseEndExclusive);
        }

        static BareJsonScanResult complete(int endExclusive) {
            return new BareJsonScanResult(Kind.COMPLETE, endExclusive);
        }
    }

    private static ProtocolArrayDecision classifyProtocolArrayPrefix(String text) {
        if (text == null || text.isEmpty() || text.charAt(0) != '[') {
            return ProtocolArrayDecision.notProtocol(
                    text == null ? 0 : Math.min(1, text.length()));
        }
        boolean sawComma = false;
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ']') {
                return sawComma
                        ? ProtocolArrayDecision.suppress(i + 1)
                        : ProtocolArrayDecision.notProtocol(i + 1);
            }
            if (c == ',') {
                sawComma = true;
            } else if (!Character.isWhitespace(c)) {
                return ProtocolArrayDecision.notProtocol(i);
            }
        }
        return ProtocolArrayDecision.waitForMore();
    }

    private record ProtocolArrayDecision(Kind kind, int endExclusive) {
        enum Kind { WAIT, NOT_PROTOCOL, SUPPRESS }

        static ProtocolArrayDecision waitForMore() {
            return new ProtocolArrayDecision(Kind.WAIT, -1);
        }

        static ProtocolArrayDecision notProtocol(int releaseEndExclusive) {
            return new ProtocolArrayDecision(
                    Kind.NOT_PROTOCOL, releaseEndExclusive);
        }

        static ProtocolArrayDecision suppress(int endExclusive) {
            return new ProtocolArrayDecision(Kind.SUPPRESS, endExclusive);
        }
    }

    private static boolean looksLikeIncompleteBareToolJson(String text) {
        return ToolProtocolText.containsPotentialExecutableToolNameField(text)
                || ToolProtocolText.containsPotentialExecutableToolNamePrefix(text);
    }

    private static boolean isProtocolLikeIncompleteFence(String fenceContent) {
        return looksLikeIncompleteBareToolJson(fenceContent);
    }

    private static boolean isPotentialExecutableStandaloneJson(String candidate) {
        for (ToolProtocolText.PotentialJsonEnvelopeSpan span
                : ToolProtocolText.potentialExecutableStandaloneJsonSpans(candidate)) {
            if (span.startInclusive() == 0
                    && span.endExclusive() == candidate.length()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExactExecutableJsonFence(String fullFence) {
        List<ToolProtocolText.SelectedExecutableJsonSpan> spans =
                ToolProtocolText.recognizedExecutableJsonSpans(fullFence);
        return spans.size() == 1
                && spans.get(0).kind() == ToolProtocolText.JsonProtocolKind.FENCED_JSON
                && spans.get(0).envelopeStartInclusive() == 0
                && spans.get(0).envelopeEndExclusive() == fullFence.length();
    }

    private static boolean isPotentialExecutableJsonFence(String fullFence) {
        for (ToolProtocolText.PotentialJsonEnvelopeSpan span
                : ToolProtocolText.potentialExecutableJsonEnvelopeSpans(fullFence)) {
            if (span.closed()
                    && span.startInclusive() == 0
                    && span.endExclusive() == fullFence.length()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A suppressed standalone fence owns the horizontal padding and one line
     * terminator immediately after its closing delimiter. This preserves the
     * established display contract without changing the canonical fence span,
     * which ends at the closing backticks.
     */
    private static String stripSuppressedFenceLineSuffix(String remainder) {
        int index = 0;
        while (index < remainder.length()) {
            char c = remainder.charAt(index);
            if (c != ' ' && c != '\t') break;
            index++;
        }
        if (index >= remainder.length()) {
            return "";
        }
        if (remainder.startsWith("\r\n", index)) {
            return remainder.substring(index + 2);
        }
        char c = remainder.charAt(index);
        if (c == '\r'
                || c == '\n'
                || c == '\u000B'
                || c == '\f'
                || c == '\u0085'
                || c == '\u2028'
                || c == '\u2029') {
            return remainder.substring(index + 1);
        }
        return remainder;
    }

    private static boolean suppressedFenceLineSuffixNeedsMore(String remainder) {
        int index = 0;
        while (index < remainder.length()) {
            char c = remainder.charAt(index);
            if (c != ' ' && c != '\t') break;
            index++;
        }
        return index == remainder.length()
                || index + 1 == remainder.length()
                && remainder.charAt(index) == '\r';
    }

    /**
     * Advance the current fence scanner without rescanning prior chunks.
     * Backtick runs inside a JSON string are payload, not closing delimiters.
     */
    private ClosingFence advanceFenceScanner(int scanLimitExclusive) {
        return advanceFenceScanner(scanLimitExclusive, false);
    }

    private ClosingFence advanceFenceScanner(
            int scanLimitExclusive,
            boolean terminal) {
        if (fenceMarkerLength < CODE_FENCE_CLOSE.length()) return null;
        int limit = Math.min(scanLimitExclusive, buffer.length());
        while (fenceScanCursor < limit) {
            char c = buffer.charAt(fenceScanCursor);

            if (!fenceContentKindSettled) {
                if (Character.isWhitespace(c)) {
                    updateFenceLinePrefix(c);
                    fenceScanCursor++;
                    continue;
                }
                fenceContentKindSettled = true;
                // Match ToolProtocolText's structural fence partition for
                // every fence kind. A quoted delimiter in ordinary code must
                // not consume the envelope that follows it.
                fenceQuoteAwareContent = true;
            }

            if (!executableFenceOpening && c == '`') {
                int runStart = fenceScanCursor;
                int runEnd = advancePendingFenceBacktickRun(runStart, limit);
                if (runEnd == limit && !terminal) {
                    fenceScanCursor = runStart;
                    return null;
                }
                if (fenceLinePrefixWhitespace
                        && runEnd - runStart >= CODE_FENCE_CLOSE.length()) {
                    int closeEnd = settledGenericClosingFenceEnd(
                            runStart, runEnd, limit, terminal);
                    if (closeEnd < 0) {
                        fenceScanCursor = runStart;
                        return null;
                    }
                    if (closeEnd > 0) {
                        return ClosingFence.close(runStart, closeEnd);
                    }
                    int nestedOpeningEnd = settledNestedExecutableFenceOpeningEnd(
                            runEnd, limit, terminal);
                    if (nestedOpeningEnd < 0) {
                        fenceScanCursor = runStart;
                        return null;
                    }
                    if (nestedOpeningEnd > 0) {
                        return ClosingFence.nestedExecutableOpening(runStart);
                    }
                }
                clearPendingFenceBacktickRun();
                fenceLinePrefixWhitespace = false;
                fenceScanCursor = runEnd;
                continue;
            }

            if (fenceQuoteAwareContent && fenceQuote != 0) {
                if (!fenceQuoteEscaped && c == '`') {
                    int runStart = fenceScanCursor;
                    int runEnd = advancePendingFenceBacktickRun(runStart, limit);
                    if (runEnd == limit && !terminal) {
                        // A later chunk may extend this delimiter run. Hold it
                        // so accepted envelopes never expose surplus ticks.
                        fenceScanCursor = runStart;
                        return null;
                    }
                    if (runEnd - runStart >= CODE_FENCE_CLOSE.length()
                            && ToolProtocolText.containsPotentialExecutableToolNamePrefix(
                            buffer.substring(0, runStart))) {
                        // A truncated name value followed by a delimiter is
                        // protocol debris. Backticks in ordinary argument
                        // strings remain JSON payload.
                        int closeEnd = settledClosingFenceEnd(
                                runStart, runEnd, limit, terminal);
                        if (closeEnd < 0) {
                            fenceScanCursor = runStart;
                            return null;
                        }
                        return ClosingFence.close(runStart, closeEnd);
                    }
                    clearPendingFenceBacktickRun();
                    fenceScanCursor = runEnd;
                    continue;
                }
                updateFenceLinePrefix(c);
                fenceScanCursor++;
                if (fenceQuoteEscaped) {
                    fenceQuoteEscaped = false;
                } else if (c == '\\') {
                    fenceQuoteEscaped = true;
                } else if (c == fenceQuote) {
                    fenceQuote = 0;
                }
                continue;
            }

            if (fenceQuoteAwareContent && c == '"') {
                fenceQuote = c;
                updateFenceLinePrefix(c);
                fenceScanCursor++;
                continue;
            }

            if (c != '`') {
                updateFenceLinePrefix(c);
                fenceScanCursor++;
                continue;
            }

            int runStart = fenceScanCursor;
            int runEnd = advancePendingFenceBacktickRun(runStart, limit);
            if (runEnd == limit && !terminal) {
                // The run may complete or grow in the next provider delta.
                fenceScanCursor = runStart;
                return null;
            }
            if (runEnd - runStart >= CODE_FENCE_CLOSE.length()) {
                int closeEnd = settledClosingFenceEnd(
                        runStart, runEnd, limit, terminal);
                if (closeEnd < 0) {
                    fenceScanCursor = runStart;
                    return null;
                }
                return ClosingFence.close(runStart, closeEnd);
            }
            clearPendingFenceBacktickRun();
            fenceScanCursor = runEnd;
            fenceLinePrefixWhitespace = false;
        }
        return null;
    }

    private int advancePendingFenceBacktickRun(int runStart, int limit) {
        int runEnd = pendingFenceBacktickRunStart == runStart
                ? Math.max(runStart, pendingFenceBacktickRunEnd)
                : runStart;
        while (runEnd < limit && buffer.charAt(runEnd) == '`') {
            runEnd++;
        }
        pendingFenceBacktickRunStart = runStart;
        pendingFenceBacktickRunEnd = runEnd;
        return runEnd;
    }

    private void clearPendingFenceBacktickRun() {
        pendingFenceBacktickRunStart = -1;
        pendingFenceBacktickRunEnd = -1;
    }

    private void rebasePendingFenceBacktickRun(int removedPrefixLength) {
        if (pendingFenceBacktickRunStart < removedPrefixLength) {
            clearPendingFenceBacktickRun();
            return;
        }
        pendingFenceBacktickRunStart -= removedPrefixLength;
        pendingFenceBacktickRunEnd -= removedPrefixLength;
    }

    private int settledGenericClosingFenceEnd(
            int runStart,
            int runEnd,
            int scanLimitExclusive,
            boolean terminal) {
        int ownedEnd = settledClosingFenceEnd(
                runStart, runEnd, scanLimitExclusive, terminal);
        if (ownedEnd < 0) return -1;
        if (ownedEnd == runStart + CODE_FENCE_CLOSE.length()
                && runEnd - runStart >= CODE_FENCE_CLOSE.length() * 2) {
            return ownedEnd;
        }

        for (int index = runEnd; index < scanLimitExclusive; index++) {
            char value = buffer.charAt(index);
            if (value == ' ' || value == '\t') continue;
            if (isFenceLineBreak(value)) return ownedEnd;
            return 0;
        }
        return terminal ? ownedEnd : -1;
    }

    /**
     * Distinguishes a complete executable fence opening from ordinary text
     * after a run that cannot close the current generic fence. The returned
     * value is positive for a complete opening, zero for a disproved opening,
     * and negative while a later chunk can still complete the info line.
     */
    private int settledNestedExecutableFenceOpeningEnd(
            int infoStart,
            int scanLimitExclusive,
            boolean terminal) {
        int cursor = infoStart;
        int jsonIndex = 0;
        boolean sawJson = false;
        boolean trailingSpacing = false;
        while (cursor < scanLimitExclusive) {
            char value = buffer.charAt(cursor);
            if (isFenceLineBreak(value)) {
                if (!sawJson && jsonIndex == 0 || sawJson && jsonIndex == 4) {
                    if (value == '\r'
                            && cursor + 1 < scanLimitExclusive
                            && buffer.charAt(cursor + 1) == '\n') {
                        return cursor + 2;
                    }
                    return cursor + 1;
                }
                return 0;
            }
            if (value == ' ' || value == '\t') {
                if (jsonIndex > 0 && jsonIndex < 4) return 0;
                trailingSpacing = sawJson;
                cursor++;
                continue;
            }
            if (trailingSpacing || jsonIndex >= 4
                    || value != "json".charAt(jsonIndex)) {
                return 0;
            }
            jsonIndex++;
            sawJson = jsonIndex == 4;
            cursor++;
        }
        if (terminal) return 0;
        return jsonIndex <= 4 ? -1 : 0;
    }

    private void updateFenceLinePrefix(char value) {
        if (isFenceLineBreak(value)) {
            fenceLinePrefixWhitespace = true;
        } else if (value != ' ' && value != '\t') {
            fenceLinePrefixWhitespace = false;
        }
    }

    private static boolean isFenceLineBreak(char value) {
        return value == '\r'
                || value == '\n'
                || value == '\u000B'
                || value == '\f'
                || value == '\u0085'
                || value == '\u2028'
                || value == '\u2029';
    }

    /**
     * Own surplus closing ticks without consuming an immediately adjacent
     * opening fence. The latter is held until its complete info line settles,
     * so the decision is independent of provider chunk boundaries.
     */
    private int settledClosingFenceEnd(
            int runStart,
            int runEnd,
            int scanLimitExclusive,
            boolean terminal) {
        int adjacentOpeningStart = runStart + CODE_FENCE_CLOSE.length();
        if (runEnd - adjacentOpeningStart < CODE_FENCE_CLOSE.length()) {
            return runEnd;
        }

        String possibleOpening = buffer.substring(
                adjacentOpeningStart, scanLimitExclusive);
        Matcher opening = CODE_FENCE_OPEN.matcher(possibleOpening);
        if (opening.lookingAt()) {
            return adjacentOpeningStart;
        }
        if (!terminal && couldBeCodeFenceOpenPrefix(possibleOpening)) {
            return -1;
        }
        return runEnd;
    }

    private record ClosingFence(
            int startInclusive,
            int endExclusive,
            boolean nestedExecutableOpening) {
        private static ClosingFence close(int startInclusive, int endExclusive) {
            return new ClosingFence(startInclusive, endExclusive, false);
        }

        private static ClosingFence nestedExecutableOpening(int startInclusive) {
            return new ClosingFence(startInclusive, startInclusive, true);
        }
    }

    private void emitPendingProtocolPrefix(boolean suppressingProtocol) {
        if (pendingProtocolPrefix.isEmpty()) return;
        String prefix = pendingProtocolPrefix;
        pendingProtocolPrefix = "";
        if (suppressingProtocol && looksLikeSpeculativePreToolProse(prefix)) {
            return;
        }
        delegate.accept(prefix);
    }

    private static boolean isJsonFenceOpening(String opening) {
        return opening != null && "```json".equalsIgnoreCase(opening.trim());
    }

    private static boolean looksLikeSpeculativePreToolProse(String text) {
        return text != null
                && text.length() <= 1000
                && SPECULATIVE_PRE_TOOL_PROSE.matcher(text).find();
    }

    /**
     * Returns true if {@code s} is a prefix of any known opening tag.
     */
    static boolean couldBeOpenTagPrefix(String s) {
        for (String tag : OPEN_TAG_STRINGS) {
            if (tag.startsWith(s)) return true;
        }
        return false;
    }

    static boolean couldBeCodeFenceOpenPrefix(String s) {
        if (s == null || s.isEmpty()) return false;
        return couldBeCodeFenceOpenPrefix(s, 0, s.length());
    }

    private static boolean couldBeCodeFenceOpenPrefix(
            String source,
            int startInclusive,
            int endExclusive) {
        int length = endExclusive - startInclusive;
        if (length <= 0) return false;
        int shared = Math.min(CODE_FENCE_PREFIX.length(), length);
        for (int index = 0; index < shared; index++) {
            if (source.charAt(startInclusive + index)
                    != CODE_FENCE_PREFIX.charAt(index)) return false;
        }
        if (length <= CODE_FENCE_PREFIX.length()) return true;
        for (int index = startInclusive + CODE_FENCE_PREFIX.length();
             index < endExclusive;
             index++) {
            char c = source.charAt(index);
            if (c == '\r' || c == '\n' || c == '\u000B' || c == '\f'
                    || c == '\u0085' || c == '\u2028' || c == '\u2029') {
                return false;
            }
        }
        return true;
    }

    private boolean drainSuppressingBareJson() {
        String text = buffer.toString();
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (suppressedJsonQuote != 0) {
                if (suppressedJsonEscaped) {
                    suppressedJsonEscaped = false;
                } else if (c == '\\') {
                    suppressedJsonEscaped = true;
                } else if (c == suppressedJsonQuote) {
                    suppressedJsonQuote = 0;
                }
                continue;
            }
            if (c == '"') {
                suppressedJsonQuote = c;
            } else if (c == '{') {
                suppressedJsonDepth++;
            } else if (c == '}') {
                suppressedJsonDepth--;
                if (suppressedJsonDepth <= 0) {
                    String remainder = text.substring(index + 1);
                    buffer.setLength(0);
                    buffer.append(remainder);
                    resetSuppressedJsonState();
                    state = State.PASSTHROUGH;
                    return true;
                }
            }
        }
        buffer.setLength(0);
        return false;
    }

    private void beginSuppressingBareJson(String text) {
        suppressedJsonDepth = bareJsonDepth;
        suppressedJsonQuote = bareJsonQuote;
        suppressedJsonEscaped = bareJsonEscaped;
        buffer.setLength(0);
        buffer.append(text);
        resetBareJsonScanner();
        state = State.SUPPRESSING_BARE_JSON;
    }

    private void resetSuppressedJsonState() {
        suppressedJsonDepth = 0;
        suppressedJsonQuote = 0;
        suppressedJsonEscaped = false;
    }

    private void resetBareJsonScanner() {
        bareJsonScanCursor = 0;
        bareJsonDepth = 0;
        bareJsonQuote = 0;
        bareJsonEscaped = false;
        bareJsonFirstMemberSeen = false;
    }

    private void resetFenceScanner() {
        fenceScanCursor = 0;
        clearPendingFenceBacktickRun();
        fenceQuote = 0;
        fenceQuoteEscaped = false;
        fenceContentKindSettled = false;
        fenceQuoteAwareContent = false;
        fenceLinePrefixWhitespace = true;
    }
}

