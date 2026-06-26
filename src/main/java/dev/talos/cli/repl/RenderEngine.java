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
import java.util.function.IntSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Renders Results to the terminal with consistent sanitize → redact → print pipeline.
 * Uses colored left-border for answers, colored prefixes for errors/info,
 * and a smooth spinner during generation.
 */
public final class RenderEngine {
    /** Fixed answer-pane width for paths without a live terminal (pre-T772 value). */
    private static final int ANSWER_PANE_DEFAULT_WIDTH = 96;

    private final Redactor redactor;
    private final PrintStream out;
    private final CliTheme theme;
    private final ProgressLineRenderer progressRenderer;
    private final IntSupplier terminalWidth;
    private final String statusLabel;
    private final boolean showStatusDuringAnswer;
    private final boolean showTimingAfterAnswer;
    private final boolean markdownEnabled;
    private final boolean interactive;
    private final dev.talos.cli.ui.StatusRowPresenter statusRow;

    // Spinner state
    private final AtomicBoolean spinnerActive = new AtomicBoolean(false);
    private final AtomicInteger spinnerFrame = new AtomicInteger(0);
    private final Object spinnerMonitor = new Object();
    private Thread spinnerThread;
    private Instant spinnerStartTime;
    private AnswerPaneRenderer.Stream activeAnswerStream;
    private dev.talos.cli.ui.StreamShaper activeAnswerShaper;
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
        this(cfg, redactor, out, interactive, CliTheme.current(), null);
    }

    /**
     * @param terminalWidth live terminal width source (JLine
     *                      {@code Terminal::getWidth}), or {@code null} for
     *                      redirected/scripted paths — those keep the fixed
     *                      pre-T772 width so output bytes never change.
     */
    public RenderEngine(Config cfg, Redactor redactor, PrintStream out, boolean interactive,
                        IntSupplier terminalWidth) {
        this(cfg, redactor, out, interactive, CliTheme.current(), terminalWidth, null);
    }

    /**
     * @param terminal the live JLine terminal, used for the Status bottom
     *                 row (T779); {@code null} keeps the legacy {@code \r}
     *                 spinner for capable detection-free paths.
     */
    public RenderEngine(Config cfg, Redactor redactor, PrintStream out, boolean interactive,
                        IntSupplier terminalWidth, org.jline.terminal.Terminal terminal) {
        this(cfg, redactor, out, interactive, CliTheme.current(), terminalWidth, terminal);
    }

    RenderEngine(Config cfg, Redactor redactor, PrintStream out, boolean interactive, CliTheme theme) {
        this(cfg, redactor, out, interactive, theme, null, null);
    }

    RenderEngine(Config cfg, Redactor redactor, PrintStream out, boolean interactive, CliTheme theme,
                 IntSupplier terminalWidth) {
        this(cfg, redactor, out, interactive, theme, terminalWidth, null);
    }

    RenderEngine(Config cfg, Redactor redactor, PrintStream out, boolean interactive, CliTheme theme,
                 IntSupplier terminalWidth, org.jline.terminal.Terminal terminal) {
        Config resolvedCfg = (cfg == null ? new Config() : cfg);
        this.redactor = (redactor == null ? new Redactor() : redactor);
        this.out = (out == null ? System.out : out);
        this.interactive = interactive;
        this.theme = theme == null ? CliTheme.current() : theme;
        this.progressRenderer = new ProgressLineRenderer(this.theme);
        this.terminalWidth = terminalWidth;
        this.statusRow = terminal != null
                ? new dev.talos.cli.ui.StatusRowPresenter(terminal, this.theme)
                : null;

        // UI config
        Map<String, Object> ui = CfgUtil.map(resolvedCfg.data.get("ui"));
        String rawLabel = ui == null ? "Thinking" : String.valueOf(ui.getOrDefault("status_label", "Thinking"));
        this.statusLabel = terminalText(rawLabel);
        this.showStatusDuringAnswer = ui == null || !(ui.get("show_status_during_answer") instanceof Boolean b) || b;
        this.showTimingAfterAnswer = ui == null || !(ui.get("show_timing_after_answer") instanceof Boolean b2) || b2;
        this.markdownEnabled = ui == null || !(ui.get("markdown") instanceof Boolean b3) || b3;
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
        // The printed line is the evidence record and stays unchanged; the
        // status row additionally mirrors the route live (T780).
        if (statusRow != null) {
            statusRow.route(terminalText(routeLabel));
        }
        out.println(progressRenderer.route(terminalText(routeLabel), ""));
        out.flush();
    }

    /** Live model/turn sources for the status row (T780); no-op without one. */
    public void setStatusContext(java.util.function.Supplier<String> model,
                                 java.util.function.IntSupplier turn) {
        if (statusRow != null) {
            statusRow.context(model, turn);
        }
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
     * T805: one muted line when the automatic compactor just summarized
     * older exchanges — the user must see their context change shape.
     * Interactive-only: scripted/redirected transcripts stay unchanged,
     * and the line never enters any Result (render-side chrome with a
     * defensive UiChrome stripper entry).
     */
    public void printCompactionNotice(int summarizedPairs, int keptPairs) {
        if (!interactive) return;

        out.println(progressRenderer.compactionNotice(summarizedPairs, keptPairs));
        out.flush();
    }

    /**
     * Starts the spinner (non-blocking).
     * Suppressed in non-interactive mode to avoid flooding piped output.
     */
    public void startSpinner() {
        if (!showStatusDuringAnswer) return;
        if (!interactive) return;
        // Capable terminals get the JLine Status bottom row (T779): drawn in
        // a managed scroll region, so no raw \r frames ever interleave with
        // answer output. Unsupported terminals keep the legacy thread.
        if (statusRow != null && statusRow.supported()) {
            statusRow.start(statusLabel);
            return;
        }
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
     * Stops the spinner (status row or legacy thread).
     */
    public void stopSpinner() {
        if (statusRow != null) {
            statusRow.stop();
        }
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
     * Tears rendering down at session end (T779): stops any spinner and
     * closes the JLine status region so the terminal scroll area is
     * restored before the process exits.
     */
    public void shutdown() {
        stopSpinner();
        if (statusRow != null) {
            statusRow.close();
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

    /**
     * Answer-pane renderer at the current terminal width. Constructed
     * per render/stream-open (cheap value object) so each answer picks up
     * the live width; an in-flight stream keeps the renderer it opened
     * with, so one answer is always internally consistent (T772).
     *
     * <p>COLUMNS is consulted only when a live terminal exists but cannot
     * report its width — the pane never read COLUMNS before T772, so
     * redirected/scripted output must not start depending on it.
     */
    private AnswerPaneRenderer answerRenderer() {
        return new AnswerPaneRenderer(theme, dev.talos.cli.ui.TerminalWidths.resolve(
                terminalWidth,
                terminalWidth != null ? System.getenv() : Map.of(),
                ANSWER_PANE_DEFAULT_WIDTH));
    }

    private String streamChunk(String chunk, Consumer<String> writer) {
        if (chunk == null || chunk.isEmpty()) return "";
        if (activeAnswerStream == null) {
            AnswerPaneRenderer renderer = answerRenderer();
            activeAnswerStream = renderer.openStream("answer");
            activeAnswerShaper = !styledStreamingEnabled() ? null
                    : markdownEnabled
                            ? new dev.talos.cli.ui.md.StreamingMarkdownShaper(renderer.contentWidth(), theme)
                            : new dev.talos.cli.ui.StreamingAnswerShaper(renderer.contentWidth());
            activeAnswerStreamWriter = writer;
        } else if (activeAnswerStreamWriter == null && writer != null) {
            activeAnswerStreamWriter = writer;
        }
        if (activeAnswerShaper == null) {
            return activeAnswerStream.accept(chunk);
        }
        String shaped = activeAnswerShaper.accept(chunk);
        return shaped.isEmpty() ? "" : activeAnswerStream.accept(shaped);
    }

    /**
     * Width-reactive wrapping (T776) — and from T777 markdown styling — is
     * an interactive-only enhancement. Every degraded mode (redirected,
     * scripted, NO_COLOR, ASCII glyphs, dumb terminal) keeps the historical
     * pass-through stream bytes: the evidence chain string-matches those
     * transcripts.
     */
    private boolean styledStreamingEnabled() {
        var caps = theme.capabilities();
        return interactive && caps.colorEnabled() && caps.unicodeSafe() && !caps.dumbTerminal();
    }

    private void closeAnswerStream(String footer) {
        if (activeAnswerStream == null) return;
        StringBuilder rendered = new StringBuilder();
        if (activeAnswerShaper != null) {
            String tail = activeAnswerShaper.flush();
            if (!tail.isEmpty()) {
                rendered.append(activeAnswerStream.accept(tail));
            }
            activeAnswerShaper = null;
        }
        rendered.append(activeAnswerStream.close(footer));
        Consumer<String> writer = activeAnswerStreamWriter;
        activeAnswerStream = null;
        activeAnswerStreamWriter = null;
        if (writer != null) {
            writer.accept(rendered.toString());
        } else {
            print(rendered.toString());
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
            print(answerRenderer().renderBlock(body, "answer"));
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
