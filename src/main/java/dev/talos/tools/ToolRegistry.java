package dev.talos.tools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry of available TalosTool instances.
 * Tools are discovered and executed via this registry by the runtime
 * (TurnProcessor) and future MCP/tool integration layers.
 *
 * <p>Supports fuzzy tool name resolution: if exact lookup fails, the
 * registry tries stripping common prefixes ({@code talos.}) and delegates
 * known tool-name aliases to {@link ToolAliasPolicy}.
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

        name = ToolAliasPolicy.normalizeTalosSeparator(name);

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
                LOG.debug("Fuzzy tool match resolved");
                return tool;
            }
        }

        // 3. Explicit canonical/alias/backend profile policy.
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(name);
        if (decision.status() == ToolAliasPolicy.AliasDecisionStatus.REJECTED_UNKNOWN_NAMESPACE) {
            return null;
        }
        if (decision.accepted()) {
            tool = tools.get(decision.canonicalToolName());
            if (tool != null) {
                if (!tool.name().equals(name)) {
                    aliasRescueCount.incrementAndGet();
                }
                LOG.debug("Alias tool match resolved");
                return tool;
            }
        }

        // 4. Case-insensitive normalization: lowercase the name (handles camelCase
        //    like writeFile → writefile, ReadFile → readfile) and retry alias lookup
        String lowered = name.toLowerCase(java.util.Locale.ROOT);
        if (!lowered.equals(name)) {
            // Try exact match with lowered name
            tool = tools.get(lowered);
            if (tool != null) {
                aliasRescueCount.incrementAndGet();
                LOG.debug("Case-normalized exact tool match resolved");
                return tool;
            }
            // Try talos. prefix with lowered name
            if (!lowered.startsWith("talos.")) {
                tool = tools.get("talos." + lowered);
                if (tool != null) {
                    aliasRescueCount.incrementAndGet();
                    LOG.debug("Case-normalized prefixed tool match resolved");
                    return tool;
                }
            }
            // Try explicit alias policy with lowered name.
            decision = ToolAliasPolicy.resolve(lowered);
            if (decision.accepted()) {
                tool = tools.get(decision.canonicalToolName());
                if (tool != null) {
                    aliasRescueCount.incrementAndGet();
                    LOG.debug("Case-normalized alias match resolved");
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
                .toList();
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
