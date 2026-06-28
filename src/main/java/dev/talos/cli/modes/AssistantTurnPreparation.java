package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.DebugLevel;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ActiveTaskContextPolicy;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.context.ProjectMemoryContext;
import dev.talos.runtime.context.ProjectMemoryLimits;
import dev.talos.runtime.context.ProjectMemoryLoader;
import dev.talos.runtime.context.ProjectMemoryRequest;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.policy.CapabilityPosturePolicy;
import dev.talos.runtime.policy.CurrentTurnPromptInstructions;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.WorkspaceTargetReconciler;
import dev.talos.runtime.toolcall.NativeToolSpecPolicy;
import dev.talos.runtime.toolcall.ToolSurfacePlanner;
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

    record PreparedTurn(Context ctx, CurrentTurnPlan plan) {}

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
        TaskContract rawTaskContract = WorkspaceTargetReconciler.reconcile(
                TaskContractResolver.fromMessages(messages),
                workspace);
        ActiveTaskContextPolicy.Decision activeDecision = activeTaskContextDecision(
                AssistantTurnExecutor.latestUserRequest(messages), rawTaskContract, ctx);
        TaskContract taskContract = WorkspaceTargetReconciler.reconcile(
                activeDecision.taskContract(),
                workspace);
        CapabilityPosturePolicy.EffectiveTurn effectiveTurn =
                CapabilityPosturePolicy.apply(capabilityPosture, taskContract);
        TaskContract effectiveTaskContract = WorkspaceTargetReconciler.reconcile(
                effectiveTurn.taskContract(),
                workspace);
        boolean activeDecisionUpdatesTurnSurface =
                activeDecisionUpdatesTurnSurface(rawTaskContract, activeDecision);
        applyActiveTaskMemoryDecision(activeDecision, ctx);
        initializeExecutionPhaseForTurn(effectiveTurn.phase(), ctx);
        Context turnContext = withNativeToolSurface(
                ctx,
                effectiveTaskContract,
                activeDecisionUpdatesTurnSurface
                        || workspaceBoundaryReplayedRequest
                        || effectiveTurn.forceNativeSurfaceRecompute());
        CurrentTurnPlan currentTurnPlan = buildCurrentTurnPlan(effectiveTaskContract, turnContext, activeDecision);
        recordPolicyTrace(currentTurnPlan, turnContext);
        ProjectMemoryContext projectMemory = loadProjectMemory(workspace, currentTurnPlan.taskContract());
        CurrentTurnPromptInstructions.injectProjectMemoryInstruction(messages, projectMemory);
        CurrentTurnPromptInstructions.replaceTaskContractInstruction(messages, currentTurnPlan);
        CurrentTurnPromptInstructions.injectStaticVerificationRepairInstruction(
                messages, currentTurnPlan.taskContract(), workspace);
        recordProjectMemoryDiagnostics(projectMemory);
        PromptAuditSnapshot promptAudit = recordPromptAudit(currentTurnPlan, messages, turnContext, projectMemory);
        recordPromptDebugDiagnostics(promptAudit);
        emitPromptAuditIfEnabled(promptAudit, turnContext);
        return new PreparedTurn(turnContext, currentTurnPlan);
    }

    private static void initializeExecutionPhaseForTurn(ExecutionPhase phase, Context ctx) {
        if (ctx == null || ctx.executionPhaseState() == null) return;
        ExecutionPhase initial = phase == null ? ExecutionPhase.INSPECT : phase;
        ctx.executionPhaseState().moveTo(initial);
    }

    private static Context withNativeToolSurface(Context ctx, TaskContract contract, boolean forceRecompute) {
        if (ctx == null || (ctx.hasNativeToolSpecOverride() && !forceRecompute)) return ctx;
        ExecutionPhase phase = ctx.executionPhaseState() == null
                ? ExecutionPhase.APPLY
                : ctx.executionPhaseState().phase();
        return ctx.withNativeToolSpecs(
                NativeToolSpecPolicy.select(contract, phase, ctx.toolRegistry()));
    }

    private static CurrentTurnPlan buildCurrentTurnPlan(
            TaskContract taskContract,
            Context ctx,
            ActiveTaskContextPolicy.Decision activeDecision
    ) {
        ExecutionPhase phase = currentExecutionPhase(ctx, taskContract);
        List<String> nativeTools = ctx == null
                ? defaultVisibleToolNames(taskContract, phase)
                : NativeToolSpecPolicy.names(ctx.nativeToolSpecs());
        String activeTaskContext = renderActiveTaskContextForPlan(activeDecision);
        String artifactGoal = renderArtifactGoalForPlan(activeDecision);
        return CurrentTurnPlan.create(
                taskContract,
                phase,
                nativeTools,
                nativeTools,
                List.of(),
                activeTaskContext,
                artifactGoal,
                CurrentTurnPlan.derivedVerifierProfile(taskContract),
                ctx == null ? null : ctx.cfg());
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
                currentUserTurnNumber(ctx));
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
        if (ctx == null || ctx.memory() == null) return 1;
        int completedUserTurns = 0;
        for (ChatMessage turn : ctx.memory().getTurns()) {
            if (turn != null && "user".equals(turn.role())) {
                completedUserTurns++;
            }
        }
        return completedUserTurns + 1;
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

    private static void recordPolicyTrace(CurrentTurnPlan plan, Context ctx) {
        if (ctx == null || !TurnAuditCapture.isActive()) return;
        TurnAuditCapture.recordPolicyTrace(TurnPolicyTrace.from(
                plan.taskContract(),
                plan.phaseInitial().name(),
                plan.nativeTools(),
                plan.promptTools()));
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

    private static void recordPromptDebugDiagnostics(PromptAuditSnapshot snapshot) {
        if (snapshot == null) return;
        if (!snapshot.compactionStatus().isBlank()
                && !PromptAuditSnapshot.NOT_DERIVED.equals(snapshot.compactionStatus())) {
            PromptDebugCapture.putTurnDiagnostic("compactionStatus", snapshot.compactionStatus());
        }
        if (!snapshot.memoryRetentionStatus().isBlank()
                && !PromptAuditSnapshot.NOT_DERIVED.equals(snapshot.memoryRetentionStatus())) {
            PromptDebugCapture.putTurnDiagnostic("memoryRetentionStatus", snapshot.memoryRetentionStatus());
        }
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
        ctx.streamSink().accept("\n" + snapshot.renderCompact() + "\n");
    }

    private static List<String> defaultVisibleToolNames(TaskContract contract, ExecutionPhase phase) {
        return ToolSurfacePlanner.defaultVisibleToolNames(contract, phase);
    }

    private static ProjectMemoryContext loadProjectMemory(Path workspace, TaskContract contract) {
        return new ProjectMemoryLoader(ProjectMemoryLimits.defaults())
                .load(new ProjectMemoryRequest(workspace, null, contract));
    }
}
