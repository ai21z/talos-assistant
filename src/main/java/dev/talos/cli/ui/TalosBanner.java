package dev.talos.cli.ui;

import dev.talos.core.Config;

import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Renders Talos startup status.
 */
public final class TalosBanner {

    private TalosBanner() {}

    // ── Public API ────────────────────────────────────────────────────────

    /** Prints the trusted startup dashboard. */
    public static void print(Path workspace, Config cfg, String activeMode, PrintStream out) {
        print(workspace, cfg, activeMode, false, out);
    }

    /** Prints the trusted startup dashboard with session debug state. */
    public static void print(Path workspace, Config cfg, String activeMode, boolean debug, PrintStream out) {
        print(workspace, cfg, activeMode, debug ? "brief" : "off", out);
    }

    /** Prints the trusted startup dashboard with session debug level. */
    public static void print(Path workspace, Config cfg, String activeMode, String debug, PrintStream out) {
        out.println();
        var snapshot = CliStatusDashboard.snapshot(
                workspace,
                cfg,
                activeMode,
                resolveModel(cfg),
                debug,
                "Type a request or /help");
        out.print(StartupBannerRenderer.render(
                snapshot,
                TerminalCapabilities.detectDefault(),
                terminalWidth(),
                StartupBannerRenderer.Variant.STARTUP_WITH_ICON));
    }

    /**
     * Prints a compact no-icon banner for --no-logo mode.
     */
    public static void printCompact(Path workspace, Config cfg, String activeMode, PrintStream out) {
        var snapshot = CliStatusDashboard.snapshot(
                workspace,
                cfg,
                activeMode,
                resolveModel(cfg),
                "off",
                "Type a request or /help");
        out.println();
        out.print(StartupBannerRenderer.render(
                snapshot,
                TerminalCapabilities.detectDefault(),
                Math.min(StartupBannerRenderer.DEFAULT_WIDTH, terminalWidth()),
                StartupBannerRenderer.Variant.COMPACT_NO_ICON));
    }

    // ── Config readers ────────────────────────────────────────────────────

    static String resolveModel(Config cfg) {
        return CliStatusDashboard.resolveModel(cfg);
    }

    private static int terminalWidth() {
        String columns = System.getenv("COLUMNS");
        if (columns != null && !columns.isBlank()) {
            try {
                int parsed = Integer.parseInt(columns.trim());
                if (parsed >= 40) return parsed;
            } catch (NumberFormatException ignored) { }
        }
        return StartupBannerRenderer.DEFAULT_WIDTH;
    }
}

