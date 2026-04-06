package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.tools.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

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
 *   <li>future transcript persistence</li>
 * </ul>
 *
 * <p>Commands (colon-prefixed) bypass TurnProcessor and are handled
 * directly by the command registry — this only processes prompts.
 */
public final class TurnProcessor {

    private final ModeController modes;
    private final ApprovalGate approvalGate;
    private final ToolRegistry toolRegistry;

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

    /**
     * Process a single user prompt through the mode system.
     *
     * <p>Exceptions are <em>not</em> caught here — they propagate to the caller
     * (typically {@code ExecutionPipeline}) which owns the error envelope,
     * redaction, and audit logging. TurnProcessor only handles turn tracking
     * and timing on the success path.
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
        return new TurnResult(
                result.get(),
                null, // trace — extracted from Prepared in future pass
                turn,
                Duration.ofNanos(elapsedNanos)
        );
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

