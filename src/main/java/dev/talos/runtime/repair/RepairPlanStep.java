package dev.talos.runtime.repair;

public record RepairPlanStep(
        RepairStepType type,
        String targetPath,
        String reason,
        String instruction,
        boolean mustHappenBeforeMutation
) {
    public RepairPlanStep {
        type = type == null ? RepairStepType.STOP_AND_REPORT : type;
        targetPath = safe(targetPath);
        reason = safe(reason);
        instruction = safe(instruction);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
