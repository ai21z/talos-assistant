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

    // Exact normalized embedded template from the isolated native-prefill diagnostic.
    private static final String QWEN25_TEMPLATE = """
            {%- if tools %}
                {{- '<|im_start|>system\\n' }}
                {%- if messages[0]['role'] == 'system' %}
                    {{- messages[0]['content'] }}
                {%- else %}
                    {{- 'You are Qwen, created by Alibaba Cloud. You are a helpful assistant.' }}
                {%- endif %}
                {{- "\\n\\n# Tools\\n\\nYou may call one or more functions to assist with the user query.\\n\\nYou are provided with function signatures within <tools></tools> XML tags:\\n<tools>" }}
                {%- for tool in tools %}
                    {{- "\\n" }}
                    {{- tool | tojson }}
                {%- endfor %}
                {{- "\\n</tools>\\n\\nFor each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:\\n<tool_call>\\n{\\"name\\": <function-name>, \\"arguments\\": <args-json-object>}\\n</tool_call><|im_end|>\\n" }}
            {%- else %}
                {%- if messages[0]['role'] == 'system' %}
                    {{- '<|im_start|>system\\n' + messages[0]['content'] + '<|im_end|>\\n' }}
                {%- else %}
                    {{- '<|im_start|>system\\nYou are Qwen, created by Alibaba Cloud. You are a helpful assistant.<|im_end|>\\n' }}
                {%- endif %}
            {%- endif %}
            {%- for message in messages %}
                {%- if (message.role == "user") or (message.role == "system" and not loop.first) or (message.role == "assistant" and not message.tool_calls) %}
                    {{- '<|im_start|>' + message.role + '\\n' + message.content + '<|im_end|>' + '\\n' }}
                {%- elif message.role == "assistant" %}
                    {{- '<|im_start|>' + message.role }}
                    {%- if message.content %}
                        {{- '\\n' + message.content }}
                    {%- endif %}
                    {%- for tool_call in message.tool_calls %}
                        {%- if tool_call.function is defined %}
                            {%- set tool_call = tool_call.function %}
                        {%- endif %}
                        {{- '\\n<tool_call>\\n{"name": "' }}
                        {{- tool_call.name }}
                        {{- '", "arguments": ' }}
                        {{- tool_call.arguments | tojson }}
                        {{- '}\\n</tool_call>' }}
                    {%- endfor %}
                    {{- '<|im_end|>\\n' }}
                {%- elif message.role == "tool" %}
                    {%- if (loop.index0 == 0) or (messages[loop.index0 - 1].role != "tool") %}
                        {{- '<|im_start|>user' }}
                    {%- endif %}
                    {{- '\\n<tool_response>\\n' }}
                    {{- message.content }}
                    {{- '\\n</tool_response>' }}
                    {%- if loop.last or (messages[loop.index0 + 1].role != "tool") %}
                        {{- '<|im_end|>\\n' }}
                    {%- endif %}
                {%- endif %}
            {%- endfor %}
            {%- if add_generation_prompt %}
                {{- '<|im_start|>assistant\\n' }}
            {%- endif %}\
            """;

    @Test
    void nativePrefillSelectsOnlyExactManagedPreparedIdentityAndOutboundModel() {
        PrefillFixture fixture = new PrefillFixture();
        assertEquals(dev.talos.engine.compat.CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED,
                LlamaCppEngine.nativeToolPrefill(fixture.config(), fixture.snapshot(), prefillRequest(fixture.alias)));
        assertEquals(dev.talos.engine.compat.CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED,
                LlamaCppEngine.nativeToolPrefill(fixture.config(), fixture.snapshot(), prefillRequest("")));
        assertEquals(dev.talos.engine.compat.CompatChatClient.NativeToolPrefill.NONE,
                LlamaCppEngine.nativeToolPrefill(fixture.config(), fixture.snapshot(), prefillRequest("gpt-oss-20b")));
        assertEquals(dev.talos.engine.compat.CompatChatClient.NativeToolPrefill.NONE,
                LlamaCppEngine.nativeToolPrefill(fixture.config(), fixture.snapshot(), prefillRequest("qwen2.5-coder-14b ")));
        assertEquals(dev.talos.engine.compat.CompatChatClient.NativeToolPrefill.NONE,
                LlamaCppEngine.nativeToolPrefill(null, fixture.snapshot(), prefillRequest(fixture.alias)));
        assertEquals(dev.talos.engine.compat.CompatChatClient.NativeToolPrefill.NONE,
                LlamaCppEngine.nativeToolPrefill(fixture.config(), null, prefillRequest(fixture.alias)));
        assertEquals(dev.talos.engine.compat.CompatChatClient.NativeToolPrefill.NONE,
                LlamaCppEngine.nativeToolPrefill(fixture.config(), fixture.snapshot(), null));
        ChatRequest crossBackend = new ChatRequest(
                "ollama", fixture.alias, "", "", List.of(), Duration.ofSeconds(5));
        assertEquals(dev.talos.engine.compat.CompatChatClient.NativeToolPrefill.NONE,
                LlamaCppEngine.nativeToolPrefill(fixture.config(), fixture.snapshot(), crossBackend));
    }

    @Test
    void nativePrefillRejectsEveryIndividualIdentityMismatchEvenIfResolutionSaysQualified() {
        Map<String, java.util.function.Consumer<PrefillFixture>> mutations = Map.ofEntries(
                Map.entry("connect-only", f -> f.mode = "connect_only"),
                Map.entry("remote managed endpoint", f -> f.host = "http://remote.example"),
                Map.entry("native configuration disabled", f -> f.nativeCalling = false),
                Map.entry("jinja disabled", f -> f.jinja = false),
                Map.entry("GPT-OSS configured", f -> f.alias = "gpt-oss-20b"),
                Map.entry("declared alias", f -> f.declaredAlias = "qwen3-8b"),
                Map.entry("prepared configured alias", f -> f.configuredAlias = "qwen3-8b"),
                Map.entry("prepared connect-only", f -> f.preparedManaged = false),
                Map.entry("unavailable props", f -> f.propsState = LlamaCppPropsClient.State.UNAVAILABLE),
                Map.entry("unavailable chat", f -> f.chatAvailable = false),
                Map.entry("wrong build", f -> f.build = "b9918-0512ef1e6"),
                Map.entry("build suffix", f -> f.build += "-other"),
                Map.entry("unqualified", f -> f.resolution = LlamaCppProfileResolver.ResolutionState.UNVERIFIED),
                Map.entry("effective text tools", f -> f.toolMode = LlamaCppProfileResolver.ToolMode.TEXT_TOOL_PROMPT),
                Map.entry("profile artifact state", f -> f.artifactState = LlamaCppProfileResolver.ArtifactState.CUSTOM),
                Map.entry("artifact identity state", f -> f.identityState = LlamaCppProfileResolver.ArtifactState.UNCHECKED),
                Map.entry("actual model size", f -> f.actualSize--),
                Map.entry("expected model size", f -> f.expectedSize--),
                Map.entry("actual model hash", f -> f.actualHash = "a".repeat(64)),
                Map.entry("expected model hash", f -> f.expectedHash = "a".repeat(64)),
                Map.entry("served model binding", f -> f.served = LlamaCppProfileResolver.MatchState.MISMATCHED),
                Map.entry("engine compatibility", f -> f.compatibility = LlamaCppProfileResolver.MatchState.UNVERIFIED),
                Map.entry("executable verification", f -> f.executableMatch = LlamaCppProfileResolver.MatchState.UNVERIFIED),
                Map.entry("runtime lane", f -> f.lane = "win-x64-cuda-13.2:b9918"),
                Map.entry("launcher hash", f -> f.executableHash = "a".repeat(64)),
                Map.entry("observations unavailable", f -> f.observation = LlamaCppProfileResolver.ObservationState.UNAVAILABLE),
                Map.entry("observed backend", f -> f.backend = "ollama"),
                Map.entry("template match state", f -> f.templateMatch = LlamaCppProfileResolver.MatchState.UNVERIFIED),
                Map.entry("observed text tools", f -> f.observedToolMode = LlamaCppProfileResolver.ToolMode.TEXT_TOOL_PROMPT),
                Map.entry("template missing", f -> f.template = ""),
                Map.entry("template length", f -> f.template += "x"),
                Map.entry("template same-size alteration", f -> f.template = f.template.replace("You are Qwen", "You are TEST")));
        for (Map.Entry<String, java.util.function.Consumer<PrefillFixture>> entry : mutations.entrySet()) {
            PrefillFixture fixture = new PrefillFixture();
            entry.getValue().accept(fixture);
            assertEquals(dev.talos.engine.compat.CompatChatClient.NativeToolPrefill.NONE,
                    LlamaCppEngine.nativeToolPrefill(fixture.config(), fixture.snapshot(),
                            prefillRequest("qwen2.5-coder-14b")), entry.getKey());
        }
    }

    @Test
    void exactManagedQwenPrefillReachesAllSixTransportRoutesWithoutChangingLogicalHistory() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = startLayoutServer(bodies);
        OwnedTestProcess process = new OwnedTestProcess();
        try {
            PrefillFixture fixture = new PrefillFixture();
            var directory = java.nio.file.Files.createTempDirectory("talos-qwen25-prefill");
            fixture.modelPath = directory.resolve("model.gguf").toString();
            java.nio.file.Files.writeString(java.nio.file.Path.of(fixture.modelPath), "fixture", StandardCharsets.UTF_8);
            fixture.serverPath = tempFilePath("llama-server.exe");
            fixture.port = server.getAddress().getPort();
            LlamaCppConfig llamaCfg = fixture.config();
            var service = new LlamaCppResolvedProfileService(
                    llamaCfg, directory,
                    (path, alias, expected, home, verify) -> fixture.artifact(),
                    base -> LlamaCppPropsClient.Result.available(new LlamaCppPropsClient.Props(
                            fixture.modelPath, fixture.template, true, true, false, false,
                            16384, fixture.build, "props-hash")),
                    (config, declared, observed, verify) -> fixture.engineFacts());
            try (LlamaCppServerManager manager = SyntheticManagedServer.manager(
                    llamaCfg, (command, logPath) -> process, HttpClient.newHttpClient(),
                    Duration.ofSeconds(2), Duration.ofMillis(10), directory.resolve("logs"))) {
                LlamaCppEngine engine = new LlamaCppEngine(llamaCfg, manager, HttpClient.newHttpClient(), service);
                ChatRequest request = prefillRequest(fixture.alias);
                List<ChatMessage> logicalMessages = List.copyOf(request.messages);
                AtomicInteger handoffs = new AtomicInteger();
                dev.talos.spi.types.ProviderTransportHandoff handoff = handoffs::incrementAndGet;

                assertEquals("ok", engine.chat(request));
                assertEquals("ok", engine.chat(request, handoff));
                try (var chunks = engine.chatStream(request)) {
                    assertEquals("ok", chunks.filter(chunk -> chunk.text() != null)
                            .map(dev.talos.spi.types.TokenChunk::text).reduce("", String::concat));
                }
                try (var chunks = engine.chatStream(request, handoff)) {
                    assertEquals("ok", chunks.filter(chunk -> chunk.text() != null)
                            .map(dev.talos.spi.types.TokenChunk::text).reduce("", String::concat));
                }
                try (var chunks = engine.chatStreamNonStreaming(request)) {
                    assertEquals("ok", chunks.filter(chunk -> chunk.text() != null)
                            .map(dev.talos.spi.types.TokenChunk::text).reduce("", String::concat));
                }
                try (var chunks = engine.chatStreamNonStreaming(request, handoff)) {
                    assertEquals("ok", chunks.filter(chunk -> chunk.text() != null)
                            .map(dev.talos.spi.types.TokenChunk::text).reduce("", String::concat));
                }

                assertEquals("QUALIFIED", engine.modelIdentity().resolution());
                assertEquals(6, bodies.size());
                assertEquals(3, handoffs.get());
                assertEquals(logicalMessages, request.messages);
                for (String body : bodies) {
                    var json = new ObjectMapper().readTree(body);
                    var messages = json.path("messages");
                    assertEquals(3, messages.size(), body);
                    assertEquals("assistant", messages.get(2).path("role").asText(), body);
                    assertEquals("<tool_call>\n", messages.get(2).path("content").asText(), body);
                    assertEquals("content", json.path("continue_final_message").asText(), body);
                    assertFalse(json.path("add_generation_prompt").asBoolean(true), body);
                    assertEquals("required", json.path("tool_choice").asText(), body);
                    assertEquals("talos.read_file", json.path("tools").get(0).path("function").path("name").asText(), body);
                }

                // A request routed through the same cached prepared engine cannot borrow this option
                // for another outbound model or for an ordinary response-only request.
                assertEquals("ok", engine.chat(prefillRequest("gpt-oss-20b")));
                assertEquals("ok", engine.chat(new ChatRequest(
                        "llama_cpp", fixture.alias, "", "Explain recursion.", List.of(), Duration.ofSeconds(5))));
                for (String body : bodies.subList(6, 8)) {
                    var json = new ObjectMapper().readTree(body);
                    assertFalse(json.has("continue_final_message"), body);
                    assertFalse(json.has("add_generation_prompt"), body);
                }
            }
            assertFalse(process.isAlive());
        } finally {
            server.stop(0);
        }
    }

    private static ChatRequest prefillRequest(String model) {
        var controls = new dev.talos.spi.types.ChatRequestControls(
                dev.talos.spi.types.ToolChoiceMode.REQUIRED, "",
                dev.talos.spi.types.ResponseFormatMode.TEXT, "", List.of())
                .withToolAuthorityMode(dev.talos.spi.types.ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE);
        return new ChatRequest(
                "llama_cpp", model, "", "", List.of(), Duration.ofSeconds(5),
                List.of(ChatMessage.system("You are a helpful assistant."),
                        ChatMessage.user("Read README.md using talos.read_file.")),
                List.of(new dev.talos.spi.types.ToolSpec("talos.read_file", "Read a file.",
                        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"],\"additionalProperties\":false}")),
                controls);
    }

    private static final class PrefillFixture {
        private String alias = "qwen2.5-coder-14b";
        private String declaredAlias = alias;
        private String configuredAlias = alias;
        private String mode = "managed";
        private String host = "http://127.0.0.1";
        private int port = 8080;
        private boolean nativeCalling = true;
        private boolean jinja = true;
        private boolean preparedManaged = true;
        private boolean chatAvailable = true;
        private String modelPath = "C:\\fixture\\qwen.gguf";
        private String serverPath = "C:\\fixture\\llama-server.exe";
        private String build = "b9918-0512ef1e5";
        private String lane = "win-x64-cuda-13.3:b9918";
        private String template = QWEN25_TEMPLATE;
        private String backend = "llama_cpp";
        private long expectedSize = 8_988_110_272L;
        private long actualSize = expectedSize;
        private String expectedHash = "c1e659736d89ac1065fb495330fb824d94001974a4bfa78e7270e43476a8d940";
        private String actualHash = expectedHash;
        private String executableHash = "ec94260223a88aa87a48fbbefa35d16a217a7df18da35eab9eef3e8790ceeb33";
        private LlamaCppPropsClient.State propsState = LlamaCppPropsClient.State.AVAILABLE;
        private LlamaCppProfileResolver.ResolutionState resolution = LlamaCppProfileResolver.ResolutionState.QUALIFIED;
        private LlamaCppProfileResolver.ToolMode toolMode = LlamaCppProfileResolver.ToolMode.NATIVE;
        private LlamaCppProfileResolver.ToolMode observedToolMode = LlamaCppProfileResolver.ToolMode.NATIVE;
        private LlamaCppProfileResolver.ArtifactState artifactState = LlamaCppProfileResolver.ArtifactState.EXACT;
        private LlamaCppProfileResolver.ArtifactState identityState = LlamaCppProfileResolver.ArtifactState.EXACT;
        private LlamaCppProfileResolver.MatchState compatibility = LlamaCppProfileResolver.MatchState.MATCHED;
        private LlamaCppProfileResolver.MatchState served = LlamaCppProfileResolver.MatchState.MATCHED;
        private LlamaCppProfileResolver.MatchState executableMatch = LlamaCppProfileResolver.MatchState.MATCHED;
        private LlamaCppProfileResolver.MatchState templateMatch = LlamaCppProfileResolver.MatchState.MATCHED;
        private LlamaCppProfileResolver.ObservationState observation = LlamaCppProfileResolver.ObservationState.AVAILABLE;

        private LlamaCppConfig config() {
            Config config = LlamaCppEngineProviderTest.config(Map.of(
                    "mode", mode, "host", host, "port", port, "model", alias,
                    "model_path", modelPath, "server_path", serverPath, "jinja", jinja, "context", 16384));
            config.data.put("tools", Map.of("native_calling", nativeCalling));
            return LlamaCppConfig.from(config);
        }

        private LlamaCppProfileResolver.ArtifactIdentity artifact() {
            return new LlamaCppProfileResolver.ArtifactIdentity(
                    identityState, modelPath, expectedSize, actualSize, expectedHash, actualHash, "verified-record");
        }

        private LlamaCppProfileResolver.EngineFacts engineFacts() {
            return new LlamaCppProfileResolver.EngineFacts(lane, executableHash, executableMatch);
        }

        private LlamaCppResolvedProfileService.Snapshot snapshot() {
            var configured = new LlamaCppProfileResolver.ConfiguredFacts(
                    configuredAlias, modelPath, 16384, "native", "", List.of(), preparedManaged);
            var observed = new LlamaCppProfileResolver.ObservedServerFacts(
                    observation, modelPath, "", backend, template, templateMatch,
                    observedToolMode, 16384, java.util.Set.of(LlamaCppProfileResolver.Modality.TEXT), "props-hash");
            var resolved = new LlamaCppProfileResolver.ResolvedLlamaCppProfile(
                    declaredAlias, configured, artifact(), engineFacts(), observed, null,
                    artifactState, compatibility, served, toolMode, RequestMessageLayout.PRESERVE_AS_WRITTEN,
                    "", 16384, List.of(), java.util.Set.of(LlamaCppProfileResolver.Modality.TEXT),
                    LlamaCppProfileResolver.HardwareSupportState.UNVERIFIED,
                    LlamaCppModelCatalog.Disposition.ACCEPTED_BETA, resolution, List.of());
            return new LlamaCppResolvedProfileService.Snapshot(
                    resolved, propsState, "", "props-hash", modelPath, build, chatAvailable, false);
        }
    }


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
                SyntheticManagedServer.manager(llamaCfg, (command, logPath) -> {
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
                SyntheticManagedServer.manager(llamaCfg, (command, logPath) -> {
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
                    SyntheticManagedServer.manager(LlamaCppConfig.from(cfg), (command, logPath) -> {
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
                    SyntheticManagedServer.manager(llamaCfg, (command, logPath) -> {
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
                    SyntheticManagedServer.manager(llamaCfg, (command, logPath) -> {
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
            try (LlamaCppServerManager manager = SyntheticManagedServer.manager(
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
