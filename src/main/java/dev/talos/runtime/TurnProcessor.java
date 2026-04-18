package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.retrieval.RetrievalTrace;
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
    private final ApprovalPolicy approvalPolicy;
    private final ToolRegistry toolRegistry;
    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();

    public TurnProcessor(ModeController modes, ApprovalGate approvalGate,
                         ToolRegistry toolRegistry, ApprovalPolicy approvalPolicy) {
        this.modes = modes;
        this.approvalGate = (approvalGate != null) ? approvalGate : new NoOpApprovalGate();
        this.toolRegistry = (toolRegistry != null) ? toolRegistry : new ToolRegistry();
        this.approvalPolicy = (approvalPolicy != null) ? approvalPolicy : ApprovalPolicy.ALWAYS_ASK;
    }

    public TurnProcessor(ModeController modes, ApprovalGate approvalGate, ToolRegistry toolRegistry) {
        this(modes, approvalGate, toolRegistry, ApprovalPolicy.ALWAYS_ASK);
    }

    public TurnProcessor(ModeController modes, ApprovalGate approvalGate) {
        this(modes, approvalGate, new ToolRegistry(), ApprovalPolicy.ALWAYS_ASK);
    }

    public TurnProcessor(ModeController modes) {
        this(modes, new NoOpApprovalGate(), new ToolRegistry(), ApprovalPolicy.ALWAYS_ASK);
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
     * Test-only introspection: true if at least one registered listener is
     * an instance of the given class. Used by the bootstrap wiring test to
     * assert post-turn hooks (memory update, JSONL turn log) are registered.
     */
    public boolean hasListenerOfType(Class<? extends SessionListener> type) {
        if (type == null) return false;
        for (SessionListener l : listeners) {
            if (type.isInstance(l)) return true;
        }
        return false;
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

        // Publish the current turn's user request + start the per-turn audit
        // bag so executeTool(...) (called many times during tool-loop runs)
        // can consult the request for scope guarding and record its tool
        // activity without threading extra arguments through every call.
        TurnUserRequestCapture.set(userInput);
        TurnAuditCapture.begin();
        TurnResult turnResult;
        try {
            Path ws = session.workspace();
            Optional<Result> result = modes.route(userInput, ws, ctx);

            if (result.isEmpty()) {
                return null;
            }

            long elapsedNanos = System.nanoTime() - startNanos;

            // Consume any retrieval trace captured during mode dispatch (e.g. by RagMode).
            // For non-RAG turns (AskMode, DevMode), this returns null — expected and correct.
            RetrievalTrace trace = TurnTraceCapture.consume();

            turnResult = new TurnResult(
                    result.get(),
                    trace,
                    turn,
                    Duration.ofNanos(elapsedNanos),
                    TurnAuditCapture.end()
            );
        } finally {
            TurnUserRequestCapture.clear();
            // Defensive: if we hit a return/throw above before end() fired,
            // ensure the thread-local bag is cleaned up.
            if (TurnAuditCapture.isActive()) {
                TurnAuditCapture.end();
            }
        }

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
     * Execute a tool call with full sandbox enforcement, scope guarding,
     * policy classification, and approval gating.
     *
     * <p>Decision order for mutating tools:
     * <ol>
     *   <li>Resolve target path (for scope warning + policy classification).</li>
     *   <li>{@link ScopeGuard} — if the request is web-scoped and the target
     *       looks obviously off-scope, a warning is prepended to the approval
     *       detail so the user sees it at decision time. Posture is warn,
     *       not block.</li>
     *   <li>{@link ApprovalPolicy#decide} — may auto-approve in-workspace
     *       edits (if the user opted in for this session) or deny without
     *       prompting.</li>
     *   <li>{@link ApprovalGate#approveFull} — tri-state gate that can emit
     *       {@link ApprovalResponse#APPROVED_REMEMBER} to record the user's
     *       "yes for this session" preference.</li>
     * </ol>
     *
     * <p>Scope guarding, policy decisions, and approval outcomes are also
     * recorded into the active {@link TurnAuditCapture} bag if one is
     * running on this thread.
     */
    public ToolResult executeTool(Session session, ToolCall call, Context ctx) {
        if (call == null) {
            return ToolResult.fail(ToolError.invalidParams("Tool call is null"));
        }

        // Check if the tool exists
        TalosTool tool = toolRegistry.get(call.toolName());
        if (tool == null) {
            return ToolResult.fail(ToolError.notFound("Unknown tool: " + call.toolName()));
        }

        ToolRiskLevel risk = tool.descriptor().riskLevel();
        String path = resolvePathParam(call);
        String userRequest = TurnUserRequestCapture.get();

        // Scope guard — narrow, lexical, warn-first. Fires only for mutating
        // calls where the request looks web-scoped and the target extension
        // is obviously off-scope. If it fires, the warning is surfaced to
        // the user through the approval detail (see buildApprovalDetail).
        String scopeWarning = null;
        if (risk.requiresApproval()
                && path != null
                && ScopeGuard.looksLikeOffScopeMutationTarget(userRequest, path)) {
            scopeWarning = ScopeGuard.warningMessage(userRequest, path);
        }

        if (risk.requiresApproval()) {
            TurnAuditCapture.recordApprovalRequired();

            // Policy classification. AUTO_APPROVE skips the gate; DENY refuses
            // without prompting; ASK falls through to the gate as before.
            Path workspace = session != null ? session.workspace() : null;
            ApprovalPolicy.Decision decision = approvalPolicy.decide(workspace, call, risk);

            // Scope-guard override: if the target looks off-scope, the user
            // MUST see the warning before the call runs. A remembered
            // AUTO_APPROVE would otherwise silently bypass the warning —
            // exactly the failure class the guard exists to catch (the
            // transcript-observed drift from `index.html` to
            // `math_operations.py` mid-session). Forcing ASK here preserves
            // the guard's "warn, do not block" posture while ensuring the
            // warning never reaches a silent-bypass path.
            if (scopeWarning != null && decision == ApprovalPolicy.Decision.AUTO_APPROVE) {
                decision = ApprovalPolicy.Decision.ASK;
            }

            if (decision == ApprovalPolicy.Decision.DENY) {
                TurnAuditCapture.recordApprovalDenied();
                return ToolResult.fail(ToolError.denied(
                        "Policy denied the " + call.toolName()
                                + " call. The session's approval policy prohibits this operation; "
                                + "choose a different action or ask the user to relax policy."));
            }

            if (decision == ApprovalPolicy.Decision.ASK) {
                String desc = risk.name().toLowerCase().replace('_', ' ')
                        + " operation: " + call.toolName();
                String detail = buildApprovalDetail(call, path, scopeWarning);
                ApprovalResponse response = approvalGate.approveFull(desc, detail);

                if (response == ApprovalResponse.DENIED) {
                    TurnAuditCapture.recordApprovalDenied();
                    // Phrasing matters: previously "Operation denied by user" caused
                    // qwen2.5-coder to hallucinate a "permissions" excuse and tell
                    // the user to "ensure you have the necessary permissions" — the
                    // word "denied" anchored the wrong narrative. Reshape the error
                    // so the model interprets it as user intent, not auth failure.
                    return ToolResult.fail(ToolError.denied(
                            "User did not approve the " + call.toolName()
                                    + " call. The user is in control of the workspace; "
                                    + "ask what they want to do differently before retrying, "
                                    + "or take a different action that does not need approval."));
                }

                // Approved — record and optionally propagate the remember choice.
                TurnAuditCapture.recordApprovalGranted();
                if (response == ApprovalResponse.APPROVED_REMEMBER) {
                    approvalPolicy.rememberApproval(workspace, call, risk);
                }
            } else {
                // AUTO_APPROVE by policy
                TurnAuditCapture.recordApprovalGranted();
            }
        }

        ToolContext toolCtx = new ToolContext(
                session.workspace(),
                ctx.sandbox(),
                session.config()
        );

        ToolResult result = toolRegistry.execute(call, toolCtx);
        TurnAuditCapture.recordToolCall(call.toolName(), path == null ? "" : path, result.success());
        return result;
    }

    /** Access the approval gate (for future use by modes/capabilities). */
    public ApprovalGate approvalGate() {
        return approvalGate;
    }

    /** Access the approval policy layer (test + introspection hook). */
    public ApprovalPolicy approvalPolicy() {
        return approvalPolicy;
    }

    /** Access the tool registry for tool discovery and registration. */
    public ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    /**
     * Resolve the target path from a tool call, trying common parameter name variants.
     * Used for the approval gate display — even when the model uses non-canonical
     * parameter names (e.g. {@code file_path} instead of {@code path}).
     */
    private static String resolvePathParam(ToolCall call) {
        for (String key : List.of("path", "file_path", "filepath", "file", "filename")) {
            String value = call.param(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    /**
     * Build a detailed approval message for write/edit operations.
     * Shows the target path, content size/line count, and a preview
     * of the first few lines so the user can make an informed decision.
     *
     * <p>If a {@code scopeWarning} is present, it is prepended on its own
     * line so the user sees the scope concern before the approval choice.
     */
    private static String buildApprovalDetail(ToolCall call, String path, String scopeWarning) {
        var sb = new StringBuilder();

        if (scopeWarning != null && !scopeWarning.isBlank()) {
            sb.append("⚠ ").append(scopeWarning).append('\n');
            sb.append("    ");
        }

        if (path != null && !path.isBlank()) {
            sb.append("target: ").append(path);
        } else {
            sb.append("(warning: no target path specified — may fail)");
        }

        // For write_file: show content size and preview
        String content = call.param("content");
        if (content == null) content = call.param("text");
        if (content == null) content = call.param("body");

        if (content != null && !content.isEmpty()) {
            long lines = content.chars().filter(c -> c == '\n').count() + 1;
            sb.append(" (").append(content.length()).append(" bytes, ").append(lines).append(" lines)");

            // Show first 5 lines as preview
            String[] contentLines = content.split("\n", 7);
            int previewCount = Math.min(5, contentLines.length);
            sb.append("\n    preview:");
            for (int i = 0; i < previewCount; i++) {
                String line = contentLines[i];
                if (line.length() > 80) line = line.substring(0, 77) + "...";
                sb.append("\n      ").append(line);
            }
            if (contentLines.length > 5) {
                sb.append("\n      ...");
            }
        }

        // For edit_file: show old_string → new_string summary
        String oldStr = call.param("old_string");
        String newStr = call.param("new_string");
        if (oldStr != null && newStr != null) {
            String oldPreview = oldStr.length() > 60 ? oldStr.substring(0, 57) + "..." : oldStr;
            String newPreview = newStr.length() > 60 ? newStr.substring(0, 57) + "..." : newStr;
            sb.append("\n    replace: ").append(oldPreview.replace('\n', '↵'));
            sb.append("\n    with:    ").append(newPreview.replace('\n', '↵'));
        }

        return sb.toString();
    }
}

