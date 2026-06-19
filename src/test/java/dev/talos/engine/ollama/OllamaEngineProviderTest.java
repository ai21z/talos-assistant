package dev.talos.engine.ollama;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaEngineProviderTest {
    @Test
    void id_is_ollama() {
        var provider = new OllamaEngineProvider();
        assertEquals("ollama", provider.id());
    }

    @Test
    void remoteChatHostIsRejectedByDefault() {
        Config cfg = config(Map.of(
                "host", "http://remote-ollama.example.com:11434",
                "model", "qwen2.5-coder:14b"));

        SecurityException ex = assertThrows(SecurityException.class,
                () -> new OllamaEngineProvider().create(cfg));

        assertTrue(ex.getMessage().contains("Remote Ollama chat host"));
        assertTrue(ex.getMessage().contains("ollama.allow_remote=true"));
    }

    @Test
    void remoteChatHostIsAllowedOnlyWithExplicitOptIn() {
        Config cfg = config(Map.of(
                "host", "http://remote-ollama.example.com:11434",
                "model", "qwen2.5-coder:14b",
                "allow_remote", true));

        assertDoesNotThrow(() -> new OllamaEngineProvider().create(cfg));
    }

    @Test
    void loopbackChatHostIsAllowedWithoutRemoteOptIn() {
        Config cfg = config(Map.of(
                "host", "http://127.0.0.1:11434",
                "model", "qwen2.5-coder:14b",
                "allow_remote", false));

        assertDoesNotThrow(() -> new OllamaEngineProvider().create(cfg));
    }

    private static Config config(Map<String, Object> ollama) {
        Config cfg = new Config();
        cfg.data.put("ollama", new LinkedHashMap<>(ollama));
        return cfg;
    }
}
