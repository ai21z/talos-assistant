package dev.talos.engine.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportAttempt;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaPromptDebugCaptureTest {

    @AfterEach
    void clearCapture() {
        PromptDebugCapture.clear();
        ProviderResponseCapture.clear();
    }

    @Test
    void chatViaMessagesCapturesActualOllamaHttpBodyShape() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(bodyRef);
        try {
            String host = "http://127.0.0.1:" + server.getAddress().getPort();
            OllamaChatClient client = new OllamaChatClient(
                    host,
                    "qwen2.5-coder:14b",
                    true,
                    HttpClient.newHttpClient(),
                    new ObjectMapper());

            ChatRequest request = new ChatRequest(
                    "ollama",
                    "qwen2.5-coder:14b",
                    "",
                    "",
                    List.of(),
                    Duration.ofSeconds(5),
                    List.of(
                            ChatMessage.system("main system"),
                            ChatMessage.user("history user"),
                            ChatMessage.system("[CurrentTurnCapability]\n[ExpectedTargets]\nrequiredTargets: scripts.js"),
                            ChatMessage.user("Create index.html, styles.css, and scripts.js")),
                    List.of(new ToolSpec("talos.write_file", "Write", "{}")));

            DispatchCapture handoff = new DispatchCapture();
            client.chat(request, handoff);

            String actualBody = bodyRef.get();
            var snapshot = PromptDebugCapture.latest().orElseThrow();
            assertEquals("OLLAMA_HTTP_BODY", snapshot.stage());
            assertFalse(snapshot.stream());
            assertEquals(actualBody, snapshot.providerBodyJson());
            assertTrue(actualBody.contains("\"system\""), actualBody);
            assertTrue(actualBody.contains("main system"), actualBody);
            assertTrue(actualBody.contains("[CurrentTurnCapability]"), actualBody);
            assertTrue(actualBody.contains("\"messages\""), actualBody);
            assertTrue(actualBody.contains("\"tools\""), actualBody);
            assertFalse(actualBody.contains("\"role\":\"system\""), actualBody);
            assertEquals(0, handoff.unboundDispatches);
            assertEquals(1, handoff.evidence.size());
            assertEquals(sha256Utf8(actualBody),
                    handoff.evidence.getFirst().providerBodyHash());
            assertEquals(List.of("talos.write_file"),
                    handoff.evidence.getFirst().nativeTools());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateStreamMarksHandoffAtTheOllamaTransportBoundary() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(bodyRef);
        try {
            String host = "http://127.0.0.1:" + server.getAddress().getPort();
            OllamaChatClient client = new OllamaChatClient(
                    host,
                    "qwen2.5-coder:14b",
                    true,
                    HttpClient.newHttpClient(),
                    new ObjectMapper());
            ChatRequest request = new ChatRequest(
                    "ollama",
                    "qwen2.5-coder:14b",
                    "system",
                    "user",
                    List.of(),
                    Duration.ofSeconds(5),
                    List.of(),
                    List.of());
            DispatchCapture handoff = new DispatchCapture();

            client.chatStream(request, handoff).toList();

            assertEquals(0, handoff.unboundDispatches);
            assertEquals(1, handoff.evidence.size());
            assertEquals(sha256Utf8(bodyRef.get()),
                    handoff.evidence.getFirst().providerBodyHash());
            assertEquals(List.of(), handoff.evidence.getFirst().nativeTools());
            assertTrue(bodyRef.get().contains("\"stream\":true"), bodyRef.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateNonStreamingBindsTheExactBodyWithNoNativeSurface() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(bodyRef);
        try {
            OllamaChatClient client = new OllamaChatClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "qwen2.5-coder:14b",
                    true,
                    HttpClient.newHttpClient(),
                    new ObjectMapper());
            ChatRequest request = new ChatRequest(
                    "ollama", "qwen2.5-coder:14b", "system", "user",
                    List.of(), Duration.ofSeconds(5), List.of(),
                    List.of(new ToolSpec("talos.read_file", "Read", "{}")));
            DispatchCapture handoff = new DispatchCapture();

            assertEquals("ok", client.chat(request, handoff));

            assertEquals(0, handoff.unboundDispatches);
            assertEquals(1, handoff.evidence.size());
            assertEquals(sha256Utf8(bodyRef.get()),
                    handoff.evidence.getFirst().providerBodyHash());
            assertEquals(List.of(), handoff.evidence.getFirst().nativeTools());
            assertTrue(bodyRef.get().contains("\"prompt\""), bodyRef.get());
            assertFalse(bodyRef.get().contains("\"tools\""), bodyRef.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void messageStreamMarksHandoffAtTheOllamaTransportBoundary() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(bodyRef);
        try {
            String host = "http://127.0.0.1:" + server.getAddress().getPort();
            OllamaChatClient client = new OllamaChatClient(
                    host,
                    "qwen2.5-coder:14b",
                    false,
                    HttpClient.newHttpClient(),
                    new ObjectMapper());
            ChatRequest request = new ChatRequest(
                    "ollama",
                    "qwen2.5-coder:14b",
                    "",
                    "",
                    List.of(),
                    Duration.ofSeconds(5),
                    List.of(ChatMessage.user("hello")),
                    List.of(new ToolSpec("talos.read_file", "Read", "{}")));
            DispatchCapture handoff = new DispatchCapture();

            client.chatStream(request, handoff).toList();

            assertEquals(0, handoff.unboundDispatches);
            assertEquals(1, handoff.evidence.size());
            assertEquals(sha256Utf8(bodyRef.get()),
                    handoff.evidence.getFirst().providerBodyHash());
            assertEquals(List.of(), handoff.evidence.getFirst().nativeTools());
            assertTrue(bodyRef.get().contains("\"messages\""), bodyRef.get());
            assertTrue(bodyRef.get().contains("\"stream\":true"), bodyRef.get());
            assertFalse(bodyRef.get().contains("\"tools\""), bodyRef.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void messageStreamNativeEnabledBindsTheSerializedToolSurface() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(bodyRef);
        try {
            OllamaChatClient client = new OllamaChatClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "qwen2.5-coder:14b",
                    true,
                    HttpClient.newHttpClient(),
                    new ObjectMapper());
            ChatRequest request = new ChatRequest(
                    "ollama", "qwen2.5-coder:14b", "", "",
                    List.of(), Duration.ofSeconds(5),
                    List.of(ChatMessage.user("read README.md")),
                    List.of(new ToolSpec("talos.read_file", "Read", "{}")));
            DispatchCapture handoff = new DispatchCapture();

            client.chatStream(request, handoff).toList();

            assertEquals(0, handoff.unboundDispatches);
            assertEquals(1, handoff.evidence.size());
            assertEquals(sha256Utf8(bodyRef.get()),
                    handoff.evidence.getFirst().providerBodyHash());
            assertEquals(List.of("talos.read_file"),
                    handoff.evidence.getFirst().nativeTools());
            assertTrue(bodyRef.get().contains("\"tools\""), bodyRef.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ollamaLocalUriFailureDoesNotClaimTransportDispatch() {
        OllamaChatClient client = new OllamaChatClient(
                "http://[invalid-host",
                "qwen2.5-coder:14b",
                true,
                HttpClient.newHttpClient(),
                new ObjectMapper());
        ChatRequest request = new ChatRequest(
                "ollama",
                "qwen2.5-coder:14b",
                "system",
                "user",
                List.of(),
                Duration.ofSeconds(5),
                List.of(),
                List.of());
        AtomicInteger dispatches = new AtomicInteger();

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> client.chatStream(request, dispatches::incrementAndGet));

        assertEquals(0, dispatches.get());
    }

    @Test
    void truncatedStreamingBodyIsNormalizedAsNonRetryableProviderFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            byte[] partial = "{\"response\":\"partial\",\"done\":false}\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, partial.length + 256L);
            exchange.getResponseBody().write(partial);
            exchange.close();
        });
        server.start();
        try {
            OllamaChatClient client = new OllamaChatClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "qwen2.5-coder:14b",
                    true,
                    HttpClient.newHttpClient(),
                    new ObjectMapper());
            ChatRequest request = new ChatRequest(
                    "ollama", "qwen2.5-coder:14b", "system", "user",
                    List.of(), Duration.ofSeconds(5), List.of(), List.of());
            AtomicInteger dispatches = new AtomicInteger();

            assertThrows(
                    EngineException.ResponseError.class,
                    () -> client.chatStream(request, dispatches::incrementAndGet).toList());
            assertEquals(1, dispatches.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ollamaNonStreamingCompatPathUsesObservedTransportBoundary() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(bodyRef);
        try {
            OllamaEngine engine = new OllamaEngine(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "qwen2.5-coder:14b",
                    true);
            ChatRequest request = new ChatRequest(
                    "ollama", "qwen2.5-coder:14b", "system", "user",
                    List.of(), Duration.ofSeconds(5), List.of(), List.of());
            List<ProviderResponseCapture.TransportLifecycleEvent> lifecycle =
                    new CopyOnWriteArrayList<>();
            ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                    new ProviderResponseCapture.Listener() {
                        @Override
                        public void transportLifecycle(
                                ProviderResponseCapture.TransportLifecycleEvent event) {
                            lifecycle.add(event);
                        }
                    },
                    "ollama-nonstream-transport");
            ProviderResponseCapture.Generation generation = scope.openGeneration(
                    ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                    ProviderResponseCapture.Purpose.TURN_RESPONSE);
            ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                    ProviderResponseCapture.AttemptKind.PRIMARY,
                    ProviderResponseCapture.RetryReason.NONE);
            ProviderTransportHandoff handoff = new ProviderTransportHandoff() {
                @Override
                public void requestDispatched() { }

                @Override
                public ProviderTransportAttempt admitTransport(DispatchEvidence evidence) {
                    return attempt.admitTransport(evidence);
                }
            };

            engine.chatStreamNonStreaming(request, handoff).toList();

            assertEquals(List.of(
                            ProviderTransportAttempt.Transition.HANDOFF_ADMITTED,
                            ProviderTransportAttempt.Transition.SEND_API_ENTERED,
                            ProviderTransportAttempt.Transition.RESPONSE_HEADERS_OBSERVED,
                            ProviderTransportAttempt.Transition.RESPONSE_BODY_OPENED,
                            ProviderTransportAttempt.Transition.PROVIDER_TERMINAL_OBSERVED,
                            ProviderTransportAttempt.Transition.RESPONSE_BODY_COMPLETED,
                            ProviderTransportAttempt.Transition.SETTLED),
                    lifecycle.stream()
                            .map(ProviderResponseCapture.TransportLifecycleEvent::transition)
                            .toList());
            assertEquals(ProviderTransportAttempt.Settlement.COMPLETED_PROVIDER_TERMINAL,
                    lifecycle.getLast().settlement());
            scope.close();
            ProviderResponseCapture.clear();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonStreamingResponseRejectsTrailingJsonRoot() throws Exception {
        HttpServer server = responseServer(
                "/api/generate",
                "{\"response\":\"unsafe\",\"done\":true} {\"done\":false}");
        try {
            OllamaChatClient client = client(server);
            ChatRequest request = new ChatRequest(
                    "ollama", "qwen2.5-coder:14b", "system", "user",
                    List.of(), Duration.ofSeconds(5), List.of(), List.of());

            assertThrows(EngineException.MalformedResponse.class,
                    () -> client.chat(request));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamingResponseRejectsTrailingBytesAfterJsonRoot() throws Exception {
        HttpServer server = responseServer(
                "/api/generate",
                "{\"response\":\"unsafe\",\"done\":true} trailing-script\n");
        try {
            OllamaChatClient client = client(server);
            ChatRequest request = new ChatRequest(
                    "ollama", "qwen2.5-coder:14b", "system", "user",
                    List.of(), Duration.ofSeconds(5), List.of(), List.of());

            assertThrows(EngineException.MalformedResponse.class,
                    () -> client.chatStream(request).toList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateStreamRejectsNonterminalObjectWithoutResponsePayload() throws Exception {
        HttpServer server = responseServer("/api/generate", "{\"unexpected\":\"shape\"}\n");
        try {
            ChatRequest request = new ChatRequest(
                    "ollama", "qwen2.5-coder:14b", "system", "user",
                    List.of(), Duration.ofSeconds(5), List.of(), List.of());

            assertThrows(EngineException.MalformedResponse.class,
                    () -> client(server).chatStream(request).toList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatStreamRejectsNonterminalObjectWithoutMessagePayload() throws Exception {
        HttpServer server = responseServer("/api/chat", "{\"unexpected\":\"shape\"}\n");
        try {
            ChatRequest request = new ChatRequest(
                    "ollama", "qwen2.5-coder:14b", "", "",
                    List.of(), Duration.ofSeconds(5),
                    List.of(ChatMessage.user("hello")), List.of());

            assertThrows(EngineException.MalformedResponse.class,
                    () -> client(server).chatStream(request).toList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nestedDoneTrueCannotBecomeProviderTerminal() throws Exception {
        HttpServer server = responseServer(
                "/api/generate",
                "{\"response\":\"text\",\"metadata\":{\"done\":true}}\n");
        try {
            OllamaChatClient client = client(server);
            ChatRequest request = new ChatRequest(
                    "ollama", "qwen2.5-coder:14b", "system", "user",
                    List.of(), Duration.ofSeconds(5), List.of(), List.of());

            List<dev.talos.spi.types.TokenChunk> chunks =
                    client.chatStream(request).toList();

            assertTrue(chunks.stream().noneMatch(chunk ->
                    chunk.terminalSignal()
                            == dev.talos.spi.types.TokenChunk.TerminalSignal.PROVIDER_SENTINEL));
        } finally {
            server.stop(0);
        }
    }

    private static OllamaChatClient client(HttpServer server) {
        return new OllamaChatClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "qwen2.5-coder:14b",
                true,
                HttpClient.newHttpClient(),
                new ObjectMapper());
    }

    private static HttpServer responseServer(String path, String response) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static HttpServer startServer(AtomicReference<String> bodyRef) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodyRef.set(body);
            byte[] response = """
                    {"message":{"role":"assistant","content":"ok"},"done":true}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/generate", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodyRef.set(body);
            byte[] response = """
                    {"response":"ok","done":true}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String sha256Utf8(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class DispatchCapture implements ProviderTransportHandoff {
        private int unboundDispatches;
        private final List<DispatchEvidence> evidence = new ArrayList<>();

        @Override
        public void requestDispatched() {
            unboundDispatches++;
        }

        @Override
        public void requestDispatched(DispatchEvidence dispatchEvidence) {
            evidence.add(dispatchEvidence);
        }
    }
}
