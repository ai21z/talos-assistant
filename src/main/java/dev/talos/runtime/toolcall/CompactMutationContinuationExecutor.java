package dev.talos.runtime.toolcall;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.ResponseObligationVerifier;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class CompactMutationContinuationExecutor {
    private CompactMutationContinuationExecutor() {}

    enum Outcome {
        NOT_APPLICABLE,
        CONTINUE_LOOP,
        STOP_TURN
    }

    static Outcome tryExecute(
            LoopState state,
            List<ToolSpec> baseTools,
            String retryName,
            String reason
    ) {
        Optional<CompactMutationContinuationPlanner.Plan> continuation =
                CompactMutationContinuationPlanner.planForContextBudget(
                        state,
                        baseTools,
                        retryName);
        if (continuation.isEmpty()) return Outcome.NOT_APPLICABLE;

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
                return Outcome.CONTINUE_LOOP;
            }
            state.stopWithFailure(
                    FailureDecision.stop(
                            FailureAction.ASK_USER,
                            "COMPACT_MUTATION_CONTINUATION_NO_TOOL: "
                                    + "compact mutation continuation returned no write/edit tool calls."),
                    ResponseObligationVerifier.deterministicNoActionAnswer(ActionObligation.MUTATING_TOOL_REQUIRED));
            return Outcome.STOP_TURN;
        } catch (EngineException.ContextBudgetExceeded budget) {
            LocalTurnTraceCapture.warning(
                    "COMPACT_MUTATION_CONTINUATION_CONTEXT_BUDGET_EXCEEDED",
                    ResponseObligationVerifier.contextBudgetRetrySkippedDetail(budget));
            return Outcome.NOT_APPLICABLE;
        } catch (EngineException ee) {
            LocalTurnTraceCapture.warning(
                    "COMPACT_MUTATION_CONTINUATION_FAILED",
                    ee.getMessage() == null ? ee.getClass().getSimpleName() : ee.getMessage());
            return Outcome.NOT_APPLICABLE;
        } catch (Exception e) {
            LocalTurnTraceCapture.warning(
                    "COMPACT_MUTATION_CONTINUATION_FAILED",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return Outcome.NOT_APPLICABLE;
        }
    }
}
