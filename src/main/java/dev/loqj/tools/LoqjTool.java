package dev.loqj.tools;
/**
 * Minimal tool contract for future MCP/tool exposure.
 * This seam exists to avoid blocking future tool integration.
 * Implementations will wrap LOQ-J capabilities (retrieval, indexing, etc.)
 * as callable tools with standardized descriptors and results.
 *
 * NOT fully implemented in this pass. This is a forward-looking interface.
 */
public interface LoqjTool {
    /** Machine-readable tool name (e.g., "loqj.retrieve", "loqj.index"). */
    String name();
    /** Human-readable description of what this tool does. */
    String description();
    /** Execute the tool with the given input and return a result. */
    ToolResult execute(ToolCall call);
    /** Describes the tool's parameters and capabilities. */
    record ToolDescriptor(String name, String description, String parametersSchema) {}
    /** A call to a tool with named string parameters. */
    record ToolCall(String toolName, java.util.Map<String, String> parameters) {}
    /** Result of a tool execution. */
    record ToolResult(boolean success, String output, String error) {
        public static ToolResult ok(String output)      { return new ToolResult(true, output, null); }
        public static ToolResult fail(String error)     { return new ToolResult(false, null, error); }
    }
}
