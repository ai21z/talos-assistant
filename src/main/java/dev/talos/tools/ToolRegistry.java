package dev.talos.tools;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
/**
 * Registry of available TalosTool instances.
 * Future MCP/tool integration layers discover tools via this registry.
 */
public final class ToolRegistry {
    private final Map<String, TalosTool> tools = new ConcurrentHashMap<>();
    public void register(TalosTool tool) {
        tools.put(tool.name(), tool);
    }
    public TalosTool get(String name) {
        return tools.get(name);
    }
    public Map<String, TalosTool> all() {
        return Map.copyOf(tools);
    }
    /** List descriptors of all registered tools (for MCP discovery). */
    public List<ToolDescriptor> descriptors() {
        return tools.values().stream()
                .map(TalosTool::descriptor)
                .collect(Collectors.toUnmodifiableList());
    }
    /** Execute a tool call by name, returning a ToolResult. */
    public ToolResult execute(ToolCall call) {
        TalosTool tool = tools.get(call.toolName());
        if (tool == null) {
            return ToolResult.fail(ToolError.notFound("Unknown tool: " + call.toolName()));
        }
        return tool.execute(call);
    }
}
