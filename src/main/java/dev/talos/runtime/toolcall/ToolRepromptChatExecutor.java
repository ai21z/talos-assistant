package dev.talos.runtime.toolcall;

import dev.talos.core.llm.LlmClient;
import dev.talos.core.util.UiChrome;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

final class ToolRepromptChatExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(ToolRepromptChatExecutor.class);
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
        try {
            return executeResult(
                    state,
                    requestMessages,
                    repromptToolSpecs,
                    controls,
                    NO_ANSWER_AFTER_TOOL_EXECUTION);
        } catch (EngineException.ContextBudgetExceeded budget) {
            return ToolRepromptContextBudgetHandler.handle(state, budget, retryName);
        } catch (EngineException.ConnectionFailed cf) {
            LOG.warn("Ollama not reachable during {}: {}",
                    SafeLogFormatter.value(retryName), SafeLogFormatter.throwableMessage(cf));
            state.finishWithAnswer("[Ollama not reachable — tool loop aborted. " + cf.guidance() + "]");
            return false;
        } catch (EngineException.ModelNotFound mnf) {
            LOG.warn("Model not found during {}: {}",
                    SafeLogFormatter.value(retryName), SafeLogFormatter.value(mnf.model()));
            state.finishWithAnswer(UiChrome.MODEL_NOT_FOUND_OPEN + mnf.model() + UiChrome.MODEL_NOT_FOUND_MARKER
                    + " — tool loop aborted. " + mnf.guidance() + "]");
            return false;
        } catch (EngineException ee) {
            LOG.warn("Engine error during {}: {}",
                    SafeLogFormatter.value(retryName), SafeLogFormatter.throwableMessage(ee));
            state.finishWithAnswer(UiChrome.ENGINE_ERROR_PREFIX + " during tool loop: " + ee.getMessage() + "]");
            return false;
        } catch (Exception e) {
            LOG.warn("LLM call failed during {}: {}",
                    SafeLogFormatter.value(retryName), SafeLogFormatter.throwableMessage(e));
            state.finishWithAnswer("(error during follow-up LLM call: " + e.getMessage() + ")");
            return false;
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
        LlmClient.StreamResult repromptResult =
                state.ctx.llm().chatFull(
                        requestMessages,
                        repromptToolSpecs,
                        controls);
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
        state.currentText = repromptResult.text();
        state.currentNativeCalls = repromptResult.hasToolCalls()
                ? new ArrayList<>(repromptResult.toolCalls()) : List.of();
        if (state.currentText == null) state.currentText = "";
        if (state.currentText.isEmpty() && state.currentNativeCalls.isEmpty()) {
            if (failPendingObligationOnEmptyResult
                    && state.failPendingActionObligationAfterNoExecutableToolCalls()) {
                return false;
            }
            if (!state.pendingMutationSummaries.isEmpty()) {
                state.finishWithAnswer(String.join("\n", state.pendingMutationSummaries));
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
