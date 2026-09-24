package dev.talos.runtime.toolcall;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.util.Sanitize;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.task.ReadOnlyRequestMeaning;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ProviderReadInputBinding;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.BiFunction;

/** Test observations pass through runtime recording, formatting and selected transport capture. */
public final class ReadOnlyFinalizationEvidenceTestSupport {
    private ReadOnlyFinalizationEvidenceTestSupport() {}

    public static ReadOnlyFinalizationEvidence evidence(Path workspace, TaskContract contract,
                                                        String answer, Map<String, String> rawSources)
            throws Exception {
        return captured(workspace, contract, rawSources, ignored -> answer).evidence();
    }

    public record CapturedAnswer(String answer, ReadOnlyFinalizationEvidence evidence) {}

    public static CapturedAnswer captured(Path workspace, TaskContract contract, Map<String, String> rawSources,
                                         Function<List<ReadOnlyFinalizationEvidence.Source>, String> candidate)
            throws Exception {
        return captured(workspace, contract, rawSources, (meaning, sources) -> candidate.apply(sources));
    }

    public static CapturedAnswer captured(Path workspace, TaskContract contract, Map<String, String> rawSources,
                                         BiFunction<ReadOnlyRequestMeaning, List<ReadOnlyFinalizationEvidence.Source>, String> candidate)
            throws Exception {
        if (!LocalTurnTraceCapture.isActive() || ProviderResponseCapture.currentScope() == null)
            throw new IllegalStateException("An existing trace and provider capture scope are required");
        var previous = TurnTaskContractCapture.getResolution();
        try {
            TurnTaskContractCapture.set(contract);
            LoopState state = recordedState(workspace, contract, rawSources);
            var selected = ReadOnlyEvidenceContext.select(state);
            var frozen = ReadOnlyFinalizationEvidence.freeze(state, selected);
            String answer = candidate.apply(frozen.requestMeaning(), frozen.sources());
            if (!selected.complete()) return new CapturedAnswer(answer, frozen.bind(answer, ProviderResponseCapture.Receipt.none()));
            List<ChatMessage> prepared = new ArrayList<>();
            for (var carrier : selected.carriers().stream()
                    .sorted(Comparator.comparingInt(ReadOnlyEvidenceContext.Carrier::index)).toList()) {
                prepared.add(ChatMessage.assistantWithToolCalls("", List.of(carrier.call())));
                prepared.add(ChatMessage.toolResult(carrier.message().toolCallId(),
                        Sanitize.sanitizeMessageContent(carrier.message().content())));
            }
            ProviderResponseCapture.Receipt receipt = selectedReceipt(answer, prepared);
            state.finishWithCapturedAnswer(answer == null ? "" : answer.strip(), receipt);
            return new CapturedAnswer(answer, frozen.bind(answer, receipt));
        } finally {
            TurnTaskContractCapture.set(previous);
        }
    }

    static LoopState recordedState(Path workspace, TaskContract contract, Map<String, String> rawSources) {
        LoopState state = new LoopState("", List.of(), new ArrayList<>(List.of(
                ChatMessage.user(contract.originalUserRequest()))), workspace, null, null, 10, 0);
        for (var source : rawSources.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            String id = "observed-read-" + state.messages.size();
            ToolCall call = new ToolCall("talos.read_file", Map.of("path", source.getKey()));
            String body = numbered(source.getValue());
            ToolResult result = ToolResult.ok(body);
            state.messages.add(ChatMessage.assistantWithToolCalls("", List.of(
                    new ChatMessage.NativeToolCall(id, call.toolName(), Map.of("path", source.getKey())))));
            ReadEvidenceStateAccounting.recordSuccessfulToolResult(state, call, source.getKey(), result);
            var formatted = ToolResultFormatter.formatWithBody(call, result, false);
            state.messages.add(ChatMessage.toolResult(id, formatted.text()));
            int index = state.messages.size() - 1;
            state.readEvidence.bindToolResult(call, index, state.messages.get(index), formatted);
        }
        return state;
    }

    static ProviderResponseCapture.Receipt selectedReceipt(String answer, List<ChatMessage> prepared)
            throws Exception {
        var controls = new ChatRequestControls(ToolChoiceMode.NONE, "", ResponseFormatMode.TEXT, "",
                List.of(ProviderReadInputBinding.REQUEST_TAG), null, 2048).withSingleAttempt(true);
        ChatRequest request = new ChatRequest("fixture", "fixture", "", "", List.of(),
                Duration.ofSeconds(1), prepared, List.of(), controls);
        List<Map<String, Object>> serializedMessages = new ArrayList<>();
        for (ChatMessage message : prepared) {
            Map<String, Object> serialized = new LinkedHashMap<>();
            serialized.put("role", message.role());
            serialized.put("content", message.content());
            if (message.toolCallId() != null) serialized.put("tool_call_id", message.toolCallId());
            serializedMessages.add(serialized);
        }
        String json = new ObjectMapper().writeValueAsString(Map.of("messages", serializedMessages));
        var generation = ProviderResponseCapture.currentScope().openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        var attempt = generation.startAttempt(ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.bindPreparedReadInputs(request, ProviderReadInputBinding.SANITIZER_VERSION);
        var transport = attempt.admitTransport(
                ProviderTransportHandoff.DispatchEvidence.fromProviderBody(json, List.of(), request));
        transport.invokeSend(() -> null);
        transport.responseHeadersObserved();
        transport.responseBodyOpened(() -> {});
        transport.providerTerminalObserved();
        transport.responseBodyCompleted();
        var receipt = attempt.respond(new ProviderResponseCapture.Response(answer, 0,
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.PROVIDER_EOS, false, false));
        generation.complete(receipt);
        return receipt;
    }

    static String numbered(String raw) {
        List<String> lines = raw.lines().toList();
        if (lines.isEmpty()) return "(file has 0 lines; offset 1 is past end)";
        StringBuilder result = new StringBuilder();
        int count = Math.min(500, lines.size());
        for (int i = 0; i < count; i++) result.append(i + 1).append(" | ").append(lines.get(i)).append('\n');
        if (count < lines.size()) result.append("... (").append(lines.size() - count).append(" more lines)\n");
        if (result.length() > 16_000)
            return result.substring(0, 16_000) + "\n... [output truncated at 16K chars]";
        return result.toString();
    }
}
