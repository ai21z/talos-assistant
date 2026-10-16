package dev.talos.runtime.toolcall;

import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.failure.FailureAction;
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
        if (state.toolOutcomes.stream().anyMatch(result -> result.mutating() && !result.success()
                && result.mutationEffects().hasEffects())) {
            FailureDecision decision = FailureDecision.stop(FailureAction.ASK_USER,
                    "Mutation execution failed after applied or uncertain effects; automatic recovery is stopped.");
            state.stopWithFailure(decision, ToolFailurePolicyStopAnswer.render(state, decision));
            return false;
        }
        if (outcome != null
                && outcome.executionAuthorityRejectedThisIteration()) {
            FailureDecision decision = FailureDecision.stop(
                    FailureAction.ASK_USER,
                    "tool-call execution authority was rejected");
            state.stopWithFailure(
                    decision,
                    "[Tool execution was blocked because executable tool authority was invalid"
                            + (outcome.authorityRejections().isEmpty() ? ""
                                    : " (" + outcome.authorityRejections().stream().map(Enum::name)
                                            .collect(java.util.stream.Collectors.joining(", ")) + ")")
                            + ". No follow-up provider request was sent.]");
            LOG.debug(
                    "Stopping tool-call loop after executable authority "
                            + "rejection; not re-prompting.");
            return false;
        }

        if (outcome.approvalDeniedThisIteration()) {
            state.finishWithAnswer("[Tool loop stopped because the requested mutation was not approved.]");
            LOG.debug("Stopping tool-call loop after denied mutating tool call; not re-prompting.");
            return false;
        }

        if (outcome.mutatingDeniedThisIteration()) {
            DeniedMutationResponseOnlySynthesizer.Result synthesis =
                    DeniedMutationResponseOnlySynthesizer.synthesize(state);
            if (synthesis.capturedGeneration()) {
                state.finishWithCapturedAnswer(synthesis.text(), synthesis.receipt());
            } else {
                state.finishWithAnswer(synthesis.text());
            }
            if (synthesis.incompleteGeneration()) {
                state.currentProviderAnswerIncomplete = true;
                dev.talos.runtime.IncompleteGenerationPresentation.record(state.currentText);
            }
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

        if (ReadOnlyEvidenceContext.select(state).retentionLimited()) {
            state.stopWithFailure(FailureDecision.stop(FailureAction.ASK_USER,
                            TerminalReadOnlyStopAnswer.RETENTION_LIMIT_REASON),
                    TerminalReadOnlyStopAnswer.retentionLimitAnswer());
            return false;
        }

        Optional<Boolean> readOnlySynthesis = ReadOnlyEvidenceSynthesizer.tryHandle(state, outcome);
        if (readOnlySynthesis.isPresent()) return readOnlySynthesis.get();

        if (state.iterations >= state.maxIterations) {
            Optional<Boolean> pendingReadsAtLimit = ReadOnlyEvidenceContinuation.tryHandle(state);
            if (pendingReadsAtLimit.isPresent()) return pendingReadsAtLimit.get();
        }

        TerminalReadOnlyStopAnswer.Answer terminalReadOnlyAnswer =
                TerminalReadOnlyStopAnswer.select(state, outcome);
        if (terminalReadOnlyAnswer != null) {
            if (terminalReadOnlyAnswer.synthesisIncomplete()) {
                state.stopWithFailure(dev.talos.runtime.failure.FailureDecision.stop(
                        dev.talos.runtime.failure.FailureAction.ASK_USER,
                        TerminalReadOnlyStopAnswer.SYNTHESIS_UNAVAILABLE_REASON), terminalReadOnlyAnswer.text());
            } else {
                state.finishWithToolAnswer(terminalReadOnlyAnswer.text());
            }
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

        // T1003: register bounded full-rewrite recovery targets BEFORE the
        // failure policy runs, so strikes spent in the same generation batch
        // as the read (the observed weak-model shape) can still recover.
        EditRecoveryFullRewritePlanner.plan(state);

        FailureDecision failureDecision = FailurePolicy.defaults(state.maxIterations)
                .afterIteration(state, outcome);
        if (failureDecision.shouldStop()) {
            state.stopWithFailure(failureDecision, ToolFailurePolicyStopAnswer.render(state, failureDecision));
            LOG.debug("Stopping tool-call loop by failure policy: {}",
                    dev.talos.safety.SafeLogFormatter.text(failureDecision.reason()));
            return false;
        }

        Optional<Boolean> pendingComparison = DocumentComparisonContinuation.tryHandle(state);
        if (pendingComparison.isPresent()) return pendingComparison.get();

        Optional<Boolean> pendingDiagnosisReads = ReadOnlyEvidenceContinuation.tryHandle(state);
        if (pendingDiagnosisReads.isPresent()) return pendingDiagnosisReads.get();

        Optional<Boolean> supportingAcquisition = ReadOnlyEvidenceContinuation.tryContinueAcquisition(state);
        if (supportingAcquisition.isPresent()) return supportingAcquisition.get();

        if (state.iterations >= 3) {
            ToolCallSupport.compactOlderToolResultsInPlace(
                    state.messages, state.compactedToolResultOriginals);
        }

        String userTask =
                TurnTaskContractCapture.originalUserRequestOrResolve(
                        state.messages);
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
        return state.readOnlySynthesisAtLimit || state.readOnlyAcquisitionAtLimit || state.iterations >= state.maxIterations
                && (!state.currentNativeCalls.isEmpty() || ToolCallParser.containsToolCalls(state.currentText));
    }

}
