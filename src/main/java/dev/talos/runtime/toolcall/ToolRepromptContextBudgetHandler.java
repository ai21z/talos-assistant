package dev.talos.runtime.toolcall;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.ResponseObligationVerifier;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.EngineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
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
        CompactMutationContinuationOutcome compactMutation =
                tryCompactMutationContinuation(
                        state,
                        retryName,
                        "exceeded context budget: "
                                + ResponseObligationVerifier.contextBudgetRetrySkippedDetail(budget));
        if (compactMutation == CompactMutationContinuationOutcome.CONTINUE_LOOP) {
            LOG.info("Continuing {} with compact mutation continuation after context budget overflow.",
                    retryName);
            return true;
        }
        if (compactMutation == CompactMutationContinuationOutcome.STOP_TURN) {
            return false;
        }
        if (CompactReadOnlyEvidenceContinuation.tryAnswer(state, retryName)) {
            LOG.info("Answered {} with compact read-only evidence continuation after context budget overflow.",
                    retryName);
            return false;
        }
        if (state != null) {
            state.failureDecision = FailureDecision.stop(
                    FailureAction.ASK_USER,
                    "Context budget prevented " + retryName + ". " + detail);
            state.currentText = ResponseObligationVerifier
                    .deterministicContextBudgetRetrySkippedAnswer(retryName, budget);
            state.currentNativeCalls = List.of();
        }
        LOG.info("Skipping {} because it exceeded the local context budget.", retryName);
        return false;
    }

    static Optional<Boolean> handleReadOnlyMutationEvidenceBudget(
            LoopState state,
            int readOnlyInspectionAttemptCount
    ) {
        CompactMutationContinuationOutcome compactMutation =
                tryCompactMutationContinuation(
                        state,
                        "read-only mutation evidence budget",
                        "read-only mutation evidence budget was exhausted after "
                                + readOnlyInspectionAttemptCount
                                + " read-only/no-progress inspection attempt(s)");
        if (compactMutation == CompactMutationContinuationOutcome.CONTINUE_LOOP) {
            LOG.info("Continuing mutation task with compact continuation after read-only inspection budget.");
            return Optional.of(true);
        }
        if (compactMutation == CompactMutationContinuationOutcome.STOP_TURN) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    private enum CompactMutationContinuationOutcome {
        NOT_APPLICABLE,
        CONTINUE_LOOP,
        STOP_TURN
    }

    private static CompactMutationContinuationOutcome tryCompactMutationContinuation(
            LoopState state,
            String retryName,
            String reason
    ) {
        Optional<CompactMutationContinuationPlanner.Plan> continuation =
                CompactMutationContinuationPlanner.planForContextBudget(
                        state,
                        ToolRepromptRequestBuilder.currentNativeToolSpecs(state),
                        retryName);
        if (continuation.isEmpty()) return CompactMutationContinuationOutcome.NOT_APPLICABLE;

        CompactMutationContinuationPlanner.Plan compact = continuation.get();
        try {
            LlmClient.StreamResult result = state.ctx.llm().chatFull(
                    compact.messages(),
                    compact.tools(),
                    compact.controls());
            state.currentText = result.text() == null ? "" : result.text();
            state.currentNativeCalls = result.hasToolCalls()
                    ? new ArrayList<>(result.toolCalls())
                    : List.of();
            LocalTurnTraceCapture.warning(
                    "COMPACT_MUTATION_CONTINUATION",
                    "used compact mutation continuation after " + retryName
                            + ": "
                            + (reason == null || reason.isBlank() ? "compact retry requested" : reason));
            LocalTurnTraceCapture.recordActionObligation(
                    ActionObligation.MUTATING_TOOL_REQUIRED.name(),
                    "RETRIED_COMPACT_CONTEXT",
                    "compact mutation continuation retried current request with narrowed write/edit tools");
            if (!state.currentNativeCalls.isEmpty()
                    || ToolCallParser.containsToolCalls(state.currentText)) {
                return CompactMutationContinuationOutcome.CONTINUE_LOOP;
            }
            state.failureDecision = FailureDecision.stop(
                    FailureAction.ASK_USER,
                    "COMPACT_MUTATION_CONTINUATION_NO_TOOL: compact mutation continuation returned no write/edit tool calls.");
            state.currentText = ResponseObligationVerifier
                    .deterministicNoActionAnswer(ActionObligation.MUTATING_TOOL_REQUIRED);
            state.currentNativeCalls = List.of();
            return CompactMutationContinuationOutcome.STOP_TURN;
        } catch (EngineException.ContextBudgetExceeded budget) {
            LocalTurnTraceCapture.warning(
                    "COMPACT_MUTATION_CONTINUATION_CONTEXT_BUDGET_EXCEEDED",
                    ResponseObligationVerifier.contextBudgetRetrySkippedDetail(budget));
            return CompactMutationContinuationOutcome.NOT_APPLICABLE;
        } catch (EngineException ee) {
            LocalTurnTraceCapture.warning(
                    "COMPACT_MUTATION_CONTINUATION_FAILED",
                    ee.getMessage() == null ? ee.getClass().getSimpleName() : ee.getMessage());
            return CompactMutationContinuationOutcome.NOT_APPLICABLE;
        } catch (Exception e) {
            LocalTurnTraceCapture.warning(
                    "COMPACT_MUTATION_CONTINUATION_FAILED",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return CompactMutationContinuationOutcome.NOT_APPLICABLE;
        }
    }
}
