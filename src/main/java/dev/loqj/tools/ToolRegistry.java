package dev.loqj.tools;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
/**
 * Registry of available LoqjTool instances.
 * Future MCP/tool integration layers discover tools via this registry.
 */
public final class ToolRegistry {
    private final Map<String, LoqjTool> tools = new ConcurrentHashMap<>();
    public void register(LoqjTool tool) {
        tools.put(tool.name(), tool);
    }
    public LoqjTool get(String name) {
        return tools.get(name);
    }
    public Map<String, LoqjTool> all() {
        return Map.copyOf(tools);
    }
    /** List descriptors of all registered tools (for MCP discovery). */
    public List<ToolDescriptor> descriptors() {
        return tools.values().stream()
                .map(LoqjTool::descriptor)
                .collect(Collectors.toUnmodifiableList());
    }
    /** Execute a tool call by name, returning a ToolResult. */
    public ToolResult execute(ToolCall call) {
        LoqjTool tool = tools.get(call.toolName());
        if (tool == null) {
            return ToolResult.fail(ToolError.notFound("Unknown tool: " + call.toolName()));
        }
        return tool.execute(call);
    }
}
