package dev.talos.runtime.toolcall;

import dev.talos.spi.types.ToolSpec;

import java.util.List;

final class ToolRepromptObligationSelector {

    private ToolRepromptObligationSelector() {
    }

    record Selection(
            List<String> remainingRepairTargets,
            List<String> remainingExpectedTargets,
            boolean staticRepairObligationActive,
            List<ToolSpec> repromptToolSpecs
    ) {
    }

    static Selection select(
            LoopState state,
            ToolCallExecutionStage.IterationOutcome outcome
    ) {
        List<String> remainingRepairTargets =
                StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets(state);
        List<String> remainingExpectedTargets =
                ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state);
        // T1003: unprompted invalid-edit recovery targets carry a bounded
        // full-rewrite obligation of their own and narrow the retry surface
        // to talos.write_file, like the static repair flow.
        List<String> editRecoveryTargets = state.editRecoveryFullRewriteTargets.stream()
                .filter(path -> !state.editRecoveryPromptedPaths.contains(path))
                .sorted()
                .toList();
        boolean staticRepairObligationActive = !remainingRepairTargets.isEmpty()
                && (!state.staticWebFullRewriteRequiredTargets.isEmpty()
                || StaticRepairTargetProgressAccounting.hasStaticRepairContext(state)
                || state.hasPendingActionObligation());
        boolean editRecoveryObligationActive = !editRecoveryTargets.isEmpty();
        boolean expectedTargetObligationActive = !remainingExpectedTargets.isEmpty()
                && (outcome.mutationsThisIteration() > 0 || state.hasPendingActionObligation());
        if (staticRepairObligationActive) {
            state.setPendingActionObligation(
                    PendingActionObligation.staticRepairTargets(remainingRepairTargets));
        } else if (editRecoveryObligationActive) {
            state.setPendingActionObligation(
                    PendingActionObligation.expectedTargets(
                            editRecoveryTargets,
                            PendingActionObligation.EDIT_RECOVERY_FAILURE_MARKER
                                    + " talos.edit_file was repeatedly called with an empty "
                                    + "old_string for " + String.join(", ", editRecoveryTargets)
                                    + " and the recovery generation did not produce the required "
                                    + "talos.write_file call. The requested edit was not applied to "
                                    + "these target(s)."));
        } else if (expectedTargetObligationActive) {
            state.setPendingActionObligation(
                    PendingActionObligation.expectedTargets(remainingExpectedTargets));
        } else {
            state.clearPendingActionObligation();
        }
        List<ToolSpec> repromptToolSpecs = ToolRepromptRequestBuilder.toolSpecs(
                state,
                staticRepairObligationActive || editRecoveryObligationActive,
                expectedTargetObligationActive);
        return new Selection(
                remainingRepairTargets,
                expectedTargetObligationActive ? remainingExpectedTargets : List.of(),
                staticRepairObligationActive,
                repromptToolSpecs);
    }
}
