package dev.talos.runtime.toolcall;

import dev.talos.core.util.Sanitize;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ProviderReadInputBinding;
import dev.talos.spi.types.ProviderResponseCapture;

import java.util.ArrayList;
import java.util.List;

/** Acquired data is bound to delivery independently of answer or tool authority. */
final class ReadEvidenceDeliveryRequest {
    private ReadEvidenceDeliveryRequest() {}

    static ChatRequestControls controls(ChatRequestControls controls) {
        var tags = new ArrayList<>(controls.debugTags());
        if (!tags.contains(ProviderReadInputBinding.DELIVERY_TAG)) tags.add(ProviderReadInputBinding.DELIVERY_TAG);
        return new ChatRequestControls(controls.toolChoice(), controls.namedTool(), controls.responseFormat(),
                controls.jsonSchema(), tags, controls.sampling(), controls.maxOutputTokens(),
                controls.toolAuthorityMode(), controls.singleAttempt());
    }

    static List<ProviderReadInputBinding.ToolResultInput> expected(ReadOnlyEvidenceContext.Delivery delivery) {
        return delivery.carriers().stream().map(carrier -> new ProviderReadInputBinding.ToolResultInput(
                carrier.message().toolCallId(), Sanitize.sanitizeMessageContent(carrier.message().content()))).toList();
    }

    static boolean contains(List<ChatMessage> request, List<ProviderReadInputBinding.ToolResultInput> expected) {
        for (var input : expected) {
            var matches = request.stream().filter(message -> message != null && "tool".equals(message.role())
                    && input.toolCallId().equals(message.toolCallId())).toList();
            if (matches.size() != 1 || !matches.getFirst().contentRestrictions().modelAllowed()
                    || matches.getFirst().hasNativeToolCalls()
                    || !input.content().equals(Sanitize.sanitizeMessageContent(matches.getFirst().content()))) return false;
        }
        return true;
    }

    static boolean delivered(ProviderResponseCapture.Receipt receipt,
                             List<ProviderReadInputBinding.ToolResultInput> expected) {
        if (receipt == null || !receipt.belongsToCurrentScope()) return false;
        var binding = receipt.readInputBinding();
        return binding.bound() && binding.purpose() == ProviderReadInputBinding.Purpose.DELIVERY
                && ProviderReadInputBinding.SANITIZER_VERSION.equals(binding.sanitizerVersion())
                && binding.preparedToolResults().containsAll(expected);
    }

    static boolean stop(LoopState state, String reason) {
        state.stopWithFailure(FailureDecision.stop(FailureAction.ASK_USER, TerminalReadOnlyStopAnswer.DELIVERY_UNAVAILABLE_REASON),
                "Acquired read evidence could not be preserved in the bounded provider request. "
                        + "No further tools were executed. The requested answer remains incomplete.");
        LocalTurnTraceCapture.warning(TerminalReadOnlyStopAnswer.DELIVERY_UNAVAILABLE_REASON, reason);
        return false;
    }
}
