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

    /** Current suppression state.
     *  SUPPRESSING_XML is DEPRECATED compatibility-only (for models that emit XML from training).
     *  Scheduled for removal once native tool calling is stable across model versions. */
    private enum State { PASSTHROUGH, SUPPRESSING_XML, BUFFERING_FENCE, SUPPRESSING_FENCE }
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
    private static final Pattern CODE_FENCE_OPEN = Pattern.compile("```(?:json)?\\s*\\n");

    /** Closing code fence: ``` at start of a line (preceded by newline) or at end of content. */
    private static final Pattern CODE_FENCE_CLOSE = Pattern.compile("\\n```(?:\\s*\\n|\\s*$)");

    /** Tool-call JSON signature inside a code fence. */
    private static final Pattern TOOL_CALL_JSON = Pattern.compile(
            "\"name\"\\s*:\\s*\"talos\\."
    );

    /** All possible code fence opening prefixes (for chunk boundary detection). */
    private static final String CODE_FENCE_PREFIX = "```";

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
                    delegate.accept(buffer.toString());
                    break;
                case BUFFERING_FENCE:
                    // Never completed — emit opening fence + content as regular text
                    delegate.accept(fenceOpening + buffer.toString());
                    break;
                case SUPPRESSING_XML:
                case SUPPRESSING_FENCE:
                    // Incomplete tool-call block — discard
                    break;
            }
        }
        buffer.setLength(0);
        fenceOpening = "";
        state = State.PASSTHROUGH;
    }

    /**
     * Reset state without flushing (e.g., between turns).
     */
    public void reset() {
        buffer.setLength(0);
        fenceOpening = "";
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
            if (TOOL_CALL_JSON.matcher(fenceContent).find()) {
                // It's a tool call — suppress the entire block including closing fence
                String remainder = text.substring(cm.end());
                buffer.setLength(0);
                buffer.append(remainder);
                fenceOpening = "";
                state = State.PASSTHROUGH;
                return true;
            } else {
                // Not a tool call — emit the opening fence + content + closing fence
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

        // Neither found — try to emit safe prefix
        if (xmlStart < 0 && fenceStart < 0) {
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
        boolean isXml;
        if (xmlStart >= 0 && (fenceStart < 0 || xmlStart <= fenceStart)) {
            firstPos = xmlStart;
            isXml = true;
        } else {
            firstPos = fenceStart;
            isXml = false;
        }

        // Emit everything before the first match
        if (firstPos > 0) {
            delegate.accept(text.substring(0, firstPos));
        }

        if (isXml) {
            // XML tag — enter XML suppression
            String remainder = text.substring(om.end());
            buffer.setLength(0);
            buffer.append(remainder);
            state = State.SUPPRESSING_XML;
        } else {
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
        // Scan from end: longest XML tag "<function_call>" = 16 chars, fence "```json\n" = 8
        int scanFrom = Math.max(0, len - 16);

        for (int i = len - 1; i >= scanFrom; i--) {
            char c = text.charAt(i);
            if (c == '<') {
                String tail = text.substring(i);
                if (couldBeOpenTagPrefix(tail)) {
                    return i;
                }
            }
            if (c == '`') {
                String tail = text.substring(i);
                if (CODE_FENCE_PREFIX.startsWith(tail) || tail.startsWith(CODE_FENCE_PREFIX)) {
                    return i;
                }
            }
        }
        return len;
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
}

