package dev.talos.tools;

import java.util.Objects;

/**
 * Describes a tool's identity, purpose, and parameter schema.
 * Used for tool discovery and documentation by external callers (MCP, agent layers).
 */
public record ToolDescriptor(String name, String description, String parametersSchema) {
    public ToolDescriptor {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
    }

    /** Convenience constructor for tools without a formal schema. */
    public ToolDescriptor(String name, String description) {
        this(name, description, null);
    }
}

