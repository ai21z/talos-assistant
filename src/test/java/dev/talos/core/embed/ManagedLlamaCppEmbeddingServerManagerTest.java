package dev.talos.core.embed;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedLlamaCppEmbeddingServerManagerTest {

    @TempDir Path tempDir;

    @Test
    void buildCommandUsesEmbeddingModePoolingAndNoChatFlags() throws Exception {
        Path server = touch("llama-server.exe");
        Path model = touch("bge-m3-q8_0.gguf");
        Config cfg = config(Map.of(
                "enabled", true,
                "server_path", server.toString(),
                "model_path", model.toString(),
                "host", "http://127.0.0.1",
                "port", 18116,
                "pooling", "mean",
                "server_args", List.of("--threads", "4")));
        ManagedLlamaCppEmbeddingConfig managed = ManagedLlamaCppEmbeddingConfig.from(cfg);
        ManagedLlamaCppEmbeddingServerManager manager = new ManagedLlamaCppEmbeddingServerManager(
                managed,
                (command, logPath, environment) -> new FakeProcess(),
                HttpClient.newHttpClient(),
                Duration.ofMillis(1),
                Duration.ofMillis(1),
                tempDir.resolve("logs"));

        List<String> command = manager.buildCommand();

        assertEquals(server.toString(), command.get(0));
        assertTrue(command.contains("-m"), command.toString());
        assertTrue(command.contains(model.toString()), command.toString());
        assertTrue(command.contains("--embedding"), command.toString());
        assertTrue(command.contains("--pooling"), command.toString());
        assertTrue(command.contains("mean"), command.toString());
        assertTrue(command.contains("--host"), command.toString());
        assertTrue(command.contains("127.0.0.1"), command.toString());
        assertTrue(command.contains("--port"), command.toString());
        assertTrue(command.contains("18116"), command.toString());
        assertTrue(command.contains("--threads"), command.toString());
        assertFalse(command.contains("--alias"), command.toString());
        assertFalse(command.contains("--jinja"), command.toString());
        assertFalse(command.contains("--chat-template"), command.toString());
        assertFalse(command.contains("--chat-template-file"), command.toString());
    }

    @Test
    void buildCommandCanUseHuggingFaceSourceAndSetsHfHome() {
        Path server = tempDir.resolve("llama-server.exe");
        Path cache = tempDir.resolve("hf-cache");
        Config cfg = config(Map.of(
                "enabled", true,
                "server_path", server.toString(),
                "hf_repo", "ggml-org/bge-m3-Q8_0-GGUF",
                "hf_file", "bge-m3-q8_0.gguf",
                "hf_cache_dir", cache.toString()));
        ManagedLlamaCppEmbeddingConfig managed = ManagedLlamaCppEmbeddingConfig.from(cfg);
        ManagedLlamaCppEmbeddingServerManager manager = new ManagedLlamaCppEmbeddingServerManager(
                managed,
                (command, logPath, environment) -> new FakeProcess(),
                HttpClient.newHttpClient(),
                Duration.ofMillis(1),
                Duration.ofMillis(1),
                tempDir.resolve("logs"));

        List<String> command = manager.buildCommand();

        assertTrue(command.contains("--hf-repo"), command.toString());
        assertTrue(command.contains("ggml-org/bge-m3-Q8_0-GGUF"), command.toString());
        assertTrue(command.contains("--hf-file"), command.toString());
        assertTrue(command.contains("bge-m3-q8_0.gguf"), command.toString());
        assertEquals(cache.toString(), manager.buildEnvironment().get("HF_HOME"));
    }

    private Config config(Map<String, Object> managed) {
        Config cfg = new Config(null);
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("provider", "llama_cpp");
        embed.put("model", "bge-m3");
        embed.put("managed", new LinkedHashMap<>(managed));
        cfg.data.put("embed", embed);
        return cfg;
    }

    private Path touch(String name) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, "fake", StandardCharsets.UTF_8);
        return path;
    }

    private static final class FakeProcess implements ManagedLlamaCppEmbeddingServerManager.ManagedProcess {
        @Override public boolean isAlive() { return true; }
        @Override public void destroy() {}
    }
}
