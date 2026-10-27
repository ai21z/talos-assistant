package dev.talos.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.talos.cli.modes.UnifiedAssistantMode;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.engine.compat.CompatChatClient;
import dev.talos.runtime.Session;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.context.ContentDraftProposal;
import dev.talos.spi.types.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Real request serialization is exercised against a synthetic local provider. */
@Timeout(20)
class ContentDraftGenerationTransportTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REQUEST = "I'd like a short story about Lina, a clockmaker in a seaside town. "
            + "Her clock is always seven minutes slow. Please write it and save it as a text file, not notes.txt. "
            + "I'll give you the filename in a moment.";
    private static final String CONTENT = "Lina watched the seaside town from her clock shop. Her clock was seven minutes slow.";
    @TempDir Path workspace;

    @AfterEach
    void clear() {
        PromptDebugCapture.clear();
        ProviderResponseCapture.clear();
        dev.talos.runtime.trace.LocalTurnTraceCapture.clear();
        dev.talos.runtime.TurnTaskContractCapture.clear();
        dev.talos.runtime.TurnUserRequestCapture.clear();
        dev.talos.runtime.TurnSourceEvidenceCapture.clear();
        dev.talos.runtime.TurnTraceCapture.clear();
        dev.talos.cli.prompt.LastPromptCapture.clear();
        if (dev.talos.runtime.TurnAuditCapture.isActive()) dev.talos.runtime.TurnAuditCapture.end();
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void oneToolFreeRequestUsesTheAvailableFormatWithoutChangingAcceptance(boolean schema, boolean reject) throws Exception {
        AtomicInteger sends = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String proposal = JSON.writeValueAsString(Map.of("kind", "draft", "text", CONTENT));
        String response = reject ? "{\"error\":{\"message\":\"Synthetic request rejection\"}}"
                : JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message",
                    Map.of("role", "assistant", "content", proposal), "finish_reason", "stop"))));
        server.createContext("/v1/chat/completions", exchange -> {
            sends.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reject ? 503 : 200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            exchange.close();
        });
        server.start();
        HttpClient http = HttpClient.newHttpClient();
        try {
            var adapter = new CompatChatClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    "fixture-model", http, JSON);
            var resolver = new LlmEngineResolver() {
                @Override public void select(String backend, String model) { }
                @Override public void close() { }
                @Override public boolean capabilitiesAuthoritative() { return true; }
                @Override public Capabilities capabilities() {
                    return Capabilities.of(true, false, false, 8192, schema, schema, schema, schema, schema, false, false);
                }
                @Override public Stream<TokenChunk> chatStream(ChatRequest request) {
                    throw new AssertionError("An unobserved dispatch was attempted");
                }
                @Override public Stream<TokenChunk> chatStream(ChatRequest request, ProviderTransportHandoff handoff) throws Exception {
                    return chatStreamNonStreaming(request, handoff);
                }
                @Override public Stream<TokenChunk> chatStreamNonStreaming(ChatRequest request, ProviderTransportHandoff handoff) throws Exception {
                    assertTrue(request.controls.singleAttempt());
                    assertEquals(ProviderResponseCapture.ToolAuthorityMode.NONE, request.controls.toolAuthorityMode());
                    return adapter.chatStreamNonStreaming(request, RequestMessageLayout.SINGLE_LEADING_SYSTEM,
                            CompatChatClient.NativeToolPrefill.NONE, handoff);
                }
            };
            Config config = new Config(null);
            config.data.put("llm", new LinkedHashMap<>(Map.of("transport", "engine", "default_backend", "llama_cpp",
                    "sampling", Map.of("temperature", 0.2, "top_p", 0.8, "top_k", 20, "seed", 4242))));
            config.data.put("llama_cpp", new LinkedHashMap<>(Map.of("model", "fixture-model")));
            SessionMemory memory = new SessionMemory();
            try (var client = new LlmClient(config, resolver); var session = new Session(workspace, config)) {
                var processor = new TurnProcessor((raw, root, context) ->
                        new UnifiedAssistantMode().handle(raw, root, (Context) context));
                var settled = processor.process(session, REQUEST,
                        Context.builder(config).memory(memory).llm(client)
                                .exactAnswerPresentation(dev.talos.cli.repl.ExactAnswerRenderFixture.create(
                                        new java.io.ByteArrayOutputStream(), true, false, 96,
                                        new dev.talos.core.security.Redactor()).exactAnswerPresentation()).build());
                assertEquals(1, sends.get());
                var wire = JSON.readTree(body.get());
                assertEquals("none", wire.path("tool_choice").asText());
                assertFalse(wire.has("tools"));
                assertFalse(wire.has("continue_final_message"));
                assertEquals(0.2, wire.path("temperature").doubleValue());
                assertEquals(0.8, wire.path("top_p").doubleValue());
                assertEquals(20, wire.path("top_k").intValue());
                assertEquals(4242, wire.path("seed").intValue());
                assertEquals(2, wire.path("messages").size());
                assertEquals("system", wire.at("/messages/0/role").textValue());
                assertEquals("user", wire.at("/messages/1/role").textValue());
                assertEquals(REQUEST, wire.at("/messages/1/content").textValue());
                assertTrue(wire.at("/messages/0/content").textValue().contains(ContentDraftProposal.SCHEMA));
                if (schema) {
                    assertEquals("json_schema", wire.at("/response_format/type").asText());
                    assertEquals(JSON.readTree(ContentDraftProposal.SCHEMA), wire.at("/response_format/json_schema/schema"));
                    assertFalse(wire.at("/response_format/json_schema/schema/properties/text").has("maxLength"));
                } else assertFalse(wire.has("response_format"));
                assertTrue(settled.audit().toolCalls().isEmpty());
                assertEquals(0, settled.audit().approvalsRequired());
                assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
                if (reject) assertNull(memory.pendingContentDraft());
                else assertEquals(CONTENT, memory.pendingContentDraft().content());
                try (var files = Files.list(workspace)) { assertEquals(0, files.count()); }
            }
        } finally {
            server.stop(0);
            http.shutdownNow();
        }
    }
}
