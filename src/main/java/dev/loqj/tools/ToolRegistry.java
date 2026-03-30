package dev.loqj.tools;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
/**
 * Registry of available LoqjTool instances.
 * Future MCP/tool integration layers will discover tools via this registry.
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
}
