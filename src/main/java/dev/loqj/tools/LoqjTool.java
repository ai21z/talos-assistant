package dev.loqj.tools;
/**
 * Synchronous tool contract for LOQ-J capabilities exposed to external callers.
 * Implementations wrap LOQ-J operations (retrieval, indexing, etc.) as callable
 * tools with standardized descriptors and results.
 * <p>
 * Future MCP/tool integration layers discover tools via {@link ToolRegistry}.
 */
public interface LoqjTool {
    /** Machine-readable tool name (e.g., "loqj.retrieve", "loqj.index"). */
    String name();
    /** Human-readable description of what this tool does. */
    String description();
    /** The descriptor for this tool, including parameter schema. */
    ToolDescriptor descriptor();
    /** Execute the tool synchronously with the given call and return a result. */
    ToolResult execute(ToolCall call);
}
