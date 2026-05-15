package dev.talos.app.ui;

import dev.talos.runtime.policy.SafeLogFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Terminal-based first-run setup flow.
 *
 * <p>Lightweight terminal
 * flow that works on all platforms including headless (WSL, SSH, Docker).
 *
 * <p>Steps:
 * <ol>
 *   <li>Describe active local engine configuration</li>
 *   <li>Point users at llama.cpp server/model path settings</li>
 *   <li>Write sentinel file to skip on next launch</li>
 * </ol>
 */
public final class TerminalFirstRun {

    private static final Logger LOG = LoggerFactory.getLogger(TerminalFirstRun.class);

    private static final Path SENTINEL =
            Paths.get(System.getProperty("user.home"), ".talos", "first_run_done");

    private static final String DEFAULT_MODEL = "talos-agent";
    private static final long OLLAMA_PROBE_TIMEOUT_SECONDS = 5;

    private TerminalFirstRun() {}

    /** Returns true if the first-run flow should be presented. */
    public static boolean shouldRun() {
        return !Files.exists(SENTINEL);
    }

    /**
     * Run the terminal-based first-run flow.
     * Returns true if setup completed successfully.
     */
    public static boolean run() {
        System.out.println();
        System.out.println("  ╭──────────────────────────────────────╮");
        System.out.println("  │       Talos — First Run Setup        │");
        System.out.println("  ╰──────────────────────────────────────╯");
        System.out.println();

        System.out.println(setupSummary());
        System.out.println();

        // Step 1: Write config & sentinel
        System.out.println("  Configuration:");
        System.out.println("    Backend:   llama_cpp");
        System.out.println("    Model:     " + DEFAULT_MODEL);
        System.out.println("    Engine:    configure engines.llama_cpp.server_path and model_path");
        System.out.println("    Embeddings: compat/talos-embed");
        System.out.println();

        writeSentinel();

        System.out.println("  ✓ Setup complete. Starting Talos...");
        System.out.println();
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────

    public static String setupSummary() {
        return "  Talos uses local model engines. The default path is llama.cpp on Windows.\n"
                + "  Run `talos setup models` to configure a tested managed llama.cpp profile.\n"
                + "  Advanced users can set engines.llama_cpp.server_path and model_path in ~/.talos/config.yaml.\n"
                + "  Ollama can still be selected explicitly as a legacy backend.";
    }

    static boolean checkOllamaInstalled() {
        try {
            Process p = new ProcessBuilder("ollama", "version")
                    .redirectErrorStream(true)
                    .start();
            if (!waitForProbe(p)) return false;
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String getOllamaVersion() {
        try {
            Process p = new ProcessBuilder("ollama", "version")
                    .redirectErrorStream(true)
                    .start();
            if (!waitForProbe(p)) return null;
            String output = new String(p.getInputStream().readAllBytes()).trim();
            return p.exitValue() == 0 ? output : null;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean checkModelAvailable(String model) {
        if (model == null || model.isBlank()) return false;
        try {
            Process p = new ProcessBuilder("ollama", "list")
                    .redirectErrorStream(true)
                    .start();
            if (!waitForProbe(p)) return false;
            String output = new String(p.getInputStream().readAllBytes());
            if (p.exitValue() != 0) return false;
            // Model name may appear with tag, e.g. "qwen3:8b"
            String baseName = model.contains(":") ? model.substring(0, model.indexOf(':')) : model;
            return output.contains(model) || output.contains(baseName);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean pullModel(String model) {
        try {
            ProcessBuilder pb = new ProcessBuilder("ollama", "pull", model)
                    .redirectErrorStream(true)
                    .inheritIO();
            Process p = pb.start();
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
            LOG.warn("Failed to pull model {}: {}",
                    SafeLogFormatter.value(model), SafeLogFormatter.throwableMessage(e));
            return false;
        }
    }

    private static boolean waitForProbe(Process process) throws InterruptedException {
        if (process.waitFor(OLLAMA_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return true;
        }
        process.destroyForcibly();
        return false;
    }

    static void writeSentinel() {
        try {
            Files.createDirectories(SENTINEL.getParent());
            Files.writeString(SENTINEL, "ok");
        } catch (IOException ex) {
            LOG.warn("Failed to write first-run sentinel {}", SENTINEL, ex);
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


