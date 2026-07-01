package dev.talos.cli.ui;

import dev.talos.core.Config;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.function.IntSupplier;

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
        print(workspace, cfg, activeMode, debug, out, null);
    }

    /** Prints the trusted startup dashboard at the live terminal width (T771). */
    public static void print(Path workspace, Config cfg, String activeMode, String debug,
                             PrintStream out, IntSupplier terminalWidth) {
        out.println();
        var snapshot = CliStatusDashboard.snapshot(
                workspace,
                cfg,
                activeMode,
                resolveModel(cfg),
                debug,
                "Type a request or /help");
        TerminalCapabilities caps = TerminalCapabilities.detectDefault();
        int width = TerminalWidths.resolve(
                terminalWidth, System.getenv(), StartupBannerRenderer.DEFAULT_WIDTH);
        out.print(StartupBannerRenderer.render(
                snapshot,
                caps,
                width,
                StartupBannerRenderer.Variant.STARTUP_WITH_ICON));
    }

    /**
     * Prints a compact no-icon banner for --no-logo mode.
     */
    public static void printCompact(Path workspace, Config cfg, String activeMode, PrintStream out) {
        printCompact(workspace, cfg, activeMode, out, null);
    }

    /** Compact banner at the live terminal width, capped at the default (T771). */
    public static void printCompact(Path workspace, Config cfg, String activeMode,
                                    PrintStream out, IntSupplier terminalWidth) {
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
                Math.min(StartupBannerRenderer.DEFAULT_WIDTH, TerminalWidths.resolve(
                        terminalWidth, System.getenv(), StartupBannerRenderer.DEFAULT_WIDTH)),
                StartupBannerRenderer.Variant.COMPACT_NO_ICON));
    }

    // ── Config readers ────────────────────────────────────────────────────

    static String resolveModel(Config cfg) {
        return CliStatusDashboard.resolveModel(cfg);
    }
}

