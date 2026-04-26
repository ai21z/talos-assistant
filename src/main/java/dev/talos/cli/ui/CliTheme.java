package dev.talos.cli.ui;

/**
 * Semantic Talos CLI theme tokens.
 *
 * <p>Only trusted renderer code should use this class. Model text must be
 * sanitized before any of these styles are applied.
 */
public final class CliTheme {
    private static final String RESET_CODE = "0";
    private static final String BOLD_CODE = "1";

    private final TerminalCapabilities capabilities;

    private CliTheme(TerminalCapabilities capabilities) {
        this.capabilities = capabilities == null
                ? TerminalCapabilities.detectDefault()
                : capabilities;
    }

    public static CliTheme current() {
        return new CliTheme(TerminalCapabilities.detectDefault());
    }

    public static CliTheme forCapabilities(TerminalCapabilities capabilities) {
        return new CliTheme(capabilities);
    }

    public TerminalCapabilities capabilities() {
        return capabilities;
    }

    public String brand(String text) { return bold(color(179, text)); }
    public String section(String text) { return color(179, text); }
    public String active(String text) { return color(86, text); }
    public String success(String text) { return color(151, text); }
    public String debug(String text) { return color(96, text); }
    public String error(String text) { return color(160, text); }
    public String warning(String text) { return color(214, text); }
    public String metadata(String text) { return color(245, text); }
    public String muted(String text) { return color(240, text); }
    public String body(String text) { return color(255, text); }

    public String bold(String text) {
        return sgr(BOLD_CODE) + safe(text) + reset();
    }

    public String color(int code256, String text) {
        return sgr("38;5;" + code256) + safe(text) + reset();
    }

    public String sgr(String code) {
        if (!capabilities.colorEnabled()) return "";
        return "\033[" + code + "m";
    }

    public String reset() {
        return sgr(RESET_CODE);
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }
}
