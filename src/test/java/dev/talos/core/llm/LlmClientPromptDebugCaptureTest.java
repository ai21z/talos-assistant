package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolSpec;
import dev.talos.spi.types.ToolChoiceMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmClientPromptDebugCaptureTest {

    @AfterEach
    void clearCapture() {
        PromptDebugCapture.clear();
    }

    @Test
    void chatFullCapturesStructuredChatRequestBeforeEngineSend() {
        RecordingResolver resolver = new RecordingResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);
        client.setToolSpecs(List.of(writeSpec()));

        client.chatFull(List.of(
                ChatMessage.system("main system prompt"),
                ChatMessage.assistant("Prior exact write used Line one."),
                ChatMessage.system("[CurrentTurnCapability]\n[ExactFileWrite]\nexpectedContent:\nAFTER"),
                ChatMessage.user("Overwrite index.html with exactly AFTER.")),
                5_000L);

        var snapshot = PromptDebugCapture.latest().orElseThrow();
        assertEquals("CHAT_REQUEST", snapshot.stage());
        assertEquals("ollama", snapshot.backend());
        assertEquals("qwen2.5-coder:14b", snapshot.model());
        assertEquals(false, snapshot.stream());
        assertEquals(List.of("talos.write_file"), snapshot.tools().stream().map(ToolSpec::name).toList());
        assertTrue(snapshot.messages().stream().anyMatch(m -> m.content().contains("[CurrentTurnCapability]")));
        assertTrue(snapshot.messages().stream().anyMatch(m -> m.content().contains("AFTER")));
        assertTrue(snapshot.messages().stream().anyMatch(m -> m.content().contains("Line one")));
    }

    @Test
    void promptDebugSnapshotCarriesRequestControls() {
        ChatRequest request = new ChatRequest(
                "llama_cpp",
                "agent.gguf",
                "",
                "",
                List.of(),
                null,
                List.of(ChatMessage.user("repair scripts.js")),
                List.of(writeSpec()),
                new ChatRequestControls(
                        ToolChoiceMode.NAMED,
                        "talos.write_file",
                        ResponseFormatMode.JSON_SCHEMA,
                        "{\"type\":\"object\"}",
                        List.of("expected-target-repair")));

        PromptDebugSnapshot snapshot = PromptDebugSnapshot.fromChatRequest(request, true);

        assertEquals(ToolChoiceMode.NAMED, snapshot.controls().toolChoice());
        assertEquals("talos.write_file", snapshot.controls().namedTool());
        assertEquals(ResponseFormatMode.JSON_SCHEMA, snapshot.controls().responseFormat());
        assertEquals("{\"type\":\"object\"}", snapshot.controls().jsonSchema());
        assertEquals(List.of("expected-target-repair"), snapshot.controls().debugTags());
    }

    @Test
    void chatFullAppliesPerRequestControlsToEngineRequest() {
        RecordingResolver resolver = new RecordingResolver(Capabilities.of(
                true, true, false, 8192,
                true, true, true,
                false, false, false, false));
        LlmClient client = new LlmClient(engineConfig(), resolver);

        client.chatFull(
                List.of(ChatMessage.user("Create scripts.js")),
                5_000L,
                List.of(writeSpec()),
                new ChatRequestControls(
                        ToolChoiceMode.REQUIRED,
                        "",
                        ResponseFormatMode.TEXT,
                        "",
                        List.of("action-obligation:MUTATING_TOOL_REQUIRED")));

        var snapshot = PromptDebugCapture.latest().orElseThrow();
        assertEquals(ToolChoiceMode.REQUIRED, snapshot.controls().toolChoice());
        assertEquals(List.of("action-obligation:MUTATING_TOOL_REQUIRED"),
                snapshot.controls().debugTags());
    }

    @Test
    void backgroundPromptDebugCaptureDoesNotOverwriteLatestUserFacingCapture() {
        PromptDebugSnapshot userFacing = PromptDebugSnapshot.fromProviderBody(
                new ChatRequest(
                        "llama_cpp",
                        "qwen2.5-coder:14b",
                        "",
                        "",
                        List.of(),
                        null,
                        List.of(ChatMessage.user("Which file imports scripts.js?")),
                        List.of(writeSpec())),
                true,
                "{\"messages\":[{\"role\":\"user\",\"content\":\"Which file imports scripts.js?\"}]}",
                "COMPAT_CHAT_HTTP_BODY");
        PromptDebugSnapshot background = PromptDebugSnapshot.fromProviderBody(
                new ChatRequest(
                        "llama_cpp",
                        "qwen2.5-coder:14b",
                        "You are a conversation summarizer for a developer CLI tool.",
                        "Recent conversation turns to incorporate:",
                        List.of(),
                        null,
                        List.of(),
                        List.of(),
                        new ChatRequestControls(
                                ToolChoiceMode.AUTO,
                                "",
                                ResponseFormatMode.TEXT,
                                "",
                                List.of("prompt-debug:background-maintenance"))),
                true,
                "{\"system\":\"You are a conversation summarizer for a developer CLI tool.\"}",
                "COMPAT_CHAT_HTTP_BODY");

        PromptDebugCapture.record(userFacing);
        PromptDebugCapture.putTurnDiagnostic("compactionStatus", "status=SKIPPED category=SKIPPED");
        PromptDebugCapture.record(background);

        PromptDebugSnapshot latest = PromptDebugCapture.latest().orElseThrow();
        assertEquals("COMPAT_CHAT_HTTP_BODY", latest.stage());
        assertTrue(latest.messages().stream()
                .anyMatch(message -> message.content().contains("Which file imports scripts.js?")));
        assertFalse(latest.controls().debugTags().contains("prompt-debug:background-maintenance"));
        assertTrue(PromptDebugCapture.latestRecorded().orElseThrow()
                .controls().debugTags().contains("prompt-debug:background-maintenance"));
        assertTrue(PromptDebugCapture.latestRecorded().orElseThrow().diagnostics().isEmpty());
    }

    @Test
    void chatPlainSummarizerDoesNotOverwriteLatestUserFacingPromptDebugCapture() {
        RecordingResolver resolver = new RecordingResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);

        client.chatFull(List.of(ChatMessage.user("List current files.")), 5_000L);
        client.chatPlain(
                "You are a conversation summarizer for a developer CLI tool.",
                "Recent conversation turns to incorporate:");

        PromptDebugSnapshot latest = PromptDebugCapture.latest().orElseThrow();
        assertTrue(latest.messages().stream()
                .anyMatch(message -> message.content().contains("List current files.")));
        assertFalse(latest.controls().debugTags().contains("prompt-debug:background-maintenance"));
    }

    @Test
    void turnDiagnosticsAttachToPromptDebugCapture() {
        PromptDebugCapture.beginTurn();
        PromptDebugCapture.putTurnDiagnostic(
                "compactionStatus",
                "status=FAILED category=INTEGRITY_REJECT reason=critical-evidence-missing:index.html");
        PromptDebugCapture.record(PromptDebugSnapshot.fromChatRequest(
                new ChatRequest(
                        "llama_cpp",
                        "qwen2.5-coder:14b",
                        "",
                        "",
                        List.of(),
                        null,
                        List.of(ChatMessage.user("Continue the site repair.")),
                        List.of(writeSpec())),
                false));

        PromptDebugSnapshot latest = PromptDebugCapture.latest().orElseThrow();
        assertEquals(
                "status=FAILED category=INTEGRITY_REJECT reason=critical-evidence-missing:index.html",
                latest.diagnostics().get("compactionStatus"));
    }

    @Test
    void exposesSelectedBackendRequiredToolChoiceCapability() {
        LlmClient required = new LlmClient(engineConfig(), new RecordingResolver(Capabilities.of(
                true, true, false, 8192,
                true, true, true,
                false, false, false, false)));
        LlmClient unsupported = new LlmClient(engineConfig(), new RecordingResolver(Capabilities.of(
                true, true, false, 8192,
                true, false, false,
                false, false, false, false)));
        required.setModel("llama_cpp/agent.gguf");
        unsupported.setModel("llama_cpp/agent.gguf");

        assertTrue(required.supportsRequiredToolChoice());
        assertEquals(false, unsupported.supportsRequiredToolChoice());
    }

    private static ToolSpec writeSpec() {
        return new ToolSpec("talos.write_file", "Write", "{}");
    }

    private static Config engineConfig() {
        Config cfg = new Config();
        LinkedHashMap<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "engine");
        llm.put("default_backend", "ollama");
        cfg.data.put("llm", llm);

        LinkedHashMap<String, Object> ollama = new LinkedHashMap<>();
        ollama.put("model", "qwen2.5-coder:14b");
        cfg.data.put("ollama", ollama);
        return cfg;
    }

    private static final class RecordingResolver implements LlmEngineResolver {
        private final AtomicInteger chatCalls = new AtomicInteger();
        private final Capabilities capabilities;

        private RecordingResolver() {
            this(Capabilities.of(true, true, false, 8192, true));
        }

        private RecordingResolver(Capabilities capabilities) {
            this.capabilities = capabilities;
        }

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            chatCalls.incrementAndGet();
            return Stream.of(TokenChunk.of("reply"), TokenChunk.eos());
        }

        @Override
        public Capabilities capabilities() {
            return capabilities;
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
