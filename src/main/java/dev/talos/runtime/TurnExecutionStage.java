package dev.talos.runtime;

/** Closed runtime-owned stages used to locate an abnormal turn settlement. */
public enum TurnExecutionStage {
    TURN_INITIALIZATION,
    MODE_ROUTING,
    RETRIEVAL_PREPARATION,
    ENGINE_CAPABILITY_PREPARATION,
    ASSISTANT_PREPARATION,
    ASSISTANT_EXECUTION,
    GENERATION_COORDINATION,
    TOOL_EXECUTION,
    VERIFICATION,
    TURN_FINALIZATION,
    UNKNOWN
}
