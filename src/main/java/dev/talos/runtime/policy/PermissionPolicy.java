package dev.talos.runtime.policy;

/** Deterministic runtime permission policy for one attempted tool call. */
public interface PermissionPolicy {
    PermissionDecision decide(PermissionRequest request);
}
