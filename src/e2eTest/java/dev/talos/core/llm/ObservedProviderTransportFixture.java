package dev.talos.core.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ProviderReadInputBinding;
import dev.talos.spi.types.ProviderTransportAttempt;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/** E2E-only observed provider transport for finite buffered responses. */
final class ObservedProviderTransportFixture {
    private ObservedProviderTransportFixture() {}

    static Stream<TokenChunk> dispatchCompletedTerminal(
            ChatRequest request,
            String fallbackProviderBody,
            ProviderTransportHandoff handoff,
            ProviderTransportAttempt.SendOperation<Stream<TokenChunk>> send) {
        ChatRequest exactRequest = Objects.requireNonNull(request, "provider request");
        boolean observedReadInputs = exactRequest.controls.debugTags().contains(ProviderReadInputBinding.REQUEST_TAG)
                || exactRequest.controls.debugTags().contains(ProviderReadInputBinding.DELIVERY_TAG);
        if (!observedReadInputs) {
            return dispatchCompletedTerminal(fallbackProviderBody, exactRequest.tools, handoff, send);
        }
        return dispatchCompletedTerminal(ProviderTransportHandoff.admit(
                handoff, serializedReadRequest(exactRequest), exactRequest.tools, exactRequest), send);
    }

    static Stream<TokenChunk> dispatchCompletedTerminal(
            String providerBody,
            List<ToolSpec> nativeTools,
            ProviderTransportHandoff handoff,
            ProviderTransportAttempt.SendOperation<Stream<TokenChunk>> send) {
        ProviderTransportAttempt transport = ProviderTransportHandoff.admit(
                handoff,
                Objects.requireNonNull(providerBody, "provider body"),
                nativeTools);
        return dispatchCompletedTerminal(transport, send);
    }

    private static Stream<TokenChunk> dispatchCompletedTerminal(
            ProviderTransportAttempt transport,
            ProviderTransportAttempt.SendOperation<Stream<TokenChunk>> send) {
        Stream<TokenChunk> stream;
        try {
            stream = Objects.requireNonNull(
                    transport.invokeSend(send),
                    "provider token stream");
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("provider fixture send failed", failure);
        }
        transport.responseHeadersObserved();
        transport.responseBodyOpened(stream::close);
        transport.providerTerminalObserved();
        transport.responseBodyCompleted();
        return stream;
    }

    private static String serializedReadRequest(ChatRequest request) {
        var mapper = new ObjectMapper();
        var body = mapper.createObjectNode();
        body.put("model", request.model);
        var messages = body.putArray("messages");
        try {
            if (request.controls.toolChoice() == ToolChoiceMode.NAMED) {
                var choice = body.putObject("tool_choice");
                choice.put("type", "function");
                choice.putObject("function").put("name", request.controls.namedTool());
            } else {
                body.put("tool_choice", request.controls.toolChoice().name().toLowerCase(Locale.ROOT));
            }
            if (!request.tools.isEmpty()) {
                var tools = body.putArray("tools");
                for (ToolSpec tool : request.tools) {
                    var serialized = tools.addObject();
                    serialized.put("type", "function");
                    var function = serialized.putObject("function");
                    function.put("name", tool.name());
                    function.put("description", tool.description());
                    function.set("parameters", tool.parametersSchemaJson() == null
                            ? mapper.createObjectNode().put("type", "object")
                            : mapper.readTree(tool.parametersSchemaJson()));
                }
            }
            for (ChatMessage message : request.messages) {
                var serialized = messages.addObject();
                serialized.put("role", message.role());
                serialized.put("content", message.content());
                if (message.toolCallId() != null) serialized.put("tool_call_id", message.toolCallId());
                if (message.hasNativeToolCalls()) {
                    var calls = serialized.putArray("tool_calls");
                    for (ChatMessage.NativeToolCall call : message.toolCalls()) {
                        var serializedCall = calls.addObject();
                        serializedCall.put("id", call.id());
                        serializedCall.put("type", "function");
                        var function = serializedCall.putObject("function");
                        function.put("name", call.name());
                        function.put("arguments", mapper.writeValueAsString(call.arguments()));
                    }
                }
            }
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("The observed E2E request could not be serialized", invalid);
        }
    }
}
