package dev.talos.app.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Terminal-based first-run setup flow.
 *
 * <p>Lightweight terminal
 * flow that works on all platforms including headless (WSL, SSH, Docker).
 *
 * <p>Steps:
 * <ol>
 *   <li>Detect Ollama — prompt to install if missing</li>
 *   <li>Detect default model — prompt to pull if missing</li>
 *   <li>Write config defaults — confirm and proceed</li>
 *   <li>Write sentinel file to skip on next launch</li>
 * </ol>
 */
public final class TerminalFirstRun {

    private static final Logger LOG = LoggerFactory.getLogger(TerminalFirstRun.class);

    private static final Path SENTINEL =
            Paths.get(System.getProperty("user.home"), ".talos", "first_run_done");

    private static final String DEFAULT_MODEL = "qwen2.5-coder:14b";

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

        // Step 1: Detect Ollama
        boolean ollamaInstalled = checkOllamaInstalled();
        if (ollamaInstalled) {
            String version = getOllamaVersion();
            System.out.println("  ✓ Ollama detected" + (version != null ? " (" + version.trim() + ")" : ""));
        } else {
            System.out.println("  ✗ Ollama not found");
            System.out.println();
            System.out.println("  Talos requires Ollama to run local AI models.");
            System.out.println("  Install from: https://ollama.com/download");
            System.out.println();
            if (isWindows()) {
                System.out.println("  Or run:  winget install Ollama.Ollama");
            } else {
                System.out.println("  Or run:  curl -fsSL https://ollama.com/install.sh | sh");
            }
            System.out.println();
            System.out.print("  Install Ollama now and press Enter to continue (or 'q' to quit): ");
            String input = readLine();
            if (input != null && input.trim().equalsIgnoreCase("q")) {
                System.out.println("  Setup cancelled. Run Talos again after installing Ollama.");
                return false;
            }

            // Re-check
            ollamaInstalled = checkOllamaInstalled();
            if (!ollamaInstalled) {
                System.out.println("  ! Ollama still not detected. You can continue, but LLM features won't work.");
                System.out.println();
            } else {
                System.out.println("  ✓ Ollama detected");
            }
        }
        System.out.println();

        // Step 2: Detect model
        if (ollamaInstalled) {
            boolean modelAvailable = checkModelAvailable(DEFAULT_MODEL);
            if (modelAvailable) {
                System.out.println("  ✓ Model '" + DEFAULT_MODEL + "' is available");
            } else {
                System.out.println("  ✗ Model '" + DEFAULT_MODEL + "' not found locally");
                System.out.println();
                System.out.print("  Pull '" + DEFAULT_MODEL + "' now? [Y/n]: ");
                String input = readLine();
                if (input == null || input.isBlank() || input.trim().toLowerCase().startsWith("y")) {
                    System.out.println("  Pulling " + DEFAULT_MODEL + "... (this may take a few minutes)");
                    boolean pulled = pullModel(DEFAULT_MODEL);
                    if (pulled) {
                        System.out.println("  ✓ Model pulled successfully");
                    } else {
                        System.out.println("  ! Pull failed. You can pull manually: ollama pull " + DEFAULT_MODEL);
                    }
                } else {
                    System.out.println("  Skipped. Pull later with: ollama pull " + DEFAULT_MODEL);
                }
            }
        }
        System.out.println();

        // Step 3: Write config & sentinel
        System.out.println("  Configuration:");
        System.out.println("    Model:     " + DEFAULT_MODEL);
        System.out.println("    Embeddings: bge-m3");
        System.out.println("    Host:      http://127.0.0.1:11434");
        System.out.println();

        writeSentinel();

        System.out.println("  ✓ Setup complete. Starting Talos...");
        System.out.println();
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────

    static boolean checkOllamaInstalled() {
        try {
            Process p = new ProcessBuilder("ollama", "version")
                    .redirectErrorStream(true)
                    .start();
            p.waitFor();
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
            String output = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
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
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor();
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
            LOG.warn("Failed to pull model {}: {}", model, e.getMessage());
            return false;
        }
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


