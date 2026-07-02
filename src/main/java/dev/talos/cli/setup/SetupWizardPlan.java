package dev.talos.cli.setup;

import java.util.List;

public record SetupWizardPlan(SetupWizardSnapshot snapshot, List<SetupWizardStep> steps) {
    public SetupWizardPlan {
        steps = List.copyOf(steps);
    }

    public boolean hasSideEffects() {
        return false;
    }

    public SetupWizardStep requiredStep(String id) {
        return steps.stream()
                .filter(step -> step.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown setup wizard step: " + id));
    }
}
