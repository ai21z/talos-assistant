package dev.talos.runtime.toolcall;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.TurnExecutionStage;
import dev.talos.runtime.TurnFailureClassifier;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ToolSpec;

import java.util.List;

final class ToolRepromptChatExecutor {
    private static final String NO_ANSWER_AFTER_TOOL_EXECUTION = "(no answer from model after tool execution)";

    private ToolRepromptChatExecutor() {
    }

    static boolean execute(
            LoopState state,
            List<ChatMessage> requestMessages,
            List<ToolSpec> repromptToolSpecs,
            ChatRequestControls controls,
            String retryName
    ) {
        TurnExecutionStage priorStage = LocalTurnTraceCapture.currentExecutionStage();
        try {
            return executeResult(
                    state,
                    requestMessages,
                    repromptToolSpecs,
                    controls,
                    NO_ANSWER_AFTER_TOOL_EXECUTION);
        } catch (EngineException.ContextBudgetExceeded budget) {
            LocalTurnTraceCapture.enterExecutionStage(priorStage);
            return ToolRepromptContextBudgetHandler.handle(state, budget, retryName);
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

    static boolean executeResult(
            LoopState state,
            List<ChatMessage> requestMessages,
            List<ToolSpec> repromptToolSpecs,
            ChatRequestControls controls,
            String noAnswerFallback
    ) {
        return executeResult(
                state,
                requestMessages,
                repromptToolSpecs,
                controls,
                noAnswerFallback,
                true);
    }

    static boolean executeRetryResult(
            LoopState state,
            List<ChatMessage> requestMessages,
            List<ToolSpec> repromptToolSpecs,
            ChatRequestControls controls,
            String noAnswerFallback
    ) {
        return executeResult(
                state,
                requestMessages,
                repromptToolSpecs,
                controls,
                noAnswerFallback,
                false);
    }

    private static boolean executeResult(
            LoopState state,
            List<ChatMessage> requestMessages,
            List<ToolSpec> repromptToolSpecs,
            ChatRequestControls controls,
            String noAnswerFallback,
            boolean failPendingObligationOnEmptyResult
    ) {
        ChatRequestControls authorityBoundControls =
                (controls == null ? ChatRequestControls.defaults() : controls)
                        .withToolAuthorityMode(
                                state.ctx.llm().toolAuthorityModeForToolSurface(
                                        repromptToolSpecs));
        TurnExecutionStage priorStage = LocalTurnTraceCapture.currentExecutionStage();
        LocalTurnTraceCapture.enterExecutionStage(
                TurnExecutionStage.GENERATION_COORDINATION);
        LlmClient.StreamResult repromptResult;
        try {
            repromptResult = state.ctx.llm().chatFull(
                    requestMessages,
                    repromptToolSpecs,
                    authorityBoundControls);
        } catch (RuntimeException | Error failure) {
            throw failure;
        }
        LocalTurnTraceCapture.enterExecutionStage(priorStage);
        return applyResult(
                state,
                repromptResult,
                noAnswerFallback,
                failPendingObligationOnEmptyResult);
    }

    static boolean applyResult(
            LoopState state,
            LlmClient.StreamResult repromptResult,
            String noAnswerFallback
    ) {
        return applyResult(state, repromptResult, noAnswerFallback, true);
    }

    private static boolean applyResult(
            LoopState state,
            LlmClient.StreamResult repromptResult,
            String noAnswerFallback,
            boolean failPendingObligationOnEmptyResult
    ) {
        state.applyProviderResult(repromptResult);
        if (state.currentText.isEmpty() && state.currentNativeCalls.isEmpty()) {
            if (failPendingObligationOnEmptyResult
                    && state.failPendingActionObligationAfterNoExecutableToolCalls()) {
                return false;
            }
            if (!state.pendingMutationSummaries.isEmpty()) {
                state.finishWithToolAnswer(String.join("\n", state.pendingMutationSummaries));
            } else {
                state.finishWithAnswer(noAnswerFallback == null || noAnswerFallback.isBlank()
                        ? NO_ANSWER_AFTER_TOOL_EXECUTION
                        : noAnswerFallback);
            }
            return false;
        }
        return true;
    }
}
