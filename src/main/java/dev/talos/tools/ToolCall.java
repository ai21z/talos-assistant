package dev.talos.tools;

import java.util.Map;
import java.util.Objects;

/**
 * Represents a request to execute a tool with named string parameters.
 * Immutable. Created by callers (agent layers, MCP adapters) and passed to tools.
 */
public record ToolCall(String toolName, Map<String, String> parameters) {
    public ToolCall {
        Objects.requireNonNull(toolName, "toolName must not be null");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    /** Convenience: get a single parameter value, or null if absent. */
    public String param(String key) {
        return parameters.get(key);
    }

    /** Convenience: get a parameter value with a default if absent. */
    public String param(String key, String defaultValue) {
        return parameters.getOrDefault(key, defaultValue);
    }

    /** Canonical Talos tool name for policy, evidence, and guard decisions. */
    public String canonicalToolName() {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted()
                && decision.canonicalToolName() != null
                && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName;
    }

    /** Local canonical name without the {@code talos.} prefix, for compact comparisons. */
    public String localCanonicalToolName() {
        return ToolAliasPolicy.localCanonicalName(toolName);
    }
}

