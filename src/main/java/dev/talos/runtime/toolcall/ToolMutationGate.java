package dev.talos.runtime.toolcall;

import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolOperationMetadata;
import dev.talos.tools.ToolRegistry;

/**
 * Fail-closed mutation/checkpoint classification for callers that hold only
 * a (possibly alias-form) tool name plus the registry (T757).
 *
 * <p>Doctrine (AGENTS.md, "Risky operations must fail closed"): a tool the
 * registry cannot resolve, or whose metadata is unavailable, is treated as
 * MUTATING and CHECKPOINT-REQUIRED. The previous hand-maintained name lists
 * had the opposite default — a registered mutating tool missing from the
 * list silently bypassed intent blocking, pre-approval validation, and
 * checkpoint capture.
 *
 * <p>{@link dev.talos.runtime.TurnProcessor} reads the resolved tool's
 * metadata directly (it already holds the {@link TalosTool}); this gate
 * exists for name-only call sites and as the documented home of the
 * fail-closed default. Static heuristics/telemetry callers that classify
 * names without a registry keep using
 * {@link dev.talos.tools.ToolAliasPolicy} via {@link ToolCallSupport} —
 * those are not gates and keep the unknown→false default.
 */
public final class ToolMutationGate {

    private ToolMutationGate() {}

    /** True when the resolved tool mutates the workspace; unresolvable → true. */
    public static boolean isMutating(ToolRegistry registry, String rawToolName) {
        ToolOperationMetadata metadata = metadata(registry, rawToolName);
        return metadata == null || metadata.mutatesWorkspace();
    }

    /** True when the resolved tool requires a checkpoint; unresolvable → true. */
    public static boolean requiresCheckpoint(ToolRegistry registry, String rawToolName) {
        ToolOperationMetadata metadata = metadata(registry, rawToolName);
        return metadata == null || metadata.requiresCheckpoint();
    }

    private static ToolOperationMetadata metadata(ToolRegistry registry, String rawToolName) {
        if (registry == null || rawToolName == null || rawToolName.isBlank()) return null;
        TalosTool tool = registry.get(rawToolName);
        if (tool == null || tool.descriptor() == null) return null;
        return tool.descriptor().operationMetadata();
    }
}
