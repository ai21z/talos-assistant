package dev.talos.core.llm;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LlmClient} error-resilience additions.
 *
 * <p>These run in explicit PLACEHOLDER mode — they verify that:
 * <ul>
 *   <li>Retry constants are sensible</li>
 *   <li>PLACEHOLDER mode is unaffected by the retry/propagation changes</li>
 *   <li>Non-streaming and streaming parity is preserved</li>
 * </ul>
 */
class LlmClientRetryTest {

    private static Config placeholderConfig() {
        Config cfg = new Config();
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "placeholder");
        llm.put("default_backend", "ollama");
        cfg.data.put("llm", llm);
        return cfg;
    }

    @Test
    void max_retries_is_positive() {
        assertTrue(LlmClient.MAX_RETRIES >= 1, "Should retry at least once");
        assertTrue(LlmClient.MAX_RETRIES <= 5, "Should not retry excessively");
    }

    @Test
    void placeholder_chat_unaffected_by_retry_changes() {
        LlmClient client = new LlmClient(placeholderConfig());
        String result = client.chat("system", "hello", List.of());
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void placeholder_chatStream_unaffected_by_retry_changes() {
        LlmClient client = new LlmClient(placeholderConfig());
        AtomicReference<String> chunk = new AtomicReference<>();
        String result = client.chatStream("system", "hello", List.of(), chunk::set);
        assertNotNull(result);
        assertFalse(result.isBlank());
        // In PLACEHOLDER mode, the full answer is emitted as a single chunk
        assertNotNull(chunk.get(), "Stream sink should have received the chunk");
        assertFalse(chunk.get().isBlank());
    }

    @Test
    void placeholder_messages_chat_unaffected() {
        LlmClient client = new LlmClient(placeholderConfig());
        var msgs = List.of(
                new dev.talos.spi.types.ChatMessage("system", "be helpful"),
                new dev.talos.spi.types.ChatMessage("user", "hello")
        );
        String result = client.chat(msgs);
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void placeholder_messages_chatStream_unaffected() {
        LlmClient client = new LlmClient(placeholderConfig());
        var msgs = List.of(
                new dev.talos.spi.types.ChatMessage("system", "be helpful"),
                new dev.talos.spi.types.ChatMessage("user", "hello")
        );
        AtomicReference<String> chunk = new AtomicReference<>();
        String result = client.chatStream(msgs, chunk::set);
        assertNotNull(result);
        assertFalse(result.isBlank());
        assertNotNull(chunk.get(), "Stream sink should have received the chunk");
    }

    @Test
    void placeholder_chatPlain_still_works() {
        LlmClient client = new LlmClient(placeholderConfig());
        String result = client.chatPlain("test prompt");
        assertNotNull(result);
        assertFalse(result.isBlank(), "chatPlain should return non-blank text");
    }

    @Test
    void close_is_safe_on_placeholder() {
        LlmClient client = new LlmClient(placeholderConfig());
        assertDoesNotThrow(client::close);
        assertDoesNotThrow(client::close);
    }
}

