package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.retrieval.RetrievalTrace;
import dev.talos.runtime.phase.PhasePolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
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

    private static final Logger LOG = LoggerFactory.getLogger(TurnProcessor.class);

    private final ModeController modes;
    private final ApprovalGate approvalGate;
    private final ApprovalPolicy approvalPolicy;
    private final ToolRegistry toolRegistry;
    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Primary constructor. All policy parameters are required — the caller
     * must pass an explicit {@link ApprovalGate}, {@link ToolRegistry}, and
     * {@link ApprovalPolicy}. Pass {@link NoOpApprovalGate} /
     * {@link ApprovalPolicy#ALWAYS_ASK} explicitly if you want the default
     * no-op policy; silent null-to-NoOp substitution is no longer supported
     * at this seam (CCR-016).
     *
     * <p>The convenience constructors below still provide explicit
     * {@code NoOpApprovalGate} / {@link ApprovalPolicy#ALWAYS_ASK} defaults
     * for tests and ad-hoc call sites — those are explicit wiring, not
     * policy-by-null.
     */
    public TurnProcessor(ModeController modes, ApprovalGate approvalGate,
                         ToolRegistry toolRegistry, ApprovalPolicy approvalPolicy) {
        this.modes = modes;
        this.approvalGate = Objects.requireNonNull(approvalGate,
                "approvalGate must not be null — pass NoOpApprovalGate() explicitly "
                        + "to keep the no-op policy (CCR-016)");
        this.toolRegistry = Objects.requireNonNull(toolRegistry,
                "toolRegistry must not be null — pass a new ToolRegistry() explicitly");
        this.approvalPolicy = Objects.requireNonNull(approvalPolicy,
                "approvalPolicy must not be null — pass ApprovalPolicy.ALWAYS_ASK explicitly");
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
            if (ctx != null && ctx.executionPhaseState() != null) {
                TurnAuditCapture.updateFinalPhase(ctx.executionPhaseState().phase().name());
            }

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
     *   <li>Mutation-intent guard — reject write/edit calls when the original
     *       user prompt did not explicitly request a modification.</li>
     *   <li>Execution phase policy — reject mutating calls outside APPLY.</li>
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
        if (session == null || ctx == null) {
            return ToolResult.fail(ToolError.invalidParams("Tool execution context is unavailable"));
        }

        // Check if the tool exists
        TalosTool tool = toolRegistry.get(call.toolName());
        if (tool == null) {
            TurnAuditCapture.recordToolCall(call.toolName(), "", false, "unknown tool");
            return ToolResult.fail(ToolError.notFound("Unknown tool: " + call.toolName()));
        }

        ToolRiskLevel risk = tool.descriptor().riskLevel();
        String path = resolvePathParam(call);
        String userRequest = TurnUserRequestCapture.get();
        TaskContract taskContract = TaskContractResolver.fromUserRequest(userRequest);

        if (ToolCallSupport.isMutatingTool(call.toolName())
                && userRequest != null
                && !taskContract.mutationAllowed()) {
            TurnAuditCapture.recordToolCall(
                    call.toolName(), path == null ? "" : path, false,
                    "task-contract read-only denied " + call.toolName());
            return ToolResult.fail(ToolError.denied(
                    "The user did not ask to modify files on this turn, so do not call "
                            + call.toolName()
                            + " for a read-only request. Answer with information only, "
                            + "or wait for an explicit change request in a later turn."));
        }

        if (ctx.executionPhaseState() != null) {
            ToolResult phaseRejection = PhasePolicy.rejectIfDisallowed(
                    ctx.executionPhaseState().phase(), tool.name(), risk);
            if (phaseRejection != null) {
                TurnAuditCapture.recordToolCall(
                        call.toolName(), path == null ? "" : path, false,
                        "phase " + ctx.executionPhaseState().phase() + " denied " + call.toolName());
                return phaseRejection;
            }
        }

        // Path-parameter placeholder guard — applies to ALL tools regardless of
        // risk level. Transcript-observed failure (qwen2.5-coder:14b, April 2026):
        // the model emitted planning narration with mixed real and template tool
        // calls: read_file(path=<html-file-path>). read_file is READ_ONLY so the
        // content-guard below (scoped to requiresApproval) was skipped entirely.
        // Path.of("<html-file-path>") is illegal on Windows (Illegal char '<' at
        // index 0), propagated uncaught as an InvalidPathException through
        // executeTool → ToolCallLoop → AssistantTurnExecutor, and was logged as
        // "LLM call failed" — killing the whole turn. A placeholder path is
        // definitionally wrong for any file tool; refuse here and return a directed
        // error so the model retries with the actual workspace path.
        for (String k : List.of("path", "file_path", "filepath", "file", "filename", "from", "to")) {
            String v = call.param(k);
            if (TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(v)) {
                String msg = TemplatePlaceholderGuard.rejectionMessage(call.toolName(), k, v);
                TurnAuditCapture.recordToolCall(
                        call.toolName(), path == null ? "" : path, false,
                        "placeholder path parameter `" + k + "` rejected");
                return ToolResult.fail(ToolError.invalidParams(msg));
            }
        }

        // Template-placeholder guard — reject BEFORE the approval gate.
        // Transcript-observed failure (qwen2.5-coder:14b, April 2026): the
        // model emits a pedagogical "step-by-step" answer using Python-style
        // variable names, then issues write_file / edit_file tool calls whose
        // content argument IS the variable name (e.g.
        // `<updated_index_html_content>`). The approval preview just mirrors
        // the placeholder back at the user; a reflex "y" overwrites real
        // files with 28 bytes of garbage. Warning-in-approval-detail would
        // not have saved the user — this class of payload is definitionally
        // garbage, so we refuse it at tool-call time and feed a directed
        // error back so the model retries with real content.
        if (risk.requiresApproval()) {
            String placeholderParam = null;
            String placeholderValue = null;
            // write_file-family: content / text / body / file_content
            for (String k : List.of("content", "text", "body", "file_content", "data")) {
                String v = call.param(k);
                if (TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(v)) {
                    placeholderParam = k;
                    placeholderValue = v;
                    break;
                }
            }
            // edit_file: new_string
            if (placeholderParam == null) {
                String v = call.param("new_string");
                if (TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(v)) {
                    placeholderParam = "new_string";
                    placeholderValue = v;
                }
            }
            if (placeholderParam != null) {
                String msg = TemplatePlaceholderGuard.rejectionMessage(
                        call.toolName(), placeholderParam, placeholderValue);
                // Recorded as a rejected (denied) approval for audit purposes
                // — the call never reached the gate because the payload was
                // definitionally bad, but from a trust-accounting perspective
                // it is a denied mutation, not a success.
                TurnAuditCapture.recordToolCall(
                        call.toolName(), path == null ? "" : path, false,
                        "placeholder content parameter `" + placeholderParam + "` rejected");
                return ToolResult.fail(ToolError.invalidParams(msg));
            }
        }

        if (risk.requiresApproval()) {
            ToolResult preApprovalValidation = validateBeforeApproval(call, session, ctx);
            if (preApprovalValidation != null) {
                TurnAuditCapture.recordToolCall(
                        call.toolName(), path == null ? "" : path, false,
                        preApprovalBlockReason(call, preApprovalValidation));
                return preApprovalValidation;
            }
        }

        // Scope guard — narrow, lexical, warn-first. Fires only for mutating
        // calls where the request looks web-scoped and the target extension
        // is obviously off-scope. If it fires, the warning is surfaced to
        // the user through the approval detail (see buildApprovalDetail).
        String scopeWarning = null;
        if (risk.requiresApproval()
                && ScopeGuard.looksLikeOffScopeMutationTarget(userRequest, path)) {
            scopeWarning = ScopeGuard.warningMessage(userRequest, path);
        }

        if (risk.requiresApproval()) {
            TurnAuditCapture.recordApprovalRequired();

            // Policy classification. AUTO_APPROVE skips the gate; DENY refuses
            // without prompting; ASK falls through to the gate as before.
            Path workspace = session.workspace();
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
                TurnAuditCapture.recordToolCall(
                        call.toolName(), path == null ? "" : path, false,
                        "approval policy denied " + call.toolName());
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
                    TurnAuditCapture.recordToolCall(
                            call.toolName(), path == null ? "" : path, false,
                            "approval denied by user for " + call.toolName());
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

        ToolResult result;
        try {
            result = toolRegistry.execute(call, toolCtx);
        } catch (Exception e) {
            LOG.warn("Tool {} threw unexpected exception: {} — returning fail result instead of crashing turn",
                    call.toolName(), e.getMessage());
            LOG.debug("Tool execution exception stack trace:", e);
            result = ToolResult.fail(ToolError.internal(
                    "Tool execution failed unexpectedly: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
        TurnAuditCapture.recordToolCall(
                call.toolName(),
                path == null ? "" : path,
                result.success(),
                result.success() ? "" : toolFailureReason(result));
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

    private static ToolResult validateBeforeApproval(ToolCall call, Session session, Context ctx) {
        ToolResult sandboxPathValidation = validateSandboxPathBeforeApproval(call, session, ctx);
        if (sandboxPathValidation != null) {
            return sandboxPathValidation;
        }

        if (!"talos.edit_file".equals(call.toolName())) {
            return null;
        }

        String path = resolveParam(call, "path", "file_path", "filepath", "file", "filename");
        if (path == null || path.isBlank()) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Invalid talos.edit_file call: missing required parameter `path`. "
                            + "No approval was requested and no file was changed."));
        }

        String oldString = resolveParam(call, "old_string", "oldString", "old_text", "search", "find", "original");
        if (oldString == null || oldString.isEmpty()) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Invalid talos.edit_file call: `old_string` must be present and non-empty. "
                            + "Call talos.read_file first, then provide the exact text to replace. "
                            + "No approval was requested and no file was changed."));
        }

        String newString = resolveParam(call, "new_string", "newString", "new_text", "replace", "replacement");
        if (newString == null) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Invalid talos.edit_file call: missing required parameter `new_string`. "
                            + "No approval was requested and no file was changed."));
        }

        if (oldString.equals(newString)) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Invalid talos.edit_file call: `old_string` and `new_string` are identical, "
                            + "so no edit would be made. No approval was requested and no file was changed."));
        }

        return null;
    }

    private static ToolResult validateSandboxPathBeforeApproval(ToolCall call, Session session, Context ctx) {
        if (call == null || !ToolCallSupport.isMutatingTool(call.toolName())) {
            return null;
        }
        if (session == null || session.workspace() == null || ctx == null || ctx.sandbox() == null) {
            return null;
        }

        for (PathParam param : pathParams(call)) {
            Path resolved;
            try {
                resolved = session.workspace().resolve(param.value()).normalize();
            } catch (Exception e) {
                return ToolResult.fail(ToolError.invalidParams(
                        "Invalid path before approval for `" + param.name() + "`: "
                                + param.value() + ". No approval was requested and no file was changed."));
            }
            if (!ctx.sandbox().allowedPath(resolved)) {
                return ToolResult.fail(ToolError.invalidParams(
                        "Path not allowed before approval for `" + param.name() + "`: "
                                + param.value() + " (" + ctx.sandbox().explain(resolved) + "). "
                                + "No approval was requested and no file was changed."));
            }
        }
        return null;
    }

    private static List<PathParam> pathParams(ToolCall call) {
        var params = new java.util.ArrayList<PathParam>();
        for (String key : List.of("path", "file_path", "filepath", "file", "filename", "from", "to")) {
            String value = call.param(key);
            if (value != null && !value.isBlank()) {
                params.add(new PathParam(key, value));
            }
        }
        return params;
    }

    private static String preApprovalBlockReason(ToolCall call, ToolResult result) {
        String name = call == null ? "tool" : call.toolName();
        String message = result == null ? "" : result.errorMessage();
        if (message != null && message.startsWith("Path not allowed before approval")) {
            return "path blocked before approval"
                    + (message.isBlank() ? "" : ": " + shortReason(message));
        }
        if (message != null && message.startsWith("Invalid path before approval")) {
            return "invalid path before approval"
                    + (message.isBlank() ? "" : ": " + shortReason(message));
        }
        if ("talos.edit_file".equals(name)) {
            return "invalid edit args before approval"
                    + (message == null || message.isBlank() ? "" : ": " + shortReason(message));
        }
        return "invalid tool args before approval"
                + (message == null || message.isBlank() ? "" : ": " + shortReason(message));
    }

    private static String toolFailureReason(ToolResult result) {
        if (result == null || result.success()) return "";
        String code = result.error() == null ? "tool failed" : result.error().code();
        String message = result.errorMessage();
        return code + (message == null || message.isBlank() ? "" : ": " + shortReason(message));
    }

    private static String shortReason(String message) {
        String oneLine = message.replace('\r', ' ').replace('\n', ' ').strip();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 157) + "...";
    }

    private static String resolveParam(ToolCall call, String canonical, String... aliases) {
        String value = call.param(canonical);
        if (value != null) return value;
        for (String alias : aliases) {
            value = call.param(alias);
            if (value != null) return value;
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
            sb.append("warning: ").append(scopeWarning).append('\n');
            sb.append("    ");
        }

        if (path != null && !path.isBlank()) {
            sb.append("target: ").append(path);
        } else {
            sb.append("(warning: no target path specified - may fail)");
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
            sb.append("\n    replace: ").append(oldPreview.replace("\n", "\\n"));
            sb.append("\n    with:    ").append(newPreview.replace("\n", "\\n"));
        }

        return sb.toString();
    }

    private record PathParam(String name, String value) { }
}

