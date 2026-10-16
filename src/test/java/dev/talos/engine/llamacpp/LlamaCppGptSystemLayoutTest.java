package dev.talos.engine.llamacpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.talos.core.Config;
import dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ProviderReadInputBinding;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.RequestMessageLayout;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.SamplingControls;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Catalog resolution and real HTTP are exercised with synthetic identity facts and replies. */
class LlamaCppGptSystemLayoutTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SYSTEMS = List.of(
            "Talos read-only policy.",
            "[Conversation reference policy]\nEarlier proposals are not current facts or completed work.",
            "[RuntimeModelIdentity]\nThe captured current backend is llama_cpp.",
            "[CurrentTurnCapability]\nOnly bounded current reads are admitted.");
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"supported\":{\"type\":\"boolean\"}},"
            + "\"required\":[\"supported\"],\"additionalProperties\":false}";
    private static final String ANSWER = "{\"supported\":true}";
    @TempDir Path home;

    @AfterEach void clear() {
        PromptDebugCapture.clear();
        ProviderResponseCapture.clear();
    }

    @ParameterizedTest
    @CsvSource({"chat,REQUIRED", "stream,REQUIRED", "non-stream,REQUIRED",
            "chat,NONE", "stream,NONE", "non-stream,NONE"})
    void qualifiedGptCatalogDeliversAllSystemsWithoutElevatingEvidence(String transport, ToolChoiceMode choice)
            throws Exception {
        exercise("gpt-oss-20b", transport, choice, true, RequestMessageLayout.SINGLE_LEADING_SYSTEM);
    }

    @ParameterizedTest
    @CsvSource({"chat", "stream", "non-stream"})
    void unboundGptEngineDoesNotAcquireTheLayoutFromItsAlias(String transport) throws Exception {
        exercise("gpt-oss-20b", transport, ToolChoiceMode.NONE, false,
                RequestMessageLayout.PRESERVE_AS_WRITTEN);
    }

    @ParameterizedTest
    @CsvSource({"chat", "stream", "non-stream"})
    void qwenCatalogKeepsItsExistingMessageLayout(String transport) throws Exception {
        exercise("qwen2.5-coder-14b", transport, ToolChoiceMode.NONE, true,
                RequestMessageLayout.PRESERVE_AS_WRITTEN);
    }

    private void exercise(String alias, String transport, ToolChoiceMode choice, boolean bound,
                          RequestMessageLayout expectedLayout) throws Exception {
        var body = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.createContext("/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean streaming = JSON.readTree(body.get()).path("stream").asBoolean();
            String reply = streaming
                    ? "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"supported\\\":true}\"},"
                        + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"{\\\"supported\\\":true}\"},\"finish_reason\":\"stop\"}]}";
            byte[] response = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var profile = LlamaCppModelCatalog.byAlias(alias).orElseThrow();
            Path model = Files.writeString(home.resolve(profile.artifactFile()), "identity fixture");
            Path executable = Files.writeString(home.resolve("llama-server.exe"), "launcher fixture");
            Config config = new Config(home.resolve("missing-config.yaml"));
            config.data.clear();
            config.data.put("engines", Map.of("llama_cpp", new LinkedHashMap<>(Map.of(
                    "mode", "connect_only", "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(), "model", alias, "context", 8192,
                    "model_path", model.toString(), "server_path", executable.toString()))));
            config.data.put("tools", Map.of("native_calling", true));
            LlamaCppConfig configured = LlamaCppConfig.from(config);
            var artifact = new LlamaCppProfileResolver.ArtifactIdentity(
                    LlamaCppProfileResolver.ArtifactState.EXACT, model.toString(),
                    profile.artifactSizeBytes(), profile.artifactSizeBytes(),
                    profile.artifactSha256(), profile.artifactSha256(), "synthetic-verification");
            var service = new LlamaCppResolvedProfileService(configured, home,
                    (path, name, expected, root, verify) -> artifact,
                    uri -> LlamaCppPropsClient.Result.available(new LlamaCppPropsClient.Props(
                            model.toString(), "synthetic embedded template", true, true, false, false,
                            8192, "b9918-test", "synthetic-props")),
                    (facts, declared, observed, verify) -> new LlamaCppProfileResolver.EngineFacts(
                            profile.runtime().compatibleEngineLanes().getFirst(), bound ? "engine-sha" : "",
                            bound ? LlamaCppProfileResolver.MatchState.MATCHED
                                    : LlamaCppProfileResolver.MatchState.UNVERIFIED));
            var messages = new ArrayList<ChatMessage>();
            SYSTEMS.forEach(value -> messages.add(ChatMessage.system(value)));
            messages.add(ChatMessage.user("Read the current settings and separate them from the earlier suggestion."));
            messages.add(ChatMessage.user("[Conversation reference data]\n"
                    + "{\"observed\":\"pine\",\"proposed\":\"cedar\",\"marker\":\"HISTORY-BYTES\"}"));
            messages.add(ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                    "read-current", "talos.read_file", Map.of("path", "settings.json")))));
            messages.add(ChatMessage.toolResult("read-current", "TOOL-BYTES {\"label\":\"oak\"}"));
            messages.add(ChatMessage.user("[PinnedFiles]\nWORKSPACE-BYTES"));
            var controls = new ChatRequestControls(choice, "", ResponseFormatMode.JSON_SCHEMA, SCHEMA,
                    List.of(ProviderReadInputBinding.DELIVERY_TAG))
                    .withToolAuthorityMode(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE)
                    .withSampling(new SamplingControls(0.2, 0.8, 20, 4242L))
                    .withMaxOutputTokens(512).withSingleAttempt(true);
            var tools = choice == ToolChoiceMode.REQUIRED
                    ? List.of(new ToolSpec("talos.read_file", "Read", "{\"type\":\"object\"}")) : List.<ToolSpec>of();
            var request = new ChatRequest("llama_cpp", alias, "", "", List.of(), Duration.ofSeconds(5),
                    messages, tools, controls);
            var dispatches = new ArrayList<ProviderTransportHandoff.DispatchEvidence>();
            var handoff = new ProviderTransportHandoff() {
                @Override public void requestDispatched() { fail("An unbound dispatch was recorded"); }
                @Override public void requestDispatched(DispatchEvidence evidence) { dispatches.add(evidence); }
            };
            try (var engine = new LlamaCppEngine(configured,
                    SyntheticManagedServer.manager(configured, (command, log) -> {
                        throw new AssertionError("Connect-only fixture must not launch a process");
                    }, HttpClient.newHttpClient()), HttpClient.newHttpClient(), service)) {
                if ("chat".equals(transport)) assertEquals(ANSWER, engine.chat(request, handoff));
                else try (var chunks = "stream".equals(transport)
                        ? engine.chatStream(request, handoff) : engine.chatStreamNonStreaming(request, handoff)) {
                    assertEquals(ANSWER, chunks.filter(chunk -> chunk.text() != null)
                            .map(dev.talos.spi.types.TokenChunk::text).reduce("", String::concat));
                }
                assertEquals(expectedLayout.name(), engine.modelIdentity().effectiveRequestLayout());
            }
            JsonNode submitted = JSON.readTree(body.get());
            assertEquals("stream".equals(transport), submitted.path("stream").asBoolean());
            JsonNode wireMessages = submitted.path("messages");
            int offset = expectedLayout == RequestMessageLayout.SINGLE_LEADING_SYSTEM ? 1 : SYSTEMS.size();
            assertEquals(offset + messages.size() - SYSTEMS.size(), wireMessages.size());
            if (offset == 1) assertEquals(String.join("\n\n", SYSTEMS), wireMessages.get(0).path("content").asText());
            else for (int index = 0; index < SYSTEMS.size(); index++) {
                assertEquals(SYSTEMS.get(index), wireMessages.get(index).path("content").asText());
            }
            for (int index = 0; index < offset; index++) {
                assertEquals("system", wireMessages.get(index).path("role").asText());
                assertFalse(wireMessages.get(index).path("content").asText().contains("HISTORY-BYTES"));
                assertFalse(wireMessages.get(index).path("content").asText().contains("TOOL-BYTES"));
                assertFalse(wireMessages.get(index).path("content").asText().contains("WORKSPACE-BYTES"));
            }
            for (int index = SYSTEMS.size(); index < messages.size(); index++) {
                assertMessage(messages.get(index), wireMessages.get(index - SYSTEMS.size() + offset));
            }
            assertEquals(choice == ToolChoiceMode.NONE ? "none" : "required", submitted.path("tool_choice").asText());
            assertEquals(JSON.readTree(SCHEMA), submitted.at("/response_format/json_schema/schema"));
            assertEquals(512, submitted.path("max_tokens").asInt());
            assertEquals(0.2, submitted.path("temperature").asDouble());
            assertEquals(0.8, submitted.path("top_p").asDouble());
            assertEquals(20, submitted.path("top_k").asInt());
            assertEquals(4242, submitted.path("seed").asLong());
            assertFalse(submitted.has("continue_final_message"));
            assertEquals(choice == ToolChoiceMode.REQUIRED, submitted.has("tools"));
            if (choice == ToolChoiceMode.REQUIRED) {
                assertEquals(1, submitted.path("tools").size());
                assertEquals("talos.read_file", submitted.at("/tools/0/function/name").asText());
                assertEquals(JSON.readTree(tools.getFirst().parametersSchemaJson()), submitted.at("/tools/0/function/parameters"));
            }
            assertEquals(1, dispatches.size());
            assertEquals(ProviderTransportHandoff.DispatchEvidence.fromProviderBody(body.get(), tools, request),
                    dispatches.getFirst());
            assertEquals(1, dispatches.getFirst().readInputMembership().toolResults().size());
            assertEquals(messages, request.messages);
            var snapshot = PromptDebugCapture.latest().orElseThrow();
            assertEquals(messages, snapshot.messages());
            assertEquals(controls, snapshot.controls());
            assertEquals(body.get(), snapshot.providerBodyJson());
        } finally {
            server.stop(0);
        }
    }

    private static void assertMessage(ChatMessage original, JsonNode actual) throws Exception {
        assertEquals(original.role(), actual.path("role").asText());
        assertEquals(original.content(), actual.path("content").asText());
        assertEquals(original.toolCallId() != null && !original.toolCallId().isBlank(), actual.has("tool_call_id"));
        if (actual.has("tool_call_id")) assertEquals(original.toolCallId(), actual.path("tool_call_id").asText());
        assertEquals(original.hasNativeToolCalls(), actual.has("tool_calls"));
        if (original.hasNativeToolCalls()) {
            assertEquals(original.toolCalls().size(), actual.path("tool_calls").size());
            for (int index = 0; index < original.toolCalls().size(); index++) {
                var call = original.toolCalls().get(index);
                var serialized = actual.path("tool_calls").get(index);
                assertEquals(call.id(), serialized.path("id").asText());
                assertEquals("function", serialized.path("type").asText());
                assertEquals(call.name(), serialized.at("/function/name").asText());
                assertEquals(JSON.valueToTree(call.arguments()), JSON.readTree(serialized.at("/function/arguments").asText()));
            }
        }
    }
}
