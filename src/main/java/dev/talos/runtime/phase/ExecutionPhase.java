package dev.talos.runtime.phase;

/** Minimal runtime phase for bounding which tool categories may execute. */
public enum ExecutionPhase {
    INSPECT,
    APPLY,
    VERIFY,
    RESPOND
}
