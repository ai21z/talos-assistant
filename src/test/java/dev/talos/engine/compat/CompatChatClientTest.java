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
import dev.talos.spi.types.SamplingControls;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                            List.of("expected-target-repair")).withMaxOutputTokens(512));

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
            assertEquals(512, body.path("max_tokens").asInt());
            assertEquals("talos.write_file", body.path("tools").get(0).path("function").path("name").asText());

            var snapshot = PromptDebugCapture.latest().orElseThrow();
            assertEquals("COMPAT_CHAT_HTTP_BODY", snapshot.stage());
            assertEquals(bodyRef.get(), snapshot.providerBodyJson());
            assertEquals(ToolChoiceMode.REQUIRED, snapshot.controls().toolChoice());
            assertEquals(ResponseFormatMode.JSON_OBJECT, snapshot.controls().responseFormat());
            assertEquals(512, snapshot.controls().maxOutputTokens());
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
    void chatSerializesSamplingControlsWhenSet() throws Exception {
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
                    List.of(ChatMessage.user("batch op")),
                    List.of(new ToolSpec("talos.apply_workspace_batch", "Batch", "{}")),
                    new ChatRequestControls(
                            ToolChoiceMode.REQUIRED,
                            "",
                            ResponseFormatMode.TEXT,
                            "",
                            List.of()).withSampling(new SamplingControls(0.2, 0.8, 20, 42L)));

            client.chat(request);

            JsonNode body = MAPPER.readTree(bodyRef.get());
            assertEquals(0.2, body.path("temperature").asDouble());
            assertEquals(0.8, body.path("top_p").asDouble());
            assertEquals(20, body.path("top_k").asInt());
            assertEquals(42L, body.path("seed").asLong());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatOmitsSamplingFieldsWhenUnset() throws Exception {
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
                    List.of(ChatMessage.user("hello")),
                    List.of(),
                    ChatRequestControls.defaults());

            client.chat(request);

            JsonNode body = MAPPER.readTree(bodyRef.get());
            assertFalse(body.has("temperature"));
            assertFalse(body.has("top_p"));
            assertFalse(body.has("top_k"));
            assertFalse(body.has("seed"));
            assertFalse(body.has("max_tokens"));
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
    void chatStreamPropagatesLengthFinishReasonOnEos() throws Exception {
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), """
                data: {"choices":[{"delta":{"content":"partial"}}]}

                data: {"choices":[{"delta":{},"finish_reason":"length"}]}

                data: [DONE]

                """, "text/event-stream");
        try {
            CompatChatClient client = client(server);

            List<TokenChunk> chunks = client.chatStream(requestForStream()).toList();

            assertEquals("partial", chunks.get(0).text());
            assertTrue(chunks.get(1).done());
            assertEquals("length", chunks.get(1).finishReason());
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
    void chatStreamMergesObjectToolArgumentDeltas() throws Exception {
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"talos.write_file","arguments":{"path":"scripts.js"}}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":{"content":"ok"}}}]},"finish_reason":"tool_calls"}]}

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
    void chatStreamUnsupportedToolArgumentShapeCarriesStructuredDiagnostic() throws Exception {
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"talos.write_file","arguments":["not","an","object"]}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """, "text/event-stream");
        try {
            CompatChatClient client = client(server);

            EngineException.MalformedResponse error = assertThrows(
                    EngineException.MalformedResponse.class,
                    () -> client.chatStream(requestForStream()).toList());

            assertEquals("compat chat stream tool arguments", error.context());
            assertEquals("", error.bodyPreview());
            assertTrue(error.bodyHash().startsWith("sha256:"), error.bodyHash());
            assertTrue(error.bodyChars() > 0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatStreamMalformedToolArgumentsCarriesStructuredDiagnostic() throws Exception {
        String malformedArguments = "{\"path\":\"scripts.js\",\"content\":\"ok\"";
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"talos.write_file","arguments":"%s"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """.formatted(malformedArguments.replace("\"", "\\\"")), "text/event-stream");
        try {
            CompatChatClient client = client(server);

            EngineException.MalformedResponse error = assertThrows(
                    EngineException.MalformedResponse.class,
                    () -> client.chatStream(requestForStream()).toList());

            assertEquals("compat chat stream tool arguments", error.context());
            assertEquals(malformedArguments.length(), error.bodyChars());
            assertEquals("", error.bodyPreview());
            assertTrue(error.bodyHash().startsWith("sha256:"), error.bodyHash());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatStreamReadAbortThrowsTransientNotMalformedResponse() {
        CompatChatClient client = new CompatChatClient(
                "http://127.0.0.1:18115",
                "agent.gguf",
                new ReadAbortHttpClient(),
                MAPPER);

        EngineException.Transient error = assertThrows(
                EngineException.Transient.class,
                () -> client.chatStream(requestForStream()).toList());

        assertEquals(408, error.httpStatus());
        assertTrue(error.getMessage().contains("Stream read aborted"), error.getMessage());
        assertTrue(error.guidance().contains("smaller model"), error.guidance());
    }

    @Test
    void chatStreamNonStreamingParsesToolCallsFromNonStreamResponse() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(new AtomicReference<>(""), bodyRef, """
                {"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_1","type":"function","function":{"name":"talos.write_file","arguments":"{\\\"path\\\":\\\"scripts.js\\\",\\\"content\\\":\\\"ok\\\"}"}}]}}]}
                """, "application/json");
        try {
            CompatChatClient client = client(server);

            List<TokenChunk> chunks = client.chatStreamNonStreaming(requestForStream()).toList();

            JsonNode body = MAPPER.readTree(bodyRef.get());
            assertEquals(false, body.path("stream").asBoolean());
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

    @Test
    void chatStreamHttp400ContextSizeThrowsContextBudgetExceededWithBodyDetails() throws Exception {
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), """
                {"error":{"message":"request (3390 tokens) exceeds the available context size (3072 tokens), try increasing it"}}
                """, "application/json", 400);
        try {
            CompatChatClient client = client(server);

            EngineException.ContextBudgetExceeded error =
                    assertThrows(EngineException.ContextBudgetExceeded.class,
                            () -> client.chatStream(requestForStream()).toList());

            assertEquals(3390, error.estimatedTokens());
            assertEquals(3072, error.inputBudgetTokens());
            assertEquals(3072, error.contextWindowTokens());
            assertEquals(400, error.httpStatus());
            assertFalse(error.getMessage().contains("complete"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatHttp500ContextSizeThrowsContextBudgetExceededInsteadOfAssistantText() throws Exception {
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), """
                {"error":{"message":"Context size has been exceeded."}}
                """, "application/json", 500);
        try {
            CompatChatClient client = client(server);

            EngineException.ContextBudgetExceeded error =
                    assertThrows(EngineException.ContextBudgetExceeded.class, () -> client.chat(requestForStream()));

            assertEquals(500, error.httpStatus());
            assertTrue(error.getMessage().contains("Request exceeds context budget"));
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

    private static final class ReadAbortHttpClient extends HttpClient {
        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            @SuppressWarnings("unchecked")
            T body = (T) new ReadAbortInputStream();
            return new StaticHttpResponse<>(request, 200, body);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private static final class ReadAbortInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new SocketTimeoutException("Read timed out");
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            throw new SocketTimeoutException("Read timed out");
        }
    }

    private record StaticHttpResponse<T>(
            HttpRequest request,
            int statusCode,
            T body) implements HttpResponse<T> {

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }
    }

    private static HttpServer startServer(
            AtomicReference<String> pathRef,
            AtomicReference<String> bodyRef,
            String response,
            String contentType
    ) throws IOException {
        return startServer(pathRef, bodyRef, response, contentType, 200);
    }

    private static HttpServer startServer(
            AtomicReference<String> pathRef,
            AtomicReference<String> bodyRef,
            String response,
            String contentType,
            int status
    ) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            pathRef.set(exchange.getRequestURI().getPath());
            bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
