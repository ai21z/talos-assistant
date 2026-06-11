package dev.talos.cli.ui.md;

import dev.talos.cli.ui.CliTheme;

/**
 * Renderer-owned markdown row styling (T777).
 *
 * <p>Operates on already-wrapped plain rows, so styling can never disturb
 * column math. Markers stay visible — styling only colors the original
 * characters, never removes them — so stripping ANSI from a styled row
 * always yields the plain row byte-for-byte, and transcript string-matching
 * (PTY evidence chain, talosbench) cannot regress. Rows with no markdown
 * tokens pass through with zero ANSI added, which keeps Talos chrome lines
 * (`✓ Edited ...`, `[Used N tool(s)...]`) byte-identical.
 *
 * <p>Inline span state (code/bold/italic) carries across the wrapped rows
 * of one logical line and resets at {@link #lineEnd()} — markdown inline
 * spans do not cross line breaks.
 */
final class MarkdownLineStyler {

    enum LineClass { PROSE, HEADING, BULLET, FENCE_DELIMITER, FENCE_CONTENT }

    private final CliTheme theme;
    private boolean codeSpan;
    private boolean boldSpan;
    private boolean italicSpan;

    MarkdownLineStyler(CliTheme theme) {
        this.theme = theme;
    }

    String styleRow(String row, LineClass lineClass, boolean firstRowOfLine) {
        if (row == null || row.isEmpty()) return "";
        if (!theme.capabilities().colorEnabled()) return row;
        return switch (lineClass) {
            case HEADING -> theme.bold(theme.section(row));
            case FENCE_DELIMITER -> theme.metadata(row);
            case FENCE_CONTENT -> row; // syntax highlighting arrives in T778
            case BULLET -> styleBullet(row, firstRowOfLine);
            case PROSE -> styleInline(row);
        };
    }

    /** Resets inline span state at the end of a logical line. */
    void lineEnd() {
        codeSpan = false;
        boldSpan = false;
        italicSpan = false;
    }

    private String styleBullet(String row, boolean firstRowOfLine) {
        if (!firstRowOfLine) {
            return styleInline(row);
        }
        int markerEnd = bulletMarkerEnd(row);
        if (markerEnd <= 0) {
            return styleInline(row);
        }
        return theme.section(row.substring(0, markerEnd)) + styleInline(row.substring(markerEnd));
    }

    /**
     * Length of the leading bullet marker ("- ", "* ", "12. " after optional
     * indent), or -1. Mirrors StreamingMarkdownShaper's classifier so the
     * marker's '*' never toggles the italic span.
     */
    static int bulletMarkerEnd(String row) {
        int i = 0;
        while (i < row.length() && row.charAt(i) == ' ') i++;
        int markerStart = i;
        if (i < row.length() && (row.charAt(i) == '-' || row.charAt(i) == '*')) {
            i++;
        } else {
            while (i < row.length() && Character.isDigit(row.charAt(i)) && i - markerStart < 3) i++;
            if (i == markerStart || i >= row.length() || row.charAt(i) != '.') return -1;
            i++;
        }
        if (i >= row.length() || row.charAt(i) != ' ') return -1;
        return i + 1;
    }

    private String styleInline(String row) {
        StringBuilder out = new StringBuilder(row.length() + 16);
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == '`') {
                if (codeSpan) {
                    run.append(c);
                    flushRun(out, run);
                    codeSpan = false;
                } else {
                    flushRun(out, run);
                    codeSpan = true;
                    run.append(c);
                }
                continue;
            }
            if (c == '*' && !codeSpan) {
                boolean doubled = i + 1 < row.length() && row.charAt(i + 1) == '*';
                if (doubled) {
                    if (boldSpan) {
                        run.append("**");
                        flushRun(out, run);
                        boldSpan = false;
                    } else {
                        flushRun(out, run);
                        boldSpan = true;
                        run.append("**");
                    }
                    i++;
                } else {
                    if (italicSpan) {
                        run.append(c);
                        flushRun(out, run);
                        italicSpan = false;
                    } else {
                        flushRun(out, run);
                        italicSpan = true;
                        run.append(c);
                    }
                }
                continue;
            }
            run.append(c);
        }
        flushRun(out, run);
        return out.toString();
    }

    private void flushRun(StringBuilder out, StringBuilder run) {
        if (run.isEmpty()) return;
        String text = run.toString();
        run.setLength(0);
        if (codeSpan) {
            out.append(theme.active(text));
        } else if (boldSpan) {
            out.append(theme.bold(text));
        } else if (italicSpan) {
            out.append(theme.sgr("3")).append(text).append(theme.reset());
        } else {
            out.append(text);
        }
    }
}
