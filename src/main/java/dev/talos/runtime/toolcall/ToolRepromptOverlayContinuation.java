package dev.talos.runtime.toolcall;

import dev.talos.safety.SafeLogFormatter;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

final class ToolRepromptOverlayContinuation {
    private static final Logger LOG = LoggerFactory.getLogger(ToolRepromptOverlayContinuation.class);

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
        try (ToolRepromptMessageOverlay ignored = ToolRepromptMessageOverlay.apply(
                state,
                remainingRepairTargets,
                remainingExpectedTargets,
                userTask)) {
            requestMessages = new ArrayList<>(ToolRepromptRequestBuilder.messages(
                    state,
                    staticRepairObligationActive,
                    remainingRepairTargets,
                    userTask));
            if (!ToolRepromptChatExecutor.executeResult(
                    state,
                    requestMessages,
                    repromptToolSpecs,
                    ToolRepromptRequestBuilder.controls(state),
                    "(no answer from model after tool execution)")) {
                return false;
            }
            return true;
        } catch (EngineException.ContextBudgetExceeded budget) {
            return ToolRepromptContextBudgetHandler.handle(state, budget, "tool-call loop continuation");
        } catch (EngineException.ConnectionFailed cf) {
            LOG.warn("Ollama not reachable during tool-call loop iteration {}: {}",
                    state.iterations, SafeLogFormatter.throwableMessage(cf));
            state.finishWithAnswer("[Ollama not reachable — tool loop aborted. " + cf.guidance() + "]");
            return false;
        } catch (EngineException.ModelNotFound mnf) {
            LOG.warn("Model not found during tool-call loop iteration {}: {}",
                    state.iterations, SafeLogFormatter.value(mnf.model()));
            state.finishWithAnswer(
                    "[Model '" + mnf.model() + "' not found — tool loop aborted. " + mnf.guidance() + "]");
            return false;
        } catch (EngineException.Transient tr) {
            LOG.warn("Transient error during tool-call loop iteration {}: {}",
                    state.iterations, SafeLogFormatter.throwableMessage(tr));
            try {
                Thread.sleep(400);
                if (!ToolRepromptChatExecutor.executeRetryResult(
                        state,
                        requestMessages,
                        repromptToolSpecs,
                        ToolRepromptRequestBuilder.controls(state),
                        "(no answer from model after retry)")) {
                    return false;
                }
                return true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                state.finishWithAnswer("[Interrupted during tool-call loop]");
                return false;
            } catch (Exception retryEx) {
                if (retryEx instanceof EngineException.ContextBudgetExceeded budget) {
                    return ToolRepromptContextBudgetHandler.handle(state, budget, "transient retry continuation");
                }
                state.finishWithAnswer("[" + tr.guidance() + "]");
                return false;
            }
        } catch (EngineException ee) {
            LOG.warn("Engine error during tool-call loop iteration {}: {}",
                    state.iterations, SafeLogFormatter.throwableMessage(ee));
            state.finishWithAnswer("[Engine error during tool loop: " + ee.getMessage() + "]");
            return false;
        } catch (Exception e) {
            LOG.warn("LLM call failed during tool-call loop iteration {}: {}",
                    state.iterations, SafeLogFormatter.throwableMessage(e));
            state.finishWithAnswer("(error during follow-up LLM call: " + e.getMessage() + ")");
            return false;
        }
    }
}
