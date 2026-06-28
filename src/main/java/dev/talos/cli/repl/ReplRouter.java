package dev.talos.cli.repl;

import dev.talos.runtime.Result;

import dev.talos.cli.repl.slash.CommandRegistry;
import dev.talos.cli.modes.ModeController;
import dev.talos.cli.modes.PromptClassifier;
import dev.talos.core.Config;
import dev.talos.runtime.Session;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnResult;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin REPL dispatcher.
 *
 * <p>Routes slash-commands via {@link CommandRegistry} and prompts via
 * {@link TurnProcessor}, rendering results through {@link RenderEngine}.
 *
 * <p>All dependencies are injected - construction and wiring live in
 * {@link TalosBootstrap}. This class only knows <em>how to dispatch</em>,
 * not <em>what to construct</em>.
 */
public final class ReplRouter {

    private final ModeController modes;
    private final TurnProcessor turnProcessor;
    private final Session runtimeSession;
    private final Context ctx;
    private final RenderEngine render;
    private final CommandRegistry registry;
    private final Path workspace;
    private final WorkspaceCommandTemplates templates;
    private final LineClassifier classifier = new LineClassifier();
    private final ExecutionPipeline pipe = new ExecutionPipeline();
    private final AtomicBoolean quit;
    private final String startupNotice;
    private volatile TurnResult lastTurnResult;

    /**
     * Primary constructor - called by {@link TalosBootstrap}.
     * All dependencies are pre-wired; the router only dispatches.
     */
    ReplRouter(ModeController modes, TurnProcessor turnProcessor, Session runtimeSession,
               Context ctx, RenderEngine render, CommandRegistry registry,
               Path workspace, AtomicBoolean quit, String startupNotice,
               WorkspaceCommandTemplates templates) {
        this.modes          = modes;
        this.turnProcessor  = turnProcessor;
        this.runtimeSession = runtimeSession;
        this.ctx            = ctx;
        this.render         = render;
        this.registry       = registry;
        this.workspace      = workspace == null ? Path.of(".") : workspace;
        this.quit           = quit;
        this.startupNotice  = startupNotice == null ? "" : startupNotice;
        this.templates      = templates == null ? WorkspaceCommandTemplates.none() : templates;
    }

    /**
     * Test-only accessor for the wired {@link TurnProcessor}. Package-private
     * so that {@code dev.talos.cli.repl} tests can assert bootstrap wiring
     * (approval policy class, registered listeners) without broadening the
     * public API surface.
     */
    TurnProcessor turnProcessor() {
        return turnProcessor;
    }

    /**
     * Test-only accessor for the wired {@link Context}. Package-private so
     * that {@code dev.talos.cli.repl} tests can assert stream-sink routing
     * (e.g. JLine-safe output path) without reaching through reflection.
     */
    Context context() {
        return ctx;
    }

    /**
     * Backward-compatible factory - delegates to {@link TalosBootstrap}.
     * Existing callers (RunCmd) continue to work without changes.
     */
    public ReplRouter(SessionState session, Config cfg, PrintStream out, Path workspace) {
        ReplRouter wired = TalosBootstrap.create(session, cfg, out, workspace);
        this.modes          = wired.modes;
        this.turnProcessor  = wired.turnProcessor;
        this.runtimeSession = wired.runtimeSession;
        this.ctx            = wired.ctx;
        this.render         = wired.render;
        this.registry       = wired.registry;
        this.workspace      = wired.workspace;
        this.quit           = wired.quit;
        this.startupNotice  = wired.startupNotice;
        this.templates      = wired.templates;
    }

    // ── Dispatch ─────────────────────────────────────────────────────────

    /** Try to handle a slash-command. Returns true if handled. */
    public boolean tryHandle(String line) {
        LineClassifier.Classified c = classifier.classify(line);
        if (c.type() != LineClassifier.LineType.COMMAND) return false;
        String name = c.commandName();
        if (!registry.has(name)) {
            // T806: registry miss → workspace template commands. Built-ins
            // always win (collisions were dropped at load). The expansion
            // runs the UNMODIFIED prompt pipeline directly - single-level,
            // never re-classified, exactly typed-input capability.
            String expansion = templates.expand(name, c.argsText());
            if (expansion == null) return false;
            processPrompt(expansion);
            return true;
        }

        Result r = pipe.run(() ->
                        registry.execute(name, c.argsText(), ctx),
                ctx, "/" + name
        );

        if (quit.get()) return true;
        render.render(r);
        return true;
    }

    /** Try to handle a non-command prompt. Returns true if handled. */
    public boolean tryHandlePrompt(String rawLine) {
        LineClassifier.Classified c = classifier.classify(rawLine);
        if (c.type() != LineClassifier.LineType.PROMPT) return false;
        processPrompt(rawLine);
        return true;
    }

    /**
     * The prompt pipeline body, shared by classified prompts and T806
     * template expansions (which bypass classification by design).
     */
    private void processPrompt(String rawLine) {
        // Show routing indicator in auto mode (dimmed, one line)
        if ("auto".equals(modes.getActiveName())) {
            PromptClassifier.Route preview = PromptClassifier.route(rawLine, modes.lastRoute(),
                    modes.getSymbolChecker());
            // In auto-mode: COMMAND -> structural handler, everything else -> agent.
            String label = (preview == PromptClassifier.Route.COMMAND) ? "structural" : "agent";
            render.printRouteHint(label);
        }

        // T802: resolve @-file pins before the spinner so skip/refusal
        // notices print as plain lines above the turn. Pins ride a
        // turn-scoped Context copy; the long-lived ctx never carries them.
        AtFilePins.Resolution pinResolution =
                AtFilePins.resolve(rawLine, workspace, ctx.sandbox());
        AtFilePins.recordLast(pinResolution); // /context pinned-bytes row (T803)
        for (String notice : pinResolution.notices()) {
            render.render(new Result.TrustedInfo(notice));
        }
        final Context turnCtx = pinResolution.pins().isEmpty()
                ? ctx
                : ctx.withPinnedFiles(pinResolution.pins());

        render.startSpinner();

        Result r = pipe.run(() -> {
                    TurnResult tr = turnProcessor.process(runtimeSession, rawLine, turnCtx);
                    if (tr == null) return null;
                    lastTurnResult = tr;
                    return tr.result();
                },
                ctx, "(prompt)"
        );

        render.render(r);

        // Show turn stats (timing) after the answer
        if (lastTurnResult != null) {
            if (ctx.session() != null && ctx.session().getDebugLevel() == DebugLevel.TRACE) {
                render.render(new Result.TrustedInfo(formatCurrentTurnTrace(lastTurnResult)));
            }
            int responseLen = (r instanceof Result.Ok ok) ? ok.text.length()
                    : (r instanceof Result.Streamed st) ? st.fullText.length()
                    : 0;
            render.printTurnStats(
                    lastTurnResult.turnNumber(),
                    lastTurnResult.elapsed().toMillis(),
                    responseLen
            );
            lastTurnResult = null;
        }

        // T805: the auto-compaction notice renders strictly AFTER the turn
        // stats. Listeners fire synchronously inside turnProcessor.process,
        // so by this point the one-shot event is already set (or not) -
        // polling here is race-free. The notice is interactive-gated render
        // chrome; scripted/redirected transcripts stay unchanged.
        if (ctx.conversationManager() != null) {
            var compaction = ctx.conversationManager().pollCompactionEvent();
            if (compaction != null) {
                render.printCompactionNotice(
                        compaction.summarizedPairs(), compaction.keptPairs());
            }
        }

        // T884: close the turn with a dim rule so it reads as a distinct block
        // before the next prompt. Interactive-only render chrome (scripted
        // transcripts stay byte-identical); scoped to real prompt turns, not
        // slash-command output.
        render.printTurnSeparator();
    }

    // ── Accessors ────────────────────────────────────────────────────────

    public boolean shouldQuit()          { return quit.get(); }
    public ModeController getModes()     { return modes; }
    public Session getRuntimeSession()   { return runtimeSession; }
    public CommandRegistry getRegistry() { return registry; }
    public WorkspaceCommandTemplates getTemplates() { return templates; }
    public String getStartupNotice()     { return startupNotice; }

    /** Stops any spinner and closes the JLine status region (T779). */
    public void shutdownRendering()      { render.shutdown(); }

    static String formatCurrentTurnTrace(TurnResult turnResult) {
        if (turnResult == null || turnResult.audit() == null) return "";
        var trace = turnResult.audit().policyTrace();
        if (trace == null || !trace.hasPolicyData()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\nCurrent Turn Trace\n");
        var localTrace = turnResult.audit().localTrace();
        if (localTrace != null && localTrace.mode() != null && !localTrace.mode().isBlank()) {
            sb.append("  mode: ").append(localTrace.mode()).append('\n');
        }
        sb.append("  contract: ").append(trace.taskType())
                .append(" mutationAllowed=").append(trace.mutationAllowed())
                .append(" verificationRequired=").append(trace.verificationRequired())
                .append('\n');
        if (!trace.classificationReason().isBlank()) {
            sb.append("  classificationReason: ").append(trace.classificationReason()).append('\n');
        }
        sb.append("  phase: initial=").append(trace.initialPhase())
                .append(" final=").append(trace.finalPhase())
                .append('\n');
        sb.append("  nativeTools: ").append(listOrNone(trace.nativeTools())).append('\n');
        sb.append("  promptTools: ").append(listOrNone(trace.promptTools())).append('\n');
        sb.append("  blocked: ").append(listOrNone(trace.blocks())).append('\n');
        return sb.toString();
    }

    private static String listOrNone(java.util.List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join(", ", values);
    }
}
