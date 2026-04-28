package dev.talos.runtime.repair;

public record RepairInstruction(
        RepairPlanKind kind,
        String path,
        String instruction
) {
    public RepairInstruction {
        kind = kind == null ? RepairPlanKind.NOT_APPLICABLE : kind;
        path = path == null ? "" : path.strip();
        instruction = instruction == null ? "" : instruction.strip();
    }
}
