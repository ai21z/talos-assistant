package dev.talos.runtime.toolcall;

import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.policy.ResponseObligationVerifier;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.EngineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

final class ToolRepromptContextBudgetHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ToolRepromptContextBudgetHandler.class);

    private ToolRepromptContextBudgetHandler() {}

    static boolean handle(
            LoopState state,
            EngineException.ContextBudgetExceeded budget,
            String retryName
    ) {
        String detail = ResponseObligationVerifier.contextBudgetRetrySkippedDetail(budget);
        LocalTurnTraceCapture.warning("CONTEXT_BUDGET_RETRY_SKIPPED", detail);
        if (state != null && state.failPendingActionObligation(detail)) {
            LOG.info("Skipping {} because it exceeded the local context budget.", retryName);
            return false;
        }
        CompactMutationContinuationExecutor.Outcome compactMutation =
                CompactMutationContinuationExecutor.tryExecute(
                        state,
                        ToolRepromptRequestBuilder.currentNativeToolSpecs(state),
                        retryName,
                        "exceeded context budget: "
                                + ResponseObligationVerifier.contextBudgetRetrySkippedDetail(budget));
        if (compactMutation == CompactMutationContinuationExecutor.Outcome.CONTINUE_LOOP) {
            LOG.info("Continuing {} with compact mutation continuation after context budget overflow.",
                    retryName);
            return true;
        }
        if (compactMutation == CompactMutationContinuationExecutor.Outcome.STOP_TURN) {
            return false;
        }
        if (CompactReadOnlyEvidenceContinuation.tryAnswer(state, retryName)) {
            LOG.info("Answered {} with compact read-only evidence continuation after context budget overflow.",
                    retryName);
            return false;
        }
        if (state != null) {
            FailureDecision decision = FailureDecision.stop(
                    FailureAction.ASK_USER,
                    "Context budget prevented " + retryName + ". " + detail);
            state.stopWithFailure(
                    decision,
                    ResponseObligationVerifier.deterministicContextBudgetRetrySkippedAnswer(retryName, budget));
        }
        LOG.info("Skipping {} because it exceeded the local context budget.", retryName);
        return false;
    }

    static Optional<Boolean> handleReadOnlyMutationEvidenceBudget(
            LoopState state,
            int readOnlyInspectionAttemptCount
    ) {
        CompactMutationContinuationExecutor.Outcome compactMutation =
                CompactMutationContinuationExecutor.tryExecute(
                        state,
                        ToolRepromptRequestBuilder.currentNativeToolSpecs(state),
                        "read-only mutation evidence budget",
                        "read-only mutation evidence budget was exhausted after "
                                + readOnlyInspectionAttemptCount
                                + " read-only/no-progress inspection attempt(s)");
        if (compactMutation == CompactMutationContinuationExecutor.Outcome.CONTINUE_LOOP) {
            LOG.info("Continuing mutation task with compact continuation after read-only inspection budget.");
            return Optional.of(true);
        }
        if (compactMutation == CompactMutationContinuationExecutor.Outcome.STOP_TURN) {
            return Optional.of(false);
        }
        return Optional.empty();
    }
}
