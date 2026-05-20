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
        sb.append(border("approval required"));
        sb.append(row("Action", safe(action, "unknown operation")));
        sb.append(row("Risk", safe(risk, "sensitive")));
        String safeDetail = detail == null ? "" : detail.strip();
        if (!safeDetail.isBlank()) {
            sb.append(blank());
            for (String line : safeDetail.lines().toList()) {
                for (String wrapped : wrap(line, contentWidth() - 2)) {
                    sb.append(rail()).append(wrapped).append(System.lineSeparator());
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
