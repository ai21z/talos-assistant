package dev.talos.tools;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
/**
 * Registry of available TalosTool instances.
 * Tools are discovered and executed via this registry by the runtime
 * (TurnProcessor) and future MCP/tool integration layers.
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
    /** Returns true if at least one tool is registered. */
    public boolean isEmpty() {
        return tools.isEmpty();
    }
    /** List descriptors of all registered tools (for MCP discovery and system prompt). */
    public List<ToolDescriptor> descriptors() {
        return tools.values().stream()
                .map(TalosTool::descriptor)
                .collect(Collectors.toUnmodifiableList());
    }
    /** Execute a tool call by name (legacy, no context). */
    public ToolResult execute(ToolCall call) {
        TalosTool tool = tools.get(call.toolName());
        if (tool == null) {
            return ToolResult.fail(ToolError.notFound("Unknown tool: " + call.toolName()));
        }
        return tool.execute(call);
    }
    /** Execute a tool call by name with workspace context (preferred). */
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        TalosTool tool = tools.get(call.toolName());
        if (tool == null) {
            return ToolResult.fail(ToolError.notFound("Unknown tool: " + call.toolName()));
        }
        return tool.execute(call, ctx);
    }
}
