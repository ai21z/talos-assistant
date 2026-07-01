package dev.talos.runtime.context;

/** Deterministic project-memory source tier. */
public enum ProjectMemoryTier {
    USER_GLOBAL,
    REPO_ROOT,
    WORKSPACE_ROOT,
    DIRECTORY_LOCAL
}
