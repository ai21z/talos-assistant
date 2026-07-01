package dev.talos.runtime.policy;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.turn.CurrentTurnPlan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Adapts current-turn plan and gathered tool evidence into an evidence policy verdict. */
public record EvidenceObligationAssessment(
        EvidenceObligation obligation,
        EvidenceObligationVerifier.Result result
) {
    public EvidenceObligationAssessment {
        obligation = obligation == null ? EvidenceObligation.NONE : obligation;
        result = result == null
                ? EvidenceObligationVerifier.Result.satisfied("No workspace evidence was required.")
                : result;
    }

    public static EvidenceObligationAssessment assess(
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult,
            Path workspace
    ) {
        if (plan == null) {
            return new EvidenceObligationAssessment(
                    EvidenceObligation.NONE,
                    EvidenceObligationVerifier.Result.satisfied("No current-turn plan was available."));
        }
        EvidenceObligation obligation = EvidenceObligationPolicy.parse(plan.evidenceObligation());
        Set<String> targets = evidenceTargets(plan.taskContract());
        if (obligation == EvidenceObligation.READ_TARGET_REQUIRED) {
            // T900: never require reading an inferred static-web satellite that is not on
            // disk and was not named. Requiring a read of a nonexistent file is always a
            // false block (e.g. plan-mode "redesign this page" on a single-file index.html).
            targets = EvidenceGate.withoutAbsentInferredStaticWebSatellites(
                    plan.taskContract(), targets, workspace);
            if (targets.isEmpty()) {
                return new EvidenceObligationAssessment(
                        obligation,
                        EvidenceObligationVerifier.Result.satisfied(
                                "Inferred static-web satellites were absent on disk; "
                                        + "no readable target was required."));
            }
        }
        EvidenceObligationVerifier.Result result = EvidenceObligationVerifier.verify(
                obligation,
                targets,
                evidenceOutcomes(loopResult),
                workspace);
        return new EvidenceObligationAssessment(obligation, result);
    }

    public boolean missingEvidence() {
        return result.status() == EvidenceObligationVerifier.Status.UNSATISFIED;
    }

    public boolean protectedReadApprovalMissing() {
        return obligation == EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED && missingEvidence();
    }

    private static Set<String> evidenceTargets(TaskContract contract) {
        if (contract == null) return Set.of();
        if (!contract.sourceEvidenceTargets().isEmpty()) {
            return contract.sourceEvidenceTargets();
        }
        return contract.expectedTargets();
    }

    private static List<ToolCallLoop.ToolOutcome> evidenceOutcomes(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null) return List.of();
        if (loopResult.toolOutcomes() != null && !loopResult.toolOutcomes().isEmpty()) {
            return loopResult.toolOutcomes();
        }
        if (loopResult.toolNames() == null || loopResult.toolNames().isEmpty()) {
            return List.of();
        }
        List<ToolCallLoop.ToolOutcome> outcomes = new ArrayList<>();
        List<String> readPaths = loopResult.readPaths() == null ? List.of() : loopResult.readPaths();
        int readPathIndex = 0;
        for (String toolName : loopResult.toolNames()) {
            String pathHint = "";
            if ("talos.read_file".equals(toolName) && readPathIndex < readPaths.size()) {
                pathHint = readPaths.get(readPathIndex++);
            }
            outcomes.add(new ToolCallLoop.ToolOutcome(
                    toolName, pathHint, true, false, false, "", ""));
        }
        return outcomes;
    }
}
