package dev.talos.runtime.repair;

import java.util.List;

public record RepairPlan(
        String planId,
        RepairPlanKind kind,
        List<RepairPlanStep> steps,
        RepairAttemptBudget budget,
        String userVisibleSummary,
        boolean mutationAllowed,
        boolean requiresApproval,
        boolean requiresCheckpoint,
        List<String> verifierProblemsUsed,
        List<String> expectedTargets,
        List<String> forbiddenTargets,
        String instruction
) {
    public RepairPlan {
        planId = safe(planId);
        kind = kind == null ? RepairPlanKind.NOT_APPLICABLE : kind;
        steps = steps == null ? List.of() : List.copyOf(steps);
        budget = budget == null ? RepairAttemptBudget.defaults() : budget;
        userVisibleSummary = safe(userVisibleSummary);
        verifierProblemsUsed = verifierProblemsUsed == null ? List.of() : List.copyOf(verifierProblemsUsed);
        expectedTargets = expectedTargets == null ? List.of() : List.copyOf(expectedTargets);
        forbiddenTargets = forbiddenTargets == null ? List.of() : List.copyOf(forbiddenTargets);
        instruction = safe(instruction);
    }

    public String traceSummary() {
        return kind + " steps=" + steps.size() + " problems=" + verifierProblemsUsed.size();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
