package dev.talos.runtime.toolcall;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.TurnExecutionStage;
import dev.talos.runtime.TurnFailureClassifier;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.ProviderPromptSurfaceReconciler;
import dev.talos.runtime.policy.ProviderRequestControlPolicy;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ProviderResponseCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DeniedMutationResponseOnlySynthesizer {
    private static final Logger LOG = LoggerFactory.getLogger(DeniedMutationResponseOnlySynthesizer.class);
    private static final String POLICY_STOP_PROMPT_PREFIX = "[Tool policy stop]";

    private DeniedMutationResponseOnlySynthesizer() {}

    record Result(
            String text,
            ProviderResponseCapture.Receipt receipt,
            boolean capturedGeneration) {
        Result {
            text = text == null ? "" : text;
            receipt = receipt == null ? ProviderResponseCapture.Receipt.none() : receipt;
        }

        static Result deterministicStop() {
            return new Result(stopMessage(), ProviderResponseCapture.Receipt.none(), false);
        }
    }

    static Result synthesize(LoopState state) {
        if (state == null || state.ctx == null || state.ctx.llm() == null) {
            return Result.deterministicStop();
        }
        var resolution = TurnTaskContractCapture.getResolution();
        if (resolution == null) {
            return Result.deterministicStop();
        }
        ExecutionPhase phase =
                state.ctx.executionPhaseState() == null
                        ? CurrentTurnPlan.defaultPhaseFor(
                                resolution.contract())
                        : state.ctx.executionPhaseState().phase();
        CurrentTurnPlan responseOnlyPlan =
                CurrentTurnPlan.createWithActionObligation(
                        resolution.contract(),
                        phase,
                        java.util.List.of(),
                        java.util.List.of(),
                        java.util.List.of(),
                        CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                        CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                        CurrentTurnPlan.derivedVerifierProfile(
                                resolution.contract()),
                        ActionObligation.NONE,
                        state.ctx.cfg());
        java.util.List<ChatMessage> requestMessages =
                new java.util.ArrayList<>(state.messages);
        requestMessages.add(ChatMessage.system(
                POLICY_STOP_PROMPT_PREFIX + " The latest mutating tool call was rejected by Talos policy. "
                        + "Do not call any more tools in this turn. Answer the user's request using only "
                        + "the tool results already gathered. If the gathered evidence is insufficient, "
                        + "say exactly what was inspected and what remains unknown."));
        ProviderPromptSurfaceReconciler.reconcile(
                requestMessages,
                responseOnlyPlan,
                java.util.List.of());
        ChatRequestControls controls =
                ProviderRequestControlPolicy.forTurn(
                        responseOnlyPlan,
                        java.util.List.of(),
                        state.ctx.llm().supportsRequiredToolChoice(),
                        state.ctx.llm().supportsNamedToolChoice());

        TurnExecutionStage priorStage = LocalTurnTraceCapture.currentExecutionStage();
        LocalTurnTraceCapture.enterExecutionStage(
                TurnExecutionStage.GENERATION_COORDINATION);
        try {
            LlmClient.StreamResult terminal =
                    state.ctx.llm().chatFull(
                            java.util.List.copyOf(requestMessages),
                            java.util.List.of(),
                            controls);
            LocalTurnTraceCapture.enterExecutionStage(priorStage);
            String text = terminal.text() == null ? "" : terminal.text();
            if (terminal.hasToolCalls()) {
                return Result.deterministicStop();
            }
            String stripped = ToolCallParser.stripToolCalls(text).strip();
            if (stripped.isBlank() || ToolCallParser.containsToolCalls(text)) {
                return Result.deterministicStop();
            }
            return new Result(stripped, terminal.provenance(), true);
        } catch (Exception e) {
            if (TurnFailureClassifier.isCancellation(e)
                    || TurnFailureClassifier.isInterruption(e)) {
                throw TurnFailureClassifier.propagateControlFlow(e);
            }
            LocalTurnTraceCapture.enterExecutionStage(priorStage);
            LOG.warn("Response-only synthesis after denied mutation failed: {}", SafeLogFormatter.throwableMessage(e));
            return Result.deterministicStop();
        }
    }

    static String stopMessage() {
        return "[Tool loop stopped because a mutating tool was not allowed for this turn.]";
    }
}
