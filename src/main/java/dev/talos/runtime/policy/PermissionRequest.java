package dev.talos.runtime.policy;

import dev.talos.core.Config;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolRiskLevel;

import java.nio.file.Path;

/**
 * Inputs needed to decide whether one tool call may run.
 *
 * <p>{@code targetNamedByRequest} is true only when the current request named the target of the call as a file
 * to change. A remembered session approval is bound to such targets.
 *
 * <p>{@code proposedAfterContinuation} is true when the model proposed the call after the tool loop of this turn
 * asked it to continue following completed changes (P20). A remembered session approval does not cover such a call,
 * and none can be granted for it (P23, D20 option A).
 */
public record PermissionRequest(
        Path workspace,
        Config config,
        ToolCall call,
        ToolRiskLevel risk,
        ExecutionPhase phase,
        boolean targetNamedByRequest,
        boolean proposedAfterContinuation
) {
    public ToolRiskLevel effectiveRisk() {
        return risk == null ? ToolRiskLevel.READ_ONLY : risk;
    }

    public ExecutionPhase effectivePhase() {
        return phase == null ? ExecutionPhase.APPLY : phase;
    }
}
