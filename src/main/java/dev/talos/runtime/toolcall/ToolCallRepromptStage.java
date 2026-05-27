package dev.talos.runtime.toolcall;

import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.failure.FailurePolicy;
import dev.talos.runtime.ToolCallParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

@SuppressWarnings("resource") // LoopState.ctx owns the shared LlmClient for the active REPL session.
public final class ToolCallRepromptStage {
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallRepromptStage.class);
    private static final int REPAIR_READ_ONLY_TOOL_BUDGET = 6;

    public boolean reprompt(LoopState state, ToolCallExecutionStage.IterationOutcome outcome) {
        if (outcome.approvalDeniedThisIteration()) {
            state.finishWithAnswer("[Tool loop stopped because the requested mutation was not approved.]");
            LOG.debug("Stopping tool-call loop after denied mutating tool call; not re-prompting.");
            return false;
        }

        if (outcome.mutatingDeniedThisIteration()) {
            state.finishWithAnswer(DeniedMutationResponseOnlySynthesizer.synthesize(state));
            LOG.debug("Stopping tool-call loop after denied mutating tool call; not re-prompting.");
            return false;
        }

        Optional<Boolean> pathPolicyBlockedDecision =
                ToolRepromptPathPolicyBlockedDecision.tryHandle(state, outcome);
        if (pathPolicyBlockedDecision.isPresent()) {
            return pathPolicyBlockedDecision.get();
        }

        Optional<Boolean> staleRereadStop = ToolRepromptStaleEditRereadStop.tryHandle(state);
        if (staleRereadStop.isPresent()) {
            return staleRereadStop.get();
        }

        TerminalReadOnlyStopAnswer.Answer terminalReadOnlyAnswer =
                TerminalReadOnlyStopAnswer.select(state, outcome);
        if (terminalReadOnlyAnswer != null) {
            state.finishWithAnswer(terminalReadOnlyAnswer.text());
            LOG.debug(terminalReadOnlyAnswer.logMessage());
            return false;
        }

        Optional<Boolean> successfulMutationDecision =
                ToolRepromptSuccessfulMutationDecision.tryHandle(state, outcome);
        if (successfulMutationDecision.isPresent()) {
            return successfulMutationDecision.get();
        }

        if (outcome.mutationsThisIteration() > 0 && outcome.failuresThisIteration() > 0) {
            LOG.debug("CCR-020: re-prompting after partial success ({} mutation(s), {} failure(s) "
                    + "this iteration) so the model can retry the failed call(s)",
                    outcome.mutationsThisIteration(), outcome.failuresThisIteration());
            // fall through to the re-prompt path below
        }

        Optional<Boolean> repairBudgetStop =
                ToolRepairInspectionBudgetGate.tryStop(state, REPAIR_READ_ONLY_TOOL_BUDGET);
        if (repairBudgetStop.isPresent()) {
            return repairBudgetStop.get();
        }

        Optional<Boolean> mutationEvidenceBudget =
                ToolMutationEvidenceBudgetGate.tryContinueOrStop(state, REPAIR_READ_ONLY_TOOL_BUDGET);
        if (mutationEvidenceBudget.isPresent()) {
            return mutationEvidenceBudget.get();
        }

        FailureDecision failureDecision = FailurePolicy.defaults(state.maxIterations)
                .afterIteration(state, outcome);
        if (failureDecision.shouldStop()) {
            state.stopWithFailure(failureDecision, ToolFailurePolicyStopAnswer.render(state, failureDecision));
            LOG.debug("Stopping tool-call loop by failure policy: {}", failureDecision.reason());
            return false;
        }

        if (state.iterations >= 3) {
            ToolCallSupport.compactOlderToolResultsInPlace(state.messages);
        }

        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
        Optional<Boolean> sourceEvidenceRepair =
                ToolRepromptSourceEvidenceRepairDecision.tryHandle(state, userTask);
        if (sourceEvidenceRepair.isPresent()) {
            return sourceEvidenceRepair.get();
        }

        Optional<Boolean> targetReadbackRepair =
                ToolRepromptTargetReadbackRepairDecision.tryHandle(state, userTask);
        if (targetReadbackRepair.isPresent()) {
            return targetReadbackRepair.get();
        }

        ToolRepromptObligationSelector.Selection obligation =
                ToolRepromptObligationSelector.select(state, outcome);

        return ToolRepromptOverlayContinuation.execute(
                state,
                obligation.remainingRepairTargets(),
                obligation.remainingExpectedTargets(),
                userTask,
                obligation.staticRepairObligationActive(),
                obligation.repromptToolSpecs());
    }

    public boolean hitIterationLimit(LoopState state) {
        return state.iterations >= state.maxIterations
                && (!state.currentNativeCalls.isEmpty() || ToolCallParser.containsToolCalls(state.currentText));
    }

}
