package dev.talos.engine.llamacpp;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.Config;
import dev.talos.core.engine.EngineRegistry;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ControlFlowFailure;
import dev.talos.spi.types.ModelRef;
import dev.talos.spi.types.ProviderProcessLaunchReceipt;
import dev.talos.testsupport.InterruptingHttpClient;
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
import java.util.concurrent.CopyOnWriteArrayList;
import dev.talos.spi.types.RequestMessageLayout;
import dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver;

import static org.junit.jupiter.api.Assertions.*;

class LlamaCppEngineProviderTest {

    @Test
    void providerIdIsLlamaCpp() {
        assertEquals("llama_cpp", new LlamaCppEngineProvider().id());
    }

    @Test
    void remoteChatHostIsRejectedByDefault() {
        Config cfg = config(Map.of(
                "mode", "connect_only",
                "host", "http://remote-llama.example.com",
                "port", 8080,
                "model", "talos-agent"));

        SecurityException ex = assertThrows(SecurityException.class,
                () -> new LlamaCppEngineProvider().create(cfg));

        assertTrue(ex.getMessage().contains("Remote llama_cpp chat host"));
        assertTrue(ex.getMessage().contains("engines.llama_cpp.allow_remote=true"));
    }

    @Test
    void remoteChatHostIsAllowedOnlyWithExplicitOptIn() {
        Config cfg = config(Map.of(
                "mode", "connect_only",
                "host", "http://remote-llama.example.com",
                "port", 8080,
                "model", "talos-agent",
                "allow_remote", true));

        assertDoesNotThrow(() -> new LlamaCppEngineProvider().create(cfg));
    }

    @Test
    void loopbackChatHostIsAllowedWithoutRemoteOptIn() {
        Config cfg = config(Map.of(
                "mode", "connect_only",
                "host", "http://localhost",
                "port", 8080,
                "model", "talos-agent",
                "allow_remote", false));

        assertDoesNotThrow(() -> new LlamaCppEngineProvider().create(cfg));
    }

    @Test
    void capsDoNotAdvertiseUnverifiedModelToolCapabilities() {
        Config cfg = config(Map.of(
                "mode", "managed",
                "context", 16384));

        Capabilities caps = new LlamaCppEngineProvider().create(cfg).caps();

        assertTrue(caps.chat());
        assertTrue(caps.stream());
        assertFalse(caps.embed());
        assertEquals(8192, caps.contextWindow());
        assertFalse(caps.nativeTools());
        assertFalse(caps.requiredToolChoice());
        assertFalse(caps.namedToolChoice());
        assertFalse(caps.jsonObjectResponse());
        assertFalse(caps.jsonSchemaResponse());
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
    void preparedQualifiedIdentityAdvertisesOnlyItsEffectiveNativeCapabilities() throws Exception {
        var profile = LlamaCppModelCatalog.byAlias("qwen3-8b").orElseThrow();
        String modelPath = tempFilePath(profile.artifactFile());
        Config cfg = config(Map.of(
                "mode", "connect_only",
                "model", profile.alias(),
                "model_path", modelPath,
                "context", 8192,
                "server_args", List.of("--reasoning", "off")));
        cfg.data.put("tools", Map.of("native_calling", true));
        LlamaCppConfig llamaCfg = LlamaCppConfig.from(cfg);
        var service = new LlamaCppResolvedProfileService(
                llamaCfg,
                java.nio.file.Files.createTempDirectory("talos-profile-service"),
                (path, alias, expected, home, verify) -> new LlamaCppProfileResolver.ArtifactIdentity(
                        LlamaCppProfileResolver.ArtifactState.EXACT,
                        path.toAbsolutePath().normalize().toString(),
                        profile.artifactSizeBytes(),
                        profile.artifactSizeBytes(),
                        profile.artifactSha256(),
                        profile.artifactSha256(),
                        "artifact-record"),
                base -> LlamaCppPropsClient.Result.available(new LlamaCppPropsClient.Props(
                        modelPath, "embedded template", true, true, false, false,
                        8192, "b9918-test", "props-hash")),
                (config, declared, observed, verify) -> new LlamaCppProfileResolver.EngineFacts(
                        profile.runtime().compatibleEngineLanes().getFirst(),
                        "engine-hash",
                        LlamaCppProfileResolver.MatchState.MATCHED));
        LlamaCppEngine engine = new LlamaCppEngine(
                llamaCfg,
                new LlamaCppServerManager(llamaCfg, (command, logPath) -> {
                    throw new AssertionError("connect-only must not launch");
                }, HttpClient.newHttpClient()),
                HttpClient.newHttpClient(),
                service);

        Capabilities caps = engine.prepareCapabilities();

        assertTrue(caps.nativeTools());
        assertTrue(caps.requiredToolChoice());
        assertTrue(caps.namedToolChoice());
        assertEquals(8192, caps.contextWindow());
        assertEquals("QUALIFIED", engine.modelIdentity().resolution());
        assertEquals("qwen3-8b", engine.modelIdentity().resolvedProfile());
    }

    @Test
    void capabilityPreparationPropagatesPropsTransportInterruption() throws Exception {
        Config cfg = config(Map.of(
                "mode", "connect_only",
                "host", "http://127.0.0.1",
                "port", 18115,
                "model", "qwen3-8b"));
        LlamaCppConfig llamaCfg = LlamaCppConfig.from(cfg);
        InterruptingHttpClient propsHttp = new InterruptingHttpClient(
                "stop capability preparation");
        LlamaCppPropsClient propsClient = new LlamaCppPropsClient(
                propsHttp, new ObjectMapper(), Duration.ofSeconds(1), 16 * 1024);
        var service = new LlamaCppResolvedProfileService(
                llamaCfg,
                java.nio.file.Files.createTempDirectory("talos-interrupted-profile-service"),
                (path, alias, expected, home, verify) -> {
                    throw new AssertionError("artifact resolution must not follow interruption");
                },
                propsClient::fetch,
                (config, declared, observed, verify) -> {
                    throw new AssertionError("engine-fact resolution must not follow interruption");
                });
        LlamaCppEngine engine = new LlamaCppEngine(
                llamaCfg,
                new LlamaCppServerManager(llamaCfg, (command, logPath) -> {
                    throw new AssertionError("connect-only must not launch");
                }, HttpClient.newHttpClient()),
                HttpClient.newHttpClient(),
                service);
        Thread.interrupted();
        try {
            RuntimeException thrown = assertThrows(RuntimeException.class,
                    engine::prepareCapabilities);

            assertEquals(ControlFlowFailure.Kind.INTERRUPTION,
                    ControlFlowFailure.classify(thrown));
            assertSame(propsHttp.interruption(), thrown.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, propsHttp.sendCalls());
        } finally {
            Thread.interrupted();
        }
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
    void exactQualificationLayoutReachesEveryCompatTransportPath() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = startLayoutServer(bodies);
        try {
            String alias = "test-single-system-candidate";
            String modelPath = tempFilePath("single-system.gguf");
            String serverPath = tempFilePath("llama-server.exe");
            Config cfg = config(Map.of(
                    "mode", "connect_only",
                    "server_path", serverPath,
                    "model_path", modelPath,
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "model", alias,
                    "context", 8192,
                    "server_args", List.of()));
            cfg.data.put("tools", Map.of("native_calling", true));
            LlamaCppConfig llamaCfg = LlamaCppConfig.from(cfg);
            var profile = qualificationCandidate(alias, RequestMessageLayout.SINGLE_LEADING_SYSTEM);
            var artifact = new LlamaCppProfileResolver.ArtifactIdentity(
                    LlamaCppProfileResolver.ArtifactState.EXACT,
                    java.nio.file.Path.of(modelPath).toAbsolutePath().normalize().toString(),
                    profile.artifactSizeBytes(), profile.artifactSizeBytes(),
                    profile.artifactSha256(), profile.artifactSha256(), "candidate-record");
            var service = new LlamaCppResolvedProfileService(
                    llamaCfg,
                    java.nio.file.Files.createTempDirectory("talos-layout-candidate"),
                    (path, model, expected, home, verify) -> artifact,
                    base -> LlamaCppPropsClient.Result.available(new LlamaCppPropsClient.Props(
                            modelPath, "embedded template", true, true, false, false,
                            8192, "b9918-test", "props-hash")),
                    (config, declared, observed, verify) -> new LlamaCppProfileResolver.EngineFacts(
                            profile.runtime().compatibleEngineLanes().getFirst(),
                            "engine-hash", LlamaCppProfileResolver.MatchState.MATCHED),
                    (config, home) -> java.util.Optional.of(
                            new LlamaCppQualificationCandidateLoader.LoadedCandidate(
                                    profile, "c".repeat(64), artifact, "engine-hash",
                                    "e".repeat(64),
                                    ProviderProcessLaunchReceipt.EndpointObserverKind
                                            .WINDOWS_NETSTAT_NOFOLLOW_SHA256,
                                    "f".repeat(64), 6)));
            LlamaCppEngine engine = new LlamaCppEngine(
                    llamaCfg,
                    new LlamaCppServerManager(llamaCfg, (command, logPath) -> {
                        throw new AssertionError("connect-only must not launch");
                    }, HttpClient.newHttpClient()),
                    HttpClient.newHttpClient(),
                    service);
            ChatRequest request = new ChatRequest(
                    "llama_cpp", alias, "", "", List.of(), Duration.ofSeconds(5),
                    List.of(
                            ChatMessage.system("base policy"),
                            ChatMessage.user("[ProjectMemory]\nworkspace bytes"),
                            ChatMessage.system("turn capability"),
                            ChatMessage.user("read README.md")));

            assertEquals("ok", engine.chat(request));
            assertEquals("ok", engine.chatStreamNonStreaming(request)
                    .filter(chunk -> chunk.text() != null && !chunk.text().isEmpty())
                    .map(dev.talos.spi.types.TokenChunk::text)
                    .reduce("", String::concat));
            assertEquals("ok", engine.chatStream(request)
                    .filter(chunk -> chunk.text() != null && !chunk.text().isEmpty())
                    .map(dev.talos.spi.types.TokenChunk::text)
                    .reduce("", String::concat));

            assertEquals(RequestMessageLayout.SINGLE_LEADING_SYSTEM,
                    service.resolveAvailable(true).profile().effectiveRequestLayout());
            assertEquals("SINGLE_LEADING_SYSTEM", engine.modelIdentity().effectiveRequestLayout());
            assertEquals(3, bodies.size());
            ObjectMapper mapper = new ObjectMapper();
            for (String body : bodies) {
                var messages = mapper.readTree(body).path("messages");
                assertEquals(3, messages.size(), body);
                assertEquals("system", messages.get(0).path("role").asText(), body);
                assertEquals("base policy\n\nturn capability",
                        messages.get(0).path("content").asText(), body);
                assertEquals("[ProjectMemory]\nworkspace bytes",
                        messages.get(1).path("content").asText(), body);
                assertEquals(1, java.util.stream.StreamSupport.stream(
                                java.util.Spliterators.spliteratorUnknownSize(messages.iterator(), 0), false)
                        .filter(message -> "system".equals(message.path("role").asText()))
                        .count(), body);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void qwen35CatalogLayoutReachesEveryTransportWithoutElevatingWorkspaceOrToolContent() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = startLayoutServer(bodies);
        try {
            var profile = LlamaCppModelCatalog.byAlias("qwen3.5-9b").orElseThrow();
            java.nio.file.Path model = java.nio.file.Files.createTempDirectory("talos-qwen35-layout")
                    .resolve(profile.artifactFile()).toAbsolutePath().normalize();
            java.nio.file.Files.writeString(model, "fixture", StandardCharsets.UTF_8);
            String serverPath = tempFilePath("llama-server.exe");
            Config cfg = config(Map.of(
                    "mode", "connect_only",
                    "server_path", serverPath,
                    "model_path", model.toString(),
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "model", profile.alias(),
                    "context", 8192,
                    "server_args", List.of("--reasoning", "off")));
            cfg.data.put("tools", Map.of("native_calling", true));
            LlamaCppConfig llamaCfg = LlamaCppConfig.from(cfg);
            var artifact = new LlamaCppProfileResolver.ArtifactIdentity(
                    LlamaCppProfileResolver.ArtifactState.EXACT,
                    model.toString(), profile.artifactSizeBytes(), profile.artifactSizeBytes(),
                    profile.artifactSha256(), profile.artifactSha256(), "verified-record");
            var service = new LlamaCppResolvedProfileService(
                    llamaCfg,
                    java.nio.file.Files.createTempDirectory("talos-qwen35-home"),
                    (path, alias, expected, home, verify) -> artifact,
                    base -> LlamaCppPropsClient.Result.available(new LlamaCppPropsClient.Props(
                            model.toString(), "embedded Qwen3.5 template", true, true, true, false,
                            8192, "b9918-test", "props-hash")),
                    (config, declared, observed, verify) -> new LlamaCppProfileResolver.EngineFacts(
                            profile.runtime().compatibleEngineLanes().getFirst(),
                            "engine-hash", LlamaCppProfileResolver.MatchState.MATCHED));
            LlamaCppEngine engine = new LlamaCppEngine(
                    llamaCfg,
                    new LlamaCppServerManager(llamaCfg, (command, logPath) -> {
                        throw new AssertionError("connect-only must not launch");
                    }, HttpClient.newHttpClient()),
                    HttpClient.newHttpClient(),
                    service);
            ChatRequest request = new ChatRequest(
                    "llama_cpp", profile.alias(), "", "", List.of(), Duration.ofSeconds(5),
                    List.of(
                            ChatMessage.system("base policy"),
                            ChatMessage.user("[ProjectMemory]\nWORKSPACE-BYTES"),
                            ChatMessage.system("turn capability"),
                            ChatMessage.assistantWithToolCalls("", List.of(
                                    new ChatMessage.NativeToolCall(
                                            "call-1", "talos.read_file", Map.of("path", "README.md")))),
                            ChatMessage.toolResult("call-1", "TOOL-RESULT-BYTES"),
                            ChatMessage.user("[PinnedFiles]\nPINNED-FILE-BYTES"),
                            ChatMessage.user("summarize README.md")));

            assertEquals("ok", engine.chat(request));
            assertEquals("ok", engine.chatStreamNonStreaming(request)
                    .filter(chunk -> chunk.text() != null && !chunk.text().isEmpty())
                    .map(dev.talos.spi.types.TokenChunk::text)
                    .reduce("", String::concat));
            assertEquals("ok", engine.chatStream(request)
                    .filter(chunk -> chunk.text() != null && !chunk.text().isEmpty())
                    .map(dev.talos.spi.types.TokenChunk::text)
                    .reduce("", String::concat));
            assertEquals("ok", engine.chat(new ChatRequest(
                    "llama_cpp", profile.alias(), "", "", List.of(), Duration.ofSeconds(5),
                    List.of(ChatMessage.user("doctor smoke")))));

            assertEquals(RequestMessageLayout.SINGLE_LEADING_SYSTEM,
                    service.resolveAvailable(true).profile().effectiveRequestLayout());
            assertEquals("SINGLE_LEADING_SYSTEM", engine.modelIdentity().effectiveRequestLayout());
            assertEquals(4, bodies.size());
            ObjectMapper mapper = new ObjectMapper();
            for (String body : bodies.subList(0, 3)) {
                var messages = mapper.readTree(body).path("messages");
                assertEquals(6, messages.size(), body);
                assertEquals("system", messages.get(0).path("role").asText(), body);
                assertEquals("base policy\n\nturn capability", messages.get(0).path("content").asText(), body);
                assertEquals("user", messages.get(1).path("role").asText(), body);
                assertEquals("[ProjectMemory]\nWORKSPACE-BYTES", messages.get(1).path("content").asText(), body);
                assertEquals("assistant", messages.get(2).path("role").asText(), body);
                assertEquals("tool", messages.get(3).path("role").asText(), body);
                assertEquals("TOOL-RESULT-BYTES", messages.get(3).path("content").asText(), body);
                assertEquals("user", messages.get(4).path("role").asText(), body);
                assertEquals("[PinnedFiles]\nPINNED-FILE-BYTES", messages.get(4).path("content").asText(), body);
                assertEquals("user", messages.get(5).path("role").asText(), body);
                assertFalse(messages.get(0).path("content").asText().contains("WORKSPACE-BYTES"), body);
                assertFalse(messages.get(0).path("content").asText().contains("TOOL-RESULT-BYTES"), body);
                assertFalse(messages.get(0).path("content").asText().contains("PINNED-FILE-BYTES"), body);
            }
            var smokeMessages = mapper.readTree(bodies.get(3)).path("messages");
            assertEquals(1, smokeMessages.size(), bodies.get(3));
            assertEquals("user", smokeMessages.get(0).path("role").asText(), bodies.get(3));
            assertEquals("doctor smoke", smokeMessages.get(0).path("content").asText(), bodies.get(3));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void managedChatWaitsForHealthBeforeSendingCompatChat() throws Exception {
        AtomicInteger healthCalls = new AtomicInteger();
        AtomicInteger chatCalls = new AtomicInteger();
        HttpServer server = startSequencedServer(healthCalls, chatCalls, List.of(503, 503, 200));
        OwnedTestProcess process = new OwnedTestProcess();
        try {
            Config cfg = config(Map.of(
                    "mode", "managed",
                    "server_path", tempFilePath("llama-server.exe"),
                    "model_path", tempFilePath("agent.gguf"),
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "model", "talos-agent"));
            LlamaCppConfig llamaCfg = LlamaCppConfig.from(cfg);
            try (LlamaCppServerManager manager = new LlamaCppServerManager(
                    llamaCfg,
                    (command, logPath) -> process,
                    HttpClient.newHttpClient(),
                    Duration.ofSeconds(2),
                    Duration.ofMillis(10),
                    java.nio.file.Files.createTempDirectory("talos-llama-test-logs"))) {
                LlamaCppEngine engine = new LlamaCppEngine(
                        llamaCfg,
                        manager,
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
                assertTrue(healthCalls.get() >= 3,
                        "chat must wait for readiness health checks");
            }
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

    private static HttpServer startLayoutServer(List<String> bodies) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] bytes = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/v1/chat/completions", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(requestBody);
            boolean stream = new ObjectMapper().readTree(requestBody).path("stream").asBoolean();
            byte[] bytes = (stream
                    ? "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                            + "data: [DONE]\n\n"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static LlamaCppModelCatalog.Profile qualificationCandidate(
            String alias,
            RequestMessageLayout layout) {
        return new LlamaCppModelCatalog.Profile(
                alias,
                new LlamaCppModelCatalog.ModelArtifact(
                        "example/test", "a".repeat(40), "test.gguf", "b".repeat(64),
                        4L, "Apache-2.0"),
                new LlamaCppModelCatalog.RuntimeDefaults(
                        "test", "test", true, "native", "none", "embedded",
                        8192, 8192, "test", layout, List.of(),
                        List.of("win-x64-cuda-13.3:b9918"), "test", true),
                new LlamaCppModelCatalog.QualificationEvidence(
                        LlamaCppModelCatalog.Disposition.LAB_ONLY, false, false, 5,
                        "qualification-candidate:test", "c".repeat(40), "not-scored",
                        java.time.LocalDate.of(1970, 1, 1), "test only"),
                "", "test only");
    }

    private static String tempFilePath(String name) throws IOException {
        java.nio.file.Path path = java.nio.file.Files.createTempFile(name, ".tmp");
        java.nio.file.Files.writeString(path, "fake", StandardCharsets.UTF_8);
        return path.toString();
    }

    private static final class OwnedTestProcess implements
            LlamaCppProcess,
            LlamaCppProcessTerminationOwner.Target {
        private final LlamaCppProcessTerminationOwner.ProcessIdentity identity =
                new LlamaCppProcessTerminationOwner.ProcessIdentity(
                        1L,
                        0L,
                        LlamaCppProcessTerminationOwner.IdentityAuthority
                                .DIRECT_PROCESS_CAPABILITY);
        private boolean alive = true;
        private final LlamaCppProcessTerminationOwner.Lease terminationLease =
                new LlamaCppProcessTerminationOwner().adopt(this);

        @Override
        public LlamaCppProcessTerminationOwner.ProcessIdentity identity() {
            return identity;
        }

        @Override
        public boolean identityStillMatches() {
            return true;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public boolean outputCaptureHealthy() {
            return true;
        }

        @Override
        public List<LlamaCppProcessTerminationOwner.Target> descendants() {
            return List.of();
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public void destroyForcibly() {
            alive = false;
        }

        @Override
        public boolean waitFor(Duration timeout) {
            return !alive;
        }

        @Override
        public LlamaCppProcessTerminationOwner.Lease terminationLease() {
            return terminationLease;
        }
    }
}
