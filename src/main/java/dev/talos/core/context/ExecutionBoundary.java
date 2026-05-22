package dev.talos.core.context;

/** Trust boundary that produced or carried a context item. */
public enum ExecutionBoundary {
    LOCAL_WORKSPACE,
    LOCAL_RUNTIME_ARTIFACT,
    RAG_INDEX,
    SESSION_MEMORY,
    COMMAND_PROFILE_OUTPUT,
    PROMPT_DEBUG_CAPTURE,
    TRACE_ARTIFACT,
    AUDIT_WORKSPACE,
    EXTERNAL_OR_CLOUD
}
