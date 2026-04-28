package dev.talos.runtime.repair;

public enum RepairPlanKind {
    STATIC_VERIFICATION_REPAIR,
    INVALID_EDIT_ARGUMENT_REPAIR,
    STALE_EDIT_REREAD_REPAIR,
    NO_PROGRESS_STOP,
    NOT_APPLICABLE
}
