package dev.talos.runtime.phase;

import dev.talos.tools.ToolError;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;

/** Sidecar runtime policy for phase-aware tool execution. */
public final class PhasePolicy {
    private PhasePolicy() {}

    public enum ToolCategory {
        READ,
        SEARCH,
        RETRIEVE,
        MUTATE
    }

    public static ToolCategory categorize(String toolName, ToolRiskLevel risk) {
        if (risk != null && risk.requiresApproval()) {
            return ToolCategory.MUTATE;
        }
        return switch (toolName == null ? "" : toolName) {
            case "talos.grep" -> ToolCategory.SEARCH;
            case "talos.retrieve" -> ToolCategory.RETRIEVE;
            default -> ToolCategory.READ;
        };
    }

    public static boolean allows(ExecutionPhase phase, ToolCategory category) {
        ExecutionPhase effectivePhase = phase == null ? ExecutionPhase.APPLY : phase;
        ToolCategory effectiveCategory = category == null ? ToolCategory.READ : category;
        return switch (effectivePhase) {
            case INSPECT, VERIFY -> effectiveCategory != ToolCategory.MUTATE;
            case APPLY -> true;
            case RESPOND -> false;
        };
    }

    public static ToolResult rejectIfDisallowed(ExecutionPhase phase, String toolName, ToolRiskLevel risk) {
        ToolCategory category = categorize(toolName, risk);
        if (allows(phase, category)) {
            return null;
        }
        ExecutionPhase effectivePhase = phase == null ? ExecutionPhase.APPLY : phase;
        String allowed = effectivePhase == ExecutionPhase.RESPOND
                ? "does not allow tool calls"
                : "allows read, search, and retrieval tools only";
        return ToolResult.fail(ToolError.denied(
                "Phase policy blocked " + toolName + " during " + effectivePhase
                        + ". Mutating tools are only allowed during APPLY; this phase "
                        + allowed + "."));
    }
}
