package dev.talos.tools;
/**
 * Synchronous tool contract for Talos capabilities exposed to external callers.
 * Implementations wrap Talos operations (retrieval, indexing, etc.) as callable
 * tools with standardized descriptors and results.
 * <p>
 * Future MCP/tool integration layers discover tools via {@link ToolRegistry}.
 */
public interface TalosTool {
    /** Machine-readable tool name (e.g., "talos.retrieve", "talos.index"). */
    String name();
    /** Human-readable description of what this tool does. */
    String description();
    /** The descriptor for this tool, including parameter schema. */
    ToolDescriptor descriptor();
    /** Execute the tool synchronously with the given call and return a result. */
    ToolResult execute(ToolCall call);
}
