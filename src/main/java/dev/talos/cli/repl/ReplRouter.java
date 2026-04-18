package dev.talos.cli.repl;

import dev.talos.cli.commands.CommandRegistry;
import dev.talos.cli.modes.ModeController;
import dev.talos.cli.modes.PromptRouter;
import dev.talos.cli.ui.AnsiColor;
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
 * <p>All dependencies are injected — construction and wiring live in
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
    private final LineClassifier classifier = new LineClassifier();
    private final ExecutionPipeline pipe = new ExecutionPipeline();
    private final AtomicBoolean quit;
    private volatile TurnResult lastTurnResult;

    /**
     * Primary constructor — called by {@link TalosBootstrap}.
     * All dependencies are pre-wired; the router only dispatches.
     */
    ReplRouter(ModeController modes, TurnProcessor turnProcessor, Session runtimeSession,
               Context ctx, RenderEngine render, CommandRegistry registry,
               Path workspace, AtomicBoolean quit) {
        this.modes          = modes;
        this.turnProcessor  = turnProcessor;
        this.runtimeSession = runtimeSession;
        this.ctx            = ctx;
        this.render         = render;
        this.registry       = registry;
        this.quit           = quit;
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
     * Backward-compatible factory — delegates to {@link TalosBootstrap}.
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
        this.quit           = wired.quit;
    }

    // ── Dispatch ─────────────────────────────────────────────────────────

    /** Try to handle a slash-command. Returns true if handled. */
    public boolean tryHandle(String line) {
        LineClassifier.Classified c = classifier.classify(line);
        if (c.type() != LineClassifier.LineType.COMMAND) return false;
        String name = c.commandName();
        if (!registry.has(name)) return false;

        Result r = pipe.run(() ->
                        registry.execute(name, c.argsText(), ctx),
                ctx, "/" + name
        );

        render.render(r);
        return true;
    }

    /** Try to handle a non-command prompt. Returns true if handled. */
    public boolean tryHandlePrompt(String rawLine) {
        LineClassifier.Classified c = classifier.classify(rawLine);
        if (c.type() != LineClassifier.LineType.PROMPT) return false;

        // Show routing indicator in auto mode (dimmed, one line)
        if ("auto".equals(modes.getActiveName())) {
            PromptRouter.Route preview = PromptRouter.route(rawLine, modes.lastRoute(),
                    modes.getSymbolChecker());
            // In auto-mode: COMMAND → dev, everything else → unified
            String label = (preview == PromptRouter.Route.COMMAND) ? "dev" : "unified";
            render.printRouteHint(label);
        }

        render.startSpinner();

        Result r = pipe.run(() -> {
                    TurnResult tr = turnProcessor.process(runtimeSession, rawLine, ctx);
                    if (tr == null) return null;
                    lastTurnResult = tr;
                    return tr.result();
                },
                ctx, "(prompt)"
        );

        if (r == null) return false;
        render.render(r);

        // Show turn stats (timing) after the answer
        if (lastTurnResult != null) {
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

        return true;
    }

    // ── Accessors ────────────────────────────────────────────────────────

    public boolean shouldQuit()          { return quit.get(); }
    public ModeController getModes()     { return modes; }
    public Session getRuntimeSession()   { return runtimeSession; }
    public CommandRegistry getRegistry() { return registry; }
}
