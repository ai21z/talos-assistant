package dev.talos.runtime;

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stream filter that suppresses tool-call protocol blocks from user-visible output.
 *
 * <p>Wraps a {@code Consumer<String>} display sink. Chunks that contain or partially
 * overlap {@code <tool_call>}, {@code <function_call>}, {@code <tool>}, or
 * {@code <function>} XML blocks are buffered and suppressed. Natural-language text
 * before/after tool-call blocks passes through to the delegate.
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
    private boolean suppressing = false;

    /** Opening tags that start suppression. */
    private static final Pattern OPEN_TAG = Pattern.compile(
            "<(tool_call|function_call|tool|function)>"
    );

    /** Closing tags that end suppression. */
    private static final Pattern CLOSE_TAG = Pattern.compile(
            "</(tool_call|function_call|tool|function)>"
    );

    /** All possible opening tag strings (for prefix matching at chunk boundaries). */
    private static final String[] OPEN_TAG_STRINGS = {
            "<tool_call>", "<function_call>", "<tool>", "<function>"
    };

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
     * tool-call content that never closed — safe to drop).
     */
    public void flush() {
        if (buffer.length() > 0 && !suppressing) {
            delegate.accept(buffer.toString());
        }
        buffer.setLength(0);
        suppressing = false;
    }

    /**
     * Reset state without flushing (e.g., between turns).
     */
    public void reset() {
        buffer.setLength(0);
        suppressing = false;
    }

    // ── Internal drain loop ──────────────────────────────────────────────

    private void drain() {
        // Process buffer until no more progress can be made
        while (buffer.length() > 0) {
            if (suppressing) {
                if (!drainSuppressing()) break;
            } else {
                if (!drainPassthrough()) break;
            }
        }
    }

    /**
     * In suppressing mode: look for closing tag.
     * Returns true if progress was made (should loop again).
     */
    private boolean drainSuppressing() {
        Matcher cm = CLOSE_TAG.matcher(buffer);
        if (cm.find()) {
            // Found closing tag — discard everything up to and including it
            String remainder = buffer.substring(cm.end());
            buffer.setLength(0);
            buffer.append(remainder);
            suppressing = false;
            return true; // made progress
        }
        // Still inside block, wait for more chunks
        return false;
    }

    /**
     * In passthrough mode: look for opening tag or hold partial matches.
     * Returns true if progress was made (should loop again).
     */
    private boolean drainPassthrough() {
        String text = buffer.toString();

        Matcher om = OPEN_TAG.matcher(text);
        if (om.find()) {
            // Found opening tag — emit everything before it, enter suppressing
            String before = text.substring(0, om.start());
            if (!before.isEmpty()) {
                delegate.accept(before);
            }
            String remainder = text.substring(om.end());
            buffer.setLength(0);
            buffer.append(remainder);
            suppressing = true;
            return true; // made progress
        }

        // No complete opening tag. Check if the buffer ends with a partial tag prefix.
        int safeEnd = findSafeEmitEnd(text);
        if (safeEnd > 0) {
            delegate.accept(text.substring(0, safeEnd));
            String remainder = text.substring(safeEnd);
            buffer.setLength(0);
            buffer.append(remainder);
        }
        // No more progress possible until next chunk arrives
        return false;
    }

    /**
     * Find the safe-to-emit boundary: everything before a potential partial
     * opening tag at the end of the buffer.
     *
     * <p>Scans backward from the end looking for {@code <} that could be
     * the start of an opening tag prefix. Returns the index up to which
     * content can safely be emitted, or the full length if no partial match.
     */
    private static int findSafeEmitEnd(String text) {
        int len = text.length();
        // Only need to check the last N chars where N = length of longest tag
        // Longest: "<function_call>" = 16 chars
        int scanFrom = Math.max(0, len - 16);

        for (int i = len - 1; i >= scanFrom; i--) {
            if (text.charAt(i) == '<') {
                String tail = text.substring(i);
                if (couldBeOpenTagPrefix(tail)) {
                    return i; // hold this partial, emit everything before
                }
            }
        }
        return len; // safe to emit everything
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

