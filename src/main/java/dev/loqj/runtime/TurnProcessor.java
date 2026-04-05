package dev.loqj.runtime;

import dev.loqj.cli.modes.ModeController;
import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;

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
 *   <li>future approval gate integration</li>
 *   <li>future transcript persistence</li>
 * </ul>
 *
 * <p>Commands (colon-prefixed) bypass TurnProcessor and are handled
 * directly by the command registry — this only processes prompts.
 */
public final class TurnProcessor {

    private final ModeController modes;
    private final ApprovalGate approvalGate;

    public TurnProcessor(ModeController modes, ApprovalGate approvalGate) {
        this.modes = modes;
        this.approvalGate = (approvalGate != null) ? approvalGate : new NoOpApprovalGate();
    }

    public TurnProcessor(ModeController modes) {
        this(modes, new NoOpApprovalGate());
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

    /** Access the approval gate (for future use by modes/capabilities). */
    public ApprovalGate approvalGate() {
        return approvalGate;
    }
}

