package dev.talos.runtime.policy;

/** Public mode capability posture applied before per-turn tool-surface planning. */
public enum CapabilityPosture {
    AGENT,
    ASK_READ_ONLY,
    PLAN_READ_ONLY;

    public boolean readOnly() {
        return this == ASK_READ_ONLY || this == PLAN_READ_ONLY;
    }
}
