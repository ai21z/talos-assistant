package dev.talos.core.context;

/** Runtime source that produced a context item. */
public enum ContextItemSource {
    USER_PROMPT,
    SYSTEM_FRAME,
    TOOL_RESULT,
    RAG_SNIPPET,
    SYMBOL_HIT,
    SESSION_MEMORY,
    PROJECT_MEMORY,
    COMMAND_OUTPUT,
    PROMPT_DEBUG,
    TRACE,
    AUDIT_ARTIFACT,
    EXTERNAL_REQUEST
}
