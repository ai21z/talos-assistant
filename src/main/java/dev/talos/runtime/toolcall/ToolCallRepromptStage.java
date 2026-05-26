package dev.talos.runtime.toolcall;

import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.failure.FailurePolicy;
import dev.talos.runtime.ToolCallParser;
import dev.talos.spi.types.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

@SuppressWarnings("resource") // LoopState.ctx owns the shared LlmClient for the active REPL session.
public final class ToolCallRepromptStage {
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallRepromptStage.class);
    private static final int REPAIR_READ_ONLY_TOOL_BUDGET = 6;

    public boolean reprompt(LoopState state, ToolCallExecutionStage.IterationOutcome outcome) {
        if (outcome.approvalDeniedThisIteration()) {
            state.currentText = "[Tool loop stopped because the requested mutation was not approved.]";
            state.currentNativeCalls = List.of();
            LOG.debug("Stopping tool-call loop after denied mutating tool call; not re-prompting.");
            return false;
        }

        if (outcome.mutatingDeniedThisIteration()) {
            state.currentText = DeniedMutationResponseOnlySynthesizer.synthesize(state);
            state.currentNativeCalls = List.of();
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
            state.currentText = terminalReadOnlyAnswer.text();
            state.currentNativeCalls = List.of();
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
            state.failureDecision = failureDecision;
            state.currentText = ToolFailurePolicyStopAnswer.render(state, failureDecision);
            state.currentNativeCalls = List.of();
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

        Optional<TargetReadbackCompactRepairPlanner.Plan> appendLineRepair =
                TargetReadbackCompactRepairPlanner.nextAppendLinePlan(
                        state,
                        ToolRepromptRequestBuilder.currentNativeToolSpecs(state),
                        userTask);
        if (appendLineRepair.isPresent()) {
            TargetReadbackCompactRepairPlanner.Plan repair = appendLineRepair.get();
            state.setPendingActionObligation(
                    PendingActionObligation.appendLineTargets(List.of(repair.path())));
            state.appendLineRepairPromptedPaths.add(repair.promptedPathKey());
            return ToolRepromptChatExecutor.execute(
                    state, repair.messages(), repair.tools(), repair.controls(), repair.retryName());
        }

        Optional<TargetReadbackCompactRepairPlanner.Plan> oldStringMissRepair =
                TargetReadbackCompactRepairPlanner.nextOldStringMissPlan(
                        state,
                        ToolRepromptRequestBuilder.currentNativeToolSpecs(state),
                        userTask);
        if (oldStringMissRepair.isPresent()) {
            TargetReadbackCompactRepairPlanner.Plan repair = oldStringMissRepair.get();
            state.setPendingActionObligation(
                    PendingActionObligation.oldStringMissTargets(List.of(repair.path())));
            state.oldStringMissRepairPromptedPaths.add(repair.promptedPathKey());
            return ToolRepromptChatExecutor.execute(
                    state, repair.messages(), repair.tools(), repair.controls(), repair.retryName());
        }

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

        return ToolRepromptOverlayContinuation.execute(
                state,
                remainingRepairTargets,
                remainingExpectedTargets,
                userTask,
                staticRepairObligationActive,
                repromptToolSpecs);
    }

    public boolean hitIterationLimit(LoopState state) {
        return state.iterations >= state.maxIterations
                && (!state.currentNativeCalls.isEmpty() || ToolCallParser.containsToolCalls(state.currentText));
    }

}
