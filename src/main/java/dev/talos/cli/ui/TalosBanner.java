package dev.talos.cli.ui;

import dev.talos.cli.CliUtil;
import dev.talos.core.Config;
import dev.talos.core.util.BuildInfo;

import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Renders Talos startup status.
 */
public final class TalosBanner {

    /**
     * R7 — single source of truth for the displayed version is the jar
     * manifest via {@link BuildInfo}. Falls back to {@code "unknown"} when
     * running from exploded classes (e.g. during tests).
     */
    private static String version() {
        return BuildInfo.version();
    }

    private TalosBanner() {}

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Prints the compact beta startup dashboard.
     */
    public static void print(Path workspace, Config cfg, String activeMode, PrintStream out) {
        print(workspace, cfg, activeMode, false, out);
    }

    /**
     * Prints the compact beta startup dashboard with session debug state.
     */
    public static void print(Path workspace, Config cfg, String activeMode, boolean debug, PrintStream out) {
        print(workspace, cfg, activeMode, debug ? "brief" : "off", out);
    }

    /**
     * Prints the compact beta startup dashboard with session debug level.
     */
    public static void print(Path workspace, Config cfg, String activeMode, String debug, PrintStream out) {
        out.println();
        var snapshot = CliStatusDashboard.snapshot(
                workspace,
                cfg,
                activeMode,
                resolveModel(cfg),
                debug,
                "Type a request or /help");
        out.print(CliStatusDashboard.render(snapshot));
    }

    /**
     * Prints a compact one-liner for --no-logo mode.
     */
    public static void printCompact(Path workspace, Config cfg, String activeMode, PrintStream out) {
        String model = resolveModel(cfg);
        String ws = CliUtil.shortenPath(workspace);
        out.println("  " + AnsiColor.brand("Talos") + " " + AnsiColor.dim("v" + version())
                + AnsiColor.grey(separator()) + model
                + AnsiColor.grey(separator()) + ws
                + AnsiColor.grey(" [") + AnsiColor.blue(activeMode) + AnsiColor.grey("]"));
        out.println();
    }

    private static String separator() {
        return AnsiColor.isUnicodeSafe() ? " · " : " - ";
    }

    // ── Config readers ────────────────────────────────────────────────────

    static String resolveModel(Config cfg) {
        return CliStatusDashboard.resolveModel(cfg);
    }
}

