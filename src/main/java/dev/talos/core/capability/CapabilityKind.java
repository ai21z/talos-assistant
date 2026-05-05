package dev.talos.core.capability;

/**
 * Product-level capability categories used by Talos runtime policy and tool
 * metadata. These values describe what kind of user-visible work an operation
 * supports, independent of the model backend that requested it.
 */
public enum CapabilityKind {
    INSPECT,
    CREATE,
    EDIT,
    ORGANIZE,
    DELETE,
    VERIFY,
    EXECUTE,
    ARTIFACT
}
