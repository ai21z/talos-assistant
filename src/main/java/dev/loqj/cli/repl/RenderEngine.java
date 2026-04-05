package dev.loqj.cli.repl;

import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;
import dev.loqj.core.security.Redactor;
import dev.loqj.core.util.Sanitize;

import java.io.PrintStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Renders Results to the terminal with consistent sanitize → redact → print pipeline.
 */
public final class RenderEngine {
    private final Config cfg;
    private final Redactor redactor;
    private final PrintStream out;
    private final String statusLabel;
    private final boolean showStatusDuringAnswer;

    // Spinner state
    private final AtomicBoolean spinnerActive = new AtomicBoolean(false);
    private final AtomicInteger spinnerFrame = new AtomicInteger(0);
    private Thread spinnerThread;
    private Instant spinnerStartTime;
    private static final String[] SPINNER_FRAMES = {"|", "/", "-", "\\"};

    public RenderEngine(Config cfg, Redactor redactor, PrintStream out) {
        this.cfg = (cfg == null ? new Config() : cfg);
        this.redactor = (redactor == null ? new Redactor() : redactor);
        this.out = (out == null ? System.out : out);

        // UI config is read for status label
        Map<String, Object> ui = CfgUtil.map(this.cfg.data.get("ui"));
        String rawLabel = ui == null ? "Answering…" : String.valueOf(ui.getOrDefault("status_label", "Answering…"));

        // ASCII fallback: ellipsis is replaced with three dots if Unicode is not supported
        this.statusLabel = supportsUnicode() ? rawLabel : rawLabel.replace("…", "...");

        this.showStatusDuringAnswer = ui == null || !(ui.get("show_status_during_answer") instanceof Boolean b) || b;
    }

    /**
     * Starts the spinner (non-blocking).
     * Honors ui.show_status_during_answer configuration.
     */
    public void startSpinner() {
        if (!showStatusDuringAnswer) return;
        if (!spinnerActive.compareAndSet(false, true)) return;

        spinnerStartTime = Instant.now();
        spinnerThread = new Thread(() -> {
            while (spinnerActive.get()) {
                int frame = spinnerFrame.getAndIncrement() % SPINNER_FRAMES.length;

                // Elapsed time is calculated in mm:ss format
                long secs = spinnerStartTime.until(Instant.now(), ChronoUnit.SECONDS);
                long mm = secs / 60;
                long ss = secs % 60;
                String elapsed = String.format(Locale.ROOT, "%d:%02d", mm, ss);

                out.print("\r" + statusLabel + " " + SPINNER_FRAMES[frame] + " " + elapsed + "   ");
                out.flush();
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // Spinner line is cleared
            out.print("\r" + " ".repeat(statusLabel.length() + 20) + "\r");
            out.flush();
        });
        spinnerThread.setDaemon(true);
        spinnerThread.start();
    }

    /**
     * Stops the spinner.
     */
    public void stopSpinner() {
        if (!spinnerActive.compareAndSet(true, false)) return;

        if (spinnerThread != null) {
            try {
                spinnerThread.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Heuristic check for Unicode support.
     * On Windows cmd.exe, Unicode ellipsis often renders as '?'.
     */
    private boolean supportsUnicode() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return false;
        }
        return true;
    }

    public void render(Result r) {
        // Spinner is stopped on any result rendering
        stopSpinner();

        if (r == null) {
            println(sro("(null result)"));
            return;
        }

        if (r instanceof Result.Ok ok) {
            printBoxed(sro(ok.text));
            return;
        }
        if (r instanceof Result.Info info) {
            println(sro(info.text));
            return;
        }
        if (r instanceof Result.TrustedInfo trustedInfo) {
            // Path redaction is bypassed for trusted workspace information
            String cleaned = Sanitize.sanitizeForOutput(trustedInfo.text == null ? "" : trustedInfo.text);
            println(cleaned);
            return;
        }
        if (r instanceof Result.Error err) {
            String msg = sro(err.message);
            if (err.code > 0) println("[error " + err.code + "] " + msg);
            else println("[error] " + msg);
            return;
        }
        if (r instanceof Result.Table tbl) {
            renderTable(tbl);
            return;
        }
        if (r instanceof Result.StreamStart ss) {
            stopSpinner();
            String pf = ss.preface == null ? "" : ss.preface;
            if (!pf.isEmpty()) println(sro(pf));
            return;
        }
        if (r instanceof Result.StreamChunk chunk) {
            stopSpinner();
            print(sroInline(chunk.text));
            return;
        }
        if (r instanceof Result.StreamEnd) {
            println("");
            return;
        }

        // Fallback for any future Result variants
        println(sro(r.toString()));
    }

    private void printBoxed(String content) {
        if (content == null || content.isEmpty()) {
            println("(empty response)");
            return;
        }

        final int MAX_WIDTH = 100;
        String[] lines = content.split("\n");

        // Top border
        println("┌" + "─".repeat(MAX_WIDTH) + "┐");

        // Content with word wrapping
        for (String line : lines) {
            if (line.length() <= MAX_WIDTH) {
                println("│ " + line + " ".repeat(Math.max(0, MAX_WIDTH - line.length() - 1)) + "│");
            } else {
                // Long lines are word-wrapped
                List<String> wrapped = wrapLine(line, MAX_WIDTH - 2);
                for (String wl : wrapped) {
                    println("│ " + wl + " ".repeat(Math.max(0, MAX_WIDTH - wl.length() - 1)) + "│");
                }
            }
        }

        // Bottom border
        println("└" + "─".repeat(MAX_WIDTH) + "┘");
    }

    private List<String> wrapLine(String line, int maxWidth) {
        List<String> result = new java.util.ArrayList<>();
        String[] words = line.split("\\s+");
        StringBuilder current = new StringBuilder();

        for (String word : words) {
            if (current.length() + word.length() + 1 > maxWidth) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current = new StringBuilder();
                }
                // Very long words are handled
                if (word.length() > maxWidth) {
                    result.add(word.substring(0, maxWidth));
                    word = word.substring(maxWidth);
                }
            }
            if (current.length() > 0) current.append(" ");
            current.append(word);
        }

        if (current.length() > 0) {
            result.add(current.toString());
        }

        return result.isEmpty() ? List.of("") : result;
    }

    private void renderTable(Result.Table tbl) {
        String title = sro(tbl.title);
        if (!title.isEmpty()) println(title);

        List<String> cols = (tbl.columns == null ? List.of() : tbl.columns);
        List<List<String>> rows = (tbl.rows == null ? List.of() : tbl.rows);

        if (!cols.isEmpty()) {
            StringBuilder header = new StringBuilder();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) header.append(" | ");
                header.append(sroInline(cols.get(i)));
            }
            println(header.toString());
            println("-".repeat(Math.max(3, header.length())));
        }

        for (List<String> row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) line.append(" | ");
                line.append(sroInline(row.get(i)));
            }
            println(line.toString());
        }
    }

    /**
     * Applies sanitize → redact pipeline for multi-line blocks.
     */
    private String sro(String s) {
        String cleaned = Sanitize.sanitizeForOutput(s == null ? "" : s);
        return redactor.redactBlock(cleaned);
    }

    /**
     * Applies sanitize → redact pipeline for inline text (e.g., table cells, streaming chunks).
     */
    private String sroInline(String s) {
        String cleaned = Sanitize.sanitizeForOutput(s == null ? "" : s);
        return redactor.redactLine(cleaned);
    }

    private void print(String s) {
        out.print(s);
        out.flush();
    }

    private void println(String s) {
        out.println(s);
        out.flush();
    }
}
