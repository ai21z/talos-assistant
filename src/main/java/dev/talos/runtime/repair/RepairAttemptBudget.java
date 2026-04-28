package dev.talos.runtime.repair;

public record RepairAttemptBudget(
        int maxRepairPlansPerTurn,
        int maxRepairPromptsPerPath,
        int maxFailedMutationsPerTarget,
        int maxNoProgressIterations
) {
    public RepairAttemptBudget {
        maxRepairPlansPerTurn = Math.max(1, maxRepairPlansPerTurn);
        maxRepairPromptsPerPath = Math.max(1, maxRepairPromptsPerPath);
        maxFailedMutationsPerTarget = Math.max(1, maxFailedMutationsPerTarget);
        maxNoProgressIterations = Math.max(1, maxNoProgressIterations);
    }

    public static RepairAttemptBudget defaults() {
        return new RepairAttemptBudget(1, 1, 2, 3);
    }
}
