package dev.talos.runtime.repair;

public enum RepairStepType {
    REREAD_TARGET,
    APPLY_EXACT_EDIT,
    WRITE_COMPLETE_FILE,
    VERIFY_STATIC,
    STOP_AND_REPORT
}
