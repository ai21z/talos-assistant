package dev.talos.cli.ui;

/**
 * Renderer-owned terminal glyphs for the line-based Talos UI.
 */
public final class SemanticGlyphSet {
    private static final SemanticGlyphSet SAFE_UNICODE = new SemanticGlyphSet(
            "•", "→", "✓", "!", "x", "│", "─", "┌", "└", "·");
    private static final SemanticGlyphSet ASCII = new SemanticGlyphSet(
            "*", "->", "ok", "!", "x", "|", "-", "+", "+", ".");

    private final String bullet;
    private final String arrow;
    private final String success;
    private final String warning;
    private final String error;
    private final String vertical;
    private final String horizontal;
    private final String topLeft;
    private final String bottomLeft;
    private final String dot;

    private SemanticGlyphSet(
            String bullet,
            String arrow,
            String success,
            String warning,
            String error,
            String vertical,
            String horizontal,
            String topLeft,
            String bottomLeft,
            String dot) {
        this.bullet = bullet;
        this.arrow = arrow;
        this.success = success;
        this.warning = warning;
        this.error = error;
        this.vertical = vertical;
        this.horizontal = horizontal;
        this.topLeft = topLeft;
        this.bottomLeft = bottomLeft;
        this.dot = dot;
    }

    public static SemanticGlyphSet forCapabilities(TerminalCapabilities capabilities) {
        TerminalCapabilities caps = capabilities == null
                ? TerminalCapabilities.detectDefault()
                : capabilities;
        return caps.unicodeSafe() ? SAFE_UNICODE : ASCII;
    }

    public String bullet() { return bullet; }
    public String arrow() { return arrow; }
    public String success() { return success; }
    public String warning() { return warning; }
    public String error() { return error; }
    public String vertical() { return vertical; }
    public String horizontal() { return horizontal; }
    public String topLeft() { return topLeft; }
    public String bottomLeft() { return bottomLeft; }
    public String dot() { return dot; }
}
