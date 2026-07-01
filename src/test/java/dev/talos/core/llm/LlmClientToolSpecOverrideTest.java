package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmClientToolSpecOverrideTest {

    @Test
    void chatFullUsesPerCallToolSpecsWithoutChangingGlobalSpecs() {
        RecordingResolver resolver = new RecordingResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);
        List<ToolSpec> all = List.of(readSpec(), writeSpec(), editSpec());
        List<ToolSpec> readOnly = List.of(readSpec());
        client.setToolSpecs(all);

        client.chatFull(messages(), readOnly);

        assertEquals(List.of("talos.read_file"), toolNames(resolver.lastRequest));
        assertEquals(List.of("talos.read_file", "talos.write_file", "talos.edit_file"),
                toolNames(client.getToolSpecs()));

        client.chatFull(messages());

        assertEquals(List.of("talos.read_file", "talos.write_file", "talos.edit_file"),
                toolNames(resolver.lastRequest));
    }

    @Test
    void chatStreamFullUsesPerCallToolSpecs() {
        RecordingResolver resolver = new RecordingResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);
        client.setToolSpecs(List.of(readSpec(), writeSpec()));

        client.chatStreamFull(messages(), null, List.of(readSpec()));

        assertEquals(List.of("talos.read_file"), toolNames(resolver.lastRequest));
    }

    private static List<ChatMessage> messages() {
        return List.of(
                ChatMessage.system("system"),
                ChatMessage.user("hello"));
    }

    private static ToolSpec readSpec() {
        return new ToolSpec("talos.read_file", "Read", "{}");
    }

    private static ToolSpec writeSpec() {
        return new ToolSpec("talos.write_file", "Write", "{}");
    }

    private static ToolSpec editSpec() {
        return new ToolSpec("talos.edit_file", "Edit", "{}");
    }

    private static List<String> toolNames(ChatRequest request) {
        return toolNames(request.tools);
    }

    private static List<String> toolNames(List<ToolSpec> specs) {
        return specs.stream().map(ToolSpec::name).toList();
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
        private volatile ChatRequest lastRequest;

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            this.lastRequest = request;
            chatCalls.incrementAndGet();
            return Stream.of(TokenChunk.of("reply"), TokenChunk.eos());
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
