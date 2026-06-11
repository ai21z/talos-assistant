package dev.talos.cli.ui;

/**
 * Incremental word-wrapper for streamed answer text (T776).
 *
 * <p>The streaming path historically never wrapped: long model lines
 * overflowed the terminal and sheared the answer-pane rail. This shaper
 * sits between sanitized chunk text and {@link AnswerPaneRenderer.Stream}
 * and injects {@code '\n'} so the streamed body rows are byte-identical to
 * what {@link AnswerPaneRenderer#renderBlock} would produce for the same
 * full text — the block wrapper is the parity oracle, replicated
 * incrementally:
 * <ul>
 *   <li>a line that fits {@code maxWidth} is emitted verbatim at its
 *       {@code '\n'}, internal spacing preserved;</li>
 *   <li>the moment a line exceeds {@code maxWidth} it switches to wrap
 *       mode: whitespace runs collapse, rows fill greedily, words longer
 *       than the width hard-split into width-sized rows;</li>
 *   <li>greedy filling is prefix-stable, so completed rows stream out as
 *       soon as the next word cannot fit — the latency bound is one row
 *       plus one in-flight word, never the whole answer.</li>
 * </ul>
 *
 * <p>Chunk boundaries are arbitrary (mid-word, mid-CRLF); state carries
 * across {@link #accept} calls. {@link #flush()} drains the remainder at
 * stream close without inventing a trailing newline, matching
 * {@code Stream.close()} separator handling. One known divergence from the
 * block oracle: trailing whitespace of the whole answer cannot be stripped
 * retroactively while streaming.
 */
public final class StreamingAnswerShaper {

    private final int maxWidth;

    /** Pre-overflow buffer: the current line while it still fits. */
    private final StringBuilder shortLine = new StringBuilder();
    /** True once the current line exceeded maxWidth (whitespace collapses). */
    private boolean wrapMode;
    /** Completed words of the row being filled (wrap mode). */
    private final StringBuilder row = new StringBuilder();
    /** The in-flight, possibly incomplete word (wrap mode). */
    private final StringBuilder word = new StringBuilder();
    /** Rows emitted for the current logical line (wrap mode). */
    private int rowsEmittedThisLine;
    /** Carry for a CR seen at a chunk boundary (CRLF normalization). */
    private boolean pendingCarriageReturn;

    public StreamingAnswerShaper(int maxWidth) {
        this.maxWidth = Math.max(16, maxWidth);
    }

    /** Shape a sanitized chunk; returns text ready for the pane stream (may be empty). */
    public String accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        StringBuilder out = new StringBuilder(chunk.length() + 8);
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (pendingCarriageReturn) {
                pendingCarriageReturn = false;
                endLine(out);
                if (c == '\n') continue; // CRLF pair → one line break
            }
            if (c == '\r') {
                pendingCarriageReturn = true;
                continue;
            }
            if (c == '\n') {
                endLine(out);
                continue;
            }
            acceptChar(c, out);
        }
        return out.toString();
    }

    /** Drain buffered state at stream close; no trailing newline is added. */
    public String flush() {
        StringBuilder out = new StringBuilder();
        if (pendingCarriageReturn) {
            pendingCarriageReturn = false;
            endLine(out);
        }
        if (!wrapMode) {
            out.append(shortLine);
            shortLine.setLength(0);
            return out.toString();
        }
        completeWord(out);
        if (!row.isEmpty()) {
            out.append(row);
            row.setLength(0);
        }
        wrapMode = false;
        rowsEmittedThisLine = 0;
        return out.toString();
    }

    private void acceptChar(char c, StringBuilder out) {
        if (!wrapMode) {
            shortLine.append(c);
            if (shortLine.length() > maxWidth) {
                enterWrapMode(out);
            }
            return;
        }
        if (isWrapSeparator(c)) {
            completeWord(out);
            return;
        }
        word.append(c);
        if (word.length() > maxWidth) {
            hardSplitOverflow(out);
        }
    }

    /** Re-tokenizes the buffered short line once it can no longer fit. */
    private void enterWrapMode(StringBuilder out) {
        wrapMode = true;
        rowsEmittedThisLine = 0;
        String buffered = shortLine.toString();
        shortLine.setLength(0);
        for (int i = 0; i < buffered.length(); i++) {
            char c = buffered.charAt(i);
            if (isWrapSeparator(c)) {
                completeWord(out);
            } else {
                word.append(c);
                if (word.length() > maxWidth) {
                    hardSplitOverflow(out);
                }
            }
        }
    }

    /** Greedy placement, mirroring AnswerPaneRenderer.wrap row filling. */
    private void completeWord(StringBuilder out) {
        if (word.isEmpty()) return;
        if (!row.isEmpty() && row.length() + 1 + word.length() > maxWidth) {
            emitRow(out);
        }
        if (!row.isEmpty()) row.append(' ');
        row.append(word);
        word.setLength(0);
    }

    /** Mirrors the oracle's width-sized chunking of overlong words. */
    private void hardSplitOverflow(StringBuilder out) {
        if (!row.isEmpty()) {
            emitRow(out);
        }
        out.append(word, 0, maxWidth).append('\n');
        rowsEmittedThisLine++;
        String remainder = word.substring(maxWidth);
        word.setLength(0);
        word.append(remainder);
    }

    private void endLine(StringBuilder out) {
        if (!wrapMode) {
            out.append(shortLine).append('\n');
            shortLine.setLength(0);
            return;
        }
        completeWord(out);
        if (!row.isEmpty()) {
            emitRow(out);
        } else if (rowsEmittedThisLine == 0) {
            // Oracle parity: a whitespace-only overlong line still renders
            // one empty row (wrap() falls back to a single empty string).
            out.append('\n');
        }
        wrapMode = false;
        rowsEmittedThisLine = 0;
    }

    private void emitRow(StringBuilder out) {
        out.append(row).append('\n');
        row.setLength(0);
        rowsEmittedThisLine++;
    }

    /** The separators AnswerPaneRenderer.wrap collapses (regex \s minus line breaks). */
    private static boolean isWrapSeparator(char c) {
        return c == ' ' || c == '\t' || c == '\u000B' || c == '\f';
    }
}
