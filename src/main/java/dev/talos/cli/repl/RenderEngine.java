package dev.talos.cli.repl;

import dev.talos.cli.ui.AnsiColor;
import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.core.security.Redactor;
import dev.talos.core.util.Sanitize;

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
 * Uses colored left-border for answers, colored prefixes for errors/info,
 * and a smooth spinner during generation.
 */
public final class RenderEngine {
    private final Config cfg;
    private final Redactor redactor;
    private final PrintStream out;
    private final String statusLabel;
    private final boolean showStatusDuringAnswer;
    private final boolean interactive;

    // Spinner state
    private final AtomicBoolean spinnerActive = new AtomicBoolean(false);
    private final AtomicInteger spinnerFrame = new AtomicInteger(0);
    private Thread spinnerThread;
    private Instant spinnerStartTime;

    // Braille spinner for Unicode-capable terminals, classic for others
    private static final String[] SPINNER_UNICODE = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final String[] SPINNER_ASCII   = {"|", "/", "-", "\\"};

    private final String[] spinnerFrames;

    public RenderEngine(Config cfg, Redactor redactor, PrintStream out) {
        this(cfg, redactor, out, isInteractiveTerminal(out));
    }

    /**
     * @param interactive when false (piped / redirected output), the spinner is
     *                    suppressed to avoid flooding non-terminal consumers with
     *                    hundreds of carriage-return lines.
     */
    public RenderEngine(Config cfg, Redactor redactor, PrintStream out, boolean interactive) {
        this.cfg = (cfg == null ? new Config() : cfg);
        this.redactor = (redactor == null ? new Redactor() : redactor);
        this.out = (out == null ? System.out : out);
        this.interactive = interactive;

        // UI config
        Map<String, Object> ui = CfgUtil.map(this.cfg.data.get("ui"));
        String rawLabel = ui == null ? "Thinking" : String.valueOf(ui.getOrDefault("status_label", "Thinking"));
        this.statusLabel = AnsiColor.isUnicodeSafe() ? rawLabel : rawLabel.replace("…", "...");
        this.showStatusDuringAnswer = ui == null || !(ui.get("show_status_during_answer") instanceof Boolean b) || b;
        this.spinnerFrames = AnsiColor.isUnicodeSafe() ? SPINNER_UNICODE : SPINNER_ASCII;
    }

    /**
     * Detect whether stdout is connected to an interactive terminal.
     * When output is piped or redirected, {@code System.console()} returns null.
     */
    private static boolean isInteractiveTerminal(PrintStream target) {
        // If output is not System.out (e.g., test harness), assume non-interactive
        if (target != null && target != System.out) return false;
        return System.console() != null;
    }

    /**
     * Starts the spinner (non-blocking).
     * Suppressed in non-interactive mode to avoid flooding piped output.
     */
    public void startSpinner() {
        if (!showStatusDuringAnswer) return;
        if (!interactive) return;
        if (!spinnerActive.compareAndSet(false, true)) return;

        spinnerStartTime = Instant.now();
        spinnerThread = new Thread(() -> {
            while (spinnerActive.get()) {
                int frame = spinnerFrame.getAndIncrement() % spinnerFrames.length;

                long secs = spinnerStartTime.until(Instant.now(), ChronoUnit.SECONDS);
                String elapsed = secs < 60
                        ? secs + "s"
                        : String.format(Locale.ROOT, "%d:%02d", secs / 60, secs % 60);

                // Colored spinner: orange dot + grey label + dim time
                out.print("\r  " + AnsiColor.ORANGE + spinnerFrames[frame] + AnsiColor.RESET
                        + " " + AnsiColor.GREY + statusLabel + AnsiColor.RESET
                        + "  " + AnsiColor.DIM + elapsed + AnsiColor.RESET + "   ");
                out.flush();
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            out.print("\r" + " ".repeat(statusLabel.length() + 30) + "\r");
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
            try { spinnerThread.join(200); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public void render(Result r) {
        stopSpinner();

        if (r == null) {
            println(sro("(null result)"));
            return;
        }

        if (r instanceof Result.Ok ok) {
            printResponse(sro(ok.text));
            return;
        }
        if (r instanceof Result.Info info) {
            println("  " + sro(info.text));
            return;
        }
        if (r instanceof Result.TrustedInfo trustedInfo) {
            String cleaned = Sanitize.sanitizeForOutput(trustedInfo.text == null ? "" : trustedInfo.text);
            println(cleaned);
            return;
        }
        if (r instanceof Result.Error err) {
            String msg = sro(err.message);
            String prefix = AnsiColor.red(AnsiColor.isUnicodeSafe() ? "✗" : "[error]");
            if (err.code > 0) println("  " + prefix + " " + AnsiColor.DIM + "[" + err.code + "]" + AnsiColor.RESET + " " + msg);
            else println("  " + prefix + " " + msg);
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
        if (r instanceof Result.Streamed streamed) {
            // Body was already printed during streaming; only render the suffix
            if (!streamed.suffix.isEmpty()) {
                println(sro(streamed.suffix));
            }
            println("");
            return;
        }

        println(sro(r.toString()));
    }

    // ── Response rendering (left-border style) ────────────────────────────

    private void printResponse(String content) {
        if (content == null || content.isEmpty()) {
            println("  " + AnsiColor.dim("(empty response)"));
            return;
        }

        final int MAX_WIDTH = 96;
        String border = AnsiColor.VIOLET + "│" + AnsiColor.RESET;
        String[] lines = content.split("\n");

        println("");  // breathing room before response
        for (String line : lines) {
            if (line.length() <= MAX_WIDTH) {
                println("  " + border + " " + line);
            } else {
                for (String wl : wrapLine(line, MAX_WIDTH)) {
                    println("  " + border + " " + wl);
                }
            }
        }
        println("");  // breathing room after response
    }

    private List<String> wrapLine(String line, int maxWidth) {
        List<String> result = new java.util.ArrayList<>();
        String[] words = line.split("\\s+");
        StringBuilder current = new StringBuilder();

        for (String word : words) {
            if (current.length() + word.length() + 1 > maxWidth) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current = new StringBuilder();
                }
                if (word.length() > maxWidth) {
                    result.add(word.substring(0, maxWidth));
                    word = word.substring(maxWidth);
                }
            }
            if (!current.isEmpty()) current.append(" ");
            current.append(word);
        }
        if (!current.isEmpty()) result.add(current.toString());

        return result.isEmpty() ? List.of("") : result;
    }

    // ── Table rendering ───────────────────────────────────────────────────

    private void renderTable(Result.Table tbl) {
        String title = sro(tbl.title);
        if (!title.isEmpty()) println("  " + AnsiColor.bold(title));

        List<String> cols = (tbl.columns == null ? List.of() : tbl.columns);
        List<List<String>> rows = (tbl.rows == null ? List.of() : tbl.rows);

        if (!cols.isEmpty()) {
            StringBuilder header = new StringBuilder();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) header.append(AnsiColor.dim(" │ "));
                header.append(AnsiColor.bold(sroInline(cols.get(i))));
            }
            println("  " + header);
            println("  " + AnsiColor.dim("─".repeat(Math.max(3, stripAnsi(header.toString()).length()))));
        }

        for (List<String> row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) line.append(AnsiColor.dim(" │ "));
                line.append(sroInline(row.get(i)));
            }
            println("  " + line);
        }
    }

    /** Strip ANSI escape codes for width calculation. */
    private static String stripAnsi(String s) {
        return s.replaceAll("\033\\[[;\\d]*m", "");
    }

    // ── Sanitize → redact pipeline ────────────────────────────────────────

    private String sro(String s) {
        String cleaned = Sanitize.sanitizeForOutput(s == null ? "" : s);
        return redactor.redactBlock(cleaned);
    }

    private String sroInline(String s) {
        String cleaned = Sanitize.sanitizeForOutput(s == null ? "" : s);
        return redactor.redactLine(cleaned);
    }

    private void print(String s) { out.print(s); out.flush(); }
    private void println(String s) { out.println(s); out.flush(); }
}
