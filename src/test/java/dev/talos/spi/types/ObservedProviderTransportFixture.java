package dev.talos.spi.types;

import java.util.Objects;
import java.util.stream.Stream;

/** Test-only foreground-provider transport with an observed terminal lifecycle. */
public final class ObservedProviderTransportFixture {
    private ObservedProviderTransportFixture() {}

    public static Stream<TokenChunk> dispatch(
            ChatRequest request,
            ProviderTransportHandoff handoff,
            ProviderTransportAttempt.SendOperation<Stream<TokenChunk>> send) {
        return dispatch(request, handoff, send, false);
    }

    public static Stream<TokenChunk> dispatchCompletedTerminal(
            ChatRequest request,
            ProviderTransportHandoff handoff,
            ProviderTransportAttempt.SendOperation<Stream<TokenChunk>> send) {
        return dispatch(request, handoff, send, true);
    }

    private static Stream<TokenChunk> dispatch(
            ChatRequest request,
            ProviderTransportHandoff handoff,
            ProviderTransportAttempt.SendOperation<Stream<TokenChunk>> send,
            boolean bufferedProviderTerminal) {
        ChatRequest exactRequest = Objects.requireNonNull(request, "provider request");
        boolean observedReadInputs = exactRequest.controls.debugTags().contains(ProviderReadInputBinding.REQUEST_TAG)
                || exactRequest.controls.debugTags().contains(ProviderReadInputBinding.DELIVERY_TAG);
        String providerBody = observedReadInputs ? serializedReadRequest(exactRequest) : String.join(
                "\n",
                exactRequest.backend,
                exactRequest.model,
                exactRequest.systemPrompt,
                exactRequest.userPrompt,
                exactRequest.snippets.toString(),
                exactRequest.messages.toString(),
                exactRequest.tools.toString(),
                exactRequest.controls.toString());
        ProviderTransportAttempt transport = observedReadInputs
                ? ProviderTransportHandoff.admit(handoff, providerBody, exactRequest.tools, exactRequest)
                : ProviderTransportHandoff.admit(handoff, providerBody, exactRequest.tools);
        Stream<TokenChunk> stream;
        try {
            stream = Objects.requireNonNull(
                    transport.invokeSend(send),
                    "provider token stream");
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("scripted provider send failed", failure);
        }
        transport.responseHeadersObserved();
        transport.responseBodyOpened(stream::close);
        if (bufferedProviderTerminal) {
            transport.providerTerminalObserved();
            transport.responseBodyCompleted();
            return stream;
        }
        return ProviderTransportTokenStream.track(stream, transport);
    }

    private static String serializedReadRequest(ChatRequest request) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var body = mapper.createObjectNode();
        body.put("model", request.model);
        body.put("stream", true);
        var messages = body.putArray("messages");
        try {
            if (request.controls.toolChoice() == ToolChoiceMode.NAMED) {
                var choice = body.putObject("tool_choice");
                choice.put("type", "function");
                choice.putObject("function").put("name", request.controls.namedTool());
            } else {
                body.put("tool_choice", request.controls.toolChoice().name().toLowerCase(java.util.Locale.ROOT));
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
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("The observed test request could not be serialized", invalid);
        }
    }

    public static void completeTerminal(
            ProviderResponseCapture.Attempt attempt,
            ProviderTransportHandoff.DispatchEvidence evidence) {
        complete(attempt, evidence, true);
    }

    public static void completeBodyEof(
            ProviderResponseCapture.Attempt attempt,
            ProviderTransportHandoff.DispatchEvidence evidence) {
        complete(attempt, evidence, false);
    }

    private static void complete(
            ProviderResponseCapture.Attempt attempt,
            ProviderTransportHandoff.DispatchEvidence evidence,
            boolean providerTerminal) {
        ProviderTransportAttempt transport = Objects.requireNonNull(
                        attempt, "provider response attempt")
                .admitTransport(Objects.requireNonNull(evidence, "dispatch evidence"));
        try {
            transport.invokeSend(() -> null);
        } catch (Exception impossible) {
            throw new IllegalStateException("scripted provider send failed", impossible);
        }
        transport.responseHeadersObserved();
        transport.responseBodyOpened(() -> { });
        if (providerTerminal) transport.providerTerminalObserved();
        transport.responseBodyCompleted();
    }
}
