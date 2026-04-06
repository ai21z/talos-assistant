package dev.talos.cli.ui;

import java.nio.charset.Charset;

/**
 * ANSI 256-color utility with runtime detection and safe fallback.
 * <p>
 * Respects the {@code NO_COLOR} convention (<a href="https://no-color.org/">no-color.org</a>),
 * {@code TALOS_COLOR} override, and piped-output detection.
 */
public final class AnsiColor {

    // ── detection (evaluated once at class load) ──────────────────────────
    private static final boolean COLOR_ENABLED  = detectColorSupport();
    private static final boolean UNICODE_SAFE   = detectUnicodeSupport();

    // ── brand gradient (left → right across logo) ─────────────────────────
    public static final String PURPLE  = esc("38;5;99");   // deep purple
    public static final String VIOLET  = esc("38;5;141");  // lavender
    public static final String BLUE    = esc("38;5;75");   // sky blue
    public static final String ORANGE  = esc("38;5;208");  // warm orange

    // ── UI semantic colors ────────────────────────────────────────────────
    public static final String GREY    = esc("38;5;245");  // labels, metadata
    public static final String DIM     = esc("38;5;240");  // separators, faint
    public static final String GREEN   = esc("38;5;114");  // healthy / success
    public static final String RED     = esc("38;5;203");  // error / failure
    public static final String YELLOW  = esc("38;5;214");  // warning
    public static final String WHITE   = esc("38;5;255");  // emphasis

    // ── formatting ────────────────────────────────────────────────────────
    public static final String BOLD    = esc("1");
    public static final String DIM_ATTR= esc("2");
    public static final String RESET   = esc("0");

    private AnsiColor() {}

    // ── helpers ───────────────────────────────────────────────────────────

    /** Build an ESC sequence; returns "" when color is disabled. */
    public static String esc(String code) {
        return COLOR_ENABLED ? "\033[" + code + "m" : "";
    }

    /** 256-color foreground. */
    public static String fg(int code256) {
        return esc("38;5;" + code256);
    }

    public static boolean isEnabled()      { return COLOR_ENABLED; }
    public static boolean isUnicodeSafe()   { return UNICODE_SAFE; }

    // ── convenience wrappers ──────────────────────────────────────────────

    public static String purple(String s) { return PURPLE + s + RESET; }
    public static String violet(String s) { return VIOLET + s + RESET; }
    public static String blue(String s)   { return BLUE   + s + RESET; }
    public static String orange(String s) { return ORANGE + s + RESET; }
    public static String grey(String s)   { return GREY   + s + RESET; }
    public static String dim(String s)    { return DIM    + s + RESET; }
    public static String green(String s)  { return GREEN  + s + RESET; }
    public static String red(String s)    { return RED    + s + RESET; }
    public static String yellow(String s) { return YELLOW + s + RESET; }
    public static String bold(String s)   { return BOLD   + s + RESET; }

    /** Brand-colored bold text ("talos" in accent violet). */
    public static String brand(String s)  { return BOLD + VIOLET + s + RESET; }

    // ── detection logic ───────────────────────────────────────────────────

    private static boolean detectColorSupport() {
        // NO_COLOR convention
        if (System.getenv("NO_COLOR") != null) return false;

        // Explicit override
        String override = System.getenv("TALOS_COLOR");
        if ("false".equalsIgnoreCase(override) || "0".equals(override)) return false;
        if ("true".equalsIgnoreCase(override)  || "1".equals(override)) return true;

        // Piped / redirected output
        if (System.console() == null) return false;

        // Modern terminal indicators
        if (System.getenv("WT_SESSION")   != null) return true;  // Windows Terminal
        if (System.getenv("COLORTERM")    != null) return true;
        if (System.getenv("TERM_PROGRAM") != null) return true;

        String term = System.getenv("TERM");
        if (term != null && (term.contains("color") || term.contains("xterm") || term.contains("256")))
            return true;

        // Default: assume modern terminal
        return true;
    }

    private static boolean detectUnicodeSupport() {
        // Windows Terminal always supports Unicode
        if (System.getenv("WT_SESSION") != null) return true;
        if (System.getenv("TERM_PROGRAM") != null) return true;

        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) return true; // Unix/macOS: always safe

        // Windows: check console charset
        try {
            Charset cs = Charset.defaultCharset();
            return "UTF-8".equalsIgnoreCase(cs.name());
        } catch (Exception e) {
            return false;
        }
    }
}

