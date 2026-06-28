package dev.talos.cli.ui.md;

import dev.talos.cli.ui.CliTheme;
import dev.talos.cli.ui.StreamShaper;
import dev.talos.cli.ui.StreamingAnswerShaper;

/**
 * Trusted streaming markdown state machine (T777).
 *
 * <p>Sits between sanitized chunk text and the answer-pane stream in fully
 * capable interactive mode. "Trusted" because sanitization and redaction
 * already happened upstream - model text can never smuggle ANSI through
 * this layer; every escape code is renderer-emitted via {@link CliTheme}.
 *
 * <p>Per logical line, the shaper classifies the line start (heading,
 * bullet, ``` fence delimiter, prose), wraps prose-family lines through the
 * T776 {@link StreamingAnswerShaper} (classification needs only a few
 * buffered characters, so completed rows still stream mid-line), and styles
 * each wrapped row via {@link MarkdownLineStyler} - markers stay visible,
 * so stripping ANSI always recovers the plain wrapped text.
 *
 * <p>Fenced code blocks toggle on ``` delimiter lines: content lines buffer
 * per line, preserve every character (no whitespace collapse), and hard-cut
 * at the pane width - word-wrapping code would corrupt indentation. An
 * unterminated fence at stream close flushes its content plain; nothing is
 * ever swallowed.
 */
public final class StreamingMarkdownShaper implements StreamShaper {

    /** Longest prefix needed to classify a line start ("###### " or "123. "). */
    private static final int MAX_CLASSIFY_PREFIX = 8;

    private final int maxWidth;
    private final MarkdownLineStyler styler;
    private final StreamingAnswerShaper wrapEngine;

    private final StringBuilder classifyBuffer = new StringBuilder();
    private final StringBuilder fenceLineBuffer = new StringBuilder();
    private final NanorcHighlighterCatalog highlighters = NanorcHighlighterCatalog.shared();
    private MarkdownLineStyler.LineClass lineClass;
    private boolean inFence;
    private String fenceLanguage = "";
    private boolean firstRowOfLinePending;
    private boolean pendingCarriageReturn;

    public StreamingMarkdownShaper(int maxWidth, CliTheme theme) {
        this.maxWidth = Math.max(16, maxWidth);
        this.styler = new MarkdownLineStyler(theme);
        this.wrapEngine = new StreamingAnswerShaper(maxWidth);
    }

    @Override
    public String accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        StringBuilder out = new StringBuilder(chunk.length() + 16);
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (pendingCarriageReturn) {
                pendingCarriageReturn = false;
                endLine(out);
                if (c == '\n') continue;
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

    @Override
    public String flush() {
        StringBuilder out = new StringBuilder();
        if (pendingCarriageReturn) {
            pendingCarriageReturn = false;
            endLine(out);
        }
        if (lineClass == null && !classifyBuffer.isEmpty()) {
            // Stream ended mid-prefix: treat as prose.
            startLine(MarkdownLineStyler.LineClass.PROSE, out);
        }
        if (lineClass == MarkdownLineStyler.LineClass.FENCE_DELIMITER
                || lineClass == MarkdownLineStyler.LineClass.FENCE_CONTENT) {
            emitBufferedFenceLine(out, false);
        } else if (lineClass != null) {
            styleRows(wrapEngine.flush(), out, false);
        }
        lineClass = null;
        styler.lineEnd();
        return out.toString();
    }

    private void acceptChar(char c, StringBuilder out) {
        if (lineClass == null) {
            classifyBuffer.append(c);
            MarkdownLineStyler.LineClass decided = classify(classifyBuffer, inFence);
            if (decided != null) {
                startLine(decided, out);
            } else if (classifyBuffer.length() >= MAX_CLASSIFY_PREFIX) {
                startLine(inFence
                        ? MarkdownLineStyler.LineClass.FENCE_CONTENT
                        : MarkdownLineStyler.LineClass.PROSE, out);
            }
            return;
        }
        switch (lineClass) {
            case FENCE_DELIMITER, FENCE_CONTENT -> fenceLineBuffer.append(c);
            default -> styleRows(wrapEngine.accept(String.valueOf(c)), out, true);
        }
    }

    /** Commits the classification and routes the buffered prefix. */
    private void startLine(MarkdownLineStyler.LineClass decided, StringBuilder out) {
        lineClass = decided;
        firstRowOfLinePending = true;
        String prefix = classifyBuffer.toString();
        classifyBuffer.setLength(0);
        switch (decided) {
            case FENCE_DELIMITER, FENCE_CONTENT -> fenceLineBuffer.append(prefix);
            default -> styleRows(wrapEngine.accept(prefix), out, true);
        }
    }

    private void endLine(StringBuilder out) {
        if (lineClass == null) {
            // Whole line fit inside the classification prefix (or was empty).
            MarkdownLineStyler.LineClass decided = classify(classifyBuffer, inFence);
            startLine(decided != null
                    ? decided
                    : (inFence ? MarkdownLineStyler.LineClass.FENCE_CONTENT
                               : MarkdownLineStyler.LineClass.PROSE), out);
        }
        switch (lineClass) {
            case FENCE_DELIMITER -> {
                String delimiterLine = fenceLineBuffer.toString();
                emitBufferedFenceLine(out, true);
                inFence = !inFence;
                // Opening delimiter carries the language tag ("```java");
                // closing resets it.
                fenceLanguage = inFence
                        ? delimiterLine.substring(3).strip().split("\\s+", 2)[0]
                        : "";
            }
            case FENCE_CONTENT -> emitBufferedFenceLine(out, true);
            default -> styleRows(wrapEngine.accept("\n"), out, true);
        }
        lineClass = null;
        styler.lineEnd();
    }

    /**
     * Hard-cuts the buffered fence/delimiter line at the pane width,
     * preserving spacing. Fence content is nanorc-highlighted when a
     * definition exists for the fence language (T778): the complete line is
     * highlighted once, then cut ANSI-aware via columnSubSequence so token
     * colors survive the cut; any highlighter failure degrades to plain.
     */
    private void emitBufferedFenceLine(StringBuilder out, boolean lineComplete) {
        String line = fenceLineBuffer.toString();
        fenceLineBuffer.setLength(0);
        org.jline.utils.AttributedString highlighted =
                lineClass == MarkdownLineStyler.LineClass.FENCE_CONTENT && !line.isEmpty()
                        ? highlighters.highlight(fenceLanguage, line)
                        : null;
        boolean first = true;
        int from = 0;
        do {
            int to = Math.min(line.length(), from + maxWidth);
            if (highlighted != null) {
                out.append(highlighted.columnSubSequence(from, to).toAnsi());
            } else {
                out.append(styler.styleRow(line.substring(from, to), lineClass, first));
            }
            boolean lastSegment = to >= line.length();
            if (!lastSegment || lineComplete) {
                out.append('\n');
            }
            first = false;
            from = to;
        } while (from < line.length());
    }

    /**
     * Styles complete rows coming out of the wrap engine. The engine emits
     * only completed rows (each '\n'-terminated) from accept(); flush() may
     * end with an unterminated final row.
     */
    private void styleRows(String wrapped, StringBuilder out, boolean fromAccept) {
        if (wrapped.isEmpty()) return;
        int start = 0;
        while (start < wrapped.length()) {
            int nl = wrapped.indexOf('\n', start);
            boolean terminated = nl >= 0;
            String row = terminated ? wrapped.substring(start, nl) : wrapped.substring(start);
            out.append(styler.styleRow(row, lineClass, firstRowOfLinePending));
            if (terminated) out.append('\n');
            firstRowOfLinePending = false;
            if (!terminated) break;
            start = nl + 1;
        }
    }

    /**
     * Classifies a line from its prefix, or {@code null} when more
     * characters are needed. Inside a fence only the closing ``` matters.
     */
    private static MarkdownLineStyler.LineClass classify(CharSequence prefix, boolean inFence) {
        int len = prefix.length();
        if (len == 0) return null;
        // ``` fence delimiter (both directions)
        if (prefix.charAt(0) == '`') {
            if (len >= 3) {
                return (prefix.charAt(1) == '`' && prefix.charAt(2) == '`')
                        ? MarkdownLineStyler.LineClass.FENCE_DELIMITER
                        : (inFence ? MarkdownLineStyler.LineClass.FENCE_CONTENT
                                   : MarkdownLineStyler.LineClass.PROSE);
            }
            if (len == 2 && prefix.charAt(1) != '`') {
                return inFence ? MarkdownLineStyler.LineClass.FENCE_CONTENT
                               : MarkdownLineStyler.LineClass.PROSE;
            }
            return null;
        }
        if (inFence) {
            return MarkdownLineStyler.LineClass.FENCE_CONTENT;
        }
        // Heading: 1-6 '#' followed by a space.
        if (prefix.charAt(0) == '#') {
            int hashes = 0;
            while (hashes < len && prefix.charAt(hashes) == '#') hashes++;
            if (hashes > 6) return MarkdownLineStyler.LineClass.PROSE;
            if (hashes == len) return null;
            return prefix.charAt(hashes) == ' '
                    ? MarkdownLineStyler.LineClass.HEADING
                    : MarkdownLineStyler.LineClass.PROSE;
        }
        // Bullet: "- ", "* ", or "12. " (no indent tracking - pane prose).
        if (prefix.charAt(0) == '-' || prefix.charAt(0) == '*') {
            if (len < 2) return null;
            return prefix.charAt(1) == ' '
                    ? MarkdownLineStyler.LineClass.BULLET
                    : MarkdownLineStyler.LineClass.PROSE;
        }
        if (Character.isDigit(prefix.charAt(0))) {
            int digits = 0;
            while (digits < len && Character.isDigit(prefix.charAt(digits)) && digits < 3) digits++;
            if (digits == len) return null;
            if (prefix.charAt(digits) == '.') {
                if (digits + 1 == len) return null;
                return prefix.charAt(digits + 1) == ' '
                        ? MarkdownLineStyler.LineClass.BULLET
                        : MarkdownLineStyler.LineClass.PROSE;
            }
            return MarkdownLineStyler.LineClass.PROSE;
        }
        return MarkdownLineStyler.LineClass.PROSE;
    }
}
