package dev.talos.cli.repl.slash;

import dev.talos.cli.ui.CliTheme;
import dev.talos.core.util.Sanitize;

final class ReindexProgressRenderer {
    private static final int DISPLAY_CHARS = 40;
    private static final int CLEAR_WIDTH = 120;

    private ReindexProgressRenderer() {
    }

    static String progressLine(int completed, int total, String file, CliTheme theme) {
        CliTheme resolvedTheme = theme == null ? CliTheme.current() : theme;
        boolean unicodeSafe = resolvedTheme.capabilities().unicodeSafe();
        int pct = total > 0 ? (completed * 100) / total : 0;
        String display = shorten(Sanitize.sanitizeForTerminalOutput(file, unicodeSafe), unicodeSafe);
        return "\r  " + resolvedTheme.muted("Indexing: "
                + completed + "/" + total + " (" + pct + "%)  " + display)
                + "          ";
    }

    static String clearLine() {
        return "\r" + " ".repeat(CLEAR_WIDTH) + "\r";
    }

    private static String shorten(String value, boolean unicodeSafe) {
        String safe = value == null ? "" : value;
        int count = safe.codePointCount(0, safe.length());
        if (count <= DISPLAY_CHARS) return safe;

        int tailChars = DISPLAY_CHARS - (unicodeSafe ? 1 : 3);
        int start = safe.offsetByCodePoints(0, count - Math.max(1, tailChars));
        return (unicodeSafe ? "…" : "...") + safe.substring(start);
    }
}
