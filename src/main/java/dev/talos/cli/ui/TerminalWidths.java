package dev.talos.cli.ui;

import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Single width-resolution rule for width-reactive rendering (T771).
 *
 * <p>Order of authority:
 * <ol>
 *   <li>A live terminal width (JLine {@code Terminal.getWidth()} via the
 *       supplied {@link IntSupplier}), clamped to [{@value #MIN},
 *       {@value #MAX}] - below 60 the bordered surfaces degrade into
 *       wrap-noise, above 120 prose lines become unreadable.</li>
 *   <li>The {@code COLUMNS} environment variable (POSIX shells; never set
 *       by default on Windows), clamped the same way.</li>
 *   <li>The caller's surface default, returned unclamped - redirected and
 *       scripted paths have no terminal, so today's fixed-width output
 *       (answer pane 96, approval window 80, banner 80) stays
 *       byte-identical by construction.</li>
 * </ol>
 */
public final class TerminalWidths {

    /** Narrowest width any width-reactive surface renders at. */
    public static final int MIN = 60;

    /** Widest width any width-reactive surface renders at. */
    public static final int MAX = 120;

    /**
     * @param terminalWidth  live terminal width source, or {@code null} when
     *                       no terminal exists (redirected/scripted paths);
     *                       non-positive values and failures are treated as
     *                       unavailable
     * @param env            environment for the {@code COLUMNS} fallback
     * @param surfaceDefault per-surface fixed width used when neither source
     *                       is available; intentionally NOT clamped
     */
    public static int resolve(IntSupplier terminalWidth, Map<String, String> env, int surfaceDefault) {
        int live = safeWidth(terminalWidth);
        if (live > 0) {
            return clamp(live);
        }
        int columns = parseColumns(env);
        if (columns > 0) {
            return clamp(columns);
        }
        return surfaceDefault;
    }

    static int clamp(int width) {
        return Math.max(MIN, Math.min(MAX, width));
    }

    private static int safeWidth(IntSupplier terminalWidth) {
        if (terminalWidth == null) {
            return 0;
        }
        try {
            return terminalWidth.getAsInt();
        } catch (RuntimeException unavailable) {
            return 0;
        }
    }

    private static int parseColumns(Map<String, String> env) {
        String columns = env == null ? null : env.get("COLUMNS");
        if (columns == null || columns.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(columns.trim());
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    private TerminalWidths() {
    }
}
