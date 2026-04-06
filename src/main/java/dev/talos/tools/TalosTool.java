package dev.talos.tools;
/**
 * Synchronous tool contract for Talos capabilities exposed to external callers.
 * Implementations wrap Talos operations (retrieval, indexing, etc.) as callable
 * tools with standardized descriptors and results.
 * <p>
 * Future MCP/tool integration layers discover tools via {@link ToolRegistry}.
 *
 * <h3>Context-aware execution</h3>
 * <p>Tools should override {@link #execute(ToolCall, ToolContext)} for
 * sandbox-checked, workspace-aware execution. The legacy no-context
 * {@link #execute(ToolCall)} delegates to the context-aware method with
 * a {@code null} context for backward compatibility.
 */
public interface TalosTool {
    /** Machine-readable tool name (e.g., "talos.retrieve", "talos.index"). */
    String name();
    /** Human-readable description of what this tool does. */
    String description();
    /** The descriptor for this tool, including parameter schema. */
    ToolDescriptor descriptor();

    /**
     * Execute the tool with workspace context (preferred).
     * The default implementation delegates to the legacy no-context method
     * for backward compatibility with existing tool implementations.
     *
     * @param call the tool call with parameters
     * @param ctx  execution context (workspace, sandbox, config) — may be null for legacy callers
     */
    default ToolResult execute(ToolCall call, ToolContext ctx) {
        return execute(call);
    }

    /** Execute the tool synchronously (legacy, no context). */
    ToolResult execute(ToolCall call);
}
