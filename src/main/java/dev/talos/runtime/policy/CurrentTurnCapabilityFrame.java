package dev.talos.runtime.policy;

import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.PromptAuditRedactor;
import dev.talos.runtime.turn.CurrentTurnPlan;

import java.util.List;

/** Renders a short current-turn-local capability frame from runtime state. */
public final class CurrentTurnCapabilityFrame {
    private CurrentTurnCapabilityFrame() {}

    public static String render(CurrentTurnPlan plan) {
        if (plan == null) {
            return render(null, ExecutionPhase.INSPECT, List.of());
        }
        return render(
                plan.taskContract(),
                plan.phaseInitial(),
                plan.nativeTools(),
                EvidenceObligationPolicy.parse(plan.evidenceObligation()),
                plan.activeTaskContext(),
                plan.artifactGoal());
    }

    public static String render(TaskContract contract, ExecutionPhase phase, List<String> visibleTools) {
        return render(
                contract,
                phase,
                visibleTools,
                EvidenceObligationPolicy.derive(
                        contract,
                        phase,
                        java.nio.file.Path.of("").toAbsolutePath()),
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED);
    }

    private static String render(
            TaskContract contract,
            ExecutionPhase phase,
            List<String> visibleTools,
            EvidenceObligation evidenceObligation,
            String activeTaskContext,
            String artifactGoal
    ) {
        TaskType type = contract == null || contract.type() == null ? TaskType.UNKNOWN : contract.type();
        ExecutionPhase safePhase = phase == null ? ExecutionPhase.INSPECT : phase;
        ActionObligation obligation = ActionObligationPolicy.derive(contract, safePhase);
        EvidenceObligation evidence = evidenceObligation == null
                ? EvidenceObligation.NONE
                : evidenceObligation;
        boolean mutationAllowed = contract != null && contract.mutationAllowed();
        boolean verificationRequired = contract != null && contract.verificationRequired();
        String tools = visibleTools == null || visibleTools.isEmpty()
                ? "(none)"
                : String.join(", ", visibleTools);

        StringBuilder frame = new StringBuilder();
        frame.append("[CurrentTurnCapability]\n")
                .append("[TaskContract]\n")
                .append("type: ").append(type.name()).append('\n')
                .append("mutationAllowed: ").append(mutationAllowed).append('\n')
                .append("verificationRequired: ").append(verificationRequired).append('\n')
                .append("phase: ").append(safePhase.name()).append('\n')
                .append("visibleTools: ").append(tools).append('\n')
                .append("obligation: ").append(obligation.name()).append('\n')
                .append("evidenceObligation: ").append(evidence.name()).append('\n');
        appendActiveTaskContext(frame, activeTaskContext, artifactGoal);

        switch (obligation) {
            case MUTATING_TOOL_REQUIRED -> frame.append("""
                    Available mutating tools: talos.write_file, talos.edit_file.
                    Use file tools to apply the requested workspace change in this turn.
                    Runtime handles approval, permissions, checkpointing, and verification.
                    Do not say you lack filesystem or workspace access.
                    Do not provide manual snippets instead of acting unless a narrow clarification is genuinely required.""");
            case LIST_DIR_ONLY -> frame.append("""
                    This turn asks only for directory entries.
                    Use only talos.list_dir.
                    Do not read, grep, retrieve, summarize, write, or edit file contents.""");
            case INSPECT_REQUIRED -> frame.append("""
                    This turn is read-only workspace inspection.
                    Use read-only tools to inspect evidence before answering.
                    Do not call talos.write_file or talos.edit_file.
                    If you identify a possible fix, describe it and wait for an explicit change request before editing.""");
            case VERIFY_FROM_EVIDENCE -> frame.append("""
                    This turn is verify/status-oriented.
                    Use read-only evidence or prior verified outcomes.
                    Do not call talos.write_file or talos.edit_file.
                    If you identify a possible fix, describe it and wait for an explicit change request before editing.""");
            case DIRECT_ANSWER_ONLY -> frame.append("""
                    This turn is conversational or capability-oriented.
                    No workspace tools are visible.
                    Do not call tools.
                    Answer directly from Talos product identity/capability only.""");
            case REPAIR_FROM_VERIFIER_FINDINGS -> frame.append("""
                    Repair must be based on previous verifier findings and remain bounded.
                    Use the visible file tools only if mutation is allowed.""");
            case NONE, UNKNOWN -> frame.append("""
                    Follow the visible tool surface and task contract.
                    Do not claim unavailable workspace capabilities that the runtime has exposed.""");
        }
        frame.append('\n').append(evidenceGuidance(evidence));
        return frame.toString();
    }

    private static void appendActiveTaskContext(
            StringBuilder frame,
            String activeTaskContext,
            String artifactGoal
    ) {
        boolean hasActiveTaskContext = isActiveContextForModel(activeTaskContext);
        boolean hasArtifactGoal = hasActiveTaskContext && isDerived(artifactGoal);
        if (!hasActiveTaskContext) {
            return;
        }
        frame.append("[ActiveTaskContext]\n")
                .append("activeTaskContext: ")
                .append(hasActiveTaskContext ? promptPreview(activeTaskContext) : CurrentTurnPlan.NONE_OR_NOT_DERIVED)
                .append('\n')
                .append("artifactGoal: ")
                .append(hasArtifactGoal ? promptPreview(artifactGoal) : CurrentTurnPlan.NONE_OR_NOT_DERIVED)
                .append('\n')
                .append("Active context is a current-turn hint only.\n")
                .append("Explicit current user instructions win over active context.\n")
                .append("Use active targets only for narrow deictic follow-ups.\n")
                .append("Do not broaden to unrelated workspace files because context is present.\n");
    }

    private static boolean isDerived(String value) {
        return value != null
                && !value.isBlank()
                && !CurrentTurnPlan.NONE_OR_NOT_DERIVED.equals(value);
    }

    private static boolean isActiveContextForModel(String value) {
        if (!isDerived(value)) return false;
        String trimmed = value.strip();
        return trimmed.startsWith("ACTIVE") || trimmed.contains("state=ACTIVE");
    }

    private static String promptPreview(String value) {
        return PromptAuditRedactor.preview(value, ActiveTaskContext.PROMPT_RENDER_CHAR_CAP);
    }

    private static String evidenceGuidance(EvidenceObligation evidence) {
        return switch (evidence) {
            case READ_TARGET_REQUIRED -> "Evidence: read the named target before answering.";
            case PROTECTED_READ_APPROVAL_REQUIRED ->
                    "Evidence: the named target is protected; obtain runtime approval before reading it.";
            case LIST_DIRECTORY_ONLY ->
                    "Evidence: list directory entries only; do not inspect file contents.";
            case WORKSPACE_INSPECTION_REQUIRED ->
                    "Evidence: inspect the workspace with read-only tools before answering.";
            case VERIFY_FROM_TRACE_OR_EVIDENCE ->
                    "Evidence: answer from prior trace/status evidence or fresh read-only verification.";
            case UNSUPPORTED_CAPABILITY_CHECK_REQUIRED ->
                    "Evidence: check and report unsupported document capability before relying on file contents.";
            case NONE -> "Evidence: no additional evidence obligation is derived.";
        };
    }
}
