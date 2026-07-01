package dev.talos.tools;

import java.util.Objects;

/**
 * Describes a tool's identity, purpose, parameter schema, and risk level.
 * Used for tool discovery and documentation by external callers (MCP, agent layers).
 *
 * <p>The {@link #riskLevel()} determines whether the {@link dev.talos.runtime.ApprovalGate}
 * requires user confirmation before execution. {@link ToolRiskLevel#READ_ONLY} tools
 * are auto-approved; {@link ToolRiskLevel#WRITE} and {@link ToolRiskLevel#DESTRUCTIVE}
 * tools require explicit approval.
 */
public record ToolDescriptor(
        String name,
        String description,
        String parametersSchema,
        ToolRiskLevel riskLevel,
        ToolOperationMetadata operationMetadata) {
    public ToolDescriptor {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        if (riskLevel == null) riskLevel = ToolRiskLevel.READ_ONLY;
        if (operationMetadata == null) {
            operationMetadata = ToolOperationMetadata.defaultFor(name, riskLevel);
        }
    }

    /** Constructor with schema but no explicit risk level (defaults to READ_ONLY). */
    public ToolDescriptor(String name, String description, String parametersSchema) {
        this(name, description, parametersSchema, ToolRiskLevel.READ_ONLY, null);
    }

    /** Constructor with schema and risk level, using conservative default metadata. */
    public ToolDescriptor(String name, String description, String parametersSchema, ToolRiskLevel riskLevel) {
        this(name, description, parametersSchema, riskLevel, null);
    }

    /** Convenience constructor for tools without schema or risk level. */
    public ToolDescriptor(String name, String description) {
        this(name, description, null, ToolRiskLevel.READ_ONLY, null);
    }
}

