package dev.talos.runtime.toolcall;

import dev.talos.runtime.TurnExecutionStage;
import dev.talos.runtime.TurnFailureClassifier;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
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
                    userTask));
            return ToolRepromptChatExecutor.executeResult(
                    state,
                    requestMessages,
                    repromptToolSpecs,
                    ToolRepromptRequestBuilder.controls(state),
                    "(no answer from model after tool execution)");
        } catch (EngineException.ContextBudgetExceeded budget) {
            LocalTurnTraceCapture.enterExecutionStage(priorStage);
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
                    return ToolRepromptContextBudgetHandler.handle(state, budget, "transient retry continuation");
                }
                if (TurnFailureClassifier.isCancellation(retryEx)
                        || TurnFailureClassifier.isInterruption(retryEx)) {
                    throw TurnFailureClassifier.propagateControlFlow(retryEx);
                }
                if (retryEx instanceof RuntimeException runtime) throw runtime;
                throw new RuntimeException(retryEx);
            }
        } catch (RuntimeException failure) {
            if (TurnFailureClassifier.isCancellation(failure)
                    || TurnFailureClassifier.isInterruption(failure)) {
                throw TurnFailureClassifier.propagateControlFlow(failure);
            }
            throw failure;
        } catch (Exception e) {
            if (TurnFailureClassifier.isCancellation(e)
                    || TurnFailureClassifier.isInterruption(e)) {
                throw TurnFailureClassifier.propagateControlFlow(e);
            }
            throw new RuntimeException(e);
        }
    }
}
