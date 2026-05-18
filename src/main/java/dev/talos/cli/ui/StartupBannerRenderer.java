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

    private static final String[] ICON = {
            "████████",
            "█      █",
            "███  ███",
            "███  ███",
            " ▀█  █▀ "
    };

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

        for (int i = 0; i < ICON.length; i++) {
            String leftContent = " "
                    + style.bronze(ICON[i])
                    + "  "
                    + styledPadded(left[i], 13, style.leftIdentityColor(i))
                    + " ";
            String rightContent = " "
                    + styledPadded(right[i][0], 11, style::bronze)
                    + " "
                    + styledPadded(right[i][1], rightValueWidth, style.valueColor(right[i][0], right[i][1], s.debug()))
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
        appendStatusRow(out, style, "TALOS", version(s.version()), valueWidth);
        appendStatusRow(out, style, "Workspace", fitWorkspace(s.workspace(), valueWidth), valueWidth);
        appendStatusRow(out, style, "Mode", fitText(s.mode(), valueWidth), valueWidth);
        appendStatusRow(out, style, "Model", fitModel(s.model(), valueWidth), valueWidth);
        appendStatusRow(out, style, "Engine", fitEngine(s.engine(), valueWidth), valueWidth);
        appendStatusRow(out, style, "Index", fitIndex(s.index(), valueWidth), valueWidth);
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
        appendPlainBoxRow(out, style, styledHint(hint, style), fitText(hint, contentWidth), contentWidth);
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

    private static void appendStatusRow(StringBuilder out, Style style, String label, String value, int valueWidth) {
        String content = " "
                + styledPadded(label, 11, style::bronze)
                + " "
                + styledPadded(value, valueWidth, style.valueColor(label, value, "off"))
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
        String styled = styledHint(plain, style);
        return style.frame("│") + " " + styled + repeat(" ", Math.max(0, contentWidth - plain.length())) + " " + style.frame("│");
    }

    private static String styledHint(String plain, Style style) {
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
