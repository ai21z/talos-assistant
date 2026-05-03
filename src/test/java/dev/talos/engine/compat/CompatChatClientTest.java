package dev.talos.engine.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatChatClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void clearPromptDebug() {
        PromptDebugCapture.clear();
    }

    @Test
    void chatSerializesRequiredToolChoiceJsonObjectAndCapturesProviderBody() throws Exception {
        AtomicReference<String> pathRef = new AtomicReference<>("");
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(pathRef, bodyRef, """
                {"choices":[{"message":{"role":"assistant","content":"ok"}}]}
                """, "application/json");
        try {
            CompatChatClient client = client(server);
            ChatRequest request = new ChatRequest(
                    "llama_cpp",
                    "agent.gguf",
                    "",
                    "",
                    List.of(),
                    Duration.ofSeconds(5),
                    List.of(
                            ChatMessage.system("main system"),
                            ChatMessage.user("Create scripts.js")),
                    List.of(new ToolSpec("talos.write_file", "Write", "{\"type\":\"object\"}")),
                    new ChatRequestControls(
                            ToolChoiceMode.REQUIRED,
                            "",
                            ResponseFormatMode.JSON_OBJECT,
                            "",
                            List.of("expected-target-repair")));

            String result = client.chat(request);

            assertEquals("ok", result);
            assertEquals("/v1/chat/completions", pathRef.get());
            JsonNode body = MAPPER.readTree(bodyRef.get());
            assertEquals("agent.gguf", body.path("model").asText());
            assertEquals(false, body.path("stream").asBoolean());
            assertEquals("system", body.path("messages").get(0).path("role").asText());
            assertEquals("main system", body.path("messages").get(0).path("content").asText());
            assertEquals("required", body.path("tool_choice").asText());
            assertEquals("json_object", body.path("response_format").path("type").asText());
            assertEquals("talos.write_file", body.path("tools").get(0).path("function").path("name").asText());

            var snapshot = PromptDebugCapture.latest().orElseThrow();
            assertEquals("COMPAT_CHAT_HTTP_BODY", snapshot.stage());
            assertEquals(bodyRef.get(), snapshot.providerBodyJson());
            assertEquals(ToolChoiceMode.REQUIRED, snapshot.controls().toolChoice());
            assertEquals(ResponseFormatMode.JSON_OBJECT, snapshot.controls().responseFormat());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatSerializesNamedToolChoiceAndJsonSchema() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(new AtomicReference<>(""), bodyRef, """
                {"choices":[{"message":{"role":"assistant","content":"ok"}}]}
                """, "application/json");
        try {
            CompatChatClient client = client(server);
            ChatRequest request = new ChatRequest(
                    "llama_cpp",
                    "agent.gguf",
                    "",
                    "",
                    List.of(),
                    Duration.ofSeconds(5),
                    List.of(ChatMessage.user("repair")),
                    List.of(new ToolSpec("talos.write_file", "Write", "{}")),
                    new ChatRequestControls(
                            ToolChoiceMode.NAMED,
                            "talos.write_file",
                            ResponseFormatMode.JSON_SCHEMA,
                            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}",
                            List.of()));

            client.chat(request);

            JsonNode body = MAPPER.readTree(bodyRef.get());
            assertEquals("function", body.path("tool_choice").path("type").asText());
            assertEquals("talos.write_file",
                    body.path("tool_choice").path("function").path("name").asText());
            assertEquals("json_schema", body.path("response_format").path("type").asText());
            assertEquals("object", body.path("response_format").path("schema").path("type").asText());
            assertTrue(body.path("response_format").path("schema").path("properties").has("path"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatStreamParsesTextChunks() throws Exception {
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), """
                data: {"choices":[{"delta":{"content":"Hel"}}]}

                data: {"choices":[{"delta":{"content":"lo"}}]}

                data: [DONE]

                """, "text/event-stream");
        try {
            CompatChatClient client = client(server);
            ChatRequest request = requestForStream();

            List<TokenChunk> chunks = client.chatStream(request).toList();

            assertEquals("Hel", chunks.get(0).text());
            assertEquals("lo", chunks.get(1).text());
            assertTrue(chunks.get(2).done());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatStreamParsesCompleteToolCallDelta() throws Exception {
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"talos.write_file","arguments":"{\\\"path\\\":\\\"scripts.js\\\",\\\"content\\\":\\\"ok\\\"}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """, "text/event-stream");
        try {
            CompatChatClient client = client(server);

            List<TokenChunk> chunks = client.chatStream(requestForStream()).toList();

            assertTrue(chunks.get(0).hasToolCalls());
            var call = chunks.get(0).toolCalls().get(0);
            assertEquals("call_1", call.id());
            assertEquals("talos.write_file", call.name());
            assertEquals("scripts.js", call.arguments().get("path"));
            assertEquals("ok", call.arguments().get("content"));
            assertTrue(chunks.get(1).done());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedSuccessfulResponseThrowsTypedMalformedResponse() throws Exception {
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), """
                {"unexpected":"shape"}
                """, "application/json");
        try {
            CompatChatClient client = client(server);

            EngineException error = assertInstanceOf(EngineException.class,
                    org.junit.jupiter.api.Assertions.assertThrows(EngineException.MalformedResponse.class,
                            () -> client.chat(requestForStream())));

            assertEquals(0, error.httpStatus());
            assertTrue(error.getMessage().contains("compat chat response"));
            assertFalse(error.getMessage().contains("complete"));
        } finally {
            server.stop(0);
        }
    }

    private static ChatRequest requestForStream() {
        return new ChatRequest(
                "llama_cpp",
                "agent.gguf",
                "",
                "",
                List.of(),
                Duration.ofSeconds(5),
                List.of(ChatMessage.user("hello")),
                List.of());
    }

    private static CompatChatClient client(HttpServer server) {
        String host = "http://127.0.0.1:" + server.getAddress().getPort();
        return new CompatChatClient(host, "agent.gguf", HttpClient.newHttpClient(), MAPPER);
    }

    private static HttpServer startServer(
            AtomicReference<String> pathRef,
            AtomicReference<String> bodyRef,
            String response,
            String contentType
    ) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            pathRef.set(exchange.getRequestURI().getPath());
            bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
