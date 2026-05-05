package dev.talos.runtime.command;

/** Classifies a validated command plan. T136 keeps this profile-owned and deterministic. */
public final class CommandRiskClassifier {
    private CommandRiskClassifier() {}

    public static CommandRisk classify(CommandPlan plan) {
        return plan == null ? CommandRisk.UNKNOWN : plan.risk();
    }
}
