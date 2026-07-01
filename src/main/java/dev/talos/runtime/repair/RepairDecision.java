package dev.talos.runtime.repair;

import java.util.Objects;
import java.util.Optional;

public record RepairDecision(
        RepairDecisionStatus status,
        Optional<RepairPlan> plan,
        String reason
) {
    public RepairDecision {
        status = status == null ? RepairDecisionStatus.NOT_APPLICABLE : status;
        plan = Objects.requireNonNullElse(plan, Optional.empty());
        reason = reason == null ? "" : reason.strip();
    }

    public static RepairDecision planned(RepairPlan plan) {
        return new RepairDecision(RepairDecisionStatus.PLAN_CREATED, Optional.ofNullable(plan), "");
    }

    public static RepairDecision notApplicable(String reason) {
        return new RepairDecision(RepairDecisionStatus.NOT_APPLICABLE, Optional.empty(), reason);
    }

    public static RepairDecision stop(String reason) {
        return new RepairDecision(RepairDecisionStatus.STOP, Optional.empty(), reason);
    }
}
