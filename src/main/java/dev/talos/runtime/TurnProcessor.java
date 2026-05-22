package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.retrieval.RetrievalTrace;
import dev.talos.core.ingest.UnsupportedDocumentFormats;
import dev.talos.runtime.command.CommandPlan;
import dev.talos.runtime.phase.PhasePolicy;
import dev.talos.runtime.command.CommandToolPlanner;
import dev.talos.runtime.checkpoint.CheckpointCaptureResult;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.expectation.ExactLiteralWriteCallCorrector;
import dev.talos.runtime.policy.DeclarativePermissionPolicy;
import dev.talos.runtime.policy.PermissionAction;
import dev.talos.runtime.policy.PermissionDecision;
import dev.talos.runtime.policy.PermissionRequest;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.policy.ProtectedPathAliasNormalizer;
import dev.talos.runtime.policy.ProtectedPathPolicy;
import dev.talos.runtime.policy.ProtectedReadScopePolicy;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.workspace.WorkspaceBatchPlanParser;
import dev.talos.runtime.workspace.WorkspaceOperationIntent;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.runtime.workspace.WorkspaceOperationPlanner;
import dev.talos.spi.types.ToolSpec;
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
    private final dev.talos.runtime.policy.PermissionPolicy permissionPolicy;
    private final CheckpointService checkpointService;
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
        this(modes, approvalGate, toolRegistry, approvalPolicy, new CheckpointService());
    }

    public TurnProcessor(ModeController modes, ApprovalGate approvalGate,
                         ToolRegistry toolRegistry, ApprovalPolicy approvalPolicy,
                         CheckpointService checkpointService) {
        this.modes = modes;
        this.approvalGate = Objects.requireNonNull(approvalGate,
                "approvalGate must not be null — pass NoOpApprovalGate() explicitly "
                        + "to keep the no-op policy (CCR-016)");
        this.toolRegistry = Objects.requireNonNull(toolRegistry,
                "toolRegistry must not be null — pass a new ToolRegistry() explicitly");
        this.approvalPolicy = Objects.requireNonNull(approvalPolicy,
                "approvalPolicy must not be null — pass ApprovalPolicy.ALWAYS_ASK explicitly");
        this.permissionPolicy = new DeclarativePermissionPolicy(this.approvalPolicy);
        this.checkpointService = Objects.requireNonNull(checkpointService,
                "checkpointService must not be null");
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
    @SuppressWarnings("resource") // Context-owned LlmClient is borrowed for metadata, not closed per turn.
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
        String traceId = LocalTurnTraceCapture.newTraceId();
        String sessionId = JsonSessionStore.sessionIdFor(session.workspace());
        String model = ctx != null && ctx.llm() != null ? ctx.llm().getModel() : "";
        LocalTurnTraceCapture.begin(
                traceId,
                sessionId,
                turn,
                java.time.Instant.now().toString(),
                sessionId,
                "unknown",
                modelBackend(model),
                modelName(model),
                userInput);
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
            String assistantText = MemoryUpdateListener.extractText(result.get());
            LocalTurnTraceCapture.recordModelResponseReceived(assistantText);
            LocalTurnTraceCapture.recordOutcomeIfAbsent(
                    JsonTurnLogAppender.statusOf(result.get()).toUpperCase(java.util.Locale.ROOT),
                    "NOT_RUN",
                    "UNKNOWN",
                    "UNKNOWN",
                    "TURN_RECORDED");
            LocalTurnTrace localTrace = LocalTurnTraceCapture.complete();
            TurnAudit audit = TurnAuditCapture.end().withLocalTrace(localTrace);

            turnResult = new TurnResult(
                    result.get(),
                    trace,
                    turn,
                    Duration.ofNanos(elapsedNanos),
                    audit
            );
        } finally {
            TurnUserRequestCapture.clear();
            LocalTurnTraceCapture.clear();
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

    private static String modelBackend(String model) {
        if (model == null || model.isBlank()) return "";
        int slash = model.indexOf('/');
        return slash > 0 ? model.substring(0, slash) : "";
    }

    private static String modelName(String model) {
        if (model == null) return "";
        int slash = model.indexOf('/');
        return slash >= 0 && slash + 1 < model.length() ? model.substring(slash + 1) : model;
    }

    private static String tracePhase(Context ctx) {
        return ctx != null && ctx.executionPhaseState() != null && ctx.executionPhaseState().phase() != null
                ? ctx.executionPhaseState().phase().name()
                : "";
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
        String tracePhase = tracePhase(ctx);
        LocalTurnTraceCapture.recordToolCallParsed(tracePhase, call);
        ToolAliasPolicy.Decision aliasDecision = ToolAliasPolicy.resolve(call.toolName());
        LocalTurnTraceCapture.recordToolAliasDecision(aliasDecision);

        // Check if the tool exists
        TalosTool tool = toolRegistry.get(call.toolName());
        if (tool == null) {
            TurnAuditCapture.recordToolCall(call.toolName(), "", false, "unknown tool");
            return ToolResult.fail(ToolError.notFound("Unknown tool: " + call.toolName()));
        }
        ToolResult surfaceRejection = rejectIfOutsideCurrentToolSurface(
                ctx, call, tool.name(), tracePhase);
        if (surfaceRejection != null) {
            TurnAuditCapture.recordToolCall(
                    call.toolName(), "", false,
                    "current-turn tool surface denied " + tool.name());
            return surfaceRejection;
        }

        boolean commandTool = CommandToolPlanner.isRunCommandTool(call.toolName());
        ToolRiskLevel risk = effectiveRisk(tool.descriptor().riskLevel(), call);
        String userRequest = TurnUserRequestCapture.get();
        TaskContract taskContract = TurnTaskContractCapture.get();
        if (taskContract == null) {
            taskContract = TaskContractResolver.fromUserRequest(userRequest);
        }
        PathArgumentCanonicalizer.ToolCallNormalization protectedAliasNormalization =
                ProtectedPathAliasNormalizer.canonicalizeExpectedProtectedAliases(
                        session.workspace(), call, pathParameterKeys(), taskContract.expectedTargets());
        if (protectedAliasNormalization.changed()) {
            for (PathArgumentCanonicalizer.PathParameterChange change : protectedAliasNormalization.changes()) {
                LocalTurnTraceCapture.recordPathArgumentNormalized(
                        tracePhase,
                        call,
                        change.key(),
                        change.rawPath(),
                        change.normalizedPath());
            }
            call = protectedAliasNormalization.call();
        }
        ExactLiteralWriteCallCorrector.Correction exactCorrection =
                ExactLiteralWriteCallCorrector.correct(call, taskContract);
        if (exactCorrection.corrected()) {
            LocalTurnTraceCapture.recordExactLiteralWriteCorrected(
                    exactCorrection.targetPath(),
                    exactCorrection.sourcePattern(),
                    exactCorrection.expectedHash(),
                    exactCorrection.expectedBytes(),
                    exactCorrection.expectedLines(),
                    exactCorrection.observedHash(),
                    exactCorrection.observedBytes(),
                    exactCorrection.observedLines());
            call = exactCorrection.call();
        }
        PathArgumentCanonicalizer.ToolCallNormalization pathNormalization =
                PathArgumentCanonicalizer.canonicalizeToolCall(session.workspace(), call, pathParameterKeys());
        if (pathNormalization.changed()) {
            for (PathArgumentCanonicalizer.PathParameterChange change : pathNormalization.changes()) {
                LocalTurnTraceCapture.recordPathArgumentNormalized(
                        tracePhase,
                        call,
                        change.key(),
                        change.rawPath(),
                        change.normalizedPath());
            }
            call = pathNormalization.call();
        }
        String path = resolvePathParam(call);

        if (taskContract.type() == TaskType.DIRECTORY_LISTING && !isListDirTool(call.toolName())) {
            TurnAuditCapture.recordToolCall(
                    call.toolName(), path == null ? "" : path, false,
                    "directory-listing contract denied " + call.toolName());
            LocalTurnTraceCapture.recordToolCallBlocked(tracePhase, call,
                    "directory-listing contract allows only talos.list_dir");
            return ToolResult.fail(ToolError.denied(
                    "The user only asked to list directory entries on this turn, so do not call "
                            + call.toolName()
                            + ". Use talos.list_dir only and answer with file and directory names."));
        }

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
                if (commandTool) {
                    String reason = "Phase policy blocked " + call.toolName()
                            + " during " + ctx.executionPhaseState().phase();
                    LocalTurnTraceCapture.recordCommandPolicyDecision(
                            tracePhase, call, "DENY", "PHASE_POLICY");
                    LocalTurnTraceCapture.recordCommandDenied(tracePhase, call, reason);
                }
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
        for (String k : pathParameterKeys()) {
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
            ToolResult preApprovalValidation = validateBeforeApproval(call, session, ctx, taskContract);
            if (preApprovalValidation != null) {
                TurnAuditCapture.recordToolCall(
                        call.toolName(), path == null ? "" : path, false,
                        preApprovalBlockReason(call, preApprovalValidation));
                LocalTurnTraceCapture.recordToolCallBlocked(
                        tracePhase,
                        call,
                        preApprovalBlockReason(call, preApprovalValidation));
                if (commandTool) {
                    String reason = preApprovalBlockReason(call, preApprovalValidation);
                    LocalTurnTraceCapture.recordCommandPolicyDecision(
                            tracePhase, call, "DENY", "PRE_APPROVAL_VALIDATION");
                    LocalTurnTraceCapture.recordCommandDenied(tracePhase, call, reason);
                }
                return preApprovalValidation;
            }
        }

        if (commandTool) {
            try {
                CommandPlan commandPlan = CommandToolPlanner.planGradleV1(
                        call,
                        session.workspace(),
                        dev.talos.runtime.command.CommandProfileRegistry.defaultRegistry());
                LocalTurnTraceCapture.recordCommandPlanCreated(tracePhase, call, commandPlan);
            } catch (Exception e) {
                String reason = CommandToolPlanner.invalidMessage(e.getMessage());
                LocalTurnTraceCapture.recordCommandPolicyDecision(
                        tracePhase, call, "DENY", "PLAN_REJECTED");
                LocalTurnTraceCapture.recordCommandDenied(tracePhase, call, reason);
                return ToolResult.fail(ToolError.invalidParams(reason));
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

        PermissionDecision permissionDecision = permissionPolicy.decide(new PermissionRequest(
                session.workspace(),
                session.config(),
                call,
                risk,
                ctx.executionPhaseState() == null ? null : ctx.executionPhaseState().phase()));

        // Scope-guard override: if the target looks off-scope, the user
        // MUST see the warning before the call runs. A remembered or configured
        // ALLOW would otherwise silently bypass the warning — exactly the failure
        // class the guard exists to catch.
        if (scopeWarning != null && permissionDecision.action() == PermissionAction.ALLOW) {
            permissionDecision = permissionDecision.forceAsk(
                    "SCOPE_WARNING_ASK",
                    "Scope warning requires approval before running " + call.toolName() + ".");
        }

        LocalTurnTraceCapture.recordPermissionDecision(
                tracePhase,
                call,
                permissionDecision.action().name(),
                permissionDecision.reasonCode(),
                permissionDecision.relativePath(),
                permissionDecision.protectedPath(),
                permissionDecision.rememberEligible());
        if (commandTool) {
            LocalTurnTraceCapture.recordCommandPolicyDecision(
                    tracePhase,
                    call,
                    permissionDecision.action().name(),
                    permissionDecision.reasonCode());
        }

        if (permissionDecision.action() == PermissionAction.DENY) {
            if (risk.requiresApproval()) {
                TurnAuditCapture.recordApprovalDenied();
                LocalTurnTraceCapture.recordApprovalDenied(tracePhase, call);
                if (commandTool) {
                    LocalTurnTraceCapture.recordCommandApprovalDenied(tracePhase, call);
                }
            }
            TurnAuditCapture.recordToolCall(
                    call.toolName(), path == null ? "" : path, false,
                    "permission policy denied " + call.toolName()
                            + " (" + permissionDecision.reasonCode() + ")");
            if (commandTool) {
                LocalTurnTraceCapture.recordCommandDenied(
                        tracePhase,
                        call,
                        "Permission policy denied " + call.toolName()
                                + " (" + permissionDecision.reasonCode() + ")");
            }
            return ToolResult.fail(ToolError.denied(
                    "Permission policy denied the " + call.toolName()
                            + " call. " + permissionDecision.userMessage()));
        }

        if (permissionDecision.action() == PermissionAction.ASK) {
            TurnAuditCapture.recordApprovalRequired();
            LocalTurnTraceCapture.recordApprovalRequired(tracePhase, call);
            if (commandTool) {
                LocalTurnTraceCapture.recordCommandApprovalRequired(tracePhase, call);
            }

            String desc = approvalDescription(call, risk, permissionDecision);
            String detail = buildApprovalDetail(
                    call,
                    path,
                    scopeWarning,
                    permissionDecision.userMessage(),
                    session.workspace(),
                    session.config());
            ApprovalResponse response = approvalGate.approveFull(desc, detail);

            if (response == ApprovalResponse.DENIED) {
                TurnAuditCapture.recordApprovalDenied();
                LocalTurnTraceCapture.recordApprovalDenied(tracePhase, call);
                if (commandTool) {
                    LocalTurnTraceCapture.recordCommandApprovalDenied(tracePhase, call);
                    LocalTurnTraceCapture.recordCommandDenied(
                            tracePhase,
                            call,
                            "User did not approve " + call.toolName());
                }
                TurnAuditCapture.recordToolCall(
                        call.toolName(), path == null ? "" : path, false,
                        "approval denied by user for " + call.toolName());
                // Phrasing matters: previously "Operation denied by user" caused
                // qwen2.5-coder to hallucinate a "permissions" excuse and tell
                // the user to "ensure you have the necessary permissions" — the
                // word "denied" anchored the wrong narrative. Reshape the error
                // so the model interprets it as user intent, not auth failure.
                String targetContext = approvalDeniedTargetContext(permissionDecision);
                return ToolResult.fail(ToolError.denied(
                        "User did not approve the " + call.toolName()
                                + " call." + targetContext
                                + " The user is in control of the workspace; "
                                + "ask what they want to do differently before retrying, "
                                + "or take a different action that does not need approval."));
            }

            // Approved — record and optionally propagate the remember choice.
            TurnAuditCapture.recordApprovalGranted();
            LocalTurnTraceCapture.recordApprovalGranted(tracePhase, call);
            if (commandTool) {
                LocalTurnTraceCapture.recordCommandApprovalGranted(tracePhase, call);
            }
            if (response == ApprovalResponse.APPROVED_REMEMBER
                    && permissionDecision.rememberEligible()) {
                approvalPolicy.rememberApproval(session.workspace(), call, risk);
            }
        } else if (risk.requiresApproval()) {
            // AUTO_ALLOW by policy for a mutating call.
            TurnAuditCapture.recordApprovalGranted();
            LocalTurnTraceCapture.recordApprovalGranted(tracePhase, call);
            if (commandTool) {
                LocalTurnTraceCapture.recordCommandApprovalGranted(tracePhase, call);
            }
        }

        if (ToolCallSupport.isMutatingTool(call.toolName())) {
            CheckpointCaptureResult checkpoint = captureCheckpointBeforeMutation(session, call);
            LocalTurnTraceCapture.recordCheckpoint(
                    checkpoint.status(),
                    checkpoint.checkpointId(),
                    checkpoint.message(),
                    checkpoint.capturedFiles());
            if (!checkpoint.success()) {
                TurnAuditCapture.recordToolCall(
                        call.toolName(), path == null ? "" : path, false,
                        "checkpoint failed before " + call.toolName());
                return ToolResult.fail(ToolError.internal(
                        "Required checkpoint failed before mutation: " + checkpoint.message()));
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
                    call.toolName(), SafeLogFormatter.throwableMessage(e));
            LOG.debug("Tool execution exception stack trace suppressed; sanitized reason={}",
                    SafeLogFormatter.throwableMessage(e));
            result = ToolResult.fail(ToolError.internal(
                    "Tool execution failed unexpectedly: "
                            + e.getClass().getSimpleName() + ": " + SafeLogFormatter.throwableMessage(e)));
        }
        if (result.success()) {
            TurnAuditCapture.recordToolCall(
                    call.toolName(),
                    auditPathHints(call, path),
                    true,
                    "");
        } else {
            TurnAuditCapture.recordToolCall(
                    call.toolName(),
                    path == null ? "" : path,
                    false,
                    toolFailureReason(result));
        }
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

    private static ToolRiskLevel effectiveRisk(ToolRiskLevel descriptorRisk, ToolCall call) {
        ToolRiskLevel risk = descriptorRisk == null ? ToolRiskLevel.READ_ONLY : descriptorRisk;
        if (call == null || !WorkspaceOperationPlanner.isWorkspaceOperationTool(call.toolName())) {
            return risk;
        }
        try {
            Optional<WorkspaceOperationPlan> plan = WorkspaceOperationPlanner.checkpointPlan(call);
            if (plan.isPresent() && plan.get().riskLevel() == ToolRiskLevel.DESTRUCTIVE) {
                return ToolRiskLevel.DESTRUCTIVE;
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid operation payloads are handled by normal pre-approval validation.
        }
        return risk;
    }

    /**
     * Resolve the target path from a tool call, trying common parameter name variants.
     * Used for the approval gate display — even when the model uses non-canonical
     * parameter names (e.g. {@code file_path} instead of {@code path}).
     */
    private static String resolvePathParam(ToolCall call) {
        for (String key : pathParameterKeys()) {
            String value = call.param(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static List<String> auditPathHints(ToolCall call, String fallbackPath) {
        if (call != null && WorkspaceOperationPlanner.isWorkspaceOperationTool(call.toolName())) {
            try {
                Optional<WorkspaceOperationPlan> plan = WorkspaceOperationPlanner.checkpointPlan(call);
                if (plan.isPresent()) {
                    List<String> changedPaths = plan.get().changedPaths();
                    if (!changedPaths.isEmpty()) return changedPaths;
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid operation payloads are handled before successful audit recording.
            }
        }
        return fallbackPath == null || fallbackPath.isBlank() ? List.of() : List.of(fallbackPath);
    }

    private CheckpointCaptureResult captureCheckpointBeforeMutation(Session session, ToolCall call) {
        Optional<WorkspaceOperationPlan> operationPlan = WorkspaceOperationPlanner.checkpointPlan(call);
        if (operationPlan.isPresent()) {
            return checkpointService.captureBeforeOperation(
                    session.workspace(),
                    session.config(),
                    operationPlan.get(),
                    LocalTurnTraceCapture.currentTraceId(),
                    LocalTurnTraceCapture.currentTurnNumber());
        }
        return checkpointService.captureBeforeMutation(
                session.workspace(),
                session.config(),
                call,
                LocalTurnTraceCapture.currentTraceId(),
                LocalTurnTraceCapture.currentTurnNumber());
    }

    private static ToolResult validateBeforeApproval(
            ToolCall call,
            Session session,
            Context ctx,
            TaskContract taskContract
    ) {
        ToolResult sandboxPathValidation = validateSandboxPathBeforeApproval(call, session, ctx);
        if (sandboxPathValidation != null) {
            return sandboxPathValidation;
        }

        ToolResult forbiddenTargetValidation = validateForbiddenTargetBeforeApproval(call, taskContract);
        if (forbiddenTargetValidation != null) {
            return forbiddenTargetValidation;
        }

        ToolResult workspaceOrganizationValidation =
                validateWorkspaceOrganizationToolBeforeApproval(call, taskContract);
        if (workspaceOrganizationValidation != null) {
            return workspaceOrganizationValidation;
        }

        ToolResult expectedTargetValidation = validateExpectedTargetBeforeApproval(call, taskContract);
        if (expectedTargetValidation != null) {
            return expectedTargetValidation;
        }

        Optional<String> workspaceOperationValidation =
                WorkspaceOperationPlanner.validateBeforeApproval(call);
        if (workspaceOperationValidation.isPresent()) {
            return ToolResult.fail(ToolError.invalidParams(workspaceOperationValidation.get()));
        }

        Optional<String> commandValidation =
                CommandToolPlanner.validateBeforeApproval(call, session.workspace());
        if (commandValidation.isPresent()) {
            return ToolResult.fail(ToolError.invalidParams(commandValidation.get()));
        }

        if (isWriteFileTool(call.toolName())) {
            String path = resolveParam(call, "path", "file_path", "filepath", "file", "filename");
            if (path == null || path.isBlank()) {
                return ToolResult.fail(ToolError.invalidParams(
                        "Invalid talos.write_file call: missing required parameter `path`. "
                                + "No approval was requested and no file was changed."));
            }

            String content = resolveParam(call, "content", "text", "body", "data", "file_content");
            if (content == null) {
                return ToolResult.fail(ToolError.invalidParams(
                        "Invalid talos.write_file call: missing required parameter `content`. "
                                + "No approval was requested and no file was changed."));
            }

            ToolResult unsupportedDocumentValidation = validateUnsupportedDocumentWriteBeforeApproval(path);
            if (unsupportedDocumentValidation != null) {
                return unsupportedDocumentValidation;
            }

            return null;
        }

        if (!isEditFileTool(call.toolName())) {
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

    private static ToolResult validateUnsupportedDocumentWriteBeforeApproval(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            Path candidate = Path.of(path);
            if (!UnsupportedDocumentFormats.isUnsupported(candidate)) return null;
            return ToolResult.fail(ToolError.unsupportedFormat(
                    UnsupportedDocumentFormats.writeCapabilityMessage(candidate)));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static ToolResult validateWorkspaceOrganizationToolBeforeApproval(
            ToolCall call,
            TaskContract taskContract) {
        if (call == null
                || taskContract == null
                || taskContract.type() != TaskType.FILE_EDIT
                || taskContract.expectedTargets().isEmpty()
                || !isWorkspaceOrganizationTool(call.toolName())) {
            return null;
        }
        if (WorkspaceOperationIntent.detect(taskContract).isPresent()) {
            return null;
        }
        return ToolResult.fail(ToolError.invalidParams(
                "Workspace organization tool `" + call.toolName()
                        + "` is not allowed for this narrow file-edit task. "
                        + "Use talos.edit_file or talos.write_file for the expected target(s): "
                        + String.join(", ", orderedExpectedTargets(taskContract))
                        + ". No approval was requested and no file was changed."));
    }

    private static ToolResult validateExpectedTargetBeforeApproval(ToolCall call, TaskContract taskContract) {
        if (call == null
                || taskContract == null
                || !taskContract.mutationAllowed()
                || taskContract.expectedTargets().isEmpty()
                || !ToolCallSupport.isMutatingTool(call.toolName())) {
            return null;
        }
        String path = resolveParam(call, "path", "file_path", "filepath", "file", "filename", "target");
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String expected : taskContract.expectedTargets()) {
            if (sameExpectedTarget(call.toolName(), path, expected)) {
                return null;
            }
        }
        return ToolResult.fail(ToolError.invalidParams(
                "Target outside expected targets before approval: `" + path
                        + "` is outside the current expected target set: "
                        + String.join(", ", orderedExpectedTargets(taskContract))
                        + ". Similar filenames are not substitutes for required target paths. "
                        + "No approval was requested and no file was changed."));
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

    private static ToolResult validateForbiddenTargetBeforeApproval(ToolCall call, TaskContract taskContract) {
        if (call == null || taskContract == null || taskContract.forbiddenTargets().isEmpty()) {
            return null;
        }
        if (!ToolCallSupport.isMutatingTool(call.toolName())) {
            return null;
        }
        List<PathParam> params = pathParams(call);
        if (params.isEmpty()) {
            return null;
        }
        for (PathParam param : params) {
            for (String forbidden : taskContract.forbiddenTargets()) {
                if (sameScopedTarget(param.value(), forbidden)) {
                    return ToolResult.fail(ToolError.invalidParams(
                            "Target forbidden before approval: `" + param.value()
                                    + "` was explicitly excluded by the user's current request. "
                                    + "No approval was requested and no file was changed."));
                }
            }
        }
        return null;
    }

    private static List<PathParam> pathParams(ToolCall call) {
        var params = new java.util.ArrayList<PathParam>();
        if ("apply_workspace_batch".equals(ToolAliasPolicy.localCanonicalName(call.toolName()))) {
            for (String value : WorkspaceBatchPlanParser.pathValues(call)) {
                if (value != null && !value.isBlank()) {
                    params.add(new PathParam("operations_json", value));
                }
            }
        }
        for (String key : pathParameterKeys()) {
            String value = call.param(key);
            if (value != null && !value.isBlank()) {
                params.add(new PathParam(key, value));
            }
        }
        return params;
    }

    private static List<String> pathParameterKeys() {
        return List.of(
                "path", "file_path", "filepath", "file", "filename",
                "from", "to", "source", "source_path", "src",
                "destination", "destination_path", "dest", "target",
                "dir", "directory");
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
        if (message != null && message.startsWith("Target forbidden before approval")) {
            return "forbidden target before approval"
                    + (message.isBlank() ? "" : ": " + shortReason(message));
        }
        if (message != null && message.startsWith("Target outside expected targets before approval")) {
            return "expected target scope before approval"
                    + (message.isBlank() ? "" : ": " + shortReason(message));
        }
        if (isEditFileTool(name)) {
            return "invalid edit args before approval"
                    + (message == null || message.isBlank() ? "" : ": " + shortReason(message));
        }
        if (isWriteFileTool(name)) {
            return "invalid write args before approval"
                    + (message == null || message.isBlank() ? "" : ": " + shortReason(message));
        }
        return "invalid tool args before approval"
                + (message == null || message.isBlank() ? "" : ": " + shortReason(message));
    }

    private static String approvalDescription(
            ToolCall call,
            ToolRiskLevel risk,
            PermissionDecision permissionDecision
    ) {
        String toolName = call == null ? "unknown tool" : call.toolName();
        if (permissionDecision != null
                && permissionDecision.protectedPath()
                && isReadFileTool(toolName)) {
            return "protected read: " + toolName;
        }
        return (risk == null ? ToolRiskLevel.READ_ONLY : risk)
                .name()
                .toLowerCase()
                .replace('_', ' ')
                + " operation: " + toolName;
    }

    private static String approvalDeniedTargetContext(PermissionDecision permissionDecision) {
        if (permissionDecision == null) return "";
        String relativePath = permissionDecision.relativePath();
        if (relativePath == null || relativePath.isBlank()) return "";
        return " Target path: `" + relativePath.strip() + "`.";
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

    private static boolean isWriteFileTool(String toolName) {
        String normalized = normalizeToolName(toolName);
        return "write_file".equals(normalized)
                || "file_write".equals(normalized)
                || "writefile".equals(normalized)
                || "create_file".equals(normalized)
                || "file_create".equals(normalized)
                || "createfile".equals(normalized);
    }

    private static boolean isEditFileTool(String toolName) {
        String normalized = normalizeToolName(toolName);
        return "edit_file".equals(normalized)
                || "file_edit".equals(normalized)
                || "editfile".equals(normalized);
    }

    private static boolean isReadFileTool(String toolName) {
        String normalized = normalizeToolName(toolName);
        return "read_file".equals(normalized)
                || "fileread".equals(normalized)
                || "readfile".equals(normalized);
    }

    private static boolean isListDirTool(String toolName) {
        String normalized = normalizeToolName(toolName);
        return "list_dir".equals(normalized)
                || "list_directory".equals(normalized)
                || "dir_list".equals(normalized)
                || "ls".equals(normalized)
                || "listdir".equals(normalized)
                || "listdirectory".equals(normalized);
    }

    private static ToolResult rejectIfOutsideCurrentToolSurface(
            Context ctx,
            ToolCall call,
            String canonicalToolName,
            String tracePhase
    ) {
        if (ctx == null || ctx.nativeToolSpecs() == null) return null;
        List<String> allowed = ctx.nativeToolSpecs().stream()
                .filter(Objects::nonNull)
                .map(ToolSpec::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted()
                .toList();
        if (allowed.contains(canonicalToolName)) return null;

        String requested = canonicalToolName == null || canonicalToolName.isBlank()
                ? call.toolName()
                : canonicalToolName;
        String allowedText = allowed.isEmpty() ? "(none)" : String.join(", ", allowed);
        LocalTurnTraceCapture.recordToolCallBlocked(
                tracePhase,
                call,
                "current-turn tool surface denied " + requested + "; allowed: " + allowedText);
        return ToolResult.fail(ToolError.denied(
                "Current-turn tool surface did not allow " + requested
                        + ". Allowed tools: " + allowedText + "."));
    }

    private static boolean isMkdirTool(String toolName) {
        String normalized = normalizeToolName(toolName);
        return "mkdir".equals(normalized)
                || "make_dir".equals(normalized)
                || "make_directory".equals(normalized)
                || "create_dir".equals(normalized)
                || "create_directory".equals(normalized);
    }

    private static boolean isWorkspaceOrganizationTool(String toolName) {
        return switch (normalizeToolName(toolName)) {
            case "apply_workspace_batch", "copy_path", "move_path", "rename_path", "delete_path" -> true;
            default -> false;
        };
    }

    private static String normalizeToolName(String toolName) {
        return ToolAliasPolicy.localCanonicalName(toolName);
    }

    private static boolean sameScopedTarget(String candidate, String forbidden) {
        String c = normalizeScopedTarget(candidate);
        String f = normalizeScopedTarget(forbidden);
        if (c.isBlank() || f.isBlank()) return false;
        return c.equals(f) || c.endsWith("/" + f);
    }

    private static boolean sameExpectedTarget(String toolName, String candidate, String expected) {
        String c = normalizeScopedTarget(candidate);
        String e = normalizeScopedTarget(expected);
        if (c.isBlank() || e.isBlank()) return false;
        return c.equals(e) || (isMkdirTool(toolName) && e.startsWith(c + "/"));
    }

    private static List<String> orderedExpectedTargets(TaskContract taskContract) {
        if (taskContract == null || taskContract.expectedTargets().isEmpty()) return List.of();
        return taskContract.expectedTargets().stream()
                .map(TurnProcessor::normalizeScopedTarget)
                .filter(path -> !path.isBlank())
                .sorted()
                .toList();
    }

    private static String normalizeScopedTarget(String path) {
        if (path == null) return "";
        String normalized = ToolCallSupport.normalizePath(path)
                .strip()
                .replaceAll("[`'\"),.;:!?\\]]+$", "");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        return normalized.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Build a detailed approval message for write/edit operations.
     * Shows the target path, content size/line count, and a preview
     * of the first few lines so the user can make an informed decision.
     *
     * <p>If a {@code scopeWarning} is present, it is prepended on its own
     * line so the user sees the scope concern before the approval choice.
     */
    private static String buildApprovalDetail(
            ToolCall call,
            String path,
            String scopeWarning,
            String permissionMessage,
            java.nio.file.Path workspace,
            dev.talos.core.Config cfg
    ) {
        var sb = new StringBuilder();

        if (permissionMessage != null && !permissionMessage.isBlank()) {
            String safePermissionMessage = sanitizeApprovalText(permissionMessage.strip());
            sb.append("permission: ")
                    .append(safePermissionMessage)
                    .append('\n');
            sb.append("    ");
        }

        if (scopeWarning != null && !scopeWarning.isBlank()) {
            sb.append("warning: ")
                    .append(sanitizeApprovalText(scopeWarning))
                    .append('\n');
            sb.append("    ");
        }

        if (CommandToolPlanner.isRunCommandTool(call.toolName())) {
            try {
                sb.append(ProtectedContentPolicy.sanitizeText(
                        CommandToolPlanner.approvalDetail(call, workspace)));
            } catch (RuntimeException e) {
                sb.append("command: invalid talos.run_command request");
            }
        } else if (path != null && !path.isBlank()) {
            boolean protectedPath = ProtectedPathPolicy.classify(workspace, path).protectedPath()
                    || ProtectedContentPolicy.looksProtectedPathString(path);
            sb.append("target: ").append(path);
            if (isReadFileTool(call.toolName()) && protectedPath) {
                sb.append("\n    ").append(ProtectedReadScopePolicy.approvedProtectedReadModelHandoffNote(cfg));
            }
        } else if ("apply_workspace_batch".equals(ToolAliasPolicy.localCanonicalName(call.toolName()))) {
            try {
                WorkspaceBatchPlanParser.parse(call)
                        .ifPresentOrElse(
                                plan -> sb.append("batch: ").append(plan.previewSummary()),
                                () -> sb.append("batch: missing operations_json"));
            } catch (IllegalArgumentException e) {
                sb.append("batch: invalid operations_json");
            }
        } else {
            sb.append("(warning: no target path specified - may fail)");
        }

        // For write_file: show content size and preview
        String content = resolveParam(call, "content", "text", "body", "data", "file_content");

        if (content != null && !content.isEmpty()) {
            long bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            long lines = content.chars().filter(c -> c == '\n').count() + 1;
            sb.append(" (").append(bytes).append(" bytes, ").append(lines).append(" lines)");

            // Show first 5 lines as preview
            String[] contentLines = content.split("\n", 7);
            int previewCount = Math.min(5, contentLines.length);
            sb.append("\n    preview:");
            for (int i = 0; i < previewCount; i++) {
                String line = ProtectedContentPolicy.sanitizeText(contentLines[i]);
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
            oldStr = ProtectedContentPolicy.sanitizeText(oldStr);
            newStr = ProtectedContentPolicy.sanitizeText(newStr);
            String oldPreview = oldStr.length() > 60 ? oldStr.substring(0, 57) + "..." : oldStr;
            String newPreview = newStr.length() > 60 ? newStr.substring(0, 57) + "..." : newStr;
            sb.append("\n    replace: ").append(oldPreview.replace("\n", "\\n"));
            sb.append("\n    with:    ").append(newPreview.replace("\n", "\\n"));
        }

        return sb.toString();
    }

    private static String sanitizeApprovalText(String text) {
        return ProtectedContentPolicy.sanitizeText(text);
    }

    private record PathParam(String name, String value) { }
}

