package dev.talos.runtime.policy;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;

import java.util.List;

/** Renders a short current-turn-local capability frame from runtime state. */
public final class CurrentTurnCapabilityFrame {
    private CurrentTurnCapabilityFrame() {}

    public static String render(TaskContract contract, ExecutionPhase phase, List<String> visibleTools) {
        TaskType type = contract == null || contract.type() == null ? TaskType.UNKNOWN : contract.type();
        ExecutionPhase safePhase = phase == null ? ExecutionPhase.INSPECT : phase;
        ActionObligation obligation = ActionObligationPolicy.derive(contract, safePhase);
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
                .append("obligation: ").append(obligation.name()).append('\n');

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
        return frame.toString();
    }
}
