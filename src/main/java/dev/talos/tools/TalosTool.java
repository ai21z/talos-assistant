package dev.talos.tools;
/**
 * Synchronous tool contract for Talos capabilities exposed to external callers.
 * Implementations wrap Talos operations (retrieval, indexing, etc.) as callable
 * tools with standardized descriptors and results.
 * <p>
 * Tool execution is context-aware: callers provide {@link ToolContext} so tools
 * can resolve workspace paths, enforce sandbox policy, and consult runtime
 * configuration consistently.
 */
public interface TalosTool {
    /** Machine-readable tool name (e.g., "talos.retrieve", "talos.index"). */
    String name();
    /** Human-readable description of what this tool does. */
    String description();
    /** The descriptor for this tool, including parameter schema. */
    ToolDescriptor descriptor();

    /**
     * Execute the tool with workspace context.
     *
     * @param call the tool call with parameters
     * @param ctx  execution context (workspace, sandbox, config)
     */
    ToolResult execute(ToolCall call, ToolContext ctx);
}
