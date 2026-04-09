package dev.talos.tools;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry of available TalosTool instances.
 * Tools are discovered and executed via this registry by the runtime
 * (TurnProcessor) and future MCP/tool integration layers.
 *
 * <p>Supports fuzzy tool name resolution: if exact lookup fails, the
 * registry tries stripping common prefixes ({@code talos.}) and
 * matching well-known aliases (e.g. {@code file_write → talos.write_file}).
 */
public final class ToolRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);
    private final Map<String, TalosTool> tools = new ConcurrentHashMap<>();

    /**
     * Common aliases that models emit instead of the canonical {@code talos.}
     * name. Maps alias → canonical tool name.
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("file_write",    "talos.write_file"),
            Map.entry("write_file",    "talos.write_file"),
            Map.entry("file_read",     "talos.read_file"),
            Map.entry("read_file",     "talos.read_file"),
            Map.entry("file_edit",     "talos.edit_file"),
            Map.entry("edit_file",     "talos.edit_file"),
            Map.entry("list_dir",      "talos.list_dir"),
            Map.entry("list_directory","talos.list_dir"),
            Map.entry("dir_list",      "talos.list_dir"),
            Map.entry("grep",          "talos.grep"),
            Map.entry("search",        "talos.grep"),
            Map.entry("retrieve",      "talos.retrieve")
    );

    public void register(TalosTool tool) {
        tools.put(tool.name(), tool);
    }

    /**
     * Look up a tool by name. If exact match fails, tries:
     * <ol>
     *   <li>Adding {@code talos.} prefix</li>
     *   <li>Known alias mapping</li>
     *   <li>Stripping {@code talos.} prefix</li>
     * </ol>
     */
    public TalosTool get(String name) {
        if (name == null) return null;

        // 1. Exact match
        TalosTool tool = tools.get(name);
        if (tool != null) return tool;

        // 2. Try adding talos. prefix
        if (!name.startsWith("talos.")) {
            tool = tools.get("talos." + name);
            if (tool != null) {
                LOG.debug("Fuzzy tool match: '{}' → '{}'", name, tool.name());
                return tool;
            }
        }

        // 3. Known alias mapping
        String canonical = ALIASES.get(name);
        if (canonical != null) {
            tool = tools.get(canonical);
            if (tool != null) {
                LOG.debug("Alias tool match: '{}' → '{}'", name, canonical);
                return tool;
            }
        }

        // 4. Also try alias after stripping talos. prefix
        if (name.startsWith("talos.")) {
            canonical = ALIASES.get(name.substring(6));
            if (canonical != null) {
                tool = tools.get(canonical);
                if (tool != null) {
                    LOG.debug("Alias tool match (stripped prefix): '{}' → '{}'", name, canonical);
                    return tool;
                }
            }
        }

        return null; // genuinely unknown
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
        TalosTool tool = get(call.toolName());
        if (tool == null) {
            return ToolResult.fail(ToolError.notFound("Unknown tool: " + call.toolName()));
        }
        return tool.execute(call);
    }
    /** Execute a tool call by name with workspace context (preferred). */
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        TalosTool tool = get(call.toolName());
        if (tool == null) {
            return ToolResult.fail(ToolError.notFound("Unknown tool: " + call.toolName()));
        }
        return tool.execute(call, ctx);
    }
}
