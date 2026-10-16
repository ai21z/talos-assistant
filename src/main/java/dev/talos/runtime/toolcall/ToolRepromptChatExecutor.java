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

    private enum RequestPurpose { EVIDENCE_CONTINUATION, TOOL_ARGUMENT_SELECTION }

    /** Argument selection is bounded by its caller and cannot itself finalize an answer. */
    static boolean executeToolSelectionResult(LoopState state, List<ChatMessage> requestMessages,
                                             List<ToolSpec> tools, ChatRequestControls controls,
                                             String noAnswerFallback) {
        if (tools == null || tools.isEmpty() || controls == null || !controls.singleAttempt()
                || controls.toolChoice() != dev.talos.spi.types.ToolChoiceMode.REQUIRED)
            throw new IllegalArgumentException("Tool selection requires one bounded required-tool request.");
        return executeResult(state, requestMessages, tools, controls, noAnswerFallback, true,
                RequestPurpose.TOOL_ARGUMENT_SELECTION);
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
                true, RequestPurpose.EVIDENCE_CONTINUATION);
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
                false, RequestPurpose.EVIDENCE_CONTINUATION);
    }

    private static boolean executeResult(
            LoopState state,
            List<ChatMessage> requestMessages,
            List<ToolSpec> repromptToolSpecs,
            ChatRequestControls controls,
            String noAnswerFallback,
            boolean failPendingObligationOnEmptyResult,
            RequestPurpose purpose
    ) {
        ChatRequestControls authorityBoundControls =
                (controls == null ? ChatRequestControls.defaults() : controls)
                        .withToolAuthorityMode(
                                state.ctx.llm().toolAuthorityModeForToolSurface(
                                        repromptToolSpecs));
        var delivery = purpose == RequestPurpose.EVIDENCE_CONTINUATION
                ? ReadOnlyEvidenceContext.delivery(state) : ReadOnlyEvidenceContext.Delivery.unavailable();
        if (delivery.retentionLimited()) return ReadEvidenceDeliveryRequest.stop(state, "SOURCE_RETENTION_LIMIT");
        if (delivery.excludedSourceCount() > 0)
            LocalTurnTraceCapture.recordRepair("READ_EVIDENCE_DELIVERY_EXCLUSIONS",
                    "excludedSources=" + delivery.excludedSourceCount());
        var expectedInputs = ReadEvidenceDeliveryRequest.expected(delivery);
        if (!expectedInputs.isEmpty()) {
            if (!ReadEvidenceDeliveryRequest.contains(requestMessages, expectedInputs))
                return ReadEvidenceDeliveryRequest.stop(state, "REQUEST_SOURCE_MEMBERSHIP_MISMATCH");
            authorityBoundControls = ReadEvidenceDeliveryRequest.controls(authorityBoundControls);
        }
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
        if (!expectedInputs.isEmpty() && (repromptResult == null
                || !ReadEvidenceDeliveryRequest.delivered(repromptResult.provenance(), expectedInputs)))
            return ReadEvidenceDeliveryRequest.stop(state, "SERIALIZED_SOURCE_MEMBERSHIP_UNAVAILABLE");
        if (!expectedInputs.isEmpty())
            LocalTurnTraceCapture.recordRepair("READ_EVIDENCE_DELIVERED",
                    "sourceCount=" + expectedInputs.size() + ", semanticCompleteness=notEstablished");
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
        if (dev.talos.runtime.IncompleteGenerationPresentation.required(repromptResult)) {
            return false;
        }
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
