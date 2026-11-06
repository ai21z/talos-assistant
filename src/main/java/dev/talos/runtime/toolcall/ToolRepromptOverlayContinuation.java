package dev.talos.runtime.toolcall;

import dev.talos.runtime.TurnExecutionStage;
import dev.talos.runtime.TurnFailureClassifier;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ToolSpec;

import java.util.ArrayList;
import java.util.List;

final class ToolRepromptOverlayContinuation {
    private ToolRepromptOverlayContinuation() {
    }

    static boolean execute(
            LoopState state,
            List<String> remainingRepairTargets,
            List<String> remainingExpectedTargets,
            String userTask,
            boolean staticRepairObligationActive,
            List<ToolSpec> repromptToolSpecs
    ) {
        if (state != null
                && state.hasPendingActionObligation()
                && (repromptToolSpecs == null
                        || repromptToolSpecs.isEmpty())) {
            state.failPendingActionObligation(
                    "required retry tool is unavailable in the captured "
                            + "current-turn surface");
            return false;
        }
        fallBackToKeepFloorIfTrimmed(state, remainingRepairTargets, remainingExpectedTargets, userTask,
                staticRepairObligationActive, repromptToolSpecs);
        List<ChatMessage> requestMessages = List.of();
        TurnExecutionStage priorStage = LocalTurnTraceCapture.currentExecutionStage();
        try (ToolRepromptMessageOverlay ignored = ToolRepromptMessageOverlay.apply(
                state,
                remainingRepairTargets,
                remainingExpectedTargets,
                userTask,
                staticRepairObligationActive)) {
            requestMessages = new ArrayList<>(ToolRepromptRequestBuilder.messages(
                    state,
                    staticRepairObligationActive,
                    remainingRepairTargets,
                    userTask,
                    repromptToolSpecs));
            return ToolRepromptChatExecutor.executeResult(
                    state,
                    requestMessages,
                    repromptToolSpecs,
                    ToolRepromptRequestBuilder.controls(state),
                    "(no answer from model after tool execution)");
        } catch (EngineException.ContextBudgetExceeded budget) {
            LocalTurnTraceCapture.enterExecutionStage(priorStage);
            // P20: after a continuation, a request that cannot be sent leaves the turn as its completed changes left it.
            if (MutationContinuationDecision.endUnanswered(state, "CONTEXT_BUDGET", budget)) return false;
            return ToolRepromptContextBudgetHandler.handle(state, budget, "tool-call loop continuation");
        } catch (EngineException.Transient tr) {
            try {
                Thread.sleep(400);
                boolean result = ToolRepromptChatExecutor.executeRetryResult(
                        state,
                        requestMessages,
                        repromptToolSpecs,
                        ToolRepromptRequestBuilder.controls(state),
                        "(no answer from model after retry)");
                LocalTurnTraceCapture.enterExecutionStage(priorStage);
                return result;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            } catch (Exception retryEx) {
                if (retryEx instanceof EngineException.ContextBudgetExceeded budget) {
                    LocalTurnTraceCapture.enterExecutionStage(priorStage);
                    if (MutationContinuationDecision.endUnanswered(state, "CONTEXT_BUDGET", budget)) return false;
                    return ToolRepromptContextBudgetHandler.handle(state, budget, "transient retry continuation");
                }
                if (TurnFailureClassifier.isCancellation(retryEx)
                        || TurnFailureClassifier.isInterruption(retryEx)) {
                    throw TurnFailureClassifier.propagateControlFlow(retryEx);
                }
                if (MutationContinuationDecision.endUnanswered(
                        state, MutationContinuationDecision.failureReason(retryEx), retryEx)) {
                    LocalTurnTraceCapture.enterExecutionStage(priorStage);
                    return false;
                }
                if (retryEx instanceof RuntimeException runtime) throw runtime;
                throw new RuntimeException(retryEx);
            }
        } catch (RuntimeException failure) {
            if (TurnFailureClassifier.isCancellation(failure)
                    || TurnFailureClassifier.isInterruption(failure)) {
                throw TurnFailureClassifier.propagateControlFlow(failure);
            }
            // P20: after a continuation, a failed request leaves the turn as its completed changes left it.
            if (MutationContinuationDecision.endUnanswered(
                    state, MutationContinuationDecision.failureReason(failure), failure)) {
                LocalTurnTraceCapture.enterExecutionStage(priorStage);
                return false;
            }
            throw failure;
        } catch (Exception e) {
            if (TurnFailureClassifier.isCancellation(e)
                    || TurnFailureClassifier.isInterruption(e)) {
                throw TurnFailureClassifier.propagateControlFlow(e);
            }
            if (MutationContinuationDecision.endUnanswered(state, "REQUEST_FAILED", e)) {
                LocalTurnTraceCapture.enterExecutionStage(priorStage);
                return false;
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * D10 fallback: from the compaction iteration on, the request is first sized through a probe overlay that
     * commits no one-shot repair. When the client would trim it, older results are compacted to the two newest,
     * which the count rule kept, before the request is built and dispatched once. The client decides fit with
     * the preparation it dispatches, so the runtime holds no size estimate.
     */
    private static void fallBackToKeepFloorIfTrimmed(
            LoopState state,
            List<String> remainingRepairTargets,
            List<String> remainingExpectedTargets,
            String userTask,
            boolean staticRepairObligationActive,
            List<ToolSpec> repromptToolSpecs
    ) {
        if (state == null || state.ctx == null || state.ctx.llm() == null
                || state.iterations < ToolCallSupport.COMPACTION_START_ITERATION) return;
        boolean fits;
        try (ToolRepromptMessageOverlay ignored = ToolRepromptMessageOverlay.probe(
                state,
                remainingRepairTargets,
                remainingExpectedTargets,
                userTask,
                staticRepairObligationActive)) {
            List<ChatMessage> probe = ToolRepromptRequestBuilder.messages(
                    state,
                    staticRepairObligationActive,
                    remainingRepairTargets,
                    userTask,
                    repromptToolSpecs);
            ChatRequestControls controls = ToolRepromptRequestBuilder.controls(state);
            controls = (controls == null ? ChatRequestControls.defaults() : controls)
                    .withToolAuthorityMode(state.ctx.llm().toolAuthorityModeForToolSurface(repromptToolSpecs));
            if (!ReadEvidenceDeliveryRequest.expected(ReadOnlyEvidenceContext.delivery(state)).isEmpty()) {
                controls = ReadEvidenceDeliveryRequest.controls(controls);
            }
            fits = state.ctx.llm().fitsContextBudgetUntrimmed(probe, repromptToolSpecs, controls);
        }
        if (!fits) compactToKeepFloor(state);
    }

    private static void compactToKeepFloor(LoopState state) {
        var compacted = ToolCallSupport.compactOlderToolResultsInPlace(
                state.messages, state.compactedToolResultOriginals, state.noticeMessageIndices);
        for (ToolCallSupport.CompactedToolResult result : compacted) {
            LocalTurnTraceCapture.recordToolResultCompacted(result.messageIndex(), result.chars());
        }
        if (compacted.isEmpty()) return;
        LocalTurnTraceCapture.recordRepair("KEEP_BUDGET_FLOOR_FALLBACK", "compacted=" + compacted.size());
    }
}
