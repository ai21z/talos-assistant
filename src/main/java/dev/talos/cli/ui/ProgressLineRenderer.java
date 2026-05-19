package dev.talos.cli.ui;

/**
 * Renders compact semantic progress lines outside the answer body.
 */
public final class ProgressLineRenderer {
    private final CliTheme theme;
    private final SemanticGlyphSet glyphs;

    public ProgressLineRenderer(CliTheme theme) {
        this.theme = theme == null ? CliTheme.current() : theme;
        this.glyphs = SemanticGlyphSet.forCapabilities(this.theme.capabilities());
    }

    public String route(String routeLabel, String detail) {
        String label = safe(routeLabel);
        if (label.isBlank()) return "";
        StringBuilder sb = new StringBuilder("  ");
        sb.append(theme.active(glyphs.bullet())).append(" ");
        sb.append(theme.metadata("route")).append(" ");
        sb.append(label);
        String extra = safe(detail);
        if (!extra.isBlank()) {
            sb.append(" ").append(theme.muted(glyphs.dot())).append(" ").append(theme.metadata(extra));
        }
        return sb.toString();
    }

    public String tool(String toolName, String action, String detail) {
        String safeAction = safe(action);
        String shortName = shortToolName(toolName);
        String safeDetail = safe(detail);
        return switch (safeAction) {
            case "executing" -> line(theme.active(glyphs.arrow()), executingLabel(shortName), safeDetail);
            case "completed" -> line(theme.success(glyphs.success()), shortName + " done", "");
            case "warning" -> line(theme.warning(glyphs.warning()), "verification warning", safeDetail);
            case "error" -> line(theme.error(glyphs.error()), shortName + " failed", safeDetail);
            case "approval" -> line(theme.warning(glyphs.warning()), "approval " + shortName, safeDetail);
            default -> line(theme.active(glyphs.arrow()), safeAction + " " + shortName, safeDetail);
        };
    }

    public String turnStats(int turnNumber, long elapsedMs, int responseLen) {
        StringBuilder sb = new StringBuilder("Turn ");
        sb.append(turnNumber);
        sb.append(" ").append(glyphs.dot()).append(" ");
        if (elapsedMs < 1000) {
            sb.append(elapsedMs).append("ms");
        } else {
            sb.append(String.format(java.util.Locale.ROOT, "%.1fs", elapsedMs / 1000.0));
        }
        if (responseLen > 0) {
            sb.append(" ").append(glyphs.dot()).append(" ~").append(responseLen).append(" chars");
        }
        sb.append(" ").append(glyphs.dot()).append(" /last trace");
        return line(theme.success(glyphs.success()), sb.toString(), "");
    }

    private String line(String icon, String label, String detail) {
        StringBuilder sb = new StringBuilder("  ");
        sb.append(icon).append(" ").append(label);
        if (detail != null && !detail.isBlank()) {
            sb.append(" ").append(theme.metadata(detail));
        }
        return sb.toString();
    }

    private static String executingLabel(String shortName) {
        return switch (shortName) {
            case "read_file" -> "read";
            case "write_file" -> "write";
            case "edit_file" -> "edit";
            case "list_dir" -> "list";
            default -> shortName;
        };
    }

    private static String shortToolName(String toolName) {
        String safeToolName = safe(toolName);
        return safeToolName.startsWith("talos.") ? safeToolName.substring(6) : safeToolName;
    }

    private static String safe(String text) {
        return text == null ? "" : text.trim();
    }
}
