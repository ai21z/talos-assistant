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
        boolean staticRepairObligationActive = !remainingRepairTargets.isEmpty()
                && (!state.staticWebFullRewriteRequiredTargets.isEmpty()
                || StaticRepairTargetProgressAccounting.hasStaticRepairContext(state)
                || state.hasPendingActionObligation());
        boolean expectedTargetObligationActive = !remainingExpectedTargets.isEmpty()
                && (outcome.mutationsThisIteration() > 0 || state.hasPendingActionObligation());
        if (staticRepairObligationActive) {
            state.setPendingActionObligation(
                    PendingActionObligation.staticRepairTargets(remainingRepairTargets));
        } else if (expectedTargetObligationActive) {
            state.setPendingActionObligation(
                    PendingActionObligation.expectedTargets(remainingExpectedTargets));
        } else {
            state.clearPendingActionObligation();
        }
        List<ToolSpec> repromptToolSpecs = ToolRepromptRequestBuilder.toolSpecs(
                state,
                staticRepairObligationActive,
                expectedTargetObligationActive);
        return new Selection(
                remainingRepairTargets,
                remainingExpectedTargets,
                staticRepairObligationActive,
                repromptToolSpecs);
    }
}
