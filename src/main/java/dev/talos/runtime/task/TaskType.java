package dev.talos.runtime.task;

/** Coarse current-turn task type derived deterministically from user text. */
public enum TaskType {
    SMALL_TALK,
    DIRECTORY_LISTING,
    READ_ONLY_QA,
    WORKSPACE_EXPLAIN,
    DIAGNOSE_ONLY,
    FILE_EDIT,
    FILE_CREATE,
    CHECKPOINT_RESTORE,
    VERIFY_ONLY,
    UNKNOWN
}
