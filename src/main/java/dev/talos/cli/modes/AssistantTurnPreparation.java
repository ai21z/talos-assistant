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
import dev.talos.runtime.policy.CurrentTurnCapabilityFrame;
import dev.talos.runtime.repair.RepairPolicy;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

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
        TaskContract rawTaskContract = WorkspaceTargetReconciler.reconcile(
                TaskContractResolver.fromMessages(messages),
                workspace);
        ActiveTaskContextPolicy.Decision activeDecision = activeTaskContextDecision(
                AssistantTurnExecutor.latestUserRequest(messages), rawTaskContract, ctx);
        TaskContract taskContract = WorkspaceTargetReconciler.reconcile(
                activeDecision.taskContract(),
                workspace);
        boolean activeDecisionUpdatesTurnSurface =
                activeDecisionUpdatesTurnSurface(rawTaskContract, activeDecision);
        applyActiveTaskMemoryDecision(activeDecision, ctx);
        initializeExecutionPhaseForTurn(taskContract, ctx);
        Context turnContext = withNativeToolSurface(
                ctx,
                taskContract,
                activeDecisionUpdatesTurnSurface || workspaceBoundaryReplayedRequest);
        CurrentTurnPlan currentTurnPlan = buildCurrentTurnPlan(taskContract, turnContext, activeDecision);
        recordPolicyTrace(currentTurnPlan, turnContext);
        ProjectMemoryContext projectMemory = loadProjectMemory(workspace, currentTurnPlan.taskContract());
        injectProjectMemoryInstruction(messages, projectMemory);
        injectTaskContractInstruction(messages, currentTurnPlan, true);
        injectStaticVerificationRepairInstruction(messages, currentTurnPlan.taskContract(), workspace);
        recordProjectMemoryDiagnostics(projectMemory);
        PromptAuditSnapshot promptAudit = recordPromptAudit(currentTurnPlan, messages, turnContext, projectMemory);
        recordPromptDebugDiagnostics(promptAudit);
        emitPromptAuditIfEnabled(promptAudit, turnContext);
        return new PreparedTurn(turnContext, currentTurnPlan);
    }

    private static void initializeExecutionPhaseForTurn(TaskContract contract, Context ctx) {
        if (ctx == null || ctx.executionPhaseState() == null) return;
        ExecutionPhase initial = CurrentTurnPlan.defaultPhaseFor(contract);
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

    static void injectProjectMemoryInstruction(List<ChatMessage> messages, ProjectMemoryContext projectMemory) {
        if (messages == null || messages.isEmpty() || projectMemory == null) return;
        messages.removeIf(AssistantTurnPreparation::isProjectMemoryInstruction);
        String rendered = projectMemory.renderForPrompt();
        if (rendered.isBlank()) return;

        int insertAt = 0;
        for (int i = 0; i < messages.size(); i++) {
            if ("system".equals(messages.get(i).role())) {
                insertAt = i + 1;
                break;
            }
        }
        messages.add(insertAt, ChatMessage.system(rendered));
    }

    static void injectTaskContractInstruction(List<ChatMessage> messages) {
        TaskContract contract = TaskContractResolver.fromMessages(messages);
        ExecutionPhase phase = CurrentTurnPlan.defaultPhaseFor(contract);
        List<String> visibleTools = defaultVisibleToolNames(contract, phase);
        injectTaskContractInstruction(messages, CurrentTurnPlan.compatibility(
                contract, phase, visibleTools, visibleTools, List.of()));
    }

    static void injectTaskContractInstruction(List<ChatMessage> messages, CurrentTurnPlan plan) {
        injectTaskContractInstruction(messages, plan, false);
    }

    static void injectTaskContractInstruction(
            List<ChatMessage> messages,
            TaskContract contract,
            ExecutionPhase phase,
            List<String> visibleTools
    ) {
        TaskContract safeContract = contract == null ? TaskContractResolver.fromMessages(messages) : contract;
        ExecutionPhase safePhase = phase == null ? CurrentTurnPlan.defaultPhaseFor(safeContract) : phase;
        injectTaskContractInstruction(messages, CurrentTurnPlan.compatibility(
                safeContract, safePhase, visibleTools, visibleTools, List.of()));
    }

    private static void injectTaskContractInstruction(
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            boolean replaceExisting
    ) {
        if (messages == null || messages.isEmpty()) return;
        if (replaceExisting) {
            messages.removeIf(AssistantTurnPreparation::isTaskContractInstruction);
        } else if (messages.stream().anyMatch(AssistantTurnPreparation::isTaskContractInstruction)) {
            return;
        }

        if (plan == null) {
            injectTaskContractInstruction(messages);
            return;
        }

        String instruction = CurrentTurnCapabilityFrame.render(plan);
        injectTaskContractInstruction(messages, instruction, replaceExisting);
    }

    private static void injectTaskContractInstruction(
            List<ChatMessage> messages,
            String instruction,
            boolean replaceExisting
    ) {
        if (messages == null || messages.isEmpty()) return;
        if (replaceExisting) {
            messages.removeIf(AssistantTurnPreparation::isTaskContractInstruction);
        } else if (messages.stream().anyMatch(AssistantTurnPreparation::isTaskContractInstruction)) {
            return;
        }

        int insertAt = messages.size();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                insertAt = i;
                break;
            }
        }
        if (insertAt == messages.size()) {
            insertAt = 0;
            for (int i = 0; i < messages.size(); i++) {
                if ("system".equals(messages.get(i).role())) {
                    insertAt = i + 1;
                    break;
                }
            }
        }
        messages.add(insertAt, ChatMessage.system(instruction));
    }

    private static List<String> defaultVisibleToolNames(TaskContract contract, ExecutionPhase phase) {
        return ToolSurfacePlanner.defaultVisibleToolNames(contract, phase);
    }

    private static ProjectMemoryContext loadProjectMemory(Path workspace, TaskContract contract) {
        return new ProjectMemoryLoader(ProjectMemoryLimits.defaults())
                .load(new ProjectMemoryRequest(workspace, null, contract));
    }

    static void injectStaticVerificationRepairInstruction(
            List<ChatMessage> messages,
            TaskContract taskContract
    ) {
        injectStaticVerificationRepairInstruction(messages, taskContract, null);
    }

    static void injectStaticVerificationRepairInstruction(
            List<ChatMessage> messages,
            TaskContract taskContract,
            Path workspace
    ) {
        if (messages == null || messages.isEmpty()) return;
        removeSupersededStaticVerificationRepairInstructions(messages, taskContract);
        if (messages.stream().anyMatch(AssistantTurnPreparation::isStaticVerificationRepairInstruction)) {
            return;
        }
        var repairDecision = RepairPolicy.planForStaticVerification(messages, taskContract);
        repairDecision
                .plan()
                .ifPresentOrElse(plan -> {
                    String instruction = enrichStaticVerificationRepairInstruction(plan.instruction(), workspace);
                    if (instruction.isBlank()) return;
                    LocalTurnTraceCapture.recordRepair("PLANNED", plan.traceSummary());
                    int insertAt = 0;
                    for (int i = 0; i < messages.size(); i++) {
                        ChatMessage message = messages.get(i);
                        if ("system".equals(message.role())) {
                            insertAt = i + 1;
                            if (isTaskContractInstruction(message)) {
                                break;
                            }
                        }
                    }
                    messages.add(insertAt, ChatMessage.system(instruction));
                }, () -> {
                    if (repairDecision.reason().contains("targets did not overlap")) {
                        LocalTurnTraceCapture.recordRepair("SKIPPED", repairDecision.reason());
                    }
                });
    }

    private static String enrichStaticVerificationRepairInstruction(String instruction, Path workspace) {
        return RepairPolicy.enrichSelectorFactsForRepairContext(instruction, workspace);
    }

    private static void removeSupersededStaticVerificationRepairInstructions(
            List<ChatMessage> messages,
            TaskContract taskContract
    ) {
        if (messages == null || messages.isEmpty()
                || taskContract == null
                || !taskContract.mutationAllowed()
                || taskContract.expectedTargets().isEmpty()) {
            return;
        }
        Set<String> currentTargets = normalizedTargets(taskContract.expectedTargets());
        if (currentTargets.isEmpty()) return;

        List<String> removedTargets = new ArrayList<>();
        messages.removeIf(message -> {
            if (!isStaticVerificationRepairInstruction(message)) return false;
            Set<String> repairTargets = RepairPolicy.fullRewriteTargetsFromRepairContext(List.of(message));
            if (repairTargets.isEmpty() || targetsOverlap(currentTargets, repairTargets)) {
                return false;
            }
            removedTargets.addAll(repairTargets.stream().sorted().toList());
            return true;
        });
        if (!removedTargets.isEmpty()) {
            LocalTurnTraceCapture.recordRepair(
                    "SUPERSEDED",
                    "stale static repair context skipped: targets did not overlap with current task targets; "
                            + "current targets: " + String.join(", ", currentTargets.stream().sorted().toList())
                            + "; stale repair targets: " + String.join(", ", removedTargets.stream().sorted().toList()));
        }
    }

    private static Set<String> normalizedTargets(Set<String> targets) {
        Set<String> out = new LinkedHashSet<>();
        for (String target : targets == null ? Set.<String>of() : targets) {
            String normalized = normalizeTargetForRepairScope(target);
            if (!normalized.isBlank()) out.add(normalized);
        }
        return Set.copyOf(out);
    }

    private static boolean targetsOverlap(Set<String> leftTargets, Set<String> rightTargets) {
        Set<String> left = normalizedTargets(leftTargets);
        Set<String> right = normalizedTargets(rightTargets);
        for (String target : left) {
            if (right.contains(target)) return true;
        }
        return false;
    }

    private static String normalizeTargetForRepairScope(String raw) {
        if (raw == null) return "";
        String normalized = raw.strip()
                .replace('\\', '/')
                .replaceAll("^[`'\"(\\[]+", "")
                .replaceAll("[`'\"),.;:!?\\]]+$", "");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static boolean isTaskContractInstruction(ChatMessage message) {
        return message != null
                && "system".equals(message.role())
                && message.content() != null
                && (message.content().startsWith("[TaskContract]")
                || message.content().startsWith("[CurrentTurnCapability]"));
    }

    private static boolean isProjectMemoryInstruction(ChatMessage message) {
        return message != null
                && "system".equals(message.role())
                && message.content() != null
                && message.content().startsWith("[ProjectMemory]");
    }

    private static boolean isStaticVerificationRepairInstruction(ChatMessage message) {
        return message != null
                && "system".equals(message.role())
                && message.content() != null
                && message.content().startsWith("[Static verification repair context]");
    }
}
