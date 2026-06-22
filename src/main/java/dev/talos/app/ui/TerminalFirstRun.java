package dev.talos.app.ui;

import dev.talos.cli.doctor.DoctorContext;
import dev.talos.cli.doctor.DoctorEngine;
import dev.talos.cli.doctor.DoctorReportRenderer;
import dev.talos.cli.doctor.ProbeResult;
import dev.talos.core.Config;
import dev.talos.safety.SafeLogFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Terminal-based first-run setup flow.
 *
 * <p>Lightweight terminal
 * flow that works on all platforms including headless (WSL, SSH, Docker).
 *
 * <p>Steps:
 * <ol>
 *   <li>Describe how local model engines are configured</li>
 *   <li>Run the doctor preflight probes and print honest per-check results
 *       (T785 — this flow used to print an unverified "Setup complete")</li>
 *   <li>Write the sentinel file to skip on next launch</li>
 * </ol>
 *
 * <p>The sentinel is written even when checks fail: orientation is shown
 * once, and {@code Main} exits the process when this flow returns false, so
 * refusing to proceed would lock an unconfigured user out of the REPL
 * entirely. Recurring verification lives in {@code talos doctor}.
 */
public final class TerminalFirstRun {

    private static final Logger LOG = LoggerFactory.getLogger(TerminalFirstRun.class);

    private static final Path SENTINEL =
            Paths.get(System.getProperty("user.home"), ".talos", "first_run_done");

    private TerminalFirstRun() {}

    /** Runs the doctor preflight; injectable so tests never probe the real machine. */
    @FunctionalInterface
    interface DoctorRunner {
        List<ProbeResult> run();
    }

    /** Returns true if the first-run flow should be presented. */
    public static boolean shouldRun() {
        return !Files.exists(SENTINEL);
    }

    /**
     * Run the terminal-based first-run flow.
     * Always returns true (see class javadoc for the sentinel policy);
     * the per-check results carry the honest verdict.
     */
    public static boolean run() {
        return run(TerminalFirstRun::runDefaultProbes, System.out, SENTINEL);
    }

    private static List<ProbeResult> runDefaultProbes() {
        Config cfg = new Config();
        Path workspace = Path.of(".").toAbsolutePath().normalize();
        // Default probes only — first-run must never load a model on the GPU.
        return DoctorEngine.run(DoctorContext.of(cfg, workspace), DoctorEngine.defaultProbes());
    }

    static boolean run(DoctorRunner doctor, PrintStream out, Path sentinel) {
        out.println();
        out.println("  ╭──────────────────────────────────────╮");
        out.println("  │       Talos — First Run Setup        │");
        out.println("  ╰──────────────────────────────────────╯");
        out.println();

        out.println(setupSummary());
        out.println();

        List<ProbeResult> results = null;
        String probeError = "";
        try {
            results = doctor.run();
        } catch (Exception e) {
            probeError = String.valueOf(e.getMessage());
        }

        if (results == null) {
            // First-run must never crash the launcher — degrade to a notice.
            out.println("  Preflight checks could not run (" + probeError + ").");
            out.println("  Run 'talos doctor' after startup to verify the environment.");
        } else {
            out.print(DoctorReportRenderer.render(results).indent(2));
            long failed = results.stream()
                    .filter(r -> r.status() == ProbeResult.Status.FAIL)
                    .count();
            boolean warned = results.stream()
                    .anyMatch(r -> r.status() == ProbeResult.Status.WARN);
            if (failed > 0) {
                out.println("  Setup incomplete — " + failed + " check(s) failed.");
                out.println("  Fix the items above, then run 'talos doctor' to re-check.");
                out.println("  Configure models with 'talos setup models'.");
            } else if (warned) {
                out.println("  Setup complete with warnings. Starting Talos...");
            } else {
                out.println("  Setup verified. Starting Talos...");
            }
        }

        writeSentinel(sentinel);
        out.println();
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────

    public static String setupSummary() {
        return "  Talos uses local model engines. The default path is llama.cpp on Windows.\n"
                + "  Run `talos setup models` to configure a tested managed llama.cpp profile.\n"
                + "  Advanced users can set engines.llama_cpp.server_path and model_path in ~/.talos/config.yaml.\n"
                + "  Ollama can still be selected explicitly as a legacy backend.";
    }

    static void writeSentinel() {
        writeSentinel(SENTINEL);
    }

    static void writeSentinel(Path sentinel) {
        try {
            Files.createDirectories(sentinel.getParent());
            Files.writeString(sentinel, "ok");
        } catch (IOException ex) {
            LOG.warn("Failed to write first-run sentinel {}: {}",
                    SafeLogFormatter.value(sentinel), SafeLogFormatter.throwableMessage(ex));
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String readLine() {
        try {
            if (System.console() != null) {
                return System.console().readLine();
            }
            // Fallback for IDE/non-interactive — just return empty (accept default)
            return "";
        } catch (Exception e) {
            return "";
        }
    }
}


