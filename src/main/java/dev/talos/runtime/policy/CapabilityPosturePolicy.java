package dev.talos.runtime.policy;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.turn.CurrentTurnPlan;

/** Applies mode-level capability ceilings before phase and native-tool planning. */
public final class CapabilityPosturePolicy {

    private CapabilityPosturePolicy() {}

    public record EffectiveTurn(
            TaskContract taskContract,
            ExecutionPhase phase,
            boolean forceNativeSurfaceRecompute
    ) {}

    public static EffectiveTurn apply(CapabilityPosture posture, TaskContract contract) {
        CapabilityPosture safePosture = posture == null ? CapabilityPosture.AGENT : posture;
        if (!safePosture.readOnly()) {
            return new EffectiveTurn(contract, CurrentTurnPlan.defaultPhaseFor(contract), false);
        }
        return new EffectiveTurn(readOnly(contract), ExecutionPhase.INSPECT, true);
    }

    private static TaskContract readOnly(TaskContract contract) {
        if (contract == null) return TaskContract.unknown("");
        return new TaskContract(
                contract.type(),
                contract.mutationRequested(),
                false,
                contract.verificationRequired(),
                contract.expectedTargets(),
                contract.sourceEvidenceTargets(),
                contract.forbiddenTargets(),
                contract.originalUserRequest(),
                readOnlyReason(contract.classificationReason()),
                contract.staticWebRequirements());
    }

    private static String readOnlyReason(String reason) {
        if (reason == null || reason.isBlank()) return "capability-posture-read-only";
        if (reason.contains("capability-posture-read-only")) return reason;
        return reason + "; capability-posture-read-only";
    }
}
