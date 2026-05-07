package dev.talos.core.llm;

import dev.talos.core.Config;
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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmClientCompatToolArgumentRecoveryTest {

    @AfterEach
    void clearPromptDebug() {
        PromptDebugCapture.clear();
    }

    @Test
    void retriesRequiredToolTurnNonStreamingAfterMalformedStreamedToolArguments() {
        RecoveringResolver resolver = new RecoveringResolver(true);
        LlmClient client = new LlmClient(engineConfig(), resolver);
        client.setModel("llama_cpp/qwen2.5-coder-14b");

        LlmClient.StreamResult result = client.chatFull(
                messages(),
                5_000L,
                List.of(writeSpec()),
                requiredToolControls());

        assertEquals(1, resolver.streamCalls.get());
        assertEquals(1, resolver.nonStreamingCalls.get());
        assertTrue(result.hasToolCalls());
        assertEquals("talos.write_file", result.toolCalls().get(0).name());
        assertEquals("scripts.js", result.toolCalls().get(0).arguments().get("path"));
        assertEquals(ToolChoiceMode.REQUIRED, resolver.nonStreamingRequest.controls.toolChoice());
        assertTrue(resolver.nonStreamingRequest.controls.debugTags()
                .contains("compat-tool-arguments-nonstream-retry"));
        assertTrue(PromptDebugCapture.history().stream()
                .anyMatch(snapshot -> snapshot.controls().debugTags()
                        .contains("compat-tool-arguments-nonstream-retry")));
    }

    @Test
    void failedNonStreamingRecoveryRethrowsTypedMalformedResponseAfterOneAttempt() {
        RecoveringResolver resolver = new RecoveringResolver(false);
        LlmClient client = new LlmClient(engineConfig(), resolver);
        client.setModel("llama_cpp/qwen2.5-coder-14b");

        EngineException.MalformedResponse error = assertThrows(
                EngineException.MalformedResponse.class,
                () -> client.chatFull(
                        messages(),
                        5_000L,
                        List.of(writeSpec()),
                        requiredToolControls()));

        assertEquals(1, resolver.streamCalls.get());
        assertEquals(1, resolver.nonStreamingCalls.get());
        assertEquals("compat chat stream tool arguments", error.context());
    }

    private static List<ChatMessage> messages() {
        return List.of(
                ChatMessage.system("[CurrentTurnCapability]\n[ExpectedTargets]\nrequiredTargets: scripts.js"),
                ChatMessage.user("Create scripts.js."));
    }

    private static ChatRequestControls requiredToolControls() {
        return new ChatRequestControls(
                ToolChoiceMode.REQUIRED,
                "",
                ResponseFormatMode.TEXT,
                "",
                List.of("required-mutation"));
    }

    private static ToolSpec writeSpec() {
        return new ToolSpec(
                "talos.write_file",
                "Write a file.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}");
    }

    private static Config engineConfig() {
        Config cfg = new Config();
        LinkedHashMap<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "engine");
        llm.put("default_backend", "llama_cpp");
        cfg.data.put("llm", llm);

        LinkedHashMap<String, Object> llamaCpp = new LinkedHashMap<>();
        llamaCpp.put("model", "qwen2.5-coder-14b");
        cfg.data.put("llama_cpp", llamaCpp);
        return cfg;
    }

    private static final class RecoveringResolver implements LlmEngineResolver {
        private final AtomicInteger streamCalls = new AtomicInteger();
        private final AtomicInteger nonStreamingCalls = new AtomicInteger();
        private final boolean recoverySucceeds;
        private volatile ChatRequest nonStreamingRequest;

        private RecoveringResolver(boolean recoverySucceeds) {
            this.recoverySucceeds = recoverySucceeds;
        }

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            streamCalls.incrementAndGet();
            return malformedToolArgumentStream();
        }

        @Override
        public Stream<TokenChunk> chatStreamNonStreaming(ChatRequest request) {
            nonStreamingCalls.incrementAndGet();
            nonStreamingRequest = request;
            if (!recoverySucceeds) {
                return malformedToolArgumentStream();
            }
            return Stream.of(
                    TokenChunk.ofToolCalls(List.of(new ChatMessage.NativeToolCall(
                            "call_1",
                            "talos.write_file",
                            Map.of("path", "scripts.js", "content", "ok")))),
                    TokenChunk.eos());
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static Stream<TokenChunk> malformedToolArgumentStream() {
        Iterator<TokenChunk> iterator = new Iterator<>() {
            @Override
            public boolean hasNext() {
                throw new EngineException.MalformedResponse(
                        "compat chat stream tool arguments",
                        "{\"path\":\"index.html\",\"content\":\"<!DOCTYPE html>");
            }

            @Override
            public TokenChunk next() {
                throw new NoSuchElementException();
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false);
    }
}
