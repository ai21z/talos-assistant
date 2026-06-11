package dev.talos.cli.repl;

import dev.talos.runtime.Result;

import dev.talos.cli.ui.CliTheme;
import dev.talos.cli.ui.AnswerPaneRenderer;
import dev.talos.cli.ui.InteractiveTty;
import dev.talos.cli.ui.ProgressLineRenderer;
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
import java.util.function.Consumer;
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
    private final CliTheme theme;
    private final ProgressLineRenderer progressRenderer;
    private final AnswerPaneRenderer answerRenderer;
    private final String statusLabel;
    private final boolean showStatusDuringAnswer;
    private final boolean showTimingAfterAnswer;
    private final boolean interactive;

    // Spinner state
    private final AtomicBoolean spinnerActive = new AtomicBoolean(false);
    private final AtomicInteger spinnerFrame = new AtomicInteger(0);
    private final Object spinnerMonitor = new Object();
    private Thread spinnerThread;
    private Instant spinnerStartTime;
    private AnswerPaneRenderer.Stream activeAnswerStream;
    private Consumer<String> activeAnswerStreamWriter;

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
        this(cfg, redactor, out, interactive, CliTheme.current());
    }

    RenderEngine(Config cfg, Redactor redactor, PrintStream out, boolean interactive, CliTheme theme) {
        this.cfg = (cfg == null ? new Config() : cfg);
        this.redactor = (redactor == null ? new Redactor() : redactor);
        this.out = (out == null ? System.out : out);
        this.interactive = interactive;
        this.theme = theme == null ? CliTheme.current() : theme;
        this.progressRenderer = new ProgressLineRenderer(this.theme);
        this.answerRenderer = new AnswerPaneRenderer(this.theme, 96);

        // UI config
        Map<String, Object> ui = CfgUtil.map(this.cfg.data.get("ui"));
        String rawLabel = ui == null ? "Thinking" : String.valueOf(ui.getOrDefault("status_label", "Thinking"));
        this.statusLabel = terminalText(rawLabel);
        this.showStatusDuringAnswer = ui == null || !(ui.get("show_status_during_answer") instanceof Boolean b) || b;
        this.showTimingAfterAnswer = ui == null || !(ui.get("show_timing_after_answer") instanceof Boolean b2) || b2;
        this.spinnerFrames = unicodeSafe() ? SPINNER_UNICODE : SPINNER_ASCII;
    }

    /**
     * Detect whether stdout is connected to an interactive terminal.
     *
     * <p>Uses the OS-level isatty probe ({@link InteractiveTty}) rather than
     * {@code System.console() != null}, which on JDK 22+ returns a console
     * even for piped/redirected output and would flood non-terminal
     * consumers with spinner carriage returns (T769).
     */
    private static boolean isInteractiveTerminal(PrintStream target) {
        // If output is not System.out (e.g., test harness), assume non-interactive
        if (target != null && target != System.out) return false;
        return InteractiveTty.stdoutIsTty();
    }

    /**
     * Print a subtle routing indicator for auto-mode.
     * Shows dimmed text like {@code [auto -> rag]} before the spinner.
     * Suppressed in non-interactive mode.
     */
    public void printRouteHint(String routeLabel) {
        if (!interactive) return;
        if (routeLabel == null || routeLabel.isBlank()) return;
        out.println(progressRenderer.route(terminalText(routeLabel), ""));
        out.flush();
    }

    /**
     * Print turn statistics after a completed turn.
     * Shows turn number, elapsed time, and response length estimate.
     * Gated by {@code ui.show_timing_after_answer} config (default true).
     *
     * <p>Format: {@code [Turn 3 | 1.2s | ~312 chars]}
     * Suppressed in non-interactive mode.
     *
     * @param turnNumber   1-based turn number
     * @param elapsedMs    elapsed time in milliseconds
     * @param responseLen  approximate response length in characters (0 to omit)
     */
    public void printTurnStats(int turnNumber, long elapsedMs, int responseLen) {
        if (!showTimingAfterAnswer) return;
        if (!interactive) return;

        out.println(progressRenderer.turnStats(turnNumber, elapsedMs, responseLen));
        out.flush();
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

                // Active status is renderer-owned; model text never controls styling.
                out.print("\r  " + theme.active(spinnerFrames[frame])
                        + " " + theme.metadata(statusLabel)
                        + "  " + theme.muted(elapsed) + "   ");
                out.flush();
                try {
                    synchronized (spinnerMonitor) {
                        spinnerMonitor.wait(120);
                    }
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
        synchronized (spinnerMonitor) {
            spinnerMonitor.notifyAll();
        }
        if (spinnerThread != null) {
            try { spinnerThread.join(200); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /**
     * Build a JLine-safe display sink for user-visible streamed assistant text.
     * Tool protocol filtering must wrap this sink, so only natural-language
     * chunks receive answer-pane chrome.
     */
    public Consumer<String> answerStreamSink(Consumer<String> trustedOutput) {
        Consumer<String> writer = trustedOutput == null ? this::print : trustedOutput;
        return chunk -> {
            stopSpinner();
            String rendered = streamChunk(sroInline(chunk), writer);
            if (!rendered.isEmpty()) {
                writer.accept(rendered);
            }
        };
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
            println("  " + theme.metadata("i") + " " + sro(info.text));
            return;
        }
        if (r instanceof Result.TrustedInfo trustedInfo) {
            println(trustedText(trustedInfo.text));
            return;
        }
        if (r instanceof Result.Error err) {
            String msg = sro(err.message);
            String prefix = theme.error("x");
            if (err.code > 0) println("  " + prefix + " " + theme.muted("[" + err.code + "]") + " " + msg);
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
            print(streamChunk(sroInline(chunk.text), null));
            return;
        }
        if (r instanceof Result.StreamEnd) {
            closeAnswerStream("answer");
            return;
        }
        if (r instanceof Result.Streamed streamed) {
            // Body was already printed during streaming; only render the suffix
            closeAnswerStream("answer");
            if (!streamed.suffix.isEmpty()) {
                printResponseSuffix(sro(streamed.suffix));
            }
            println("");
            return;
        }
        if (r instanceof Result.ToolProgress tp) {
            renderToolProgress(tp);
            return;
        }

        println(sro(r.toString()));
    }

    private String streamChunk(String chunk, Consumer<String> writer) {
        if (chunk == null || chunk.isEmpty()) return "";
        if (activeAnswerStream == null) {
            activeAnswerStream = answerRenderer.openStream("answer");
            activeAnswerStreamWriter = writer;
        } else if (activeAnswerStreamWriter == null && writer != null) {
            activeAnswerStreamWriter = writer;
        }
        return activeAnswerStream.accept(chunk);
    }

    private void closeAnswerStream(String footer) {
        if (activeAnswerStream == null) return;
        String rendered = activeAnswerStream.close(footer);
        Consumer<String> writer = activeAnswerStreamWriter;
        activeAnswerStream = null;
        activeAnswerStreamWriter = null;
        if (writer != null) {
            writer.accept(rendered);
        } else {
            print(rendered);
        }
    }

    // ── Response rendering (semantic answer pane) ─────────────────────────

    /**
     * Print a tool progress status line directly (outside the render pipeline).
     * Used by {@link dev.talos.tools.ToolProgressSink} implementations.
     * Suppressed in non-interactive mode.
     */
    public void printToolProgress(String toolName, String action, String detail) {
        if (!interactive) return;
        println(progressRenderer.tool(
                terminalText(toolName),
                terminalText(action),
                detail == null ? null : sroInline(detail)));
    }

    private void renderToolProgress(Result.ToolProgress tp) {
        printToolProgress(tp.toolName, tp.action, tp.detail);
    }

    private void printResponse(String content) {
        if (content == null || content.isEmpty()) {
            println("  " + theme.muted("(empty response)"));
            return;
        }

        ResponseParts parts = splitSources(content);
        String body = parts.body();

        println("");  // breathing room before response
        if (!body.isBlank()) {
            print(answerRenderer.renderBlock(body, "answer"));
        }
        if (!parts.sources().isEmpty()) {
            if (!body.isBlank()) println("");
            printSources(parts.sources());
        }
        println("");  // breathing room after response
    }

    private void printResponseSuffix(String suffix) {
        ResponseParts parts = splitSources(suffix);
        if (!parts.body().isBlank()) println(parts.body());
        if (!parts.sources().isEmpty()) printSources(parts.sources());
    }

    private void printSources(List<String> sources) {
        println("  " + theme.metadata("Sources"));
        for (String source : sources) {
            println("    " + theme.muted("- ") + source);
        }
    }

    private record ResponseParts(String body, List<String> sources) {}

    private static ResponseParts splitSources(String content) {
        String safe = content == null ? "" : content;
        String[] lines = safe.split("\\R", -1);
        int sourcesAt = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if ("[sources]".equalsIgnoreCase(trimmed) || "sources".equalsIgnoreCase(trimmed)) {
                sourcesAt = i;
                break;
            }
        }
        if (sourcesAt < 0) return new ResponseParts(safe, List.of());

        StringBuilder body = new StringBuilder();
        for (int i = 0; i < sourcesAt; i++) {
            if (i > 0) body.append('\n');
            body.append(lines[i]);
        }

        List<String> sources = new java.util.ArrayList<>();
        for (int i = sourcesAt + 1; i < lines.length; i++) {
            String source = lines[i].trim();
            if (source.isBlank()) continue;
            source = source.replaceFirst("^[-*]\\s*", "");
            if (!source.isBlank()) sources.add(source);
        }
        return new ResponseParts(stripTrailingBlankLines(body.toString()), List.copyOf(sources));
    }

    private static String stripTrailingBlankLines(String text) {
        return text == null ? "" : text.replaceFirst("\\s+$", "");
    }

    // ── Table rendering ───────────────────────────────────────────────────

    private void renderTable(Result.Table tbl) {
        String title = sro(tbl.title);
        if (!title.isEmpty()) println("  " + theme.bold(title));

        List<String> cols = (tbl.columns == null ? List.of() : tbl.columns);
        List<List<String>> rows = (tbl.rows == null ? List.of() : tbl.rows);
        String separator = " | ";
        String hline = "-";

        if (!cols.isEmpty()) {
            StringBuilder header = new StringBuilder();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) header.append(theme.muted(separator));
                header.append(theme.bold(sroInline(cols.get(i))));
            }
            println("  " + header);
            println("  " + theme.muted(hline.repeat(Math.max(3, stripAnsi(header.toString()).length()))));
        }

        for (List<String> row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) line.append(theme.muted(separator));
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
        String cleaned = terminalText(s);
        return redactor.redactBlock(cleaned);
    }

    private String sroInline(String s) {
        String cleaned = terminalText(s);
        return redactor.redactLine(cleaned);
    }

    private String trustedText(String s) {
        return terminalText(s);
    }

    private String terminalText(String s) {
        return Sanitize.sanitizeForTerminalOutput(s == null ? "" : s, unicodeSafe());
    }

    private boolean unicodeSafe() {
        return theme.capabilities().unicodeSafe();
    }

    private void print(String s) { out.print(s); out.flush(); }
    private void println(String s) { out.println(s); out.flush(); }
}
