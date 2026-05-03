package dev.talos.spi.types;

/** Provider-neutral tool choice policy requested for a chat turn. */
public enum ToolChoiceMode {
    /** Let the provider/model decide whether to call tools. */
    AUTO,
    /** Do not allow native tool calls for this request. */
    NONE,
    /** Require at least one native tool call where the provider supports it. */
    REQUIRED,
    /** Require a specific named tool where the provider supports it. */
    NAMED
}
