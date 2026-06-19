package dev.talos.core.embed;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Test security enforcement for remote Ollama hosts
 */
public class EmbeddingsClientSecurityTest {

    @Test
    public void testRemoteHostBlocked() {
        Config cfg = new Config();

        // Override config to use remote host without allow_remote=true
        Map<String, Object> ollama = new LinkedHashMap<>();
        ollama.put("host", "http://remote-server.example.com:11434");
        ollama.put("embed", "bge-m3");
        ollama.put("allow_remote", false);
        cfg.data.put("ollama", ollama);

        // Should throw SecurityException
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            new EmbeddingsClient(cfg);
        });

        assertTrue(exception.getMessage().contains("Remote Ollama host"));
        assertTrue(exception.getMessage().contains("not allowed"));
    }

    @Test
    public void lookalikeLoopbackHostBlocked() {
        Config cfg = new Config();

        Map<String, Object> ollama = new LinkedHashMap<>();
        ollama.put("host", "http://127.0.0.1.evil.example:11434");
        ollama.put("embed", "bge-m3");
        ollama.put("allow_remote", false);
        cfg.data.put("ollama", ollama);

        SecurityException exception = assertThrows(SecurityException.class, () -> {
            new EmbeddingsClient(cfg);
        });

        assertTrue(exception.getMessage().contains("Remote Ollama host"));
        assertTrue(exception.getMessage().contains("ollama.allow_remote=true"));
    }

    @Test
    public void testRemoteHostAllowedWithFlag() {
        Config cfg = new Config();

        // Override config to use remote host with allow_remote=true
        Map<String, Object> ollama = new LinkedHashMap<>();
        ollama.put("host", "http://remote-server.example.com:11434");
        ollama.put("embed", "bge-m3");
        ollama.put("allow_remote", true);
        cfg.data.put("ollama", ollama);

        // Should not throw exception but should log warning
        assertDoesNotThrow(() -> {
            new EmbeddingsClient(cfg);
        });
    }

    @Test
    public void testLocalhostAllowed() {
        Config cfg = new Config();

        // Override config to use localhost
        Map<String, Object> ollama = new LinkedHashMap<>();
        ollama.put("host", "http://127.0.0.1:11434");
        ollama.put("embed", "bge-m3");
        ollama.put("allow_remote", false);
        cfg.data.put("ollama", ollama);

        // Should not throw exception
        assertDoesNotThrow(() -> {
            new EmbeddingsClient(cfg);
        });
    }
}
