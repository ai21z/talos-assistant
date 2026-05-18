package dev.talos.cli.ui;

import dev.talos.core.util.Sanitize;

import java.util.Locale;
import java.util.Objects;

/**
 * Pure renderer for trusted Talos startup and status surfaces.
 *
 * <p>This class never renders model-originated text. Runtime values are still
 * sanitized defensively before styling because workspace paths, model labels,
 * and config strings can contain terminal control bytes.
 */
public final class StartupBannerRenderer {
    static final int DEFAULT_WIDTH = 80;
    private static final int SPLIT_MIN_WIDTH = 70;
    private static final int PLAIN_MIN_WIDTH = 50;
    private static final int LEFT_PANEL = 25;
    private static final int ICON_WIDTH = 10;
    private static final int LEFT_TEXT_WIDTH = LEFT_PANEL - ICON_WIDTH - 4;

    /** Talos H100 head, 10 cells x 5 rows.  Eyes on row 1.  Stem on rows 2-3. */
    private static final String[] ICON = {
            "▟███▀▀███▙",
            "  ▶    ◀  ",
            "████  ████",
            " ▀██  ██▀ ",
            "          "
    };

    // Eye coordinates inside ICON[1] for the wink animation.
    // Eye row: "  ▶    ◀  "  →  left eye at index 2, right eye at index 7.
    static final int ICON_EYE_ROW = 1;
    static final int ICON_LEFT_EYE_COL = 2;
    static final int ICON_RIGHT_EYE_COL = 7;

    // ─── Banner geometry (used by the wink animator in TalosBanner) ───────
    //
    // STARTUP_WITH_ICON line count:  top(1) + icon-rows(5) + rejoin(1)
    //                              + gov(1) + sep(1) + hint(1) + bot(1) = 11
    //
    // After PrintStream.print(banner) the trailing '\n' on the bottom border
    // moves the cursor to the line *below* the bottom border.  Distance from
    // that line back UP to the eye row is therefore 11 - 2 = 9 lines.
    // (Eye row is line index 2 when counting from the top border = line 0.)
    //
    // Right-eye screen column inside the framed left panel:
    //   col 1 = '│',  col 2 = ' ',  cols 3..12 = ICON[1],  eye at icon idx 7
    //   → screen column 3 + 7 = 10.
    static final int BANNER_LINES_BELOW_EYE_ROW = 9;
    static final int RIGHT_EYE_SCREEN_COL = 10;

    // Wink frame sequence.  Sized so the closed phase ("◞ ─ ─ ─") holds
    // 400 ms at 100 ms/frame, reading clearly as a wink, not a glitch.
    private static final String[] WINK_FRAMES = {
            "◀","◀","◄","◅","◞","─","─","─","◞","◅","◄","◀","◀","◀"
    };
    private static final long WINK_FRAME_MILLIS = 100L;

    // ─── Lamps & badges (V8/V10/V12 doctrine) ────────────────────────────
    // Each glyph carries information, never decoration.

    private static final String LAMP_OK       = "●";   // ready / loaded / verified
    private static final String LAMP_ACTIVE   = "◐";   // building / brief / partial-trust
    private static final String LAMP_WARN     = "◌";   // stale / risk
    private static final String LAMP_TRACE    = "◍";   // debug=trace / debug-on
    private static final String LAMP_UNSET    = "○";   // off / read-only / no-mutation
    private static final String LAMP_ERROR    = "●";   // error (red-colored)
    private static final String MODE_BADGE_READ  = "○";
    private static final String MODE_BADGE_AUTO  = "◐";
    private static final String MODE_BADGE_DEV   = "◉";
    private static final String MODE_BADGE_DEBUG = "◍";
    private static final String TRUST_DIAMOND = "◇";

    private StartupBannerRenderer() {}

    public enum Variant {
        STARTUP_WITH_ICON,
        STATUS_NO_ICON,
        COMPACT_NO_ICON
    }

    public static String render(
            CliStatusDashboard.Snapshot snapshot,
            TerminalCapabilities capabilities,
            int width,
            Variant variant) {
        TerminalCapabilities caps = capabilities == null
                ? TerminalCapabilities.detectDefault()
                : capabilities;
        int w = Math.max(40, width <= 0 ? DEFAULT_WIDTH : width);
        CliStatusDashboard.Snapshot s = normalize(snapshot, caps);
        Variant v = variant == null ? Variant.STARTUP_WITH_ICON : variant;

        if (!caps.unicodeSafe()) {
            return renderAscii(s, Math.max(DEFAULT_WIDTH, w));
        }
        if (w < PLAIN_MIN_WIDTH) {
            return renderPlain(s, caps);
        }
        if (v == Variant.STATUS_NO_ICON) {
            return w < SPLIT_MIN_WIDTH
                    ? renderCompact(s, caps, w)
                    : renderStatusNoIcon(s, caps, w);
        }
        if (v == Variant.COMPACT_NO_ICON || w < SPLIT_MIN_WIDTH) {
            return renderCompact(s, caps, w);
        }
        return renderStartupWithIcon(s, caps, w);
    }

    /**
     * Returns true when the renderer would have emitted the STARTUP_WITH_ICON
     * variant for the given inputs.  Callers use this to decide whether the
     * wink animation is meaningful (no icon → no wink).
     */
    public static boolean wouldRenderIcon(TerminalCapabilities capabilities, int width, Variant variant) {
        TerminalCapabilities caps = capabilities == null
                ? TerminalCapabilities.detectDefault()
                : capabilities;
        if (!caps.unicodeSafe()) return false;
        if (width < SPLIT_MIN_WIDTH) return false;
        Variant v = variant == null ? Variant.STARTUP_WITH_ICON : variant;
        return v == Variant.STARTUP_WITH_ICON;
    }

    /**
     * Animates a one-shot wink on the right eye of the H100 head, in place,
     * inside the framed banner that was just printed.
     *
     * <p>Pre-condition: the caller printed exactly one STARTUP_WITH_ICON
     * banner to {@code out} and nothing else since.  This method walks the
     * cursor up to the eye row with {@code ESC[<n>A}, repaints only the two
     * eye cells with absolute-column positioning ({@code ESC[<n>G}), then
     * walks back down so subsequent output continues normally.
     *
     * <p>Animation is OPT-OUT.  It is automatically skipped when:
     * <ul>
     *   <li>{@code $TALOS_BANNER_NO_ANIMATION} is set (any non-empty value)</li>
     *   <li>{@code $NO_COLOR} is set</li>
     *   <li>the process has no console (stdout redirected / piped)</li>
     *   <li>color or unicode are unavailable in the detected capabilities</li>
     * </ul>
     */
    public static void animateStartupWink(java.io.PrintStream out, TerminalCapabilities capabilities) {
        if (out == null) return;
        TerminalCapabilities caps = capabilities == null
                ? TerminalCapabilities.detectDefault()
                : capabilities;
        if (!shouldAnimate(caps)) return;

        String bronzeOn  = "\033[38;2;167;123;58m";
        String reset     = "\033[0m";
        int linesUp      = BANNER_LINES_BELOW_EYE_ROW;
        int eyeCol       = RIGHT_EYE_SCREEN_COL;

        try {
            for (String frame : WINK_FRAMES) {
                // Up to eye row → jump to eye column → paint glyph → restore.
                out.print("\033[" + linesUp + "A");
                out.print("\033[" + eyeCol + "G");
                out.print(bronzeOn);
                out.print(frame);
                out.print(reset);
                out.print("\033[" + linesUp + "B");
                out.print("\033[1G");
                out.flush();
                Thread.sleep(WINK_FRAME_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Restore cursor sanity if interrupted mid-animation.
            out.print("\033[" + BANNER_LINES_BELOW_EYE_ROW + "B");
            out.print("\033[1G");
            out.flush();
        }
    }

    private static boolean shouldAnimate(TerminalCapabilities caps) {
        if (caps == null || !caps.colorEnabled() || !caps.unicodeSafe()) return false;
        if (!caps.interactive()) return false;
        String optOut = System.getenv("TALOS_BANNER_NO_ANIMATION");
        if (optOut != null && !optOut.isBlank()) return false;
        String noColor = System.getenv("NO_COLOR");
        if (noColor != null && !noColor.isBlank()) return false;
        // System.console() is the most reliable "is this really a TTY?" probe
        // on Windows; capabilities.interactive() is a hint, this is the truth.
        return System.console() != null;
    }

    private static String renderStartupWithIcon(
            CliStatusDashboard.Snapshot s,
            TerminalCapabilities caps,
            int width) {
        int rightPanel = width - LEFT_PANEL - 3;
        int rightValueWidth = Math.max(8, rightPanel - 14);
        Style style = new Style(caps);
        StringBuilder out = new StringBuilder();

        appendLine(out, style.frame("┌" + repeat("─", LEFT_PANEL) + "┬" + repeat("─", rightPanel) + "┐"));

        String[] left = {"TALOS", version(s.version()), "", "", ""};
        String[][] right = {
                {"Workspace", fitWorkspace(s.workspace(), rightValueWidth)},
                {"Mode", fitText(s.mode(), rightValueWidth)},
                {"Model", fitModel(s.model(), rightValueWidth)},
                {"Engine", fitEngine(s.engine(), rightValueWidth)},
                {"Index", fitIndex(s.index(), rightValueWidth)}
        };

        int rows = Math.max(ICON.length, right.length);
        for (int i = 0; i < rows; i++) {
            String icon = i < ICON.length ? fitText(ICON[i], ICON_WIDTH) : repeat(" ", ICON_WIDTH);
            String leftContent = " "
                    + style.bronze(icon)
                    + "  "
                    + styledPadded(left[i], LEFT_TEXT_WIDTH, style.leftIdentityColor(i))
                    + " ";
            String label = right[i][0];
            String value = right[i][1];
            String rightValue = styledPadded(value, rightValueWidth, style.valueColor(label, value, s.debug()));
            String rightContent = " "
                    + styledPadded(label, 11, style::bronze)
                    + " "
                    + rightValue
                    + " ";

            appendLine(out, style.frame("│") + leftContent + style.frame("│") + rightContent + style.frame("│"));
        }

        appendLine(out, style.frame("├" + repeat("─", LEFT_PANEL) + "┴" + repeat("─", rightPanel) + "┤"));
        appendLine(out, governanceRow(s, caps, width));
        appendLine(out, style.frame("├" + repeat("─", width - 2) + "┤"));
        appendLine(out, hintRow(s, caps, width));
        appendLine(out, style.frame("└" + repeat("─", width - 2) + "┘"));
        return out.toString();
    }

    private static String renderStatusNoIcon(
            CliStatusDashboard.Snapshot s,
            TerminalCapabilities caps,
            int width) {
        Style style = new Style(caps);
        int contentWidth = width - 4;
        int valueWidth = Math.max(8, contentWidth - 12);
        StringBuilder out = new StringBuilder();

        appendLine(out, style.frame("┌" + repeat("─", width - 2) + "┐"));
        appendStatusRow(out, style, "TALOS", version(s.version()), valueWidth, s);
        appendStatusRow(out, style, "Workspace", fitWorkspace(s.workspace(), valueWidth), valueWidth, s);
        appendStatusRow(out, style, "Mode", fitText(s.mode(), valueWidth), valueWidth, s);
        appendStatusRow(out, style, "Model", fitModel(s.model(), valueWidth), valueWidth, s);
        appendStatusRow(out, style, "Engine", fitEngine(s.engine(), valueWidth), valueWidth, s);
        appendStatusRow(out, style, "Index", fitIndex(s.index(), valueWidth), valueWidth, s);
        appendLine(out, style.frame("├" + repeat("─", width - 2) + "┤"));
        appendLine(out, governanceRow(s, caps, width));
        appendLine(out, style.frame("└" + repeat("─", width - 2) + "┘"));
        return out.toString();
    }

    private static String renderCompact(
            CliStatusDashboard.Snapshot s,
            TerminalCapabilities caps,
            int width) {
        if (width < PLAIN_MIN_WIDTH) {
            return renderPlain(s, caps);
        }
        Style style = new Style(caps);
        int contentWidth = width - 4;
        StringBuilder out = new StringBuilder();

        appendLine(out, style.frame("┌" + repeat("─", width - 2) + "┐"));
        appendPlainBoxRow(out, style, styledJoin(style.bronze("TALOS"), " ", style.meta(version(s.version()))), "TALOS " + version(s.version()), contentWidth);
        appendPlainBoxRow(out, style, style.body(fitWorkspace(s.workspace(), contentWidth)), fitWorkspace(s.workspace(), contentWidth), contentWidth);
        String runtime = fitText(s.mode(), 12) + " · " + fitModel(s.model(), 28) + " · " + shortEngine(s.engine());
        appendPlainBoxRow(out, style, style.body(fitText(runtime, contentWidth)), fitText(runtime, contentWidth), contentWidth);
        String trust = "index " + compactIndex(s.index()) + " · " + s.policy() + " · debug " + s.debug();
        appendPlainBoxRow(out, style, style.body(fitText(trust, contentWidth)), fitText(trust, contentWidth), contentWidth);
        appendLine(out, style.frame("├" + repeat("─", width - 2) + "┤"));
        String hint = compactHint(s);
        appendPlainBoxRow(out, style, styledCompactHint(hint, style), fitText(hint, contentWidth), contentWidth);
        appendLine(out, style.frame("└" + repeat("─", width - 2) + "┘"));
        return out.toString();
    }

    private static String renderPlain(CliStatusDashboard.Snapshot s, TerminalCapabilities caps) {
        String sep = caps.unicodeSafe() ? " · " : " - ";
        StringBuilder out = new StringBuilder();
        appendLine(out, "TALOS " + version(s.version()));
        appendLine(out, "workspace  " + s.workspace());
        appendLine(out, "runtime    " + s.mode() + sep + s.model() + sep + shortEngine(s.engine()));
        appendLine(out, "trust      " + s.policy() + sep + "debug " + s.debug());
        appendLine(out, "index      " + compactIndex(s.index()));
        appendLine(out, compactHint(s));
        return out.toString();
    }

    private static String renderAscii(CliStatusDashboard.Snapshot s, int width) {
        int w = Math.max(60, width);
        int contentWidth = w - 4;
        StringBuilder out = new StringBuilder();
        appendLine(out, "+" + repeat("-", w - 2) + "+");
        appendAsciiRow(out, fitText("TALOS  " + version(s.version()), contentWidth), contentWidth);
        appendAsciiRow(out, asciiField("Workspace", s.workspace(), contentWidth - 12), contentWidth);
        appendAsciiRow(out, asciiPair("Mode", s.mode(), "Model", s.model(), contentWidth), contentWidth);
        appendAsciiRow(out, asciiPair("Engine", s.engine(), "Index", compactIndex(s.index()), contentWidth), contentWidth);
        appendAsciiRow(out, asciiPair("Policy", s.policy(), "Debug", s.debug(), contentWidth), contentWidth);
        appendLine(out, "+" + repeat("-", w - 2) + "+");
        Hint hint = hint(s);
        appendAsciiRow(out, "[ok] " + hint.state() + " - " + hint.rest().replace(" · ", " - "), contentWidth);
        appendLine(out, "+" + repeat("-", w - 2) + "+");
        return out.toString();
    }

    private static void appendStatusRow(StringBuilder out, Style style, String label, String value, int valueWidth, CliStatusDashboard.Snapshot s) {
        String renderedValue;
        renderedValue = styledPadded(value, valueWidth, style.valueColor(label, value, s.debug()));
        String content = " "
                + styledPadded(label, 11, style::bronze)
                + " "
                + renderedValue
                + " ";
        appendLine(out, style.frame("│") + content + style.frame("│"));
    }

    private static String governanceRow(CliStatusDashboard.Snapshot s, TerminalCapabilities caps, int width) {
        Style style = new Style(caps);
        int contentWidth = width - 4;
        int leftValueWidth = Math.min(34, Math.max(8, contentWidth - 42));
        int rightValueWidth = Math.max(4, contentWidth - (6 + 2 + leftValueWidth + 1 + 5 + 2));
        String left = styledPadded("Policy", 6, style::bronze)
                + "  "
                + styledPadded(fitText(s.policy(), leftValueWidth), leftValueWidth, style.policyColor(s.policy()));
        String right = styledPadded("Debug", 5, style::bronze)
                + "  "
                + styledPadded(fitText(s.debug(), rightValueWidth), rightValueWidth, style.debugColor(s.debug()));
        int plainLeft = 6 + 2 + leftValueWidth;
        int gap = Math.max(1, contentWidth - plainLeft - (5 + 2 + rightValueWidth));
        return style.frame("│") + " " + left + repeat(" ", gap) + right + " " + style.frame("│");
    }

    private static String hintRow(CliStatusDashboard.Snapshot s, TerminalCapabilities caps, int width) {
        Style style = new Style(caps);
        Hint hint = hint(s);
        int contentWidth = width - 4;
        String plain = fitText(hint.state() + " · " + hint.rest(), contentWidth);
        String styled = styledCompactHint(plain, style);
        return style.frame("│") + " " + styled + repeat(" ", Math.max(0, contentWidth - plain.length())) + " " + style.frame("│");
    }

    private static String styledHintWithLamp(String lamp, String stateExpected, String plain, Style style) {
        String prefix = lamp + " ";
        if (!plain.startsWith(prefix)) {
            // truncation removed lamp prefix; fall back to body styling
            return style.body(plain);
        }
        String afterLamp = plain.substring(prefix.length());
        int split = afterLamp.indexOf(" · ");
        if (split < 0) {
            return style.hintStateColor(stateExpected).apply(lamp) + " " + style.body(afterLamp);
        }
        String state = afterLamp.substring(0, split);
        String rest = afterLamp.substring(split + 3);
        Styler stateStyler = style.hintStateColor(state);
        return stateStyler.apply(lamp) + " "
                + stateStyler.apply(state)
                + style.frame(" · ")
                + style.body(rest);
    }

    private static String styledCompactHint(String plain, Style style) {
        int split = plain.indexOf(" · ");
        if (split < 0) {
            return style.valueColor("hint", plain, "off").apply(plain);
        }
        String state = plain.substring(0, split);
        String rest = plain.substring(split + 3);
        return style.hintStateColor(state).apply(state)
                + style.frame(" · ")
                + style.body(rest);
    }

    private static void appendPlainBoxRow(StringBuilder out, Style style, String styledText, String plainText, int contentWidth) {
        String clipped = fitText(plainText, contentWidth);
        String rendered = plainText.equals(clipped) ? styledText : clipped;
        appendLine(out, style.frame("│") + " " + rendered + repeat(" ", Math.max(0, contentWidth - clipped.length())) + " " + style.frame("│"));
    }

    private static String styledPadded(String text, int width, Styler styler) {
        String clipped = fitText(text, width);
        String styled = clipped.isBlank() ? clipped : styler.apply(clipped);
        return styled + repeat(" ", Math.max(0, width - clipped.length()));
    }

    private static String styledJoin(String... parts) {
        return String.join("", parts);
    }

    private static void appendAsciiRow(StringBuilder out, String content, int contentWidth) {
        appendLine(out, "| " + fitText(content, contentWidth) + repeat(" ", Math.max(0, contentWidth - fitText(content, contentWidth).length())) + " |");
    }

    private static String asciiField(String label, String value, int valueWidth) {
        return padRight(label, 11) + " " + fitText(value, valueWidth);
    }

    private static String asciiPair(String leftLabel, String leftValue, String rightLabel, String rightValue, int contentWidth) {
        String left = padRight(leftLabel, 11) + " " + fitText(leftValue, 26);
        String right = padRight(rightLabel, 8) + fitText(rightValue, Math.max(4, contentWidth - 41 - 8));
        return padRight(left, 41) + right;
    }

    private static Hint hint(CliStatusDashboard.Snapshot s) {
        String mode = lower(s.mode());
        if (mode.equals("debug")) {
            return new Hint("debug on", "use /last trace or /prompt-debug last");
        }
        if (mode.equals("read") || mode.equals("rag") || mode.equals("ask")) {
            return new Hint("read-only", "ask about files or use /help");
        }
        if (mode.equals("dev")) {
            return new Hint("governed edits", "writes require approval");
        }
        return new Hint("ready", "type /help, /status, /tools · or ask a question");
    }

    private static String compactHint(CliStatusDashboard.Snapshot s) {
        Hint hint = hint(s);
        if ("ready".equals(hint.state())) {
            return "ready · type /help · or ask a question";
        }
        return hint.state() + " · " + hint.rest();
    }

    private static String compactIndex(String index) {
        String value = Objects.toString(index, "unknown").trim();
        int dot = value.indexOf(" · ");
        if (dot >= 0) return value.substring(0, dot);
        int dash = value.indexOf(" - ");
        if (dash >= 0) return value.substring(0, dash);
        int paren = value.indexOf(" (");
        if (paren >= 0) return value.substring(0, paren);
        return value.isBlank() ? "unknown" : value;
    }

    private static String fitIndex(String value, int width) {
        String text = blankDefault(value, "unknown");
        if (text.length() <= width) return text;
        String compact = compactIndex(text);
        if (compact.length() <= width) return compact;
        return fitText(compact, width);
    }

    private static String fitEngine(String value, int width) {
        String text = blankDefault(value, "unknown");
        if (text.length() <= width) return text;
        String compact = shortEngine(text);
        if (compact.length() <= width) return compact;
        return fitText(compact, width);
    }

    private static String shortEngine(String engine) {
        String text = blankDefault(engine, "unknown");
        return text.replaceFirst("\\s*\\([^)]*\\)$", "");
    }

    private static String fitWorkspace(String value, int width) {
        String text = blankDefault(value, ".");
        if (text.length() <= width) return text;
        String shortened = middleTruncatePath(text, width);
        if (shortened.length() <= width) return shortened;
        return fitText(shortened, width);
    }

    private static String middleTruncatePath(String path, int width) {
        String normalized = path.replace('/', '\\');
        String prefix = "";
        if (normalized.matches("^[A-Za-z]:\\\\.*")) {
            prefix = normalized.substring(0, 3) + "...\\";
            normalized = normalized.substring(3);
        } else if (normalized.startsWith("~\\")) {
            prefix = "~\\...\\";
            normalized = normalized.substring(2);
        } else {
            prefix = "...\\";
        }

        String[] rawParts = normalized.split("\\\\+");
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String part : rawParts) {
            if (!part.isBlank()) parts.add(part);
        }
        String suffix = "";
        for (int i = parts.size() - 1; i >= 0; i--) {
            suffix = suffix.isBlank() ? parts.get(i) : parts.get(i) + "\\" + suffix;
            String candidate = prefix + suffix;
            if (candidate.length() > width) {
                break;
            }
            if (parts.size() - i >= 3) {
                return candidate;
            }
        }
        String candidate = prefix + suffix;
        return candidate.length() <= width ? candidate : fitText(candidate, width);
    }

    private static String fitModel(String value, int width) {
        return fitText(blankDefault(value, "unknown"), width);
    }

    private static String fitText(String value, int width) {
        String text = Objects.toString(value, "");
        if (width <= 0) return "";
        if (text.length() <= width) return text;
        if (width <= 3) return ".".repeat(width);
        return text.substring(0, width - 3) + "...";
    }

    private static CliStatusDashboard.Snapshot normalize(CliStatusDashboard.Snapshot snapshot, TerminalCapabilities caps) {
        CliStatusDashboard.Snapshot s = snapshot == null
                ? new CliStatusDashboard.Snapshot("unknown", ".", "auto", "unknown", "unknown",
                "unknown", "unknown", "off", "ready · type /help")
                : snapshot;
        boolean unicode = caps != null && caps.unicodeSafe();
        return new CliStatusDashboard.Snapshot(
                clean(s.version(), unicode),
                clean(s.workspace(), unicode),
                clean(s.mode(), unicode),
                clean(s.model(), unicode),
                clean(s.engine(), unicode),
                clean(s.index(), unicode),
                clean(s.policy(), unicode),
                clean(s.debug(), unicode),
                clean(s.next(), unicode));
    }

    private static String clean(String value, boolean unicodeSafe) {
        String cleaned = Sanitize.sanitizeForOutput(Objects.toString(value, ""));
        if (unicodeSafe) return cleaned;
        return Sanitize.toAsciiFallback(cleaned.replace("·", "-"));
    }

    private static String version(String version) {
        String value = blankDefault(version, "unknown");
        return value.startsWith("v") ? value : "v" + value;
    }

    private static String blankDefault(String value, String fallback) {
        String text = Objects.toString(value, "").trim();
        return text.isBlank() ? fallback : text;
    }

    private static String lower(String value) {
        return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
    }

    private static String padRight(String text, int width) {
        String clipped = fitText(text, width);
        return clipped + repeat(" ", Math.max(0, width - clipped.length()));
    }

    private static String repeat(String s, int count) {
        if (count <= 0) return "";
        return s.repeat(count);
    }

    private static void appendLine(StringBuilder out, String line) {
        out.append(line).append('\n');
    }

    private record Hint(String state, String rest) {}

    @FunctionalInterface
    private interface Styler {
        String apply(String value);
    }

    private static final class Style {
        // Talos site palette (site/src/styles.css)
        //   --bronze #c28a4c brand              → 194,138, 76
        //   --cyan   #43d7d2 active/affordance  →  67,215,210
        //   --text   #f3ecdf body               → 243,236,223
        //   --muted  #a99f91 meta/dim           → 169,159,145
        //   --border bronze@24% on #090c0c      → 110, 84, 46  (warm dim frame)
        // Semantic state extensions tuned to the same warm key:
        //   green (settled-ok)  → 110,200,140
        //   amber (warn/trace)  → 215,162, 90
        //   red   (error)       → 217,107, 92
        private final boolean color;

        private Style(TerminalCapabilities caps) {
            this.color = caps != null && caps.colorEnabled();
        }

        String bronze(String text) { return fg(167, 123, 58, text); }
        String cyan(String text) { return fg(95, 175, 215, text); }
        String frame(String text) { return fg(90, 90, 90, text); }
        String body(String text) { return fg(222, 222, 222, text); }
        String green(String text) { return fg(95, 175, 95, text); }
        String amber(String text) { return fg(215, 175, 95, text); }
        String red(String text) { return fg(215, 95, 95, text); }
        String meta(String text) { return frame(text); }

        Styler leftIdentityColor(int row) {
            if (row == 0) return this::bronze;
            if (row == 1) return this::meta;
            return value -> value;
        }

        Styler valueColor(String label, String value, String debug) {
            String lower = lower(value);
            if ("Index".equals(label)) {
                if (lower.contains("error") || lower.contains("unavailable")) return this::red;
                if (lower.contains("stale") || lower.contains("warn")) return this::amber;
                if (lower.contains("building")) return this::cyan;
                if (lower.contains("ready")) return this::green;
            }
            if ("Debug".equals(label)) return debugColor(debug);
            return this::body;
        }

        Styler policyColor(String policy) {
            String lower = lower(policy);
            if (lower.contains("require approval") || lower.contains("warn")) return this::amber;
            return this::body;
        }

        Styler debugColor(String debug) {
            String lower = lower(debug);
            if (lower.equals("off")) return this::meta;
            if (lower.equals("brief")) return this::cyan;
            return this::amber;
        }

        Styler debugLampColor(String debug) {
            return debugColor(debug);
        }

        Styler modeBadgeColor(String mode) {
            String lower = lower(mode);
            if (lower.equals("read") || lower.equals("rag") || lower.equals("ask")) return this::meta;
            if (lower.equals("dev")) return this::amber;
            // auto + debug both read as "live affordance"
            return this::cyan;
        }

        Styler indexLampColor(String index) {
            String lower = lower(index);
            if (lower.contains("error") || lower.contains("unavailable")) return this::red;
            if (lower.contains("stale") || lower.contains("warn")) return this::amber;
            if (lower.contains("building")) return this::cyan;
            if (lower.contains("none") || lower.contains("unknown") || lower.contains("unset")) return this::meta;
            return this::green;
        }

        Styler hintStateColor(String state) {
            String lower = lower(state);
            if (lower.contains("governed")) return this::amber;
            if (lower.contains("debug")) return this::cyan;
            if (lower.contains("read")) return this::meta;
            return this::green;
        }

        private String fg(int r, int g, int b, String text) {
            if (!color || text == null || text.isEmpty()) return Objects.toString(text, "");
            return "\033[38;2;" + r + ";" + g + ";" + b + "m" + text + "\033[0m";
        }
    }
}
