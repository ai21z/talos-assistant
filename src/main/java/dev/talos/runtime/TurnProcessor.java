package dev.talos.runtime;

import dev.talos.core.ingest.UnsupportedDocumentFormats;
import dev.talos.core.security.NoFollowPathPolicy;
import dev.talos.core.util.TextReplacement;
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
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.workspace.WorkspaceBatchPlanParser;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.runtime.workspace.WorkspaceOperationPlanner;
import dev.talos.spi.types.ToolSpec;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * directly by the command registry - this only processes prompts.
 */
public final class TurnProcessor {

    @FunctionalInterface
    interface TerminalizerFactory {
        TurnTerminalizer create(int turnNumber, long startNanos);
    }

    private static final Logger LOG = LoggerFactory.getLogger(TurnProcessor.class);

    private final TurnRouter modes;
    private final ApprovalGate approvalGate;
    private final ApprovalPolicy approvalPolicy;
    private final dev.talos.runtime.policy.PermissionPolicy permissionPolicy;
    private final CheckpointService checkpointService;
    private final dev.talos.runtime.command.CommandProfileRegistry commandProfiles;
    private final ToolRegistry toolRegistry;
    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final ThreadLocal<TurnPublicationHealth> publicationHealth = new ThreadLocal<>();
    private final TerminalizerFactory terminalizerFactory;
    private dev.talos.runtime.observation.TurnRequestFrame.ControlledContracts controlledContracts;

    /** Explicit wiring is provided for controlled boundary experiments, not model interpretation. */
    public TurnProcessor withControlledContracts(
            dev.talos.runtime.observation.TurnRequestFrame.ControlledContracts source) {
        var processor = new TurnProcessor(modes, approvalGate, toolRegistry, approvalPolicy,
                checkpointService, commandProfiles, terminalizerFactory);
        processor.controlledContracts = Objects.requireNonNull(source);
        return processor;
    }

    /**
     * Primary constructor. All policy parameters are required - the caller
     * must pass an explicit {@link ApprovalGate}, {@link ToolRegistry}, and
     * {@link ApprovalPolicy}. Pass {@link NoOpApprovalGate} /
     * {@link ApprovalPolicy#ALWAYS_ASK} explicitly if you want the default
     * no-op policy; silent null-to-NoOp substitution is no longer supported
     * at this seam (CCR-016).
     *
     * <p>The convenience constructors below still provide explicit
     * {@code NoOpApprovalGate} / {@link ApprovalPolicy#ALWAYS_ASK} defaults
     * for tests and ad-hoc call sites - those are explicit wiring, not
     * policy-by-null.
     */
    public TurnProcessor(TurnRouter modes, ApprovalGate approvalGate,
                         ToolRegistry toolRegistry, ApprovalPolicy approvalPolicy) {
        this(modes, approvalGate, toolRegistry, approvalPolicy, new CheckpointService());
    }

    public TurnProcessor(TurnRouter modes, ApprovalGate approvalGate,
                         ToolRegistry toolRegistry, ApprovalPolicy approvalPolicy,
                         CheckpointService checkpointService) {
        this(modes, approvalGate, toolRegistry, approvalPolicy, checkpointService,
                dev.talos.runtime.command.CommandProfileRegistry.defaultRegistry());
    }

    public TurnProcessor(TurnRouter modes, ApprovalGate approvalGate,
                         ToolRegistry toolRegistry, ApprovalPolicy approvalPolicy,
                         CheckpointService checkpointService,
                         dev.talos.runtime.command.CommandProfileRegistry commandProfiles) {
        this(
                modes,
                approvalGate,
                toolRegistry,
                approvalPolicy,
                checkpointService,
                commandProfiles,
                TurnTerminalizer::new);
    }

    TurnProcessor(
            TurnRouter modes,
            ApprovalGate approvalGate,
            ToolRegistry toolRegistry,
            ApprovalPolicy approvalPolicy,
            CheckpointService checkpointService,
            dev.talos.runtime.command.CommandProfileRegistry commandProfiles,
            TerminalizerFactory terminalizerFactory
    ) {
        this.modes = modes;
        this.approvalGate = Objects.requireNonNull(approvalGate,
                "approvalGate must not be null - pass NoOpApprovalGate() explicitly "
                        + "to keep the no-op policy (CCR-016)");
        this.toolRegistry = Objects.requireNonNull(toolRegistry,
                "toolRegistry must not be null - pass a new ToolRegistry() explicitly");
        this.approvalPolicy = Objects.requireNonNull(approvalPolicy,
                "approvalPolicy must not be null - pass ApprovalPolicy.ALWAYS_ASK explicitly");
        this.permissionPolicy = new DeclarativePermissionPolicy(this.approvalPolicy);
        this.checkpointService = Objects.requireNonNull(checkpointService,
                "checkpointService must not be null");
        this.commandProfiles = Objects.requireNonNullElseGet(commandProfiles,
                dev.talos.runtime.command.CommandProfileRegistry::defaultRegistry);
        this.terminalizerFactory = Objects.requireNonNull(
                terminalizerFactory, "turn terminalizer factory");
    }

    public TurnProcessor(TurnRouter modes, ApprovalGate approvalGate, ToolRegistry toolRegistry) {
        this(modes, approvalGate, toolRegistry, ApprovalPolicy.ALWAYS_ASK);
    }

    public TurnProcessor(TurnRouter modes, ApprovalGate approvalGate) {
        this(modes, approvalGate, new ToolRegistry(), ApprovalPolicy.ALWAYS_ASK);
    }

    public TurnProcessor(TurnRouter modes) {
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

    /** Consume process-local evidence-publication health for the calling turn. */
    public TurnPublicationHealth consumePublicationHealth() {
        TurnPublicationHealth health = publicationHealth.get();
        publicationHealth.remove();
        return health;
    }

    /**
     * Process a single user prompt through the mode system.
     *
     * <p>Every allocated turn is terminalized before its thread-local captures
     * are cleared. Settlement-aware listeners receive the frozen envelope;
     * legacy completion listeners receive ordinary completions only. Failed
     * and no-result settlements receive a blank listener input so they cannot
     * enter conversational memory accidentally.
     *
     * <p>Route and terminalization exceptions are rethrown to the caller
     * (typically {@code ExecutionPipeline}) after bounded local evidence is
     * published. That caller continues to own the user-facing error envelope,
     * redaction, and coarse audit logging.
     *
     * @param session   the active session
     * @param userInput raw user input (not a colon-command)
     * @param ctx       runtime context (rag, llm, sandbox, etc.)
     * @return a TurnResult, or null if no mode handled the input
     * @throws Exception if mode dispatch or terminalization fails after evidence settlement
     */
    @SuppressWarnings("resource") // RuntimeTurnContext-owned LlmClient is borrowed for metadata, not closed per turn.
    public TurnResult process(Session session, String userInput, RuntimeTurnContext ctx) throws Exception {
        if (userInput == null || userInput.isBlank()) {
            return null;
        }

        publicationHealth.remove();
        clearCapturedTurnState();
        int turn = session.nextTurn();
        long startNanos = System.nanoTime();
        Throwable primaryFailure = null;
        TurnTerminalizer terminalizer;
        try {
            terminalizer = Objects.requireNonNull(
                    terminalizerFactory.create(turn, startNanos),
                    "turn terminalizer factory returned null");
        } catch (Throwable constructionFailure) {
            primaryFailure = constructionFailure;
            terminalizer = new TurnTerminalizer(turn, startNanos);
        }

        String traceId;
        try {
            traceId = LocalTurnTraceCapture.newTraceId();
        } catch (RuntimeException identityFailure) {
            if (primaryFailure == null) primaryFailure = identityFailure;
            traceId = "trc-fallback-" + Integer.toUnsignedString(turn)
                    + "-" + Long.toUnsignedString(startNanos);
        }
        try {
            TurnUserRequestCapture.set(userInput);
            TurnAuditCapture.begin();
            PromptDebugCapture.beginTurn();
        } catch (RuntimeException captureFailure) {
            if (primaryFailure == null) primaryFailure = captureFailure;
        }
        String traceTimestamp;
        TurnPublicationHealth terminalizationHealth = null;
        try {
            traceTimestamp = java.time.Instant.now().toString();
        } catch (RuntimeException clockFailure) {
            traceTimestamp = "";
            if (primaryFailure == null) primaryFailure = clockFailure;
        }
        String sessionId = "";

        // Deliberately the bare workspace hash, NOT the per-run session
        // instance id (T799): this value only identifies the workspace
        // inside trace metadata. The storage key for turn logs and trace
        // files is owned by JsonTurnLogAppender's injected id.
        TurnSettlement settlement = null;
        TurnExecutionStage failedAt = TurnExecutionStage.TURN_INITIALIZATION;
        Optional<Result> routed = null;
        boolean traceStarted = false;
        dev.talos.runtime.observation.TurnRequestFrame requestFrame = null;
        try {
            // Begin every turn-owned capture before mode/provider preparation.
            // The local trace owns the stage marker and therefore its lifetime.
            sessionId = JsonSessionStore.sessionIdFor(session.workspace());
            LocalTurnTraceCapture.begin(
                    traceId,
                    sessionId,
                    turn,
                    traceTimestamp,
                    sessionId,
                    "unknown",
                    "",
                    "",
                    userInput);
            traceStarted = traceId.equals(LocalTurnTraceCapture.currentTraceId());
            if (!traceStarted) {
                throw new IllegalStateException("turn trace identity did not bind");
            }
            if (primaryFailure == null) {
                String model = ctx != null && ctx.llm() != null ? ctx.llm().getModel() : "";
                String traceMode = modes == null ? "unknown" : modes.traceMode(userInput);
                LocalTurnTraceCapture.updateRuntimeMetadata(
                        traceMode, modelBackend(model), modelName(model));
                Path ws = session.workspace();
                requestFrame = dev.talos.runtime.observation.TurnRequestFrame.open(
                        userInput, ws, turn, controlledContracts);
                ctx = ctx.withRequestFrame(requestFrame);
                ctx = ctx.prepareRequestPins(userInput, ws);
                LocalTurnTraceCapture.enterExecutionStage(TurnExecutionStage.MODE_ROUTING);
                routed = Objects.requireNonNull(
                        modes.route(userInput, ws, ctx),
                        "turn router returned null result");
            }
        } catch (Throwable failure) {
            if (primaryFailure == null) primaryFailure = failure;
            traceStarted = traceId.equals(LocalTurnTraceCapture.currentTraceId());
            failedAt = traceStarted
                    ? LocalTurnTraceCapture.currentExecutionStage()
                    : TurnExecutionStage.TURN_INITIALIZATION;
        }

        // Some filesystem and channel operations consume the interrupted bit.
        // Publish the already-observed failure with the bit temporarily clear,
        // then restore it for the caller on every terminal path. The bit is
        // preservation state only; typed causality still comes from the caught
        // throwable graph.
        boolean restoreInterruptAfterSettlement = Thread.interrupted();
        try {
        try {
            if (primaryFailure != null) {
                settlement = traceStarted
                        && traceId.equals(LocalTurnTraceCapture.currentTraceId())
                        ? terminalizer.fail(primaryFailure, failedAt, ctx)
                        : terminalizer.failWithoutActiveTrace(
                                primaryFailure,
                                TurnExecutionStage.TURN_INITIALIZATION,
                                ctx,
                                traceId,
                                sessionId,
                                traceTimestamp,
                                userInput);
            } else if (routed.isEmpty()) {
                settlement = terminalizer.noResult(ctx);
            } else {
                LocalTurnTraceCapture.enterExecutionStage(TurnExecutionStage.TURN_FINALIZATION);
                settlement = terminalizer.complete(routed.get(), ctx);
            }
        } catch (Throwable terminalFailure) {
            Throwable evidenceFailure = primaryFailure == null
                    ? terminalFailure
                    : primaryFailure;
            TurnExecutionStage evidenceStage = primaryFailure != null
                    ? failedAt
                    : routed != null && routed.isEmpty()
                            ? TurnExecutionStage.MODE_ROUTING
                            : TurnExecutionStage.TURN_FINALIZATION;
            try {
                settlement = terminalizer.recoverAfterTerminalizationFailure(
                        evidenceFailure,
                        evidenceStage,
                        terminalFailure,
                        traceId,
                        sessionId,
                        traceTimestamp);
            } catch (Throwable recoveryFailure) {
                terminalizationHealth = TurnPublicationHealth.combine(
                        TurnPublicationHealth.internalFailure(
                                terminalFailure.getClass().getName()),
                        TurnPublicationHealth.internalFailure(
                                recoveryFailure.getClass().getName()));
            }
            if (primaryFailure == null) {
                primaryFailure = terminalFailure;
            }
            if (terminalizationHealth == null) {
                terminalizationHealth = TurnPublicationHealth.internalFailure(
                        terminalFailure.getClass().getName());
            }
        } finally {
            // The terminal settlement is frozen before any thread-local is
            // cleared; listeners run only after cleanup so their own provider
            // activity cannot contaminate this turn's trace.
            if (requestFrame == null) clearCapturedTurnState();
            else requestFrame.finish(TurnProcessor::clearCapturedTurnState);
        }

        Throwable publicationFailure = null;
        if (settlement != null) {
            try {
                terminalizationHealth = TurnPublicationHealth.combine(
                        terminalizationHealth,
                        publishSettlement(
                                settlement,
                                userInput,
                                primaryFailure != null
                                        || !settlement.ordinaryCompletion()));
            } catch (Throwable listenerError) {
                terminalizationHealth = TurnPublicationHealth.combine(
                        terminalizationHealth,
                        TurnPublicationHealth.listenerFailure(
                                listenerError.getClass().getName()));
                publicationFailure = listenerError;
            }
        }
        if (terminalizationHealth != null) {
            publicationHealth.set(terminalizationHealth);
        }

        if (primaryFailure != null) {
            rethrow(primaryFailure);
        }
        if (publicationFailure != null) {
            rethrow(publicationFailure);
        }
        return settlement == null || settlement.kind() == TurnSettlement.Kind.NO_RESULT
                ? null
                : settlement.result();
        } finally {
            if (restoreInterruptAfterSettlement) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private TurnPublicationHealth publishSettlement(
            TurnSettlement settlement,
            String userInput,
            boolean preserveSettledOutcome
    ) {
        String listenerInput = settlement.ordinaryCompletion()
                ? userInput
                : "";
        int completed = 0;
        int skipped = 0;
        int degraded = 0;
        List<TurnPublicationReceipt.Reason> reasons = new ArrayList<>();
        List<String> exceptionClasses = new ArrayList<>();
        for (SessionListener listener : listeners) {
            try {
                TurnPublicationReceipt receipt = listener.onTurnSettled(
                        settlement, listenerInput);
                if (receipt == null) {
                    degraded++;
                    reasons.add(TurnPublicationReceipt.Reason.LISTENER_EXCEPTION);
                } else {
                    switch (receipt.status()) {
                        case COMPLETE -> completed++;
                        case SKIPPED -> skipped++;
                        case DEGRADED -> {
                            degraded++;
                            reasons.add(receipt.reason());
                        }
                    }
                }
            } catch (Exception listenerFailure) {
                degraded++;
                reasons.add(TurnPublicationReceipt.Reason.LISTENER_EXCEPTION);
                exceptionClasses.add(listenerFailure.getClass().getName());
            } catch (Error listenerError) {
                if (!preserveSettledOutcome) throw listenerError;
                degraded++;
                reasons.add(TurnPublicationReceipt.Reason.LISTENER_EXCEPTION);
                exceptionClasses.add(listenerError.getClass().getName());
            }
        }
        return new TurnPublicationHealth(
                completed, skipped, degraded, reasons, exceptionClasses);
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
        throw new RuntimeException(failure);
    }

    private static void clearCapturedTurnState() {
        TurnUserRequestCapture.clear();
        TurnTaskContractCapture.clear();
        TurnSourceEvidenceCapture.clear();
        TurnTraceCapture.clear();
        LocalTurnTraceCapture.clear();
        if (TurnAuditCapture.isActive()) {
            TurnAuditCapture.end();
        }
        PromptDebugCapture.endTurn();
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

    private static String tracePhase(RuntimeTurnContext ctx) {
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
     *   <li>Mutation-intent guard - reject write/edit calls when the original
     *       user prompt did not explicitly request a modification.</li>
     *   <li>Execution phase policy - reject mutating calls outside APPLY.</li>
     *   <li>{@link ScopeGuard} - if the request is web-scoped and the target
     *       looks obviously off-scope, a warning is prepended to the approval
     *       detail so the user sees it at decision time. Posture is warn,
     *       not block.</li>
     *   <li>{@link ApprovalPolicy#decide} - may auto-approve in-workspace
     *       edits (if the user opted in for this session) or deny without
     *       prompting.</li>
     *   <li>{@link ApprovalGate#approveFull} - tri-state gate that can emit
     *       {@link ApprovalResponse#APPROVED_REMEMBER} to record the user's
     *       "yes for this session" preference.</li>
     * </ol>
     *
     * <p>Scope guarding, policy decisions, and approval outcomes are also
     * recorded into the active {@link TurnAuditCapture} bag if one is
     * running on this thread.
    */
    public ToolResult executeTool(Session session, ToolCall call, RuntimeTurnContext ctx) {
        return executeToolWithinStage(() -> executeToolWithDirectAuthority(
                session, call, ctx));
    }

    private ToolResult executeToolWithDirectAuthority(
            Session session,
            ToolCall call,
            RuntimeTurnContext ctx
    ) {
        if (call == null) {
            return ToolResult.fail(ToolError.invalidParams("Tool call is null"));
        }
        if (session == null || ctx == null) {
            return ToolResult.fail(ToolError.invalidParams("Tool execution context is unavailable"));
        }
        LocalTurnTraceCapture.ToolCallAuthorityDecision authority =
                LocalTurnTraceCapture.beginRuntimeToolCallForExecution(
                        tracePhase(ctx),
                        call,
                        LocalTurnTraceCapture.RuntimeToolAuthorityReason
                                .DIRECT_RUNTIME_API);
        LocalTurnTraceCapture.ParsedToolCallPermit permit = authority.permit();
        if (!authority.authorized()) {
            TurnAuditCapture.recordAuthorityRejectedToolCall(call,
                    "Deterministic runtime tool authority is unavailable (" + authority.rejectionReason().name() + ")");
            return ToolResult.fail(ToolError.invalidAuthority(
                    ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID,
                    "Deterministic runtime tool authority is unavailable ("
                            + authority.rejectionReason().name() + ")"));
        }
        if (!LocalTurnTraceCapture.consumeToolCallParsedPermit(permit, call)) {
            TurnAuditCapture.recordAuthorityRejectedToolCall(call,
                    "Tool execution requires current deterministic runtime authority");
            return ToolResult.fail(ToolError.invalidAuthority(
                    ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID,
                    "Tool execution requires current deterministic runtime authority"));
        }
        return executeTool(session, call, ctx, permit);
    }

    /** Executes a call whose one-use parse permit was minted by the tool-loop stage. */
    public ToolResult executeToolAfterParsed(
            Session session,
            ToolCall call,
            RuntimeTurnContext ctx,
            LocalTurnTraceCapture.ParsedToolCallPermit parsedPermit) {
        return executeToolWithinStage(() -> executeToolAfterParsedWithinStage(
                session, call, ctx, parsedPermit));
    }

    private ToolResult executeToolAfterParsedWithinStage(
            Session session,
            ToolCall call,
            RuntimeTurnContext ctx,
            LocalTurnTraceCapture.ParsedToolCallPermit parsedPermit
    ) {
        if (!LocalTurnTraceCapture.consumeToolCallParsedPermit(parsedPermit, call)) {
            TurnAuditCapture.recordAuthorityRejectedToolCall(call,
                    "Tool execution requires the current exact parsed-call authority");
            return ToolResult.fail(ToolError.invalidAuthority(
                    ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID,
                    "Tool execution requires the current exact parsed-call authority"));
        }
        return executeTool(session, call, ctx, parsedPermit);
    }

    private ToolResult executeTool(
            Session session,
            ToolCall call,
            RuntimeTurnContext ctx,
            LocalTurnTraceCapture.ParsedToolCallPermit parsedPermit) {
        if (call == null) {
            return ToolResult.fail(ToolError.invalidParams("Tool call is null"));
        }
        if (session == null || ctx == null) {
            return ToolResult.fail(ToolError.invalidParams("Tool execution context is unavailable"));
        }
        String tracePhase = tracePhase(ctx);
        ToolAliasPolicy.Decision aliasDecision = ToolAliasPolicy.resolve(call.toolName());
        LocalTurnTraceCapture.recordToolAliasDecision(aliasDecision);

        // Check if the tool exists
        TalosTool tool = toolRegistry.get(call.toolName());
        if (tool == null) {
            TurnAuditCapture.recordToolCall(call, "", false, "unknown tool");
            return ToolResult.fail(ToolError.notFound("Unknown tool: " + call.toolName()));
        }
        var requestFrame = ctx.requestFrame();
        if (requestFrame != null) {
            requestFrame.requireCurrent();
            if (requestFrame.controlled() && (!requestFrame.workspace().equals(session.workspace().toAbsolutePath().normalize())
                    || !requestFrame.allowsTool(tool.name())
                    || !requestFrame.allowsDirectoryTarget(call.parameters()))) {
                TurnAuditCapture.recordToolCall(call, "", false, "request acquisition boundary");
                return ToolResult.fail(ToolError.denied("The operation was not admitted by the current request boundary."));
            }
        } else if (controlledContracts != null || dev.talos.runtime.observation.TurnRequestFrame.controlledCurrent()) {
            return ToolResult.fail(ToolError.denied("The current request boundary is missing from the tool context."));
        }
        ToolResult surfaceRejection = rejectIfOutsideCurrentToolSurface(
                ctx, call, tool.name(), tracePhase);
        if (surfaceRejection != null) return surfaceRejection;

        boolean commandTool = CommandToolPlanner.isRunCommandTool(call.toolName());
        ToolRiskLevel risk = effectiveRisk(tool.descriptor().riskLevel(), call);
        // Mutation/checkpoint gating reads the resolved tool's registration
        // metadata (T757), not name lists: a registered mutating tool missing
        // from a hand-maintained list previously failed OPEN past the intent
        // gate, pre-approval validators, and checkpoint capture. The
        // descriptor canonicalizes null metadata, so opMeta is never null.
        ToolOperationMetadata opMeta = tool.descriptor().operationMetadata();
        boolean mutatingTool = opMeta.mutatesWorkspace();
        String userRequest = TurnUserRequestCapture.get();
        TaskContract capturedTaskContract = TurnTaskContractCapture.get();
        TaskContract taskContract = capturedTaskContract;
        if (taskContract == null) {
            taskContract = TaskContractResolver.fromUserRequest(userRequest);
        }
        var capturedIntent = TurnTaskContractCapture.getIntent();
        var selectedDraft = capturedIntent == null ? null : capturedIntent.contentSaveRequest().draftSelection();
        if (selectedDraft != null && (!"talos.write_file".equals(call.canonicalToolName())
                || !call.parameters().keySet().equals(java.util.Set.of("path", "content"))
                || !selectedDraft.destination().equals(call.param("path"))
                || !selectedDraft.content().equals(call.param("content")))) {
            TurnAuditCapture.recordBlockedToolCall(call, resolvePathParam(call), tracePhase,
                    "selected draft mismatch", "Only the exact selected draft and destination may be submitted for approval.");
            return ToolResult.fail(ToolError.denied("The selected draft or destination changed. No approval or write was attempted."));
        }
        PathArgumentCanonicalizer.ToolCallNormalization protectedAliasNormalization =
                ProtectedPathAliasNormalizer.canonicalizeExpectedProtectedAliases(
                        session.workspace(), call, pathParameterKeys(),
                        requestFrame != null && requestFrame.controlled() ? java.util.Set.of() : taskContract.expectedTargets());
        if (protectedAliasNormalization.changed()) {
            for (PathArgumentCanonicalizer.PathParameterChange change : protectedAliasNormalization.changes()) {
                LocalTurnTraceCapture.recordPathArgumentNormalized(
                        tracePhase,
                        call,
                        change.key(),
                        change.rawPath(),
                        change.normalizedPath());
            }
            call = transformedToolCall(
                    parsedPermit,
                    tracePhase,
                    call,
                    protectedAliasNormalization.call(),
                    LocalTurnTraceCapture.ToolCallTransformationReason
                            .PROTECTED_ALIAS_NORMALIZATION);
        }
        // Markdown-commentary sanitization runs HERE, before pre-approval
        // validation and the approval preview, so approved bytes == written
        // bytes (T755). It runs BEFORE the exact-literal corrector on purpose:
        // the corrector is the runtime-owned ground truth for exact-write
        // contracts and, running after, restores the user's literal payload
        // even if sanitization touched it - exactness always wins.
        if (selectedDraft == null && (isWriteFileTool(call.toolName()) || isEditFileTool(call.toolName()))) {
            MarkdownCommentaryCallNormalizer.Normalization sanitization =
                    isWriteFileTool(call.toolName())
                            ? MarkdownCommentaryCallNormalizer.normalizeWriteContent(call)
                            : MarkdownCommentaryCallNormalizer.normalizeEditNewString(call);
            if (sanitization.changed()) {
                LocalTurnTraceCapture.recordContentSanitized(
                        tracePhase,
                        call,
                        sanitization.key(),
                        sanitization.strippedChars(),
                        sanitization.beforeValue(),
                        sanitization.afterValue());
                call = transformedToolCall(
                        parsedPermit,
                        tracePhase,
                        call,
                        sanitization.call(),
                        LocalTurnTraceCapture.ToolCallTransformationReason
                                .MARKDOWN_COMMENTARY_SANITIZATION);
            }
        }
        ExactLiteralWriteCallCorrector.Correction exactCorrection =
                ExactLiteralWriteCallCorrector.correct(call, taskContract);
        if (selectedDraft == null && exactCorrection.corrected()) {
            LocalTurnTraceCapture.recordExactLiteralWriteCorrected(
                    exactCorrection.targetPath(),
                    exactCorrection.sourcePattern(),
                    exactCorrection.expectedHash(),
                    exactCorrection.expectedBytes(),
                    exactCorrection.expectedLines(),
                    exactCorrection.observedHash(),
                    exactCorrection.observedBytes(),
                    exactCorrection.observedLines());
            call = transformedToolCall(
                    parsedPermit,
                    tracePhase,
                    call,
                    exactCorrection.call(),
                    LocalTurnTraceCapture.ToolCallTransformationReason
                            .EXACT_LITERAL_WRITE_CORRECTION);
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
            call = transformedToolCall(
                    parsedPermit,
                    tracePhase,
                    call,
                    pathNormalization.call(),
                    LocalTurnTraceCapture.ToolCallTransformationReason
                            .PATH_ARGUMENT_NORMALIZATION);
        }
        // Byte-faithful existing-file writes (T1012): re-apply the target's
        // BOM, line-ending style, and terminal-newline state to the proposed
        // content BEFORE pre-approval validation, the approval preview,
        // checkpoint capture, and execution, so the exact bytes approved are
        // the exact bytes written. Runs after every other content rewrite
        // (sanitizer, exact-literal corrector); idempotent by construction.
        WriteFormatPolicy.Preservation formatPreservation =
                selectedDraft == null ? WriteFormatPolicy.preserveWriteCall(session.workspace(), call, taskContract, userRequest)
                        : WriteFormatPolicy.Preservation.unchanged(call);
        if (formatPreservation.changed()) {
            LocalTurnTraceCapture.recordActionObligation(
                    "WRITE_FORMAT_PRESERVATION",
                    "REPAIRED",
                    formatPreservation.description(),
                    "WRITE_FORMAT_PRESERVED_BEFORE_APPROVAL");
            call = transformedToolCall(
                    parsedPermit,
                    tracePhase,
                    call,
                    formatPreservation.call(),
                    LocalTurnTraceCapture.ToolCallTransformationReason
                            .WRITE_FORMAT_PRESERVATION);
        }
        String path = resolvePathParam(call);

        if (taskContract.type() == TaskType.DIRECTORY_LISTING && !isListDirTool(call.toolName())) {
            TurnAuditCapture.recordBlockedToolCall(
                    call,
                    path == null ? "" : path,
                    tracePhase,
                    "directory-listing contract denied " + call.toolName(),
                    "directory-listing contract allows only talos.list_dir");
            return ToolResult.fail(ToolError.denied(
                    "The user only asked to list directory entries on this turn, so do not call "
                            + call.toolName()
                            + ". Use talos.list_dir only and answer with file and directory names."));
        }

        ToolResult incompleteTargetScanValidation =
                validateIncompleteAuthorityBeforeApproval(call, mutatingTool);
        if (incompleteTargetScanValidation != null) {
            TurnAuditCapture.recordBlockedToolCall(
                    call,
                    path == null ? "" : path,
                    tracePhase,
                    preApprovalBlockReason(
                            call,
                            incompleteTargetScanValidation));
            return incompleteTargetScanValidation;
        }

        if (mutatingTool
                && (!taskContract.mutationAllowed()
                        || TaskContractResolver.explicitlyForbidsMutation(userRequest))
                && (userRequest != null || capturedTaskContract != null)) {
            String denial = taskContract.mutationRequested()
                    ? "A file change was requested, but its scope is not authorized by the current task contract. "
                            + "Ask the user to clarify the target files and requested changes before retrying. "
                            + "Earlier approval must not be reused, and assistant proposals must not supply missing authority."
                    : "The user did not ask to modify files on this turn, so do not call "
                            + call.toolName()
                            + " for a read-only request. Answer with information only, "
                            + "or wait for an explicit change request in a later turn.";
            TurnAuditCapture.recordBlockedToolCall(
                    call, path == null ? "" : path, tracePhase,
                    "task-contract mutation denied " + call.toolName(), denial);
            return ToolResult.fail(ToolError.denied(denial));
        }

        if (ctx.executionPhaseState() != null) {
            ToolResult phaseRejection = PhasePolicy.rejectIfDisallowed(
                    ctx.executionPhaseState().phase(), tool.name(), risk);
            if (phaseRejection != null) {
                TurnAuditCapture.recordToolCall(
                        call, path == null ? "" : path, false,
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

        // Path-parameter placeholder guard - applies to ALL tools regardless of
        // risk level. Transcript-observed failure (qwen2.5-coder:14b, April 2026):
        // the model emitted planning narration with mixed real and template tool
        // calls: read_file(path=<html-file-path>). read_file is READ_ONLY so the
        // content-guard below (scoped to requiresApproval) was skipped entirely.
        // Path.of("<html-file-path>") is illegal on Windows (Illegal char '<' at
        // index 0), propagated uncaught as an InvalidPathException through
        // executeTool → ToolCallLoop → AssistantTurnExecutor, and was logged as
        // "LLM call failed" - killing the whole turn. A placeholder path is
        // definitionally wrong for any file tool; refuse here and return a directed
        // error so the model retries with the actual workspace path.
        for (String k : pathParameterKeys()) {
            String v = call.param(k);
            if (TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(v)) {
                String msg = TemplatePlaceholderGuard.rejectionMessage(call.toolName(), k, v);
                TurnAuditCapture.recordToolCall(
                        call, path == null ? "" : path, false,
                        "placeholder path parameter `" + k + "` rejected");
                return ToolResult.fail(ToolError.invalidParams(msg));
            }
        }

        // Template-placeholder guard - reject BEFORE the approval gate.
        // Transcript-observed failure (qwen2.5-coder:14b, April 2026): the
        // model emits a pedagogical "step-by-step" answer using Python-style
        // variable names, then issues write_file / edit_file tool calls whose
        // content argument IS the variable name (e.g.
        // `<updated_index_html_content>`). The approval preview just mirrors
        // the placeholder back at the user; a reflex "y" overwrites real
        // files with 28 bytes of garbage. Warning-in-approval-detail would
        // not have saved the user - this class of payload is definitionally
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
                // - the call never reached the gate because the payload was
                // definitionally bad, but from a trust-accounting perspective
                // it is a denied mutation, not a success.
                TurnAuditCapture.recordToolCall(
                        call, path == null ? "" : path, false,
                        "placeholder content parameter `" + placeholderParam + "` rejected");
                return ToolResult.fail(ToolError.invalidParams(msg));
            }
        }

        if (risk.requiresApproval()) {
            ToolResult preApprovalValidation =
                    validateBeforeApproval(call, session, ctx, taskContract, mutatingTool,
                            commandProfiles);
            if (preApprovalValidation != null) {
                String blockReason = preApprovalBlockReason(call, preApprovalValidation);
                TurnAuditCapture.recordBlockedToolCall(
                        call,
                        path == null ? "" : path,
                        tracePhase,
                        blockReason);
                if (commandTool) {
                    LocalTurnTraceCapture.recordCommandPolicyDecision(
                            tracePhase, call, "DENY", "PRE_APPROVAL_VALIDATION");
                    LocalTurnTraceCapture.recordCommandDenied(tracePhase, call, blockReason);
                }
                return preApprovalValidation;
            }
        }

        if (commandTool) {
            try {
                CommandPlan commandPlan = CommandToolPlanner.plan(
                        call,
                        session.workspace(),
                        commandProfiles);
                LocalTurnTraceCapture.recordCommandPlanCreated(tracePhase, call, commandPlan);
            } catch (Exception e) {
                String reason = CommandToolPlanner.invalidMessage(e.getMessage());
                LocalTurnTraceCapture.recordCommandPolicyDecision(
                        tracePhase, call, "DENY", "PLAN_REJECTED");
                LocalTurnTraceCapture.recordCommandDenied(tracePhase, call, reason);
                TurnAuditCapture.recordBlockedToolCall(
                        call,
                        path == null ? "" : path,
                        tracePhase,
                        reason);
                return ToolResult.fail(ToolError.invalidParams(reason));
            }
        }

        // Scope guard - narrow, lexical, warn-first. Fires only for mutating
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
        // ALLOW would otherwise silently bypass the warning - exactly the failure
        // class the guard exists to catch.
        if (scopeWarning != null && permissionDecision.action() == PermissionAction.ALLOW) {
            permissionDecision = permissionDecision.forceAsk(
                    "SCOPE_WARNING_ASK",
                    "Scope warning requires approval before running " + call.toolName() + ".");
        }

        // Format-change override (T1012): a BOM, line-ending, or
        // terminal-newline change that remains after preservation is a
        // material byte change the user must SEE; a remembered or
        // configured ALLOW would bypass the Format changes section.
        if (permissionDecision.action() == PermissionAction.ALLOW
                && isWriteFileTool(call.toolName())
                && path != null && !path.isBlank()) {
            String pendingContent = resolveParam(call, "content", "text", "body", "data", "file_content");
            if (pendingContent != null && !WriteFormatPolicy
                    .remainingFormatChanges(session.workspace(), path, pendingContent)
                    .isEmpty()) {
                permissionDecision = permissionDecision.forceAsk(
                        "FORMAT_CHANGE_ASK",
                        "Format changes require approval before running " + call.toolName() + ".");
            }
        }

        LocalTurnTraceCapture.recordPermissionDecision(
                tracePhase,
                call,
                permissionDecision.action().name(),
                permissionDecision.reasonCode(),
                permissionDecision.relativePath(),
                permissionDecision.protectedPath(),
                permissionDecision.protectedKind(),
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
                recordWorkspaceOperationLifecycle(
                        call,
                        LocalTurnTraceCapture.WorkspaceOperationLifecycleStage
                                .APPROVAL_DENIED);
                if (commandTool) {
                    LocalTurnTraceCapture.recordCommandApprovalDenied(tracePhase, call);
                }
            }
            String policyDenial = "permission policy denied " + call.toolName()
                    + " (" + permissionDecision.reasonCode() + ")";
            TurnAuditCapture.recordBlockedToolCall(call, path == null ? "" : path,
                    tracePhase, policyDenial, policyDenial, ToolFailureReason.PERMISSION_POLICY_DENIED);
            if (commandTool) {
                LocalTurnTraceCapture.recordCommandDenied(
                        tracePhase,
                        call,
                        "Permission policy denied " + call.toolName()
                                + " (" + permissionDecision.reasonCode() + ")");
            }
            return ToolResult.fail(ToolError.denied(
                    ToolFailureReason.PERMISSION_POLICY_DENIED,
                    "Permission policy denied the " + call.toolName()
                            + " call. " + permissionDecision.userMessage()));
        }

        if (permissionDecision.action() == PermissionAction.ASK) {
            TurnAuditCapture.recordApprovalRequired();
            LocalTurnTraceCapture.recordApprovalRequired(tracePhase, call);
            recordWorkspaceOperationLifecycle(
                    call,
                    LocalTurnTraceCapture.WorkspaceOperationLifecycleStage
                            .APPROVAL_REQUIRED);
            if (commandTool) {
                LocalTurnTraceCapture.recordCommandApprovalRequired(tracePhase, call);
            }

            ApprovalDiffPreview.Preview diffPreview = approvalDiffPreview(call, path, session.workspace());
            if (diffPreview != null) {
                LocalTurnTraceCapture.recordApprovalDiffPreview(
                        tracePhase,
                        call,
                        diffPreview.text(),
                        diffPreview.added(),
                        diffPreview.removed(),
                        diffPreview.diffLineCount(),
                        diffPreview.truncated(),
                        diffPreview.skippedReason());
            }
            String desc = approvalDescription(call, risk, permissionDecision);
            String detail = buildApprovalDetail(
                    call,
                    path,
                    scopeWarning,
                    permissionDecision.userMessage(),
                    session.workspace(),
                    session.config(),
                    diffPreview,
                    commandProfiles);
            ApprovalResponse response = permissionDecision.rememberEligible()
                    ? approvalGate.approveFull(desc, detail)
                    : approvalGate.approveOnce(desc, detail);

            if (response == ApprovalResponse.DENIED) {
                TurnAuditCapture.recordApprovalDenied();
                LocalTurnTraceCapture.recordApprovalDenied(tracePhase, call);
                recordWorkspaceOperationLifecycle(
                        call,
                        LocalTurnTraceCapture.WorkspaceOperationLifecycleStage
                                .APPROVAL_DENIED);
                if (commandTool) {
                    LocalTurnTraceCapture.recordCommandApprovalDenied(tracePhase, call);
                    LocalTurnTraceCapture.recordCommandDenied(
                            tracePhase,
                            call,
                            "User did not approve " + call.toolName());
                }
                String approvalDenial = "approval denied by user for " + call.toolName();
                TurnAuditCapture.recordBlockedToolCall(call, path == null ? "" : path,
                        tracePhase, approvalDenial, approvalDenial, ToolFailureReason.USER_APPROVAL_DENIED);
                // Phrasing matters: previously "Operation denied by user" caused
                // qwen2.5-coder to hallucinate a "permissions" excuse and tell
                // the user to "ensure you have the necessary permissions" - the
                // word "denied" anchored the wrong narrative. Reshape the error
                // so the model interprets it as user intent, not auth failure.
                String targetContext = approvalDeniedTargetContext(permissionDecision);
                return ToolResult.fail(ToolError.denied(
                        ToolFailureReason.USER_APPROVAL_DENIED,
                        "User did not approve the " + call.toolName()
                                + " call." + targetContext
                                + " The user is in control of the workspace; "
                                + "ask what they want to do differently before retrying, "
                                + "or take a different action that does not need approval."));
            }

            // Approved - record and optionally propagate the remember choice.
            TurnAuditCapture.recordApprovalGranted();
            LocalTurnTraceCapture.recordApprovalGranted(tracePhase, call);
            recordWorkspaceOperationLifecycle(
                    call,
                    LocalTurnTraceCapture.WorkspaceOperationLifecycleStage
                            .APPROVAL_GRANTED);
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
            recordWorkspaceOperationLifecycle(
                    call,
                    LocalTurnTraceCapture.WorkspaceOperationLifecycleStage
                            .APPROVAL_GRANTED);
            if (commandTool) {
                LocalTurnTraceCapture.recordCommandApprovalGranted(tracePhase, call);
            }
        }

        if (opMeta.requiresCheckpoint()) {
            CheckpointCaptureResult checkpoint = captureCheckpointBeforeMutation(session, call);
            LocalTurnTraceCapture.recordCheckpoint(
                    checkpoint.status(),
                    checkpoint.checkpointId(),
                    checkpoint.message(),
                    checkpoint.capturedFiles(),
                    checkpoint.authorityPlanId(),
                    checkpoint.operationId());
            recordWorkspaceOperationLifecycle(
                    call,
                    switch (checkpoint.status()) {
                        case "CREATED" ->
                                LocalTurnTraceCapture
                                        .WorkspaceOperationLifecycleStage
                                        .CHECKPOINT_CREATED;
                        case "SKIPPED" ->
                                LocalTurnTraceCapture
                                        .WorkspaceOperationLifecycleStage
                                        .CHECKPOINT_SKIPPED;
                        default ->
                                LocalTurnTraceCapture
                                        .WorkspaceOperationLifecycleStage
                                        .CHECKPOINT_FAILED;
                    });
            if (!checkpoint.success()) {
                TurnAuditCapture.recordToolCall(
                        call, path == null ? "" : path, false,
                        "checkpoint failed before " + call.toolName());
                return ToolResult.fail(ToolError.internal(
                        "Required checkpoint failed before mutation: " + checkpoint.message()));
            }
        }

        ToolContext toolCtx = new ToolContext(
                session.workspace(),
                ctx.sandbox(),
                session.config(),
                ctx.requestFrame() == null
                        ? dev.talos.spi.WorkspaceObservationBoundary.unrestricted()
                        : ctx.requestFrame().observationBoundary()
        );

        ToolResult result;
        try {
            result = toolRegistry.execute(call, toolCtx);
        } catch (Exception e) {
            if (TurnFailureClassifier.isCancellation(e)
                    || TurnFailureClassifier.isInterruption(e)) {
                throw TurnFailureClassifier.propagateControlFlow(e);
            }
            LOG.warn("Tool {} threw unexpected exception: {} - returning fail result instead of crashing turn",
                    call.toolName(), SafeLogFormatter.throwableMessage(e));
            LOG.debug("Tool execution exception stack trace suppressed; sanitized reason={}",
                    SafeLogFormatter.throwableMessage(e));
            result = ToolResult.fail(ToolError.internal(
                    "Tool execution failed unexpectedly: "
                            + e.getClass().getSimpleName() + ": " + SafeLogFormatter.throwableMessage(e)));
        }
        List<String> resultPathHints = result.mutationEffects().hasEffects()
                ? result.mutationEffects().affectedPaths()
                : result.success()
                ? auditPathHints(call, path, result)
                : (path == null || path.isBlank() ? List.of() : List.of(path));
        recordWorkspaceOperationLifecycle(
                call,
                result.success()
                        ? LocalTurnTraceCapture.WorkspaceOperationLifecycleStage
                                .EXECUTION_SUCCEEDED
                        : LocalTurnTraceCapture.WorkspaceOperationLifecycleStage
                                .EXECUTION_FAILED);
        TurnAuditCapture.recordExecutedToolCall(
                call,
                "talos.edit_file".equals(call.canonicalToolName())
                        || "talos.write_file".equals(call.canonicalToolName())
                        ? (path == null ? "" : path)
                        : (resultPathHints.isEmpty() ? "" : resultPathHints.getFirst()),
                resultPathHints,
                result.success(),
                result.success() ? "" : toolFailureReason(result),
                result.mutationEffects(),
                result.error() == null ? ToolFailureReason.NONE : result.error().reason(),
                result.executedEditDetail());
        if (result.success()) {
            TurnTaskContractCapture.recordSuccessfulToolCall(call);
            if (WorkspaceOperationPlanner.isWorkspaceOperationTool(
                    call.toolName())) {
                LocalTurnTraceCapture.recordWorkspaceOperationConsumed(
                        TurnTaskContractCapture.workspaceOperationPlan());
            }
        }
        return result;
    }

    private static void recordWorkspaceOperationLifecycle(
            ToolCall call,
            LocalTurnTraceCapture.WorkspaceOperationLifecycleStage stage
    ) {
        if (call == null
                || !WorkspaceOperationPlanner.isWorkspaceOperationTool(
                        call.toolName())) {
            return;
        }
        var plan = TurnTaskContractCapture.workspaceOperationPlan();
        if (plan.authorizes(call)) {
            LocalTurnTraceCapture.recordWorkspaceOperationLifecycle(
                    plan,
                    call,
                    stage);
        }
    }

    private ToolResult executeToolWithinStage(ToolExecution execution) {
        TurnExecutionStage priorStage = LocalTurnTraceCapture.currentExecutionStage();
        LocalTurnTraceCapture.enterExecutionStage(TurnExecutionStage.TOOL_EXECUTION);
        try {
            ToolResult result = execution.run();
            LocalTurnTraceCapture.enterExecutionStage(priorStage);
            return result;
        } catch (RuntimeException | Error failure) {
            throw failure;
        }
    }

    @FunctionalInterface
    private interface ToolExecution {
        ToolResult run();
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
            Optional<WorkspaceOperationPlan> plan =
                    WorkspaceOperationPlanner.checkpointPlan(
                            call,
                            TurnTaskContractCapture.workspaceOperationPlan());
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
     * Used for the approval gate display - even when the model uses non-canonical
     * parameter names (e.g. {@code file_path} instead of {@code path}).
     */
    private static String resolvePathParam(ToolCall call) {
        for (String key : pathParameterKeys()) {
            String value = call.param(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static List<String> auditPathHints(ToolCall call, String fallbackPath, ToolResult result) {
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
        boolean operationPathsResolved = false;
        if (call != null && WorkspaceOperationPlanner.isWorkspaceOperationTool(call.toolName())) {
            try {
                Optional<WorkspaceOperationPlan> plan =
                        WorkspaceOperationPlanner.checkpointPlan(
                                call,
                                TurnTaskContractCapture.workspaceOperationPlan());
                if (plan.isPresent()) {
                    List<String> changedPaths = plan.get().changedPaths();
                    paths.addAll(changedPaths);
                    operationPathsResolved = !changedPaths.isEmpty();
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid operation payloads are handled before successful audit recording.
            }
        }
        if (!operationPathsResolved && fallbackPath != null && !fallbackPath.isBlank()) {
            paths.add(fallbackPath);
        }
        if (result != null && result.contentMetadata() != null) {
            paths.addAll(result.contentMetadata().sourcePaths());
        }
        return List.copyOf(paths);
    }

    private CheckpointCaptureResult captureCheckpointBeforeMutation(Session session, ToolCall call) {
        Optional<WorkspaceOperationPlan> operationPlan =
                WorkspaceOperationPlanner.checkpointPlan(
                        call,
                        TurnTaskContractCapture.workspaceOperationPlan());
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
            RuntimeTurnContext ctx,
            TaskContract taskContract,
            boolean mutatingTool,
            dev.talos.runtime.command.CommandProfileRegistry commandProfiles
    ) {
        ToolResult sandboxPathValidation =
                validateSandboxPathBeforeApproval(call, session, ctx, mutatingTool);
        if (sandboxPathValidation != null) {
            return sandboxPathValidation;
        }

        ToolResult forbiddenTargetValidation =
                validateForbiddenTargetBeforeApproval(
                        call,
                        taskContract,
                        mutatingTool,
                        session == null ? null : session.workspace());
        if (forbiddenTargetValidation != null) {
            return forbiddenTargetValidation;
        }

        ToolResult workspaceOrganizationValidation =
                validateWorkspaceOrganizationToolBeforeApproval(
                        call,
                        taskContract,
                        session == null ? null : session.workspace());
        if (workspaceOrganizationValidation != null) {
            return workspaceOrganizationValidation;
        }

        ToolResult workspaceContentValidation =
                validateWorkspaceOperationContentPredecessorBeforeApproval(
                        call,
                        mutatingTool);
        if (workspaceContentValidation != null) {
            return workspaceContentValidation;
        }

        ToolResult expectedTargetValidation =
                validateExpectedTargetBeforeApproval(
                        call,
                        taskContract,
                        mutatingTool,
                        session.workspace());
        if (expectedTargetValidation != null) {
            return expectedTargetValidation;
        }

        Optional<String> workspaceOperationValidation =
                WorkspaceOperationPlanner.validateBeforeApproval(call);
        if (workspaceOperationValidation.isPresent()) {
            return ToolResult.fail(ToolError.invalidParams(workspaceOperationValidation.get()));
        }

        Optional<String> commandValidation =
                CommandToolPlanner.validateBeforeApproval(call, session.workspace(), commandProfiles);
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

            String unsupportedEncodingError = WriteFormatPolicy
                    .unsupportedEncodingDirectiveError(TurnUserRequestCapture.get());
            if (unsupportedEncodingError != null) {
                return ToolResult.fail(ToolError.unsupportedFormat(unsupportedEncodingError));
            }

            String requestedLineEndingError = WriteFormatPolicy
                    .requestedLineEndingError(TurnUserRequestCapture.get(), content);
            if (requestedLineEndingError != null) {
                return ToolResult.fail(ToolError.invalidParams(requestedLineEndingError));
            }

            String oversizedReplacementError = WriteFormatPolicy
                    .oversizedExistingFileReplacementError(session.workspace(), path);
            if (oversizedReplacementError != null) {
                return ToolResult.fail(ToolError.invalidParams(oversizedReplacementError));
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
                    ToolFailureReason.EDIT_EMPTY_ARGUMENTS,
                    "Invalid talos.edit_file call: `old_string` must be present and non-empty. "
                            + "Call talos.read_file first, then provide the exact text to replace. "
                            + "No approval was requested and no file was changed."));
        }

        String newString = resolveParam(call, "new_string", "newString", "new_text", "replace", "replacement");
        if (newString == null) {
            return ToolResult.fail(ToolError.invalidParams(
                    ToolFailureReason.EDIT_EMPTY_ARGUMENTS,
                    "Invalid talos.edit_file call: missing required parameter `new_string`. "
                            + "No approval was requested and no file was changed."));
        }

        if (oldString.equals(newString)) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Invalid talos.edit_file call: `old_string` and `new_string` are identical, "
                            + "so no edit would be made. No approval was requested and no file was changed."));
        }

        ToolResult exactEditMatchValidation =
                validateExactEditMatchBeforeApproval(path, oldString, newString, session.workspace());
        if (exactEditMatchValidation != null) {
            return exactEditMatchValidation;
        }

        return null;
    }

    private static ToolResult validateExactEditMatchBeforeApproval(
            String path,
            String oldString,
            String newString,
            Path workspace
    ) {
        if (workspace == null || path == null || path.isBlank()
                || oldString == null || oldString.isEmpty()) {
            return null;
        }
        Path root = workspace.normalize();
        Path target;
        try {
            target = root.resolve(path).normalize();
        } catch (RuntimeException e) {
            return null;
        }
        if (!target.startsWith(root)) return null;
        if (!Files.isRegularFile(target)) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Invalid talos.edit_file call: target file not found before approval: `"
                            + path
                            + "`. Call talos.read_file or talos.list_dir first to confirm the path. "
                            + "No approval was requested and no file was changed."));
        }
        String content;
        try {
            content = Files.readString(target);
        } catch (Exception e) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Invalid talos.edit_file call: target file could not be read before approval: `"
                            + path
                            + "`. Call talos.read_file first and retry with exact current text. "
                            + "No approval was requested and no file was changed."));
        }
        int occurrences = TextReplacement.findUniqueMatch(content, oldString, newString).count();
        if (occurrences == 0) {
            return ToolResult.fail(ToolError.invalidParams(
                    ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND,
                    "Invalid talos.edit_file call: old_string not found in `"
                            + path
                            + "` before approval. Call talos.read_file first, then retry with exact current text "
                            + "or use talos.write_file with the complete updated file content. "
                            + "No approval was requested and no file was changed."));
        }
        if (occurrences > 1) {
            return ToolResult.fail(ToolError.invalidParams(
                    ToolFailureReason.EDIT_OLD_STRING_AMBIGUOUS,
                    "Invalid talos.edit_file call: old_string appears "
                            + occurrences
                            + " times in `"
                            + path
                            + "` before approval. Provide a unique old_string from talos.read_file output "
                            + "or use talos.write_file with the complete updated file content. "
                            + "No approval was requested and no file was changed."));
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
            TaskContract taskContract,
            Path workspace) {
        if (call == null
                || !WorkspaceOperationPlanner.isWorkspaceOperationTool(
                        call.toolName())) {
            return null;
        }
        TurnTaskContractCapture.WorkspaceAuthorization authorization =
                TurnTaskContractCapture.authorizeWorkspaceOperation(call);
        LocalTurnTraceCapture.recordWorkspaceOperationAuthorityDecision(
                TurnTaskContractCapture.workspaceOperationPlan(),
                call,
                authorization.name());
        if (authorization
                == TurnTaskContractCapture.WorkspaceAuthorization.AUTHORIZED) {
            var operation = TurnTaskContractCapture.workspaceOperationPlan()
                    .authorizedOperations()
                    .getFirst();
            if (operation.operands().size() == 2
                    && !workspaceOperationPathsAreDistinctWithoutLinks(
                            workspace,
                            operation.sourcePath(),
                            operation.destinationPath())) {
                return ToolResult.fail(ToolError.invalidParams(
                        ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                        "The typed workspace-operation source and destination "
                                + "must identify distinct ordinary paths. No "
                                + "approval was requested and no file was changed."));
            }
            if (operation.kind()
                            == dev.talos.runtime.intent
                                    .WorkspaceOperationObligation.Kind.MKDIR
                    && !workspaceTargetIsMissingWithoutLinks(
                            workspace,
                            operation.targetPath())) {
                return ToolResult.fail(ToolError.invalidParams(
                        ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                        "The typed directory-creation plan requires an absent "
                                + "ordinary target. No approval was requested "
                                + "and no file was changed."));
            }
            if (operation.parentPolicy()
                            == dev.talos.runtime.intent
                                    .WorkspaceOperationObligation.ParentPolicy
                                    .REQUIRE_EXISTING_PARENT
                    && !destinationParentExistsWithoutLinks(
                            workspace,
                            operation.kind()
                                            == dev.talos.runtime.intent
                                                    .WorkspaceOperationObligation.Kind
                                                    .MKDIR
                                    ? operation.targetPath()
                                    : operation.destinationPath())) {
                return ToolResult.fail(ToolError.invalidParams(
                        ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                        "The typed workspace-operation plan requires an existing "
                                + "ordinary destination parent. No approval was "
                                + "requested and no file was changed."));
            }
            return null;
        }
        String reason = switch (authorization) {
            case PREDECESSOR_NOT_COMPLETE ->
                    "The preceding typed content-mutation obligations have not completed.";
            case ALREADY_CONSUMED ->
                    "The exact workspace-operation obligation was already consumed.";
            case ALREADY_ATTEMPTED ->
                    "The exact workspace-operation obligation was already attempted; "
                            + "a new attempt requires typed attempt-aware lifecycle authority.";
            case CALL_DOES_NOT_MATCH_PLAN ->
                    "The tool, operand roles, paths, or explicit flags do not match the stored plan.";
            case NO_TYPED_AUTHORITY, NOT_WORKSPACE_OPERATION ->
                    "No executable typed workspace-operation authority is stored for this turn.";
            case AUTHORIZED -> "";
        };
        return ToolResult.fail(ToolError.invalidParams(
                ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                "Workspace operation `" + call.toolName()
                        + "` is not authorized by the current typed task plan. "
                        + reason
                        + " No approval was requested and no file was changed."));
    }

    private static ToolResult
            validateWorkspaceOperationContentPredecessorBeforeApproval(
                    ToolCall call,
                    boolean mutatingTool
            ) {
        var plan = TurnTaskContractCapture.workspaceOperationPlan();
        if (call == null
                || !mutatingTool
                || WorkspaceOperationPlanner.isWorkspaceOperationTool(
                        call.toolName())
                || plan.authorizedOperations().isEmpty()) {
            return null;
        }
        TaskResolution resolution = TurnTaskContractCapture.getResolution();
        List<String> predecessors = resolution == null
                ? List.of()
                : resolution.contentMutationPredecessorPaths();
        String path = resolveParam(
                call,
                "path",
                "file_path",
                "filepath",
                "file",
                "filename",
                "target");
        boolean exactContentPredecessor =
                (isWriteFileTool(call.toolName())
                                || isEditFileTool(call.toolName()))
                        && path != null
                        && predecessors.contains(
                                path.strip().replace('\\', '/'));
        if (exactContentPredecessor) return null;
        return ToolResult.fail(ToolError.invalidParams(
                ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                "The current typed task plan authorizes only its exact "
                        + "workspace operation and declared preceding content "
                        + "mutations. No approval was requested and no file "
                        + "was changed."));
    }

    private static boolean destinationParentExistsWithoutLinks(
            Path workspace,
            String destination
    ) {
        if (workspace == null || destination == null || destination.isBlank()) {
            return false;
        }
        try {
            NoFollowPathPolicy.GuardedRoot root =
                    NoFollowPathPolicy.bindExistingDirectory(workspace);
            Path target = workspace.toAbsolutePath().normalize()
                    .resolve(destination)
                    .normalize();
            Path parent = target.getParent();
            if (parent == null) return false;
            root.requireDirectory(parent);
            return true;
        } catch (Exception rejected) {
            return false;
        }
    }

    private static boolean workspaceTargetIsMissingWithoutLinks(
            Path workspace,
            String targetPath
    ) {
        if (workspace == null || targetPath == null || targetPath.isBlank()) {
            return false;
        }
        try {
            NoFollowPathPolicy.GuardedRoot root =
                    NoFollowPathPolicy.bindExistingDirectory(workspace);
            Path target = workspace.toAbsolutePath().normalize()
                    .resolve(targetPath)
                    .normalize();
            return root.inspect(target) == NoFollowPathPolicy.EntryType.MISSING;
        } catch (Exception rejected) {
            return false;
        }
    }

    private static boolean workspaceOperationPathsAreDistinctWithoutLinks(
            Path workspace,
            String sourcePath,
            String destinationPath
    ) {
        if (workspace == null
                || sourcePath == null
                || sourcePath.isBlank()
                || destinationPath == null
                || destinationPath.isBlank()) {
            return false;
        }
        try {
            NoFollowPathPolicy.GuardedRoot root =
                    NoFollowPathPolicy.bindExistingDirectory(workspace);
            Path workspaceRoot = workspace.toAbsolutePath().normalize();
            Path source = workspaceRoot.resolve(sourcePath).normalize();
            Path destination = workspaceRoot.resolve(destinationPath).normalize();
            NoFollowPathPolicy.EntryType sourceType = root.inspect(source);
            NoFollowPathPolicy.EntryType destinationType = root.inspect(destination);
            if (source.equals(destination)) return false;
            if (sourceType == NoFollowPathPolicy.EntryType.MISSING
                    || destinationType == NoFollowPathPolicy.EntryType.MISSING) {
                return true;
            }
            return !Files.isSameFile(source, destination);
        } catch (Exception rejected) {
            return false;
        }
    }

    private static ToolResult validateExpectedTargetBeforeApproval(
            ToolCall call,
            TaskContract taskContract,
            boolean mutatingTool,
            Path workspace
    ) {
        var capturedResolution = TurnTaskContractCapture.getResolution();
        if (call != null
                && mutatingTool
                && capturedResolution != null
                && capturedResolution.blocksUnscopedMutation()) {
            return ToolResult.fail(ToolError.invalidParams(
                    ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                    "The current typed task plan authorizes no content-mutation "
                            + "target for this non-authoritative mutation tail. "
                            + "No approval was requested and no file was changed."));
        }
        if (call == null
                || taskContract == null
                || !taskContract.mutationAllowed()
                || taskContract.expectedTargets().isEmpty()
                || !mutatingTool) {
            return null;
        }
        if (WorkspaceOperationPlanner.isWorkspaceOperationTool(call.toolName())
                && workspaceOperationRequestOwnsTargetShape(taskContract)) {
            return null;
        }
        List<String> paths = expectedTargetValidationPaths(call);
        if (paths.isEmpty()) {
            return null;
        }
        for (String path : paths) {
            boolean matched = false;
            for (String expected : taskContract.expectedTargets()) {
                if (sameExpectedTarget(
                        call.toolName(),
                        path,
                        expected,
                        workspace)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                for (String optional : optionalMutationTargets(taskContract)) {
                    if (sameExpectedTarget(
                            call.toolName(),
                            path,
                            optional,
                            workspace)) {
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) {
                return ToolResult.fail(ToolError.invalidParams(
                        ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                        "Target outside expected targets before approval: `" + path
                                + "` is outside the current expected target set: "
                                + String.join(", ", orderedExpectedTargets(taskContract))
                                + ". Similar filenames are not substitutes for required target paths. "
                                + "No approval was requested and no file was changed."));
            }
        }
        return null;
    }

    private static ToolResult validateIncompleteAuthorityBeforeApproval(
            ToolCall call,
            boolean mutatingTool
    ) {
        TaskResolution capturedResolution =
                TurnTaskContractCapture.getResolution();
        if (call == null
                || !mutatingTool
                || capturedResolution == null
                || !capturedResolution.sequentialMutationPlan()
                        .authorityLimitExceeded()) {
            return null;
        }
        return ToolResult.fail(ToolError.invalidParams(
                ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                "The current typed authority analysis did not complete, so no "
                        + "content-mutation target is authorized. No approval "
                        + "was requested and no file was changed."));
    }

    private static List<String> expectedTargetValidationPaths(ToolCall call) {
        var paths = new LinkedHashSet<String>();
        if (call != null && WorkspaceOperationPlanner.isWorkspaceOperationTool(call.toolName())) {
            try {
                WorkspaceOperationPlanner.checkpointPlan(
                                call,
                                TurnTaskContractCapture.workspaceOperationPlan())
                        .ifPresent(plan -> paths.addAll(plan.changedPaths()));
            } catch (IllegalArgumentException ignored) {
                // Invalid operation payloads are handled by normal pre-approval validation.
            }
        }
        String path = resolveParam(call, "path", "file_path", "filepath", "file", "filename", "target");
        if (path != null && !path.isBlank()) {
            paths.add(path);
        }
        return List.copyOf(paths);
    }

    private static boolean workspaceOperationRequestOwnsTargetShape(TaskContract taskContract) {
        return !TurnTaskContractCapture.workspaceOperationPlan()
                .authorizedOperations().isEmpty();
    }

    private static Set<String> optionalMutationTargets(TaskContract taskContract) {
        if (taskContract == null
                || taskContract.originalUserRequest().isBlank()
                || taskContract.expectedTargets().isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        var capturedIntent = TurnTaskContractCapture.getIntent();
        var intent = capturedIntent == null
                ? TaskContractResolver.resolveFromUserRequest(
                        taskContract.originalUserRequest()).intent()
                : capturedIntent;
        intent
                .targets()
                .targets()
                .stream()
                .filter(target -> target.role() == TargetRole.MAY_MUTATE)
                .map(target -> target.path())
                .filter(path -> path != null && !path.isBlank())
                .forEach(out::add);
        return Set.copyOf(out);
    }

    private static ToolResult validateSandboxPathBeforeApproval(
            ToolCall call,
            Session session,
            RuntimeTurnContext ctx,
            boolean mutatingTool
    ) {
        if (call == null || !mutatingTool) {
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
                        ToolFailureReason.PRE_APPROVAL_PATH_INVALID,
                        "Invalid path before approval for `" + param.name() + "`: "
                                + param.value() + ". No approval was requested and no file was changed."));
            }
            if (!ctx.sandbox().allowedPath(resolved)) {
                return ToolResult.fail(ToolError.invalidParams(
                        ToolFailureReason.PRE_APPROVAL_PATH_NOT_ALLOWED,
                        "Path not allowed before approval for `" + param.name() + "`: "
                                + param.value() + " (" + ctx.sandbox().explain(resolved) + "). "
                                + "No approval was requested and no file was changed."));
            }
        }
        return null;
    }

    private static ToolResult validateForbiddenTargetBeforeApproval(
            ToolCall call,
            TaskContract taskContract,
            boolean mutatingTool,
            Path workspace
    ) {
        if (call == null || taskContract == null || taskContract.forbiddenTargets().isEmpty()) {
            return null;
        }
        if (!mutatingTool) {
            return null;
        }
        List<PathParam> params = pathParams(call);
        if (params.isEmpty()) {
            return null;
        }
        for (PathParam param : params) {
            for (String forbidden : taskContract.forbiddenTargets()) {
                if (sameForbiddenTarget(
                        param.value(),
                        forbidden,
                        workspace)) {
                    return ToolResult.fail(ToolError.invalidParams(
                            ToolFailureReason.PRE_APPROVAL_TARGET_FORBIDDEN,
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
                "left", "right",
                "destination", "destination_path", "dest", "target",
                "dir", "directory");
    }

    private static String preApprovalBlockReason(ToolCall call, ToolResult result) {
        String name = call == null ? "tool" : call.toolName();
        String message = result == null ? "" : result.errorMessage();
        // T758: switch on the typed reason, keep the audit prose identical.
        ToolFailureReason reason = result == null || result.error() == null
                ? ToolFailureReason.NONE
                : result.error().reason();
        if (reason == ToolFailureReason.PRE_APPROVAL_PATH_NOT_ALLOWED) {
            return "path blocked before approval"
                    + (message == null || message.isBlank() ? "" : ": " + shortReason(message));
        }
        if (reason == ToolFailureReason.PRE_APPROVAL_PATH_INVALID) {
            return "invalid path before approval"
                    + (message == null || message.isBlank() ? "" : ": " + shortReason(message));
        }
        if (reason == ToolFailureReason.PRE_APPROVAL_TARGET_FORBIDDEN) {
            return "forbidden target before approval"
                    + (message == null || message.isBlank() ? "" : ": " + shortReason(message));
        }
        if (reason == ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED) {
            return "expected target scope before approval"
                    + (message == null || message.isBlank() ? "" : ": " + shortReason(message));
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
        var workspacePlan = TurnTaskContractCapture.workspaceOperationPlan();
        if (workspacePlan.authorizes(call)) {
            return "workspace operation: "
                    + workspacePlan.authorizedOperations().getFirst().kind()
                            .name().toLowerCase();
        }
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
        if (result.contentMetadata().source() == ToolContentMetadata.ContentSource.DOCUMENT_COMPARISON) {
            return dev.talos.safety.ContentRestrictions.WITHHELD;
        }
        String code = result.error() == null ? "tool failed" : result.error().code();
        String message = result.errorMessage();
        return code + (message == null || message.isBlank() ? "" : ": " + shortReason(message));
    }

    private static String shortReason(String message) {
        String oneLine = message.replace('\r', ' ').replace('\n', ' ').strip();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 157) + "...";
    }

    private static ToolCall transformedToolCall(
            LocalTurnTraceCapture.ParsedToolCallPermit permit,
            String phase,
            ToolCall from,
            ToolCall to,
            LocalTurnTraceCapture.ToolCallTransformationReason reason) {
        if (from.equals(to)) return to;
        if (permit == null) return to;
        if (!LocalTurnTraceCapture.recordToolCallTransformedForExecution(
                permit, phase, to, reason)) {
            throw new IllegalStateException(
                    "Could not advance the exact parsed-call evidence lineage for " + reason);
        }
        return to;
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
            RuntimeTurnContext ctx,
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
        String reason = "current-turn tool surface denied " + requested + "; allowed: " + allowedText;
        TurnAuditCapture.recordBlockedToolCall(
                call,
                "",
                tracePhase,
                "current-turn tool surface denied " + requested,
                reason);
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

    private static String normalizeToolName(String toolName) {
        return ToolAliasPolicy.localCanonicalName(toolName);
    }

    private static boolean sameForbiddenTarget(
            String candidate,
            String forbidden,
            Path workspace
    ) {
        String f = normalizeScopedTargetPreservingCase(forbidden);
        if (candidate == null || candidate.isBlank() || f.isBlank()) {
            return false;
        }
        try {
            Path root = workspace == null
                    ? Path.of("").toAbsolutePath().normalize()
                    : workspace.toAbsolutePath().normalize();
            PathArgumentCanonicalizer.Resolution candidateResolution =
                    PathArgumentCanonicalizer.canonicalizeExistingPathWhitespace(
                            root,
                            candidate);
            Path candidatePath = candidateResolution.resolvedPath();
            Path forbiddenLexical = Path.of(f);
            Path forbiddenPath = forbiddenLexical.isAbsolute()
                    ? forbiddenLexical.normalize()
                    : root.resolve(forbiddenLexical).normalize();
            if (candidatePath == null
                    || !lexicallyWithin(root, candidatePath)
                    || !lexicallyWithin(root, forbiddenPath)) {
                return false;
            }
            PathIdentity identity = guardedPathIdentity(
                    root,
                    candidatePath,
                    forbiddenPath);
            return identity == PathIdentity.SAME
                    || identity == PathIdentity.UNPROVEN
                    || identity == PathIdentity.REJECTED;
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    private static boolean sameExpectedTarget(
            String toolName,
            String candidate,
            String expected,
            Path workspace
    ) {
        String e = normalizeScopedTargetPreservingCase(expected);
        if (candidate == null || candidate.isBlank() || e.isBlank()) return false;
        try {
            Path root = workspace == null
                    ? Path.of("").toAbsolutePath().normalize()
                    : workspace.toAbsolutePath().normalize();
            PathArgumentCanonicalizer.Resolution candidateResolution =
                    PathArgumentCanonicalizer.canonicalizeExistingPathWhitespace(
                            root,
                            candidate);
            Path candidatePath = candidateResolution.resolvedPath();
            Path expectedLexical = Path.of(e);
            Path expectedPath = expectedLexical.isAbsolute()
                    ? expectedLexical.normalize()
                    : root.resolve(expectedLexical).normalize();
            if (candidatePath == null
                    || !lexicallyWithin(root, candidatePath)
                    || !lexicallyWithin(root, expectedPath)) {
                return false;
            }
            if (guardedPathIdentity(root, candidatePath, expectedPath)
                    == PathIdentity.SAME) {
                return true;
            }
            if (!isMkdirTool(toolName)) return false;

            Path expectedParent = expectedPath.getParent();
            while (expectedParent != null
                    && lexicallyWithin(root, expectedParent)
                    && guardedPathIdentity(root, expectedParent, root)
                            != PathIdentity.SAME) {
                if (guardedPathIdentity(root, candidatePath, expectedParent)
                        == PathIdentity.SAME) {
                    return true;
                }
                expectedParent = expectedParent.getParent();
            }
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static PathIdentity guardedPathIdentity(
            Path root,
            Path first,
            Path second
    ) {
        Path a = first.toAbsolutePath().normalize();
        Path b = second.toAbsolutePath().normalize();
        boolean sameLexicalShape = a.toString().equals(b.toString())
                || isWindowsPathPlatform()
                        && samePathShapeIgnoringCase(a, b);
        if (!sameLexicalShape) {
            return PathIdentity.DISTINCT;
        }
        try {
            NoFollowPathPolicy.GuardedRoot guardedRoot =
                    NoFollowPathPolicy.bindExistingDirectory(root);
            NoFollowPathPolicy.EntryType firstType = guardedRoot.inspect(first);
            NoFollowPathPolicy.EntryType secondType = guardedRoot.inspect(second);
            if (a.toString().equals(b.toString())) return PathIdentity.SAME;
            if (firstType == NoFollowPathPolicy.EntryType.MISSING
                    || secondType == NoFollowPathPolicy.EntryType.MISSING) {
                return PathIdentity.UNPROVEN;
            }
            return Files.isSameFile(a, b)
                    ? PathIdentity.SAME
                    : PathIdentity.DISTINCT;
        } catch (java.io.IOException | RuntimeException identityFailure) {
            return PathIdentity.REJECTED;
        }
    }

    private enum PathIdentity {
        SAME,
        DISTINCT,
        UNPROVEN,
        REJECTED
    }

    private static boolean lexicallyWithin(Path root, Path candidate) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (normalizedCandidate.startsWith(normalizedRoot)) return true;
        if (!isWindowsPathPlatform()
                || normalizedCandidate.getNameCount() < normalizedRoot.getNameCount()
                || !sameRootIgnoringCase(normalizedCandidate, normalizedRoot)) {
            return false;
        }
        for (int index = 0; index < normalizedRoot.getNameCount(); index++) {
            if (!normalizedCandidate.getName(index).toString().equalsIgnoreCase(
                    normalizedRoot.getName(index).toString())) {
                return false;
            }
        }
        return true;
    }

    private static boolean samePathShapeIgnoringCase(Path first, Path second) {
        if (first.getNameCount() != second.getNameCount()
                || !sameRootIgnoringCase(first, second)) {
            return false;
        }
        for (int index = 0; index < first.getNameCount(); index++) {
            if (!first.getName(index).toString().equalsIgnoreCase(
                    second.getName(index).toString())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameRootIgnoringCase(Path first, Path second) {
        Path firstRoot = first.getRoot();
        Path secondRoot = second.getRoot();
        if (firstRoot == null || secondRoot == null) {
            return firstRoot == null && secondRoot == null;
        }
        return firstRoot.toString().equalsIgnoreCase(secondRoot.toString());
    }

    private static boolean isWindowsPathPlatform() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    private static List<String> orderedExpectedTargets(TaskContract taskContract) {
        if (taskContract == null || taskContract.expectedTargets().isEmpty()) return List.of();
        return taskContract.expectedTargets().stream()
                .map(TurnProcessor::normalizeScopedTargetPreservingCase)
                .filter(path -> !path.isBlank())
                .sorted()
                .toList();
    }

    private static String normalizeScopedTargetPreservingCase(String path) {
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
        return normalized;
    }

    /*
     * Build a detailed approval message for write/edit operations.
     * Shows the target path, content size/line count, and a preview
     * of the first few lines so the user can make an informed decision.
     *
     * If a scopeWarning is present, it is prepended on its own
     * line so the user sees the scope concern before the approval choice.
     */
    /**
     * Computes the unified-diff preview for the approval window (T756).
     * Only write/edit calls get one; the sandbox and pre-approval validators
     * have already run by the time this is called, and the builder itself
     * fail-closes (protected path, oversized, binary, unreadable → skipped).
     */
    private static ApprovalDiffPreview.Preview approvalDiffPreview(
            ToolCall call,
            String path,
            Path workspace
    ) {
        if (isWriteFileTool(call.toolName())) {
            String content = resolveParam(call, "content", "text", "body", "data", "file_content");
            if (content == null) return null;
            return ApprovalDiffPreview.forWrite(workspace, path, content);
        }
        if (isEditFileTool(call.toolName())) {
            String oldStr = resolveParam(call, "old_string", "oldString", "old_text", "search", "find", "original");
            String newStr = resolveParam(call, "new_string", "newString", "new_text", "replace", "replacement");
            if (oldStr == null || newStr == null) return null;
            return ApprovalDiffPreview.forEdit(workspace, path, oldStr, newStr);
        }
        return null;
    }

    private static String buildApprovalDetail(
            ToolCall call,
            String path,
            String scopeWarning,
            String permissionMessage,
            java.nio.file.Path workspace,
            dev.talos.core.Config cfg,
            ApprovalDiffPreview.Preview diffPreview,
            dev.talos.runtime.command.CommandProfileRegistry commandProfiles
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

        String workspaceOperationDetail = workspaceOperationApprovalDetail(call);
        if (workspaceOperationDetail != null) {
            sb.append(workspaceOperationDetail);
        } else if (CommandToolPlanner.isRunCommandTool(call.toolName())) {
            try {
                sb.append(ProtectedContentPolicy.sanitizeText(
                        CommandToolPlanner.approvalDetail(call, workspace, commandProfiles)));
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
            long lines = countLineSeparators(content) + 1;
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

        // Unified diff block (T756): strictly additive, appended after every
        // legacy detail line so existing prompt content stays byte-identical.
        // CliApprovalGate.inferRisk deliberately ignores everything from the
        // "diff (+" marker on - keep the marker in sync with riskScanScope().
        if (diffPreview != null && !diffPreview.skipped()) {
            sb.append("\n    diff (+").append(diffPreview.added())
                    .append(" -").append(diffPreview.removed()).append("):");
            if (!diffPreview.note().isEmpty()) {
                sb.append(' ').append(diffPreview.note());
            }
            if (!diffPreview.text().isEmpty()) {
                for (String line : diffPreview.text().split("\n", -1)) {
                    sb.append("\n    ").append(line);
                }
            }
        }

        // Format-change disclosure (T1012): any BOM, line-ending, or
        // terminal-newline difference that remains after format
        // preservation is a material byte change the line diff cannot
        // show, so it must be visible before approval.
        if (isWriteFileTool(call.toolName()) && content != null && path != null && !path.isBlank()) {
            List<String> formatChanges =
                    WriteFormatPolicy.remainingFormatChanges(workspace, path, content);
            if (!formatChanges.isEmpty()) {
                sb.append("\n    Format changes:");
                for (String change : formatChanges) {
                    sb.append("\n      ").append(sanitizeApprovalText(change));
                }
            }
        }

        return sb.toString();
    }

    private static String workspaceOperationApprovalDetail(ToolCall call) {
        var plan = TurnTaskContractCapture.workspaceOperationPlan();
        if (!plan.authorizes(call)) return null;

        var operation = plan.authorizedOperations().getFirst();
        var sb = new StringBuilder();
        sb.append("workspace operation: ")
                .append(operation.kind().name().toLowerCase());
        for (var operand : operation.operands()) {
            String label = switch (operand.role()) {
                case SOURCE -> "source";
                case DESTINATION -> "destination";
                case TARGET -> "target";
            };
            sb.append("\n    ")
                    .append(label)
                    .append(": ")
                    .append(sanitizeApprovalText(operand.effectivePath()));
        }
        sb.append("\n    overwrite: ")
                .append(operation.overwritePolicy().name().toLowerCase());
        sb.append("\n    recursive: ")
                .append(operation.recursivePolicy().name().toLowerCase());
        sb.append("\n    parent: ")
                .append(operation.parentPolicy().name().toLowerCase());
        sb.append("\n    authority plan: ").append(plan.planId());
        sb.append("\n    operation: ").append(operation.operationId());
        return sb.toString();
    }

    private static String sanitizeApprovalText(String text) {
        return ProtectedContentPolicy.sanitizeText(text);
    }

    /** Counts logical line separators (CRLF, lone CR, lone LF each count once). */
    private static long countLineSeparators(String content) {
        long count = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\r') {
                count++;
                if (i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
            } else if (c == '\n') {
                count++;
            }
        }
        return count;
    }

    private record PathParam(String name, String value) { }
}

