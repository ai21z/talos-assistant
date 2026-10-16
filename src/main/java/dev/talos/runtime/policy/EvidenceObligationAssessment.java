package dev.talos.runtime.policy;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.turn.CurrentTurnPlan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
        var workspaceResults = WorkspaceResultEvidencePolicy.assess(plan, loopResult, workspace);
        if (workspaceResults.applicable())
            return new EvidenceObligationAssessment(obligation, workspaceResults.aggregate());
        if (obligation == EvidenceObligation.PROJECT_SUMMARY_EVIDENCE_REQUIRED)
            return new EvidenceObligationAssessment(obligation,
                    ProjectSummaryEvidencePolicy.assess(workspace, plan.taskContract(), loopResult).result());
        Set<String> targets = evidenceTargets(plan.taskContract(), loopResult);
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
        List<ToolCallLoop.ToolOutcome> outcomes = evidenceOutcomes(loopResult);
        EvidenceObligationVerifier.Result coverage = assessCoverage(plan, loopResult, obligation, targets, outcomes, workspace);
        if (coverage != null && coverage.status() != EvidenceObligationVerifier.Status.SATISFIED)
            return new EvidenceObligationAssessment(obligation, coverage);
        if (coverage != null && obligation == EvidenceObligation.READ_TARGET_REQUIRED)
            outcomes = loopResult.readEvidenceCoverage().effectiveOutcomes(outcomes);
        EvidenceObligationVerifier.Result result = EvidenceObligationVerifier.verify(
                obligation,
                targets,
                outcomes,
                workspace,
                explicitReadOnlyTargets(plan.taskContract(), targets));
        return new EvidenceObligationAssessment(obligation, result);
    }

    /** Read outcomes are assessed without substituting current filesystem content. */
    public static EvidenceObligationAssessment assessObserved(
            CurrentTurnPlan plan, ToolCallLoop.LoopResult loopResult) {
        return assessObserved(plan, loopResult, null);
    }

    public static EvidenceObligationAssessment assessObserved(
            CurrentTurnPlan plan, ToolCallLoop.LoopResult loopResult, Path workspace) {
        if (plan == null) return assess(null, loopResult, null);
        EvidenceObligation obligation = EvidenceObligationPolicy.parse(plan.evidenceObligation());
        var workspaceResults = WorkspaceResultEvidencePolicy.assess(plan, loopResult, workspace);
        if (workspaceResults.applicable())
            return new EvidenceObligationAssessment(obligation, workspaceResults.aggregate());
        if (obligation == EvidenceObligation.PROJECT_SUMMARY_EVIDENCE_REQUIRED)
            return new EvidenceObligationAssessment(obligation,
                    ProjectSummaryEvidencePolicy.assess(workspace, plan.taskContract(), loopResult).result());
        Set<String> targets = evidenceTargets(plan.taskContract(), loopResult);
        List<ToolCallLoop.ToolOutcome> outcomes = evidenceOutcomes(loopResult);
        EvidenceObligationVerifier.Result coverage = assessCoverage(plan, loopResult, obligation, targets, outcomes, workspace);
        if (coverage != null && coverage.status() != EvidenceObligationVerifier.Status.SATISFIED)
            return new EvidenceObligationAssessment(obligation, coverage);
        // Unlisted static-web sources may be linked dependencies, not proven optional reads.
        boolean scopedReadAssessment = coverage != null && obligation == EvidenceObligation.READ_TARGET_REQUIRED;
        if (scopedReadAssessment) outcomes = loopResult.readEvidenceCoverage().effectiveOutcomes(outcomes);
        if (!scopedReadAssessment && outcomes.stream().anyMatch(ToolCallLoop.ToolOutcome::denied)) {
            return new EvidenceObligationAssessment(obligation, EvidenceObligationVerifier.Result.blocked(
                    "An attempted diagnosis operation was blocked by approval."));
        }
        if (!scopedReadAssessment && outcomes.stream().anyMatch(outcome -> !outcome.success())) {
            return new EvidenceObligationAssessment(obligation, EvidenceObligationVerifier.Result.unsatisfied(
                    "An attempted diagnosis operation did not produce successful evidence."));
        }
        if (loopResult != null && (!scopedReadAssessment && loopResult.failedCalls() > 0 || loopResult.failureDecision().shouldStop()
                && !dev.talos.runtime.toolcall.TerminalReadOnlyStopAnswer.rejectedProposalSynthesis(loopResult))) {
            return new EvidenceObligationAssessment(obligation, EvidenceObligationVerifier.Result.unsatisfied(
                    "The diagnosis was stopped before successful completion."));
        }
        return new EvidenceObligationAssessment(obligation, EvidenceObligationVerifier.verify(
                obligation, targets, outcomes, null,
                explicitReadOnlyTargets(plan.taskContract(), targets)));
    }

    private static EvidenceObligationVerifier.Result assessCoverage(
            CurrentTurnPlan plan, ToolCallLoop.LoopResult loop, EvidenceObligation obligation,
            Set<String> targets, List<ToolCallLoop.ToolOutcome> outcomes, Path workspace) {
        if (loop == null || !loop.readEvidenceCoverage().captured()
                || plan.taskContract() == null || plan.taskContract().mutationAllowed()
                || plan.taskContract().mutationRequested()
                || obligation != EvidenceObligation.READ_TARGET_REQUIRED
                    && obligation != EvidenceObligation.STATIC_WEB_DIAGNOSIS_REQUIRED) return null;
        Set<String> mandatory = obligation == EvidenceObligation.READ_TARGET_REQUIRED
                ? EvidenceObligationVerifier.withoutUnsupportedTargetsCoveredByTextFallback(targets, outcomes)
                : explicitReadOnlyTargets(plan.taskContract(), targets);
        Set<String> applicable = obligation == EvidenceObligation.READ_TARGET_REQUIRED ? mandatory : targets;
        return loop.readEvidenceCoverage().assess(applicable, mandatory, outcomes, workspace, plan.taskContract());
    }

    private static Set<String> explicitReadOnlyTargets(TaskContract contract, Set<String> evidenceTargets) {
        if (contract == null || contract.mutationRequested() || contract.mutationAllowed()) return Set.of();
        Set<String> named = TaskContractResolver.extractExpectedTargets(contract.originalUserRequest());
        return evidenceTargets.stream().filter(named::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean missingEvidence() {
        return result.status() == EvidenceObligationVerifier.Status.UNSATISFIED;
    }

    public boolean protectedReadApprovalMissing() {
        return obligation == EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED && missingEvidence();
    }

    private static Set<String> evidenceTargets(TaskContract contract, ToolCallLoop.LoopResult loop) {
        if (contract == null) return Set.of();
        if (loop != null && loop.readEvidenceCoverage().captured()
                && !contract.mutationAllowed() && !contract.mutationRequested()) {
            Set<String> targets = new LinkedHashSet<>(contract.expectedTargets());
            targets.addAll(contract.sourceEvidenceTargets());
            return java.util.Collections.unmodifiableSet(targets);
        }
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
