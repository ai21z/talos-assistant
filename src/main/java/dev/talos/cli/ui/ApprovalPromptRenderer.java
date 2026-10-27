package dev.talos.cli.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Renderer-owned approval/trust prompt body.
 */
public final class ApprovalPromptRenderer {
    private static final String INDENT = "  ";

    private final CliTheme theme;
    private final SemanticGlyphSet glyphs;
    private final int width;

    public ApprovalPromptRenderer(CliTheme theme, int width) {
        this.theme = theme == null ? CliTheme.current() : theme;
        this.glyphs = SemanticGlyphSet.forCapabilities(this.theme.capabilities());
        this.width = Math.max(52, width);
    }

    public String render(String action, String detail, String risk) {
        return render(action, detail, risk, true);
    }

    public String renderOnce(String action, String detail, String risk) {
        return render(action, detail, risk, false);
    }

    private String render(String action, String detail, String risk, boolean allowRemember) {
        StringBuilder sb = new StringBuilder();
        sb.append(border(ApprovalPromptText.WINDOW_TITLE));
        sb.append(row("Action", safe(action, "unknown operation")));
        sb.append(row("Risk", safe(risk, "sensitive")));
        String safeDetail = detail == null ? "" : detail.strip();
        if (!safeDetail.isBlank()) {
            sb.append(blank());
            // The diff block is the final detail section (TurnProcessor
            // appends it last), so once entered it never closes - diff
            // context lines are indistinguishable from prose after strip().
            boolean inDiffBlock = false;
            for (String line : safeDetail.lines().toList()) {
                String stripped = line.strip();
                if (!inDiffBlock && stripped.startsWith("diff (+") && stripped.contains("):")) {
                    inDiffBlock = true;
                }
                // Diff lines are file content, so they are sliced character for character with their indent
                // kept and continuation rows hanging deeper, never word-wrapped with collapsed whitespace.
                List<String> rows = inDiffBlock && !stripped.startsWith("diff (+")
                        ? sliceDiffLine(line, contentWidth() - 2) : wrap(line, contentWidth() - 2);
                for (String wrapped : rows) {
                    sb.append(rail())
                            .append(inDiffBlock ? colorizeDiffLine(wrapped, stripped) : wrapped)
                            .append(System.lineSeparator());
                }
            }
        }
        sb.append(blank());
        String choices = allowRemember
                ? "y = approve once " + glyphs.dot()
                        + " a = approve for session " + glyphs.dot()
                        + " Enter = deny"
                : "y = approve this turn " + glyphs.dot()
                        + " Enter = deny";
        for (String wrapped : wrap(choices, contentWidth() - 2)) {
            sb.append(rail()).append(wrapped).append(System.lineSeparator());
        }
        sb.append(close());
        return sb.toString();
    }

    private String border(String title) {
        String label = " " + title + " ";
        int count = Math.max(1, width - INDENT.length() - glyphs.topLeft().length()
                - glyphs.horizontal().length() - label.length());
        return INDENT + theme.warning(glyphs.topLeft() + glyphs.horizontal() + label
                + glyphs.horizontal().repeat(count)) + System.lineSeparator();
    }

    private String close() {
        return INDENT + theme.warning(glyphs.bottomLeft()
                + glyphs.horizontal().repeat(Math.max(1, width - INDENT.length() - glyphs.bottomLeft().length())))
                + System.lineSeparator();
    }

    private String row(String label, String value) {
        return rail() + String.format(java.util.Locale.ROOT, "%-7s %s", label, value)
                + System.lineSeparator();
    }

    private String blank() {
        return rail() + System.lineSeparator();
    }

    private String rail() {
        return INDENT + theme.warning(glyphs.vertical()) + " ";
    }

    private int contentWidth() {
        return Math.max(24, width - INDENT.length() - glyphs.vertical().length() - 1);
    }

    /**
     * Diff-block colorization (T756). Scoped to lines inside a block opened
     * by a "diff (+A -R):" marker so YAML-style "- item" lines elsewhere in
     * the detail are never colorized. With color disabled,
     * {@link CliTheme#sgr} returns "" and output stays byte-identical plain.
     */
    private String colorizeDiffLine(String renderedLine, String strippedLine) {
        if (strippedLine.startsWith("diff (+")) return theme.metadata(renderedLine);
        if (strippedLine.startsWith("+")) return theme.success(renderedLine);
        if (strippedLine.startsWith("-")) return theme.error(renderedLine);
        if (strippedLine.startsWith("@") || strippedLine.startsWith("...")) {
            return theme.metadata(renderedLine);
        }
        return renderedLine;
    }

    private static final String DIFF_INDENT = "    ";
    /** A continuation row is marked, so it cannot read as a diff line of its own. */
    static final String CONTINUATION = DIFF_INDENT + "~ ";

    private static List<String> sliceDiffLine(String line, int maxWidth) {
        if (line == null || line.isEmpty()) return List.of("");
        if (line.length() <= maxWidth) return List.of(line);
        String body = line.startsWith(DIFF_INDENT) ? line.substring(DIFF_INDENT.length()) : line;
        List<String> out = new ArrayList<>();
        int at = cutBefore(body, Math.max(1, maxWidth - DIFF_INDENT.length()));
        out.add(DIFF_INDENT + body.substring(0, at));
        int rest = Math.max(1, maxWidth - CONTINUATION.length());
        while (at < body.length()) {
            int end = cutBefore(body, Math.min(at + rest, body.length()));
            out.add(CONTINUATION + body.substring(at, end));
            at = end;
        }
        return out;
    }

    /** A cut never splits a surrogate pair. */
    private static int cutBefore(String text, int index) {
        int end = Math.min(index, text.length());
        return end > 0 && end < text.length() && Character.isLowSurrogate(text.charAt(end)) ? end - 1 : end;
    }

    private static List<String> wrap(String line, int maxWidth) {
        if (line == null || line.isEmpty()) return List.of("");
        if (line.length() <= maxWidth) return List.of(line);
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : line.split("\\s+")) {
            if (!current.isEmpty() && current.length() + 1 + word.length() > maxWidth) {
                out.add(current.toString());
                current = new StringBuilder();
            }
            while (word.length() > maxWidth) {
                if (!current.isEmpty()) {
                    out.add(current.toString());
                    current = new StringBuilder();
                }
                out.add(word.substring(0, maxWidth));
                word = word.substring(maxWidth);
            }
            if (!current.isEmpty()) current.append(' ');
            current.append(word);
        }
        if (!current.isEmpty()) out.add(current.toString());
        return out.isEmpty() ? List.of("") : out;
    }

    private static String safe(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text.strip();
    }
}
