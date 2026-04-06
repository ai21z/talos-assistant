package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.tools.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Processes a single user turn (prompt → result) through the mode system.
 *
 * <p>This is the thin runtime layer between the CLI REPL loop and the
 * mode/knowledge-engine dispatch. All prompt handling flows through here,
 * giving one composable point for:
 * <ul>
 *   <li>session-aware turn tracking</li>
 *   <li>timing and trace capture</li>
 *   <li>tool execution with sandbox enforcement</li>
 *   <li>approval gate integration for sensitive tools</li>
 *   <li>centralized post-turn hooks via {@link SessionListener}</li>
 * </ul>
 *
 * <p>Commands (colon-prefixed) bypass TurnProcessor and are handled
 * directly by the command registry — this only processes prompts.
 */
public final class TurnProcessor {

    private final ModeController modes;
    private final ApprovalGate approvalGate;
    private final ToolRegistry toolRegistry;
    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();

    public TurnProcessor(ModeController modes, ApprovalGate approvalGate, ToolRegistry toolRegistry) {
        this.modes = modes;
        this.approvalGate = (approvalGate != null) ? approvalGate : new NoOpApprovalGate();
        this.toolRegistry = (toolRegistry != null) ? toolRegistry : new ToolRegistry();
    }

    public TurnProcessor(ModeController modes, ApprovalGate approvalGate) {
        this(modes, approvalGate, new ToolRegistry());
    }

    public TurnProcessor(ModeController modes) {
        this(modes, new NoOpApprovalGate(), new ToolRegistry());
    }

    /** Register a session lifecycle listener for post-turn hooks. */
    public void addListener(SessionListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Fire onSessionEnd on all registered listeners. */
    public void fireSessionEnd() {
        for (SessionListener l : listeners) {
            try { l.onSessionEnd(); } catch (Exception ignored) { }
        }
    }

    /**
     * Process a single user prompt through the mode system.
     *
     * <p>After a successful turn, all registered {@link SessionListener}s
     * receive an {@code onTurnComplete} callback with the result and the
     * original user input. This centralizes memory updates, audit logging,
     * and future transcript persistence.
     *
     * <p>Exceptions are <em>not</em> caught here — they propagate to the caller
     * (typically {@code ExecutionPipeline}) which owns the error envelope,
     * redaction, and audit logging.
     *
     * @param session   the active session
     * @param userInput raw user input (not a colon-command)
     * @param ctx       runtime context (rag, llm, sandbox, etc.)
     * @return a TurnResult, or null if no mode handled the input
     * @throws Exception if mode dispatch fails (propagated for envelope handling)
     */
    public TurnResult process(Session session, String userInput, Context ctx) throws Exception {
        if (userInput == null || userInput.isBlank()) {
            return null;
        }

        int turn = session.nextTurn();
        long startNanos = System.nanoTime();

        Path ws = session.workspace();
        Optional<Result> result = modes.route(userInput, ws, ctx);

        if (result.isEmpty()) {
            return null;
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        TurnResult turnResult = new TurnResult(
                result.get(),
                null, // trace — extracted from Prepared in future pass
                turn,
                Duration.ofNanos(elapsedNanos)
        );

        // Fire post-turn hooks on all listeners
        for (SessionListener listener : listeners) {
            try {
                listener.onTurnComplete(turnResult, userInput);
            } catch (Exception ignored) {
                // Listener errors must not break the turn pipeline
            }
        }

        return turnResult;
    }

    /**
     * Execute a tool call with full sandbox enforcement.
     *
     * <p>Builds a {@link ToolContext} from the session and delegates
     * to the registry. Returns a {@link ToolResult} — never throws.
     *
     * @param session the active session (provides workspace + config)
     * @param call    the tool call to execute
     * @param ctx     runtime context (provides sandbox)
     * @return tool execution result
     */
    public ToolResult executeTool(Session session, ToolCall call, Context ctx) {
        if (call == null) {
            return ToolResult.fail(ToolError.invalidParams("Tool call is null"));
        }

        ToolContext toolCtx = new ToolContext(
                session.workspace(),
                ctx.sandbox(),
                session.config()
        );

        return toolRegistry.execute(call, toolCtx);
    }

    /** Access the approval gate (for future use by modes/capabilities). */
    public ApprovalGate approvalGate() {
        return approvalGate;
    }

    /** Access the tool registry for tool discovery and registration. */
    public ToolRegistry toolRegistry() {
        return toolRegistry;
    }
}

