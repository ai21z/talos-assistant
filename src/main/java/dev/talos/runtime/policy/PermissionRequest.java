package dev.talos.runtime.policy;

import dev.talos.core.Config;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolRiskLevel;

import java.nio.file.Path;

/** Inputs needed to decide whether one tool call may run. */
public record PermissionRequest(
        Path workspace,
        Config config,
        ToolCall call,
        ToolRiskLevel risk,
        ExecutionPhase phase
) {
    public ToolRiskLevel effectiveRisk() {
        return risk == null ? ToolRiskLevel.READ_ONLY : risk;
    }

    public ExecutionPhase effectivePhase() {
        return phase == null ? ExecutionPhase.APPLY : phase;
    }
}
