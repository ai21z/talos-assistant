package dev.talos.qualification;

import dev.talos.runtime.policy.ActionObligation;

import java.util.EnumSet;
import java.util.Optional;

/** Compares frozen scenario authority with authority derived from the persisted Talos trace. */
final class QualificationAuthorityPolicy {
    private static final EnumSet<ActionObligation> MUTATING_OBLIGATIONS = EnumSet.of(
            ActionObligation.CONDITIONAL_REVIEW_FIX,
            ActionObligation.MUTATING_TOOL_REQUIRED,
            ActionObligation.WORKSPACE_OPERATION_REQUIRED,
            ActionObligation.REPAIR_FROM_VERIFIER_FINDINGS);

    private QualificationAuthorityPolicy() {}

    static Optional<String> mismatch(
            QualificationProtocol.Scenario scenario,
            String taskType,
            boolean mutationAllowed,
            String actionObligation) {
        boolean mutationRequired = switch (scenario.category()) {
            case NO_TOOL, READ -> false;
            case MUTATION, REPAIR, INTERRUPTION -> true;
        };
        ActionObligation obligation;
        try {
            obligation = ActionObligation.valueOf(actionObligation);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.of(finding(scenario, taskType, mutationAllowed, actionObligation,
                    "unknown action obligation"));
        }
        boolean mutatingObligation = MUTATING_OBLIGATIONS.contains(obligation);
        if (mutationAllowed != mutationRequired || mutatingObligation != mutationRequired) {
            return Optional.of(finding(scenario, taskType, mutationAllowed, actionObligation,
                    mutationRequired ? "mutation authority required" : "read-only authority required"));
        }
        return Optional.empty();
    }

    private static String finding(
            QualificationProtocol.Scenario scenario,
            String taskType,
            boolean mutationAllowed,
            String actionObligation,
            String reason) {
        return "scenario/trace authority mismatch for " + scenario.id()
                + ": category=" + scenario.category()
                + ", taskType=" + taskType
                + ", mutationAllowed=" + mutationAllowed
                + ", actionObligation=" + actionObligation
                + " (" + reason + ")";
    }
}
