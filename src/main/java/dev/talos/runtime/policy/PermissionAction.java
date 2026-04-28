package dev.talos.runtime.policy;

/** Declarative permission action for one attempted tool call. */
public enum PermissionAction {
    ALLOW,
    ASK,
    DENY
}
