package dev.talos.runtime;

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
 *   <li><b>Native tool calls (primary path)</b> never appear in the text stream —
 *       they are emitted as {@link dev.talos.spi.types.TokenChunk#ofToolCalls} chunks
 *       and captured directly by {@link dev.talos.core.llm.LlmClient#chatStreamFull},
 *       bypassing this filter entirely.</li>
 *   <li><b>JSON code fences (active text fallback)</b> — suppressed when the content
 *       matches a tool-call signature ({@code "name": "talos."}).</li>
 *   <li><b>Bare standalone JSON (compat fallback)</b> — buffered until a complete
 *       top-level object is available, then suppressed only if it parses as a
 *       Talos tool call.</li>
 *   <li><b>XML tags (deprecated compatibility)</b> — {@code <tool_call>},
 *       {@code <function_call>}, {@code <tool>}, {@code <function>} — retained
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
    /** Saved opening fence text (e.g. "```json\n") for re-emission of non-tool fences. */
    private String fenceOpening = "";
    /** Text immediately before a JSON protocol candidate, held until the candidate is classified. */
    private String pendingProtocolPrefix = "";

    /** Current suppression state.
     *  SUPPRESSING_XML is DEPRECATED compatibility-only (for models that emit XML from training).
     *  Scheduled for removal once native tool calling is stable across model versions. */
    private enum State { PASSTHROUGH, SUPPRESSING_XML, BUFFERING_FENCE, SUPPRESSING_FENCE, BUFFERING_BARE_JSON }
    private State state = State.PASSTHROUGH;

    /** Opening XML tags that start suppression.
     *  DEPRECATED COMPATIBILITY ONLY — retained for models that emit XML from training habits.
     *  Scheduled for removal. */
    private static final Pattern OPEN_TAG = Pattern.compile(
            "<(tool_call|function_call|tool|function)>"
    );

    /** Closing XML tags that end suppression.
     *  DEPRECATED COMPATIBILITY ONLY — retained alongside OPEN_TAG.
     *  Scheduled for removal. */
    private static final Pattern CLOSE_TAG = Pattern.compile(
            "</(tool_call|function_call|tool|function)>"
    );

    /** All possible opening XML tag strings (for prefix matching at chunk boundaries).
     *  DEPRECATED COMPATIBILITY ONLY — retained alongside OPEN_TAG.
     *  Scheduled for removal. */
    private static final String[] OPEN_TAG_STRINGS = {
            "<tool_call>", "<function_call>", "<tool>", "<function>"
    };

    /** Opening code fence that could start a tool-call block. */
    private static final Pattern CODE_FENCE_OPEN = Pattern.compile("```(?:json)?[ \\t]*\\R");

    /** Closing code fence at the start of a line. Some models put adjacent JSON immediately after it. */
    private static final Pattern CODE_FENCE_CLOSE = Pattern.compile(
            "\\R```(?:[ \\t]*\\R|[ \\t]*(?=\\S|$))");

    /** All possible code fence opening prefixes (for chunk boundary detection). */
    private static final String CODE_FENCE_PREFIX = "```";

    /** Upper bound for speculative bare-JSON buffering in the display path. */
    private static final int MAX_BARE_JSON_BUFFER_CHARS = 2 * 1024 * 1024;

    /** Incomplete bare JSON tool-call signature used only during flush. */
    private static final Pattern INCOMPLETE_BARE_TOOL_JSON = Pattern.compile(
            "\"(?:name|function|tool_name|tool)\"\\s*:\\s*\"(?:talos[.:/_-])?"
                    + "(?:read_file|write_file|edit_file|list_dir|grep|retrieve|"
                    + "file_write|file_read|file_edit|list_directory|dir_list|ls|"
                    + "search|writefile|readfile|editfile|listdir|listdirectory|grepsearch)\"",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

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
        buffer.append(chunk);
        drain();
    }

    /**
     * Flush any remaining buffered content to the delegate.
     *
     * <p>Call this after the stream completes (e.g., after {@code chatStream()} returns).
     * If currently inside a suppressed block, the partial block is discarded (it was
     * tool-call content that never closed — safe to drop). If buffering a code fence
     * that never completed, the buffered content is emitted (it was not a tool call).
     */
    public void flush() {
        if (buffer.length() > 0 || !fenceOpening.isEmpty()) {
            switch (state) {
                case PASSTHROUGH:
                    emitPendingProtocolPrefix(false);
                    delegate.accept(buffer.toString());
                    break;
                case BUFFERING_FENCE:
                    if (isJsonFenceOpening(fenceOpening) && buffer.toString().isBlank()) {
                        // Blank, incomplete JSON fence — protocol debris.
                        emitPendingProtocolPrefix(true);
                    } else {
                        // Never completed — emit opening fence + content as regular text
                        emitPendingProtocolPrefix(false);
                        delegate.accept(fenceOpening + buffer.toString());
                    }
                    break;
                case BUFFERING_BARE_JSON:
                    if (looksLikeIncompleteBareToolJson(buffer.toString())) {
                        // Incomplete protocol debris — discard
                        emitPendingProtocolPrefix(true);
                    } else {
                        emitPendingProtocolPrefix(false);
                        delegate.accept(buffer.toString());
                    }
                    break;
                case SUPPRESSING_XML:
                case SUPPRESSING_FENCE:
                    // Incomplete tool-call block — discard
                    emitPendingProtocolPrefix(true);
                    break;
            }
        }
        buffer.setLength(0);
        fenceOpening = "";
        pendingProtocolPrefix = "";
        state = State.PASSTHROUGH;
    }

    /**
     * Reset state without flushing (e.g., between turns).
     */
    public void reset() {
        buffer.setLength(0);
        fenceOpening = "";
        pendingProtocolPrefix = "";
        state = State.PASSTHROUGH;
    }

    // ── Internal drain loop ──────────────────────────────────────────────

    private void drain() {
        // Process buffer until no more progress can be made
        while (buffer.length() > 0) {
            boolean progress = switch (state) {
                case SUPPRESSING_XML -> drainSuppressingXml();
                case SUPPRESSING_FENCE -> drainSuppressingFence();
                case BUFFERING_FENCE -> drainBufferingFence();
                case BUFFERING_BARE_JSON -> drainBufferingBareJson();
                case PASSTHROUGH -> drainPassthrough();
            };
            if (!progress) break;
        }
    }

    /**
     * DEPRECATED COMPATIBILITY ONLY: In XML suppressing mode — look for closing tag.
     * Retained temporarily for models that emit XML tool-call tags from training habits.
     * Not actively instructed. Scheduled for removal.
     * Returns true if progress was made (should loop again).
     */
    private boolean drainSuppressingXml() {
        Matcher cm = CLOSE_TAG.matcher(buffer);
        if (cm.find()) {
            // Found closing tag — discard everything up to and including it
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

    /**
     * In bare-JSON buffering mode: wait until a complete top-level JSON object
     * is available, then suppress only Talos tool-call objects.
     */
    private boolean drainBufferingBareJson() {
        String text = buffer.toString();
        if (text.isEmpty()) return false;

        if (!couldStillBeJsonObject(text)) {
            emitPendingProtocolPrefix(false);
            delegate.accept(text);
            buffer.setLength(0);
            state = State.PASSTHROUGH;
            return true;
        }

        int objectEnd = findCompleteJsonObjectEnd(text);
        if (objectEnd < 0) {
            if (buffer.length() > MAX_BARE_JSON_BUFFER_CHARS) {
                delegate.accept(buffer.toString());
                buffer.setLength(0);
                state = State.PASSTHROUGH;
                return true;
            }
            return false;
        }

        String candidate = text.substring(0, objectEnd + 1);
        String remainder = text.substring(objectEnd + 1);
        boolean toolProtocol = ToolCallParser.looksLikeStandaloneToolJson(candidate)
                || looksLikeIncompleteBareToolJson(candidate);
        if (!toolProtocol) {
            emitPendingProtocolPrefix(false);
            delegate.accept(candidate);
        } else {
            emitPendingProtocolPrefix(true);
        }
        buffer.setLength(0);
        buffer.append(remainder);
        state = State.PASSTHROUGH;
        return true;
    }

    /**
     * In fence-suppressing mode: look for closing ```.
     * Returns true if progress was made.
     */
    private boolean drainSuppressingFence() {
        String text = buffer.toString();
        Matcher cm = CODE_FENCE_CLOSE.matcher(text);
        if (cm.find()) {
            String remainder = text.substring(cm.end());
            buffer.setLength(0);
            buffer.append(remainder);
            state = State.PASSTHROUGH;
            return true;
        }
        return false;
    }

    /**
     * In fence-buffering mode: we've seen the opening ``` and the buffer
     * contains only the content AFTER the opening fence. Look for the
     * closing ``` to decide whether to suppress (tool call) or emit (regular code).
     */
    private boolean drainBufferingFence() {
        String text = buffer.toString();
        Matcher cm = CODE_FENCE_CLOSE.matcher(text);
        if (cm.find()) {
            // We have the full code fence content — check if it's a tool call
            String fenceContent = text.substring(0, cm.start());
            boolean toolCallFence = ToolCallParser.looksLikeStandaloneToolJson(fenceContent)
                    || looksLikeIncompleteBareToolJson(fenceContent);
            boolean emptyJsonFence = isJsonFenceOpening(fenceOpening) && fenceContent.isBlank();
            if (toolCallFence || emptyJsonFence) {
                // Tool-call or empty JSON protocol debris — suppress the fence.
                emitPendingProtocolPrefix(true);
                String remainder = text.substring(cm.end());
                buffer.setLength(0);
                buffer.append(remainder);
                fenceOpening = "";
                state = State.PASSTHROUGH;
                return true;
            } else {
                // Not a tool call — emit the opening fence + content + closing fence
                emitPendingProtocolPrefix(false);
                String full = fenceOpening + text.substring(0, cm.end());
                String remainder = text.substring(cm.end());
                delegate.accept(full);
                buffer.setLength(0);
                buffer.append(remainder);
                fenceOpening = "";
                state = State.PASSTHROUGH;
                return true;
            }
        }
        // Still waiting for closing fence
        return false;
    }

    /**
     * In passthrough mode: look for opening XML tag or code fence.
     * Returns true if progress was made (should loop again).
     */
    private boolean drainPassthrough() {
        String text = buffer.toString();

        // Check for XML opening tag
        Matcher om = OPEN_TAG.matcher(text);
        int xmlStart = om.find() ? om.start() : -1;

        // Check for code fence opening
        Matcher fm = CODE_FENCE_OPEN.matcher(text);
        int fenceStart = fm.find() ? fm.start() : -1;

        // Check for bare standalone JSON object opening
        int jsonStart = findBareJsonStart(text);

        // None found — try to emit safe prefix
        if (xmlStart < 0 && fenceStart < 0 && jsonStart < 0) {
            int safeEnd = findSafeEmitEnd(text);
            if (safeEnd > 0) {
                delegate.accept(text.substring(0, safeEnd));
                String remainder = text.substring(safeEnd);
                buffer.setLength(0);
                buffer.append(remainder);
            }
            return false;
        }

        // Determine which comes first
        int firstPos;
        MatchKind kind;
        if (xmlStart >= 0 && (fenceStart < 0 || xmlStart <= fenceStart)
                && (jsonStart < 0 || xmlStart <= jsonStart)) {
            firstPos = xmlStart;
            kind = MatchKind.XML;
        } else if (fenceStart >= 0 && (jsonStart < 0 || fenceStart <= jsonStart)) {
            firstPos = fenceStart;
            kind = MatchKind.FENCE;
        } else {
            firstPos = jsonStart;
            kind = MatchKind.BARE_JSON;
        }

        // Emit everything before the first match
        if (firstPos > 0 && kind == MatchKind.XML) {
            delegate.accept(text.substring(0, firstPos));
        } else if (firstPos > 0) {
            pendingProtocolPrefix += text.substring(0, firstPos);
        }

        switch (kind) {
            case XML -> {
                // XML tag — enter XML suppression
                String remainder = text.substring(om.end());
                buffer.setLength(0);
                buffer.append(remainder);
                state = State.SUPPRESSING_XML;
            }
            case FENCE -> {
                // Code fence — enter fence buffering.
                // Store only the content AFTER the opening fence (```json\n)
                // so the close-fence pattern doesn't match the opening fence.
                String remainder = text.substring(fm.end());
                buffer.setLength(0);
                buffer.append(remainder);
                // Remember the opening fence text for re-emission if it turns out
                // to be a non-tool-call code fence.
                fenceOpening = text.substring(fenceStart, fm.end());
                state = State.BUFFERING_FENCE;
            }
            case BARE_JSON -> {
                String remainder = text.substring(firstPos);
                buffer.setLength(0);
                buffer.append(remainder);
                state = State.BUFFERING_BARE_JSON;
            }
        }
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
    private static int findSafeEmitEnd(String text) {
        int len = text.length();
        int safeEnd = len;
        // Scan from end: longest XML tag "<function_call>" = 16 chars, fence "```json\n" = 8
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

        for (int i = scanFrom; i < len; i++) {
            if (text.charAt(i) != '`') continue;
            String tail = text.substring(i);
            if (couldBeCodeFenceOpenPrefix(tail)) {
                safeEnd = Math.min(safeEnd, i);
                break;
            }
        }

        return safeEnd;
    }

    private enum MatchKind { XML, FENCE, BARE_JSON }

    private static int findBareJsonStart(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '{') continue;
            if (!isStandaloneBoundary(text, i)) continue;
            if (couldBeginJsonObject(text, i)) return i;
        }
        return -1;
    }

    private static boolean isStandaloneBoundary(String text, int braceIndex) {
        if (braceIndex <= 0) return true;
        char prev = text.charAt(braceIndex - 1);
        return Character.isWhitespace(prev);
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

    private static boolean couldStillBeJsonObject(String text) {
        if (!text.startsWith("{")) return false;
        return couldBeginJsonObject(text, 0);
    }

    private static int findCompleteJsonObjectEnd(String text) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
                if (depth < 0) return -1;
            }
        }
        return -1;
    }

    private static boolean looksLikeIncompleteBareToolJson(String text) {
        return text != null && INCOMPLETE_BARE_TOOL_JSON.matcher(text).find();
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
        if (s == null || s.isEmpty() || s.length() > 16) return false;
        if (CODE_FENCE_PREFIX.startsWith(s)) return true;

        String lower = s.toLowerCase(java.util.Locale.ROOT);
        if ("```json".startsWith(lower)) return true;
        if (!lower.startsWith(CODE_FENCE_PREFIX)) return false;

        String rest = lower.substring(CODE_FENCE_PREFIX.length());
        if (rest.startsWith("json")) {
            rest = rest.substring("json".length());
        }
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r') return false;
        }
        return true;
    }
}

