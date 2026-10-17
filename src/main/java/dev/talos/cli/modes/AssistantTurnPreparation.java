package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.DebugLevel;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.MutationIntent;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ActiveTaskContextPolicy;
import dev.talos.runtime.context.ContentSaveContinuation;
import dev.talos.runtime.context.ContentDraftContinuation;
import dev.talos.runtime.context.PendingContentDraft;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.context.ProjectMemoryContext;
import dev.talos.runtime.context.ProjectMemoryLimits;
import dev.talos.runtime.context.ProjectMemoryLoader;
import dev.talos.runtime.context.ProjectMemoryRequest;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.intent.TaskIntentProjector;
import dev.talos.runtime.intent.TargetSource;
import dev.talos.runtime.intent.CoordinatedMutationTailPlan;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.policy.CapabilityPosturePolicy;
import dev.talos.runtime.policy.CurrentTurnPromptInstructions;
import dev.talos.runtime.policy.ProviderPromptSurfaceReconciler;
import dev.talos.runtime.policy.ActionObligationPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.task.WorkspaceTargetReconciler;
import dev.talos.runtime.toolcall.CanonicalToolDescriptors;
import dev.talos.runtime.toolcall.NativeToolSpecPolicy;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.PromptAuditSnapshot;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.PromptDebugCapture;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

final class AssistantTurnPreparation {

    private AssistantTurnPreparation() {}

    record PreparedTurn(
            Context ctx,
            CurrentTurnPlan plan,
            TaskResolution taskResolution,
            String directAnswer,
            PendingContentDraft draftGeneration
    ) {
        PreparedTurn(Context ctx, CurrentTurnPlan plan, TaskResolution taskResolution) {
            this(ctx, plan, taskResolution, null, null);
        }
        PreparedTurn {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(taskResolution, "taskResolution");
            if (!plan.taskContract().equals(taskResolution.contract())) {
                throw new IllegalArgumentException(
                        "prepared turn plan and task resolution must share one contract");
            }
        }
    }

    /**
     * Ordered, side-effectful preparation for a single assistant turn.
     *
     * <p>This mutates turn-local prompt messages, session/phase state, and
     * trace/prompt-debug captures. Do not memoize or reorder the steps without
     * preserving the existing execute() behavior tests.
     */
    static PreparedTurn prepare(
            List<ChatMessage> messages,
            Path workspace,
            Context ctx,
            boolean workspaceBoundaryReplayedRequest
    ) {
        return prepare(
                messages,
                workspace,
                ctx,
                workspaceBoundaryReplayedRequest,
                CapabilityPosture.AGENT);
    }

    static PreparedTurn prepare(
            List<ChatMessage> messages,
            Path workspace,
            Context ctx,
            boolean workspaceBoundaryReplayedRequest,
            CapabilityPosture capabilityPosture
    ) {
        TaskResolution initialResolution =
                TaskContractResolver.resolveFromMessages(messages);
        if (workspaceBoundaryReplayedRequest) {
            initialResolution = TaskResolution.confirmedReplay(
                    initialResolution);
        }
        ContentDraftContinuation.Decision draft = ContentDraftContinuation.evaluate(
                initialResolution, ctx == null || ctx.memory() == null ? null : ctx.memory().pendingContentDraft(),
                workspace, currentUserTurnNumber(ctx), capabilityPosture, currentUserRestrictions(messages),
                path -> ctx == null || ctx.exactAnswerPresentation() == null
                        || ctx.exactAnswerPresentation().preserves().test(path));
        initialResolution = draft.resolution();
        ContentSaveContinuation.Decision saveContinuation = draft.handled()
                ? new ContentSaveContinuation.Decision(initialResolution, null, draft.directAnswer(), "", true,
                    initialResolution.intent().contentSaveRequest().draftSelection() != null)
                : ContentSaveContinuation.evaluate(
                initialResolution, ctx == null || ctx.memory() == null ? null : ctx.memory().pendingContentSave(),
                workspace, currentUserTurnNumber(ctx), capabilityPosture,
                currentUserRestrictions(messages));
        initialResolution = saveContinuation.resolution();
        TaskContract rawTaskContract = WorkspaceTargetReconciler.reconcile(
                initialResolution.contract(),
                workspace);
        TaskIntent rawTaskIntent = TaskIntentProjector.project(
                initialResolution.intent(),
                rawTaskContract,
                TargetSource.WORKSPACE_EVIDENCE,
                "workspace-target-reconciliation");
        ActiveTaskContextPolicy.Decision activeDecision = saveContinuation.handled()
                ? new ActiveTaskContextPolicy.Decision(rawTaskContract, ActiveTaskContext.none(),
                    ArtifactGoal.none(), ActiveTaskContext.none(), false)
                : activeTaskContextDecision(
                AssistantTurnExecutor.latestUserRequest(messages),
                rawTaskContract,
                initialResolution,
                ctx);
        if (ctx != null && ctx.requestFrame() != null) {
            ctx.requestFrame().adopt(initialResolution.projectOnto(activeDecision.taskContract(),
                    TaskIntentProjector.project(rawTaskIntent, activeDecision.taskContract(),
                            TargetSource.REPAIR_CONTEXT, "active-task-context-projection")));
        }
        TaskContract taskContract = WorkspaceTargetReconciler.reconcile(
                activeDecision.taskContract(),
                workspace);
        TaskIntent taskIntent = TaskIntentProjector.project(
                rawTaskIntent,
                taskContract,
                TargetSource.REPAIR_CONTEXT,
                "active-task-context-projection");
        CapabilityPosturePolicy.EffectiveTurn effectiveTurn =
                CapabilityPosturePolicy.apply(capabilityPosture, taskContract);
        TaskContract effectiveTaskContract = WorkspaceTargetReconciler.reconcile(
                effectiveTurn.taskContract(),
                workspace);
        TaskIntent effectiveTaskIntent = TaskIntentProjector.project(
                taskIntent,
                effectiveTurn.taskContract(),
                TargetSource.RUNTIME_DEFAULT,
                "capability-posture-projection");
        effectiveTaskIntent = TaskIntentProjector.project(
                effectiveTaskIntent,
                effectiveTaskContract,
                TargetSource.WORKSPACE_EVIDENCE,
                "workspace-target-reconciliation");
        TaskResolution effectiveResolution = initialResolution.projectOnto(
                effectiveTaskContract,
                effectiveTaskIntent);
        boolean activeDecisionUpdatesTurnSurface =
                activeDecisionUpdatesTurnSurface(rawTaskContract, activeDecision);
        applyActiveTaskMemoryDecision(activeDecision, ctx);
        if (ctx != null && ctx.memory() != null) {
            ctx.memory().setPendingContentSave(saveContinuation.nextPending());
            ctx.memory().setPendingContentDraft(draft.next());
        }
        ExecutionPhase authoritativePhase = effectiveResolution
                .blocksUnscopedMutation()
                ? ExecutionPhase.INSPECT
                : effectiveTurn.phase();
        initializeExecutionPhaseForTurn(authoritativePhase, ctx);
        Context turnContext = withNativeToolSurface(
                ctx,
                effectiveResolution,
                activeDecisionUpdatesTurnSurface
                        || workspaceBoundaryReplayedRequest
                        || saveContinuation.consumed()
                        || effectiveTurn.forceNativeSurfaceRecompute());
        CurrentTurnPlan currentTurnPlan = buildCurrentTurnPlan(
                effectiveResolution,
                turnContext,
                activeDecision);
        recordPolicyTrace(
                currentTurnPlan,
                effectiveResolution,
                turnContext);
        LocalTurnTraceCapture.recordMutationRequestDecision(
                MutationIntent.classifyShape(
                        AssistantTurnExecutor.latestUserRequest(messages)),
                effectiveResolution.provenance());
        ProjectMemoryContext projectMemory = loadProjectMemory(workspace, currentTurnPlan.taskContract());
        CurrentTurnPromptInstructions.injectProjectMemoryInstruction(messages, projectMemory);
        if (saveContinuation.consumed()) {
            ProviderPromptSurfaceReconciler.reconcile(messages, currentTurnPlan, turnContext.nativeToolSpecs());
        }
        CurrentTurnPromptInstructions.replaceTaskContractInstruction(messages, currentTurnPlan);
        CurrentTurnPromptInstructions.injectStaticVerificationRepairInstruction(
                messages, currentTurnPlan.taskContract(), workspace);
        if (!saveContinuation.originPromptData().isBlank()) {
            int currentUserIndex = messages.size();
            for (int index = messages.size() - 1; index >= 0; index--) {
                if ("user".equals(messages.get(index).role())) { currentUserIndex = index; break; }
            }
            messages.add(currentUserIndex, ChatMessage.user(saveContinuation.originPromptData()));
        }
        recordProjectMemoryDiagnostics(projectMemory);
        PromptAuditSnapshot promptAudit = recordPromptAudit(currentTurnPlan, messages, turnContext, projectMemory);
        recordPromptDebugDiagnostics(promptAudit, currentTurnPlan, messages);
        emitPromptAuditIfEnabled(promptAudit, turnContext);
        return new PreparedTurn(turnContext, currentTurnPlan, effectiveResolution, saveContinuation.directAnswer(), draft.generate());
    }

    private static void initializeExecutionPhaseForTurn(ExecutionPhase phase, Context ctx) {
        if (ctx == null || ctx.executionPhaseState() == null) return;
        ExecutionPhase initial = phase == null ? ExecutionPhase.INSPECT : phase;
        ctx.executionPhaseState().moveTo(initial);
    }

    private static Context withNativeToolSurface(
            Context ctx,
            TaskResolution resolution,
            boolean forceRecompute
    ) {
        TaskContract contract = resolution == null
                ? TaskContract.unknown("")
                : resolution.contract();
        boolean contractRemovesToolAuthority = contract != null && contract.type() == TaskType.SMALL_TALK;
        boolean blocksWorkspaceOperations =
                resolution != null && resolution.blocksWorkspaceOperations();
        boolean blocksUnscopedMutation =
                resolution != null && resolution.blocksUnscopedMutation();
        if (ctx == null) {
            return ctx;
        }
        if (ctx.hasNativeToolSpecOverride()
                && !forceRecompute
                && !contractRemovesToolAuthority
                && !blocksWorkspaceOperations
                && !blocksUnscopedMutation) {
            return ctx;
        }
        ExecutionPhase phase = ctx.executionPhaseState() == null
                ? ExecutionPhase.APPLY
                : ctx.executionPhaseState().phase();
        return ctx.withNativeToolSpecs(
                NativeToolSpecPolicy.select(
                        resolution,
                        phase,
                        ctx.toolRegistry(),
                        ctx.cfg()));
    }

    private static CurrentTurnPlan buildCurrentTurnPlan(
            TaskResolution resolution,
            Context ctx,
            ActiveTaskContextPolicy.Decision activeDecision
    ) {
        TaskContract taskContract = resolution == null
                ? TaskContract.unknown("")
                : resolution.contract();
        ExecutionPhase phase = currentExecutionPhase(ctx, taskContract);
        List<String> promptTools = ctx == null
                ? defaultVisibleToolNames(resolution, phase)
                : NativeToolSpecPolicy.names(ctx.nativeToolSpecs());
        boolean nativeEnabled = ctx == null
                || ctx.llm() == null
                || ctx.llm().nativeToolsCurrentlyEnabled();
        List<String> nativeTools = nativeEnabled ? promptTools : List.of();
        String activeTaskContext = renderActiveTaskContextForPlan(activeDecision);
        String artifactGoal = renderArtifactGoalForPlan(activeDecision);
        return CurrentTurnPlan.createWithActionObligation(
                resolution,
                phase,
                nativeTools,
                promptTools,
                List.of(),
                activeTaskContext,
                artifactGoal,
                CurrentTurnPlan.derivedVerifierProfile(taskContract),
                ActionObligationPolicy.derive(resolution, phase),
                ctx == null ? null : ctx.cfg());
    }

    private static List<String> defaultVisibleToolNames(
            TaskResolution resolution,
            ExecutionPhase phase
    ) {
        if (resolution == null) return List.of();
        return dev.talos.runtime.toolcall.ToolSurfacePlanner.plan(
                resolution,
                phase,
                CanonicalToolDescriptors.registry(),
                dev.talos.runtime.toolcall.ToolCapabilityAvailability
                        .allEnabled())
                .nativeToolNames();
    }

    private static String renderActiveTaskContextForPlan(ActiveTaskContextPolicy.Decision activeDecision) {
        if (activeDecision == null || activeDecision.planContext() == null) {
            return ActiveTaskContext.NONE_OR_NOT_DERIVED;
        }
        ActiveTaskContext planContext = activeDecision.planContext();
        if (planContext.state() == ActiveTaskContext.State.NONE) {
            return ActiveTaskContext.NONE_OR_NOT_DERIVED;
        }
        if (planContext.state() == ActiveTaskContext.State.ACTIVE) {
            return planContext.renderForPlan();
        }
        return "activeTaskContext{state=" + planContext.state() + "}";
    }

    private static String renderArtifactGoalForPlan(ActiveTaskContextPolicy.Decision activeDecision) {
        if (activeDecision == null || activeDecision.planContext() == null) {
            return ActiveTaskContext.NONE_OR_NOT_DERIVED;
        }
        if (activeDecision.planContext().state() != ActiveTaskContext.State.ACTIVE) {
            return ActiveTaskContext.NONE_OR_NOT_DERIVED;
        }
        return activeDecision.artifactGoal().renderForPlan();
    }

    private static ActiveTaskContextPolicy.Decision activeTaskContextDecision(
            String userRequest,
            TaskContract rawTaskContract,
            TaskResolution initialResolution,
            Context ctx
    ) {
        ActiveTaskContext savedContext = ctx == null || ctx.memory() == null
                ? ActiveTaskContext.none()
                : ctx.memory().activeTaskContext();
        ArtifactGoal savedGoal = ctx == null || ctx.memory() == null
                ? ArtifactGoal.none()
                : ctx.memory().artifactGoal();
        return ActiveTaskContextPolicy.evaluate(
                userRequest,
                rawTaskContract,
                savedContext,
                savedGoal,
                currentUserTurnNumber(ctx),
                initialResolution == null
                        || !initialResolution.sequentialMutationPlan()
                                .authorityLimitExceeded());
    }

    private static boolean activeDecisionUpdatesTurnSurface(
            TaskContract rawTaskContract,
            ActiveTaskContextPolicy.Decision decision
    ) {
        if (decision == null) return false;
        if (!Objects.equals(rawTaskContract, decision.taskContract())) return true;
        ActiveTaskContext planContext = decision.planContext();
        return planContext != null && planContext.hasPromptContext();
    }

    private static int currentUserTurnNumber(Context ctx) {
        int tracedTurn = LocalTurnTraceCapture.currentTurnNumber();
        if (tracedTurn > 0) return tracedTurn;
        if (ctx == null || ctx.memory() == null) return 1;
        int completedUserTurns = 0;
        for (ChatMessage turn : ctx.memory().getTurns()) {
            if (turn != null && "user".equals(turn.role())) {
                completedUserTurns++;
            }
        }
        return completedUserTurns + 1;
    }

    private static dev.talos.safety.ContentRestrictions currentUserRestrictions(List<ChatMessage> messages) {
        var restrictions = dev.talos.safety.ContentRestrictionScope.current();
        for (int index = messages.size() - 1; index >= 0; index--) {
            if ("user".equals(messages.get(index).role())) {
                return restrictions.intersect(messages.get(index).contentRestrictions());
            }
        }
        return restrictions;
    }

    private static void applyActiveTaskMemoryDecision(
            ActiveTaskContextPolicy.Decision decision,
            Context ctx
    ) {
        if (decision == null || ctx == null || ctx.memory() == null) return;
        ActiveTaskContext planContext = decision.planContext();
        if (planContext != null && planContext.state() == ActiveTaskContext.State.SUPPRESSED) {
            return;
        }
        ActiveTaskContext memoryContext = decision.memoryContext();
        if (memoryContext == null || memoryContext.state() == ActiveTaskContext.State.NONE) {
            ctx.memory().clearActiveTaskContext();
            return;
        }
        boolean derivedActiveUpdate = planContext != null
                && planContext.state() == ActiveTaskContext.State.ACTIVE
                && memoryContext.state() == ActiveTaskContext.State.ACTIVE
                && decision.artifactGoal().source() != ArtifactGoal.Source.NONE;
        if (derivedActiveUpdate) {
            ctx.memory().setActiveTaskContext(memoryContext);
            ctx.memory().setArtifactGoal(decision.artifactGoal());
        }
    }

    private static ExecutionPhase currentExecutionPhase(Context ctx, TaskContract contract) {
        if (ctx != null && ctx.executionPhaseState() != null) {
            return ctx.executionPhaseState().phase();
        }
        return contract != null && contract.mutationAllowed()
                ? ExecutionPhase.APPLY
                : ExecutionPhase.INSPECT;
    }

    private static void recordPolicyTrace(
            CurrentTurnPlan plan,
            TaskResolution resolution,
            Context ctx
    ) {
        if (plan != null && plan.requestObligation() != null) {
            PromptDebugCapture.putTurnDiagnostic(
                    "requestObligation.redactedProjectionJson",
                    plan.requestObligation().redactedProjectionJson());
            PromptDebugCapture.putTurnDiagnostic(
                    "requestObligation.fullCanonicalSha256",
                    plan.requestObligation().canonicalSha256());
            LocalTurnTraceCapture.recordRequestObligation(plan.requestObligation());
        }
        if (plan == null) return;
        TurnPolicyTrace policyTrace = TurnPolicyTrace.from(
                resolution,
                plan.phaseInitial().name(),
                plan.nativeTools(),
                plan.promptTools());
        String targetRoles = policyTrace.rolefulTargets().stream()
                .sorted(java.util.Comparator
                        .comparing(TurnPolicyTrace.RolefulTarget::path)
                .thenComparing(TurnPolicyTrace.RolefulTarget::role))
                .map(target -> {
                    String rendered = target.path() + " = " + target.role();
                    rendered = target.reason().isBlank()
                            ? rendered
                            : rendered + " (" + target.reason() + ")";
                    return rendered + " [sourceSpan="
                            + renderOffset(target.startOffset())
                            + ":"
                            + renderOffset(target.authorityEndOffset())
                            + ":"
                            + renderOffset(target.endOffset())
                            + "]";
                })
                .collect(java.util.stream.Collectors.joining(", "));
        if (targetRoles.isBlank()) {
            targetRoles = "(none)";
        }
        PromptDebugCapture.putTurnDiagnostic(
                PromptDebugCapture.TASK_CONTRACT_TARGET_ROLES_DIAGNOSTIC,
                targetRoles);
        if (ctx == null) return;
        if (TurnAuditCapture.isActive()) {
            TurnAuditCapture.recordPolicyTrace(
                    policyTrace,
                    plan.workspaceOperationPlan());
        } else {
            LocalTurnTraceCapture.recordPolicyTrace(policyTrace);
        }
        LocalTurnTraceCapture.recordActionObligation(
                plan.actionObligation().name(),
                "SELECTED",
                "derived from task contract and execution phase");
    }

    private static PromptAuditSnapshot recordPromptAudit(
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            Context ctx,
            ProjectMemoryContext projectMemory
    ) {
        PromptAuditSnapshot snapshot = PromptAuditSnapshot.fromPlan(
                plan,
                messages,
                ctx == null || ctx.conversationManager() == null
                        ? null
                        : ctx.conversationManager().lastCompactionStatus(),
                projectMemory == null ? PromptAuditSnapshot.NOT_DERIVED : projectMemory.renderDiagnostic(),
                memoryRetentionStatus(ctx));
        LocalTurnTraceCapture.recordPromptAudit(snapshot);
        return snapshot;
    }

    private static void recordPromptDebugDiagnostics(
            PromptAuditSnapshot snapshot,
            CurrentTurnPlan plan,
            List<ChatMessage> messages
    ) {
        if (snapshot == null) return;
        if (plan != null && plan.taskContract() != null) {
            TaskContract contract = plan.taskContract();
            PromptDebugCapture.putTurnDiagnostic(
                    PromptDebugCapture.TASK_CONTRACT_SUMMARY_DIAGNOSTIC,
                    contract.type()
                            + ", mutationAllowed="
                            + contract.mutationAllowed()
                            + ", verificationRequired="
                            + contract.verificationRequired());
            PromptDebugCapture.putTurnDiagnostic(
                    PromptDebugCapture.TASK_CONTRACT_TARGET_LABEL_DIAGNOSTIC,
                    contract.mutationAllowed()
                            ? "Expected targets"
                            : "Evidence target hints");
            PromptDebugCapture.putTurnDiagnostic(
                    PromptDebugCapture.TASK_CONTRACT_TARGETS_DIAGNOSTIC,
                    orderedExpectedTargets(contract));
            PromptDebugCapture.putTurnDiagnostic(
                    PromptDebugCapture
                            .TASK_CONTRACT_TARGET_COVERAGE_LABEL_DIAGNOSTIC,
                    contract.mutationAllowed()
                            ? "Expected-target coverage"
                            : "Evidence-target frame coverage");
            PromptDebugCapture.putTurnDiagnostic(
                    PromptDebugCapture.TASK_CONTRACT_TARGET_COVERAGE_DIAGNOSTIC,
                    expectedTargetCoverage(contract, currentTurnFrame(messages)));
        }
        if (!snapshot.compactionStatus().isBlank()
                && !PromptAuditSnapshot.NOT_DERIVED.equals(snapshot.compactionStatus())) {
            PromptDebugCapture.putTurnDiagnostic("compactionStatus", snapshot.compactionStatus());
        }
        if (!snapshot.memoryRetentionStatus().isBlank()
                && !PromptAuditSnapshot.NOT_DERIVED.equals(snapshot.memoryRetentionStatus())) {
            PromptDebugCapture.putTurnDiagnostic("memoryRetentionStatus", snapshot.memoryRetentionStatus());
        }
    }

    private static String orderedExpectedTargets(TaskContract contract) {
        if (contract == null || contract.expectedTargets().isEmpty()) {
            return "(none)";
        }
        String request = Objects.toString(
                contract.originalUserRequest(), "").toLowerCase(java.util.Locale.ROOT);
        return contract.expectedTargets().stream()
                .sorted(java.util.Comparator
                        .comparingInt((String target) ->
                                targetIndex(request, target))
                        .thenComparing(java.util.Comparator.naturalOrder()))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static int targetIndex(String request, String target) {
        if (request == null || request.isBlank() || target == null) {
            return Integer.MAX_VALUE;
        }
        int index = request.indexOf(target.toLowerCase(java.util.Locale.ROOT));
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private static String currentTurnFrame(List<ChatMessage> messages) {
        if (messages == null) return "";
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            String content =
                    message == null ? "" : Objects.toString(message.content(), "");
            if (message != null
                    && "system".equals(message.role())
                    && content.contains("[CurrentTurnCapability]")) {
                return content;
            }
        }
        return "";
    }

    private static String expectedTargetCoverage(
            TaskContract contract,
            String frame
    ) {
        if (contract == null || contract.expectedTargets().isEmpty()) {
            return "N/A";
        }
        if (!contract.mutationAllowed()) {
            return "N/A (read-only task)";
        }
        if (frame == null
                || frame.isBlank()
                || !frame.contains("[ExpectedTargets]")) {
            return "MISSING";
        }
        for (String target : contract.expectedTargets()) {
            if (!frame.contains(target)) {
                return "MISSING";
            }
        }
        return "OK";
    }

    private static String memoryRetentionStatus(Context ctx) {
        if (ctx == null || ctx.memory() == null) return PromptAuditSnapshot.NOT_DERIVED;
        SessionMemory.RetentionEvictionStats stats = ctx.memory().retentionEvictionStats();
        if (stats.rawTurnMessagesEvictedWithoutSketch() == 0 && stats.toolEvidenceEntriesEvicted() == 0) {
            return "NONE";
        }
        return "rawTurnMessagesEvictedWithoutSketch=" + stats.rawTurnMessagesEvictedWithoutSketch()
                + " toolEvidenceEntriesEvicted=" + stats.toolEvidenceEntriesEvicted();
    }

    private static String renderOffset(int offset) {
        return offset < 0 ? "unknown" : Integer.toString(offset);
    }

    private static void recordProjectMemoryDiagnostics(ProjectMemoryContext projectMemory) {
        if (projectMemory == null) return;
        PromptDebugCapture.putTurnDiagnostic("projectMemoryStatus", projectMemory.renderDiagnostic());
        String details = projectMemory.renderDebugDetails();
        if (!details.isBlank()) {
            PromptDebugCapture.putTurnDiagnostic("projectMemoryDetails", details);
        }
    }

    private static void emitPromptAuditIfEnabled(PromptAuditSnapshot snapshot, Context ctx) {
        if (snapshot == null || ctx == null || ctx.streamSink() == null || ctx.session() == null) return;
        if (ctx.session().getDebugLevel() != DebugLevel.PROMPT) return;
        PromptDebugCapture.putTurnDiagnostic("promptAudit", snapshot.renderCompact());
    }

    private static ProjectMemoryContext loadProjectMemory(Path workspace, TaskContract contract) {
        return new ProjectMemoryLoader(ProjectMemoryLimits.defaults())
                .load(new ProjectMemoryRequest(workspace, null, contract));
    }
}
