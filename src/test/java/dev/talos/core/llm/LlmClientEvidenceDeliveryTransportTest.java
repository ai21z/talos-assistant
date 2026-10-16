package dev.talos.core.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.talos.core.Config;
import dev.talos.core.util.Sanitize;
import dev.talos.engine.compat.CompatChatClient;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ProviderReadInputBinding;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportAttempt;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.RequestMessageLayout;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Real llama.cpp compatibility HTTP transport, with authored responses and explicit serializer faults. */
@Timeout(15)
class LlmClientEvidenceDeliveryTransportTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ToolSpec> TOOLS = List.of(new ToolSpec("talos.read_file", "Read a file.",
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"));
    private static final String SOURCE = "\u001b[31m1 | <button id=\"é\">\\</button>\u001b[0m\r\n2 | end\n";

    @AfterEach void clearCaptures() {
        ProviderResponseCapture.clear();
        PromptDebugCapture.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deliveryBindsSanitizedWireInputsAndPreservesExistingNativeAuthority(boolean streaming) throws Exception {
        try (var fixture = new Fixture(streaming, Fault.NONE)) {
            var receipt = fixture.chat(messages(2, SOURCE), delivery(true), TOOLS);
            var binding = receipt.readInputBinding();
            assertTrue(binding.bound(), binding.reasonCode());
            assertEquals(ProviderReadInputBinding.Purpose.DELIVERY, binding.purpose());
            assertEquals(ProviderReadInputBinding.SANITIZER_VERSION, binding.sanitizerVersion());
            assertEquals(List.of(new ProviderReadInputBinding.ToolResultInput("read-0", Sanitize.sanitizeMessageContent(SOURCE)),
                    new ProviderReadInputBinding.ToolResultInput("read-1", Sanitize.sanitizeMessageContent(SOURCE))),
                    binding.preparedToolResults());
            assertNotEquals(SOURCE, binding.preparedToolResults().getFirst().content());
            assertEquals(hash(fixture.body.get()), binding.providerBodyHash());
            JsonNode wire = JSON.readTree(fixture.body.get());
            assertFalse(wire.has("tool_choice"), "the adapter represents AUTO by omitting tool_choice");
            assertEquals("talos.read_file", wire.at("/tools/0/function/name").asText());
            assertEquals(Sanitize.sanitizeMessageContent(SOURCE), wireToolMessages(wire).getFirst().path("content").asText());
            assertTrue(receipt.providerToolCallEligible());
            assertEquals(1, receipt.capturedToolProposalCount());
            assertTrue(receipt.authorizesExactToolName("talos.read_file"));
            assertFalse(receipt.authorizesExactToolName("talos.write_file"));
            fixture.assertObservedSingleSend();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deliveryDoesNotGrantNativeAuthorityWhenNoAuthorityWasRequested(boolean streaming) throws Exception {
        try (var fixture = new Fixture(streaming, Fault.NONE)) {
            var receipt = fixture.chat(messages(2, SOURCE), delivery(false), TOOLS);
            assertTrue(receipt.readInputBinding().bound(), receipt.readInputBinding().reasonCode());
            assertEquals(ProviderResponseCapture.ToolAuthorityMode.NONE, receipt.toolAuthorityMode());
            assertFalse(receipt.providerToolCallEligible());
            assertFalse(receipt.authorizesExactToolName("talos.read_file"));
            assertFalse(JSON.readTree(fixture.body.get()).has("tools"),
                    "the client omits native tools when authority is NONE");
            fixture.assertObservedSingleSend();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changedOrOmittedSerializedToolResultsCannotBindPreparedDelivery(boolean streaming) throws Exception {
        for (Fault fault : List.of(Fault.CHANGE_CONTENT, Fault.OMIT_RESULT)) {
            try (var fixture = new Fixture(streaming, fault)) {
                var receipt = fixture.chat(messages(2, SOURCE), delivery(true), TOOLS);
                assertTrue(receipt.providerTerminalEligible());
                assertFalse(receipt.readInputBinding().bound());
                assertEquals("TOOL_RESULT_MEMBERSHIP_MISMATCH", receipt.readInputBinding().reasonCode());
                var results = wireToolMessages(JSON.readTree(fixture.body.get()));
                if (fault == Fault.CHANGE_CONTENT) {
                    assertEquals(2, results.size());
                    assertEquals("injected serialized change", results.getFirst().path("content").asText());
                } else {
                    assertEquals(1, results.size());
                    assertEquals("read-1", results.getFirst().path("tool_call_id").asText());
                }
                assertTrue(receipt.authorizesExactToolName("talos.read_file"));
                fixture.assertObservedSingleSend();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void synthesisTagRetainsStrictAuthorityRulesWhenDeliveryIsAlsoRequested(boolean streaming) throws Exception {
        var controls = new ChatRequestControls(ToolChoiceMode.AUTO, "", ResponseFormatMode.TEXT, "",
                List.of(ProviderReadInputBinding.DELIVERY_TAG, ProviderReadInputBinding.REQUEST_TAG))
                .withSingleAttempt(true).withToolAuthorityMode(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE);
        try (var fixture = new Fixture(streaming, Fault.NONE)) {
            var receipt = fixture.chat(messages(2, SOURCE), controls, TOOLS);
            assertTrue(receipt.providerTerminalEligible());
            assertFalse(receipt.readInputBinding().bound());
            assertEquals("REQUEST_NOT_RESPONSE_ONLY", receipt.readInputBinding().reasonCode());
            fixture.assertObservedSingleSend();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deliveryLimitsDoNotRelaxTheSynthesisLimits(boolean streaming) throws Exception {
        var sources = messages(9, "x".repeat(2_000));
        try (var fixture = new Fixture(streaming, Fault.NONE)) {
            var receipt = fixture.chat(sources, delivery(true), TOOLS);
            assertTrue(receipt.readInputBinding().bound(), receipt.readInputBinding().reasonCode());
            assertEquals(9, receipt.readInputBinding().preparedToolResults().size());
            assertEquals(9, wireToolMessages(JSON.readTree(fixture.body.get())).size());
            fixture.assertObservedSingleSend();
        }
        var strict = new ChatRequestControls(ToolChoiceMode.NONE, "", ResponseFormatMode.TEXT, "",
                List.of(ProviderReadInputBinding.DELIVERY_TAG, ProviderReadInputBinding.REQUEST_TAG)).withSingleAttempt(true);
        try (var fixture = new Fixture(streaming, Fault.NONE)) {
            var receipt = fixture.chat(sources, strict, List.of());
            assertTrue(receipt.providerTerminalEligible());
            assertFalse(receipt.readInputBinding().bound());
            assertEquals("PREPARED_INPUT_LIMIT", receipt.readInputBinding().reasonCode());
            assertFalse(receipt.providerToolCallEligible());
            fixture.assertObservedSingleSend();
        }
    }

    private static ChatRequestControls delivery(boolean authority) {
        return new ChatRequestControls(ToolChoiceMode.AUTO, "", ResponseFormatMode.TEXT, "",
                List.of(ProviderReadInputBinding.DELIVERY_TAG)).withSingleAttempt(true).withToolAuthorityMode(authority
                ? ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE : ProviderResponseCapture.ToolAuthorityMode.NONE);
    }

    private static List<ChatMessage> messages(int count, String source) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("Inspect the available evidence."));
        messages.add(ChatMessage.user("Diagnose the requested source."));
        for (int index = 0; index < count; index++) {
            String id = "read-" + index;
            messages.add(ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                    id, "talos.read_file", Map.of("path", "source-" + index + ".html")))));
            messages.add(ChatMessage.toolResult(id, source));
        }
        return messages;
    }

    private static List<JsonNode> wireToolMessages(JsonNode body) {
        var results = new ArrayList<JsonNode>();
        body.path("messages").forEach(message -> {
            if ("tool".equals(message.path("role").asText())) results.add(message);
        });
        return results;
    }

    private static String hash(String value) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private enum Fault { NONE, CHANGE_CONTENT, OMIT_RESULT }

    private static final class FaultMapper extends ObjectMapper {
        private final Fault fault;
        FaultMapper(Fault fault) { this.fault = fault; }

        @Override public String writeValueAsString(Object value) throws JsonProcessingException {
            String serialized = super.writeValueAsString(value);
            if (fault == Fault.NONE) return serialized;
            JsonNode body = readTree(serialized);
            if (!body.path("messages").isArray()) return serialized;
            var messages = (ArrayNode) body.path("messages");
            for (int index = 0; index < messages.size(); index++) {
                if (!"tool".equals(messages.get(index).path("role").asText())) continue;
                if (fault == Fault.OMIT_RESULT) messages.remove(index);
                else ((ObjectNode) messages.get(index)).put("content", "injected serialized change");
                break;
            }
            return super.writeValueAsString(body);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicReference<String> body = new AtomicReference<>();
        final AtomicInteger sends = new AtomicInteger();
        final List<ProviderResponseCapture.TransportLifecycleEvent> lifecycle = new CopyOnWriteArrayList<>();
        final HttpServer server;
        final HttpClient http;
        final LlmClient client;

        Fixture(boolean streaming, Fault fault) throws IOException {
            ProviderResponseCapture.begin(new ProviderResponseCapture.Listener() {
                @Override public void transportLifecycle(ProviderResponseCapture.TransportLifecycleEvent event) {
                    lifecycle.add(event);
                }
            });
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                sends.incrementAndGet();
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                var nativeCall = Map.of("index", 0, "id", "next-read", "type", "function",
                        "function", Map.of("name", "talos.read_file", "arguments", "{\"path\":\"next.html\"}"));
                String response = JSON.writeValueAsString(Map.of("choices", List.of(Map.of(
                        streaming ? "delta" : "message", Map.of("role", "assistant",
                                "content", "Evidence received.", "tool_calls", List.of(nativeCall)),
                        "finish_reason", "tool_calls"))));
                if (streaming) response = "data: " + response + "\n\ndata: [DONE]\n\n";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
                exchange.close();
            });
            server.start();
            http = HttpClient.newHttpClient();
            var adapter = new CompatChatClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    "fixture-model", http, new FaultMapper(fault));
            var resolver = new LlmEngineResolver() {
                @Override public void select(String backend, String model) { }
                @Override public void close() { }
                @Override public Capabilities capabilities() { return Capabilities.of(true, true, false, 32_768, true); }
                @Override public Stream<TokenChunk> chatStream(ChatRequest request) {
                    throw new AssertionError("unobserved dispatch");
                }
                @Override public Stream<TokenChunk> chatStream(ChatRequest request, ProviderTransportHandoff handoff) throws Exception {
                    return streaming
                            ? adapter.chatStream(request, RequestMessageLayout.SINGLE_LEADING_SYSTEM, handoff)
                            : adapter.chatStreamNonStreaming(request, RequestMessageLayout.SINGLE_LEADING_SYSTEM,
                                    CompatChatClient.NativeToolPrefill.NONE, handoff);
                }
            };
            var config = new Config();
            config.data.put("llm", new LinkedHashMap<>(Map.of("transport", "engine", "default_backend", "llama_cpp")));
            config.data.put("llama_cpp", new LinkedHashMap<>(Map.of("model", "fixture-model")));
            config.data.put("limits", new LinkedHashMap<>(Map.of("llm_context_max_tokens", 32_768)));
            client = new LlmClient(config, resolver);
        }

        ProviderResponseCapture.Receipt chat(List<ChatMessage> messages, ChatRequestControls controls, List<ToolSpec> tools) {
            var result = client.chatFull(messages, 5_000, tools, controls);
            assertEquals("Evidence received.", result.text());
            assertTrue(result.provenance().providerTerminalEligible());
            return result.provenance();
        }

        void assertObservedSingleSend() {
            assertEquals(1, sends.get());
            assertEquals(1, lifecycle.stream().filter(event ->
                    event.transition() == ProviderTransportAttempt.Transition.SEND_API_ENTERED).count());
            assertTrue(lifecycle.stream().anyMatch(event ->
                    event.transition() == ProviderTransportAttempt.Transition.RESPONSE_HEADERS_OBSERVED));
            assertTrue(lifecycle.stream().allMatch(event ->
                    event.observationMode() == ProviderTransportAttempt.ObservationMode.OBSERVED));
        }

        @Override public void close() {
            client.close();
            server.stop(0);
            http.shutdownNow();
        }
    }
}
