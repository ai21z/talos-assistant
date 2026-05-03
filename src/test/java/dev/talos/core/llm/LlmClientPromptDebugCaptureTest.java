package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        public void close() {
            // no-op
        }
    }
}
