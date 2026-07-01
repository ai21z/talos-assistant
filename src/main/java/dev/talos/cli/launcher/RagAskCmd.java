package dev.talos.cli.launcher;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.core.rag.RagService;
import dev.talos.core.util.Sanitize;
import dev.talos.cli.ui.TerminalCapabilities;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@CommandLine.Command(name="rag-ask", description="Ask with RAG")
public class RagAskCmd implements Runnable {
    @CommandLine.Option(names="--root") String root;
    @CommandLine.Option(names="--k") Integer k;
    @CommandLine.Parameters(index="0") String question;

    @Override public void run() {
        try {
            boolean unicodeSafe = TerminalCapabilities.detectDefault().unicodeSafe();
            Path r = resolveWorkspaceRoot();
            if (!Files.isDirectory(r)) {
                System.err.println("rag-ask failed: not a directory: " + r);
                return;
            }

            Config cfg = new Config();

            // UI config is read
            Map<String, Object> ui = CfgUtil.map(cfg.data.get("ui"));
            boolean showStatus = ui == null || !(ui.get("show_status_during_answer") instanceof Boolean b) || b;
            boolean showTiming = ui == null || !(ui.get("show_timing_after_answer") instanceof Boolean b2) || b2;
            String statusLabel = term(ui == null
                    ? "Answering…"
                    : String.valueOf(ui.getOrDefault("status_label", "Answering…")), unicodeSafe);

            long t0 = System.nanoTime();

            // Pre-answer status is shown
            if (showStatus) {
                System.out.print("\r" + statusLabel + " ");
                System.out.flush();
            }

            var ans = new RagService(cfg).ask(r, question, k);

            long elapsed = System.nanoTime() - t0;

            // Status line is cleared before printing answer
            if (showStatus) {
                System.out.print("\r" + " ".repeat(statusLabel.length() + 1) + "\r");
                System.out.flush();
            }

            System.out.println(term(ans.text(), unicodeSafe));
            if (!ans.citations().isEmpty()) {
                System.out.println("\n[Sources]");
                for (var c : ans.citations()) {
                    // Paths are normalized to forward slashes
                    String normalized = c.replace('\\', '/');
                    System.out.println(" - " + term(normalized, unicodeSafe));
                }
            }

            // Post-answer timing is shown
            if (showTiming) {
                String timeStr = formatElapsedTime(elapsed);
                System.out.println("\nCompleted in " + timeStr + ".");
            }

        } catch (Exception e) {
            System.err.println("rag-ask failed: " + e.getMessage());
        }
    }

    private static String term(String text, boolean unicodeSafe) {
        return Sanitize.sanitizeForTerminalOutput(text, unicodeSafe);
    }

    private Path resolveWorkspaceRoot() {
        if (root != null && !root.isBlank()) {
            return Path.of(root).toAbsolutePath().normalize();
        }

        String envRoot = System.getenv("TALOS_WORKSPACE");
        if (envRoot != null && !envRoot.isBlank()) {
            return Path.of(envRoot).toAbsolutePath().normalize();
        }

        return Path.of(".").toAbsolutePath().normalize();
    }

    /**
     * Formats elapsed time according to spec:
     * <1s → XYZms
     * 1-59s → X.Ys
     * >=60s → M:SS
     */
    private static String formatElapsedTime(long nanos) {
        long millis = nanos / 1_000_000;
        if (millis < 1000) {
            return millis + "ms";
        }
        double seconds = millis / 1000.0;
        if (seconds < 60) {
            return String.format("%.1fs", seconds);
        }
        long totalSeconds = (long) seconds;
        long minutes = totalSeconds / 60;
        long secs = totalSeconds % 60;
        return String.format("%d:%02d", minutes, secs);
    }
}
