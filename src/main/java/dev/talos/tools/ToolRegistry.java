package dev.talos.tools;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
     * Strict-mode flag. When true, {@link #get(String)} performs exact-match
     * lookup only — no {@code talos.} prefix insertion, no alias mapping, no
     * case-insensitive normalization.
     *
     * <p>This is a <b>measurement</b> knob, not a safety knob. It exists so
     * the scenario harness can observe raw model tool-name behavior instead
     * of the cushioned fuzzy-resolution behavior that production runs rely
     * on. Default is {@code false} (cushioned, production-equivalent).
     */
    private final boolean strict;

    /**
     * N5: total number of successful fuzzy/alias/case-normalization rescues
     * performed by {@link #get(String)} across the lifetime of this registry
     * instance. {@link dev.talos.runtime.ToolCallLoop} snapshots this value at
     * the start of each turn and reports the per-turn delta on
     * {@code LoopResult.cushionFiresAliasRescue()}.
     *
     * <p>In strict mode, {@link #get(String)} short-circuits before any rescue
     * branch, so this counter is never incremented and per-turn deltas remain
     * zero — which is exactly the contract strict measurement mode promises.
     */
    private final AtomicInteger aliasRescueCount = new AtomicInteger();

    /** @return total alias/fuzzy rescue fires since this registry was created. */
    public int aliasRescueCount() {
        return aliasRescueCount.get();
    }

    /** Default (non-strict) registry — preserves all existing behavior. */
    public ToolRegistry() {
        this(false);
    }

    /**
     * Create a registry with an explicit strict-mode flag.
     * @param strict if true, disable fuzzy/alias/case-normalization rescue in {@link #get(String)}
     */
    public ToolRegistry(boolean strict) {
        this.strict = strict;
    }

    /** @return true if this registry is running in strict-measurement mode. */
    public boolean isStrict() {
        return strict;
    }

    /**
     * Common aliases that models emit instead of the canonical {@code talos.}
     * name. Maps alias → canonical tool name.
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            // snake_case variants
            Map.entry("file_write",    "talos.write_file"),
            Map.entry("write_file",    "talos.write_file"),
            Map.entry("file_create",   "talos.write_file"),
            Map.entry("create_file",   "talos.write_file"),
            Map.entry("file_read",     "talos.read_file"),
            Map.entry("read_file",     "talos.read_file"),
            Map.entry("file_edit",     "talos.edit_file"),
            Map.entry("edit_file",     "talos.edit_file"),
            Map.entry("list_dir",      "talos.list_dir"),
            Map.entry("list_directory","talos.list_dir"),
            Map.entry("dir_list",      "talos.list_dir"),
            // Unix muscle-memory: models trained on shell transcripts frequently
            // emit bare `ls` (and, via the separator-rewrite above, `talos:ls`
            // → `talos.ls` → alias lookup of "ls"). Observed: gemma4:26b,
            // test-output.txt Apr 2026 — two wasted tool-loop iterations on
            // "Unknown tool: ls" / "Unknown tool: talos:ls" before abandoning
            // the listing attempt. One entry closes both.
            Map.entry("ls",            "talos.list_dir"),
            Map.entry("grep",          "talos.grep"),
            Map.entry("search",        "talos.grep"),
            Map.entry("retrieve",      "talos.retrieve"),
            // camelCase variants (models frequently emit these)
            Map.entry("writefile",     "talos.write_file"),
            Map.entry("createfile",    "talos.write_file"),
            Map.entry("readfile",      "talos.read_file"),
            Map.entry("editfile",      "talos.edit_file"),
            Map.entry("listdir",       "talos.list_dir"),
            Map.entry("listdirectory", "talos.list_dir"),
            Map.entry("grepsearch",    "talos.grep")
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
     *   <li>Case-insensitive / camelCase normalization</li>
     * </ol>
     */
    public TalosTool get(String name) {
        if (name == null) return null;

        // Separator normalization: local models frequently emit "talos:X",
        // "talos/X", "talos-X", "talos_X" instead of the canonical "talos.X"
        // (observed: gemma4:26b mixed colon and dot in the same turn,
        // wasting two tool-loop iterations on "Unknown tool" errors). Rewrite
        // any non-dot separator immediately after the "talos" prefix once
        // before the cache lookup. Bounded to the prefix so unrelated tokens
        // containing these characters (e.g., an embedded path) are untouched.
        if (name.length() > 5) {
            char c = name.charAt(5);
            if ((c == ':' || c == '/' || c == '-' || c == '_')
                    && name.regionMatches(true, 0, "talos", 0, 5)) {
                name = "talos." + name.substring(6);
            }
        }

        // 1. Exact match
        TalosTool tool = tools.get(name);
        if (tool != null) return tool;

        // Strict measurement mode: no fuzzy rescue. Return null so the
        // caller produces a clean "Unknown tool" error that reflects the
        // raw model output.
        if (strict) {
            return null;
        }

        // 2. Try adding talos. prefix
        if (!name.startsWith("talos.")) {
            tool = tools.get("talos." + name);
            if (tool != null) {
                aliasRescueCount.incrementAndGet();
                LOG.debug("Fuzzy tool match: '{}' → '{}'", name, tool.name());
                return tool;
            }
        }

        // 3. Known alias mapping
        String canonical = ALIASES.get(name);
        if (canonical != null) {
            tool = tools.get(canonical);
            if (tool != null) {
                aliasRescueCount.incrementAndGet();
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
                    aliasRescueCount.incrementAndGet();
                    LOG.debug("Alias tool match (stripped prefix): '{}' → '{}'", name, canonical);
                    return tool;
                }
            }
        }

        // 5. Case-insensitive normalization: lowercase the name (handles camelCase
        //    like writeFile → writefile, ReadFile → readfile) and retry alias lookup
        String lowered = name.toLowerCase(java.util.Locale.ROOT);
        if (!lowered.equals(name)) {
            // Try exact match with lowered name
            tool = tools.get(lowered);
            if (tool != null) {
                aliasRescueCount.incrementAndGet();
                LOG.debug("Case-normalized tool match: '{}' → '{}'", name, tool.name());
                return tool;
            }
            // Try talos. prefix with lowered name
            if (!lowered.startsWith("talos.")) {
                tool = tools.get("talos." + lowered);
                if (tool != null) {
                    aliasRescueCount.incrementAndGet();
                    LOG.debug("Case-normalized tool match: '{}' → '{}'", name, tool.name());
                    return tool;
                }
            }
            // Try alias lookup with lowered name
            canonical = ALIASES.get(lowered);
            if (canonical != null) {
                tool = tools.get(canonical);
                if (tool != null) {
                    aliasRescueCount.incrementAndGet();
                    LOG.debug("Case-normalized alias match: '{}' → '{}'", name, canonical);
                    return tool;
                }
            }
            // Try alias after stripping talos. prefix from lowered name
            if (lowered.startsWith("talos.")) {
                canonical = ALIASES.get(lowered.substring(6));
                if (canonical != null) {
                    tool = tools.get(canonical);
                    if (tool != null) {
                        aliasRescueCount.incrementAndGet();
                        LOG.debug("Case-normalized alias match (stripped): '{}' → '{}'", name, canonical);
                        return tool;
                    }
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
    /** Execute a tool call by name with workspace context (preferred). */
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        TalosTool tool = get(call.toolName());
        if (tool == null) {
            return ToolResult.fail(ToolError.notFound("Unknown tool: " + call.toolName()));
        }
        return tool.execute(call, ctx);
    }
}
