package dev.talos.cli.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders Talos answers with the same rail/pane shape for streamed and
 * non-streamed output.
 */
public final class AnswerPaneRenderer {
    private static final String INDENT = "  ";

    private final CliTheme theme;
    private final SemanticGlyphSet glyphs;
    private final int width;

    public AnswerPaneRenderer(CliTheme theme, int width) {
        this.theme = theme == null ? CliTheme.current() : theme;
        this.glyphs = SemanticGlyphSet.forCapabilities(this.theme.capabilities());
        this.width = Math.max(32, width);
    }

    public String renderBlock(String content, String footer) {
        StringBuilder sb = new StringBuilder();
        sb.append(header("answer"));
        for (String line : lines(content)) {
            for (String wrapped : wrap(line, contentWidth())) {
                sb.append(rail()).append(wrapped).append(System.lineSeparator());
            }
        }
        sb.append(close(footer));
        return sb.toString();
    }

    public Stream openStream(String footer) {
        return new Stream(footer);
    }

    private String header(String title) {
        String label = " " + safe(title) + " ";
        int count = Math.max(1, width - INDENT.length() - glyphs.topLeft().length()
                - glyphs.horizontal().length() - label.length());
        return INDENT + theme.section(glyphs.topLeft() + glyphs.horizontal() + label
                + glyphs.horizontal().repeat(count)) + System.lineSeparator();
    }

    private String rail() {
        return INDENT + theme.section(glyphs.vertical()) + " ";
    }

    private String close(String footer) {
        return INDENT + theme.section(glyphs.bottomLeft() + glyphs.horizontal()
                + " " + safe(footer)) + System.lineSeparator();
    }

    private int contentWidth() {
        return Math.max(16, width - INDENT.length() - glyphs.vertical().length() - 1);
    }

    private List<String> lines(String content) {
        String safe = content == null ? "" : content;
        safe = safe.replace("\r\n", "\n").replace('\r', '\n');
        safe = safe.replaceFirst("\\s+$", "");
        if (safe.isEmpty()) return List.of("");
        return List.of(safe.split("\n", -1));
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

    private static String safe(String text) {
        return text == null || text.isBlank() ? "answer" : text.trim();
    }

    public final class Stream {
        private final String footer;
        private boolean opened;
        private boolean lineStart = true;

        private Stream(String footer) {
            this.footer = footer;
        }

        public boolean opened() {
            return opened;
        }

        public String accept(String chunk) {
            if (chunk == null || chunk.isEmpty()) return "";
            String normalized = chunk.replace("\r\n", "\n").replace('\r', '\n');
            StringBuilder sb = new StringBuilder();
            if (!opened) {
                opened = true;
                sb.append(header("answer"));
            }
            for (int i = 0; i < normalized.length(); i++) {
                if (lineStart) {
                    sb.append(rail());
                    lineStart = false;
                }
                char ch = normalized.charAt(i);
                sb.append(ch);
                if (ch == '\n') {
                    lineStart = true;
                }
            }
            return sb.toString();
        }

        public String close(String fallbackFooter) {
            if (!opened) return "";
            StringBuilder sb = new StringBuilder();
            if (!lineStart) {
                sb.append(System.lineSeparator());
            }
            sb.append(AnswerPaneRenderer.this.close(
                    fallbackFooter == null || fallbackFooter.isBlank() ? footer : fallbackFooter));
            opened = false;
            lineStart = true;
            return sb.toString();
        }
    }
}
