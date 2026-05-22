package dev.talos.engine.llamacpp;

import com.sun.net.httpserver.HttpServer;
import dev.talos.core.Config;
import dev.talos.core.engine.EngineRegistry;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ModelRef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LlamaCppEngineProviderTest {

    @Test
    void providerIdIsLlamaCpp() {
        assertEquals("llama_cpp", new LlamaCppEngineProvider().id());
    }

    @Test
    void capsReportLlamaCppCompatSurface() {
        Config cfg = config(Map.of(
                "mode", "managed",
                "context", 16384));

        Capabilities caps = new LlamaCppEngineProvider().create(cfg).caps();

        assertTrue(caps.chat());
        assertTrue(caps.stream());
        assertFalse(caps.embed());
        assertEquals(16384, caps.contextWindow());
        assertTrue(caps.nativeTools());
        assertTrue(caps.requiredToolChoice());
        assertTrue(caps.namedToolChoice());
        assertTrue(caps.jsonObjectResponse());
        assertTrue(caps.jsonSchemaResponse());
        assertTrue(caps.serverModelCatalog());
        assertTrue(caps.managedProcess());
    }

    @Test
    void managedCapsReportRaisedAgentMinimumContext() {
        Config cfg = config(Map.of(
                "mode", "managed",
                "context", 4096));

        Capabilities caps = new LlamaCppEngineProvider().create(cfg).caps();

        assertEquals(8192, caps.contextWindow());
    }

    @Test
    void connectOnlyCapsReportConfiguredExternalContext() {
        Config cfg = config(Map.of(
                "mode", "connect_only",
                "context", 4096));

        Capabilities caps = new LlamaCppEngineProvider().create(cfg).caps();

        assertEquals(4096, caps.contextWindow());
    }

    @Test
    void providerIsDiscoverableThroughEngineRegistry() {
        EngineRegistry registry = new EngineRegistry(config(Map.of("mode", "connect_only")));
        try {
            assertNotNull(registry.catalog("llama_cpp"));
        } finally {
            registry.close();
        }
    }

    @Test
    void connectOnlyChatRoutesThroughCompatTransport() throws Exception {
        HttpServer server = startServer("""
                {"choices":[{"message":{"role":"assistant","content":"hello from llama.cpp"}}]}
                """, """
                {"data":[{"id":"talos-agent"}]}
                """);
        try {
            Config cfg = config(Map.of(
                    "mode", "connect_only",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "model", "talos-agent"));
            LlamaCppEngine engine = new LlamaCppEngine(
                    LlamaCppConfig.from(cfg),
                    new LlamaCppServerManager(LlamaCppConfig.from(cfg), (command, logPath) -> {
                        throw new AssertionError("connect-only must not launch");
                    }, HttpClient.newHttpClient()),
                    HttpClient.newHttpClient());

            String response = engine.chat(new ChatRequest(
                    "llama_cpp",
                    "talos-agent",
                    "",
                    "",
                    List.of(),
                    Duration.ofSeconds(5),
                    List.of(ChatMessage.user("hello"))));

            assertEquals("hello from llama.cpp", response);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void managedChatWaitsForHealthBeforeSendingCompatChat() throws Exception {
        AtomicInteger healthCalls = new AtomicInteger();
        AtomicInteger chatCalls = new AtomicInteger();
        HttpServer server = startSequencedServer(healthCalls, chatCalls, List.of(503, 503, 200));
        try {
            Config cfg = config(Map.of(
                    "mode", "managed",
                    "server_path", tempFilePath("llama-server.exe"),
                    "model_path", tempFilePath("agent.gguf"),
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "model", "talos-agent"));
            LlamaCppConfig llamaCfg = LlamaCppConfig.from(cfg);
            LlamaCppEngine engine = new LlamaCppEngine(
                    llamaCfg,
                    new LlamaCppServerManager(llamaCfg, (command, logPath) -> new LlamaCppProcess() {
                        @Override public boolean isAlive() { return true; }
                        @Override public void destroy() {}
                    }, HttpClient.newHttpClient(), Duration.ofSeconds(2), Duration.ofMillis(10),
                            java.nio.file.Files.createTempDirectory("talos-llama-test-logs")),
                    HttpClient.newHttpClient());

            String response = engine.chat(new ChatRequest(
                    "llama_cpp",
                    "talos-agent",
                    "",
                    "",
                    List.of(),
                    Duration.ofSeconds(5),
                    List.of(ChatMessage.user("hello"))));

            assertEquals("hello after ready", response);
            assertEquals(1, chatCalls.get());
            assertTrue(healthCalls.get() >= 3, "chat must wait for readiness health checks");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void catalogReadsModelsEndpointAndFallsBackToConfiguredModel() throws Exception {
        HttpServer server = startServer("""
                {"choices":[{"message":{"role":"assistant","content":"ok"}}]}
                """, """
                {"data":[{"id":"server-model"},{"id":"second-model"}]}
                """);
        try {
            Config cfg = config(Map.of(
                    "mode", "connect_only",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "model", "talos-agent"));

            List<ModelRef> installed = new LlamaCppEngineProvider().catalog(cfg).installed();

            assertEquals(List.of("server-model", "second-model"),
                    installed.stream().map(ModelRef::name).toList());
        } finally {
            server.stop(0);
        }

        List<ModelRef> fallback = new LlamaCppEngineProvider()
                .catalog(config(Map.of("mode", "connect_only", "model", "configured-agent")))
                .installed();
        assertEquals("configured-agent", fallback.get(0).name());
        assertEquals("llama_cpp", fallback.get(0).backend());
    }

    private static Config config(Map<String, Object> llamaCpp) {
        Config cfg = new Config();
        Map<String, Object> engines = new LinkedHashMap<>();
        engines.put("llama_cpp", new LinkedHashMap<>(llamaCpp));
        cfg.data.put("engines", engines);
        return cfg;
    }

    private static HttpServer startServer(String chatBody, String modelsBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] bytes = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] bytes = chatBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/v1/models", exchange -> {
            byte[] bytes = modelsBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static HttpServer startSequencedServer(AtomicInteger healthCalls,
                                                   AtomicInteger chatCalls,
                                                   List<Integer> healthStatuses) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            int index = healthCalls.getAndIncrement();
            int status = healthStatuses.get(Math.min(index, healthStatuses.size() - 1));
            byte[] bytes = (status == 200 ? "ok" : "loading").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/v1/chat/completions", exchange -> {
            chatCalls.incrementAndGet();
            byte[] bytes = """
                    {"choices":[{"message":{"role":"assistant","content":"hello after ready"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String tempFilePath(String name) throws IOException {
        java.nio.file.Path path = java.nio.file.Files.createTempFile(name, ".tmp");
        java.nio.file.Files.writeString(path, "fake", StandardCharsets.UTF_8);
        return path.toString();
    }
}
