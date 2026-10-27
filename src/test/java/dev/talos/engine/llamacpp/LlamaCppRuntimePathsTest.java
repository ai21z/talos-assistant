package dev.talos.engine.llamacpp;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlamaCppRuntimePathsTest {

    @Test
    void effectivePortPrefersTheExplicitPortKey() {
        assertEquals(18_115, LlamaCppRuntimePaths.effectivePort(cfg(Map.of(
                "host", "http://127.0.0.1",
                "port", 18_115))));
    }

    @Test
    void effectivePortFallsBackToTheHostEmbeddedPort() {
        assertEquals(19_342, LlamaCppRuntimePaths.effectivePort(cfg(Map.of(
                "host", "http://127.0.0.1:19342"))));
    }

    @Test
    void effectivePortDefaultsWhenNeitherIsConfigured() {
        assertEquals(8_080, LlamaCppRuntimePaths.effectivePort(cfg(Map.of(
                "host", "http://127.0.0.1"))));
    }

    @Test
    void managedLogFileOwnsTheSharedNamingConvention() {
        assertEquals(Path.of("C:/x/logs/llama_cpp-19342.log"),
                LlamaCppRuntimePaths.managedLogFile(Path.of("C:/x/logs"), 19_342));
    }

    private static Config cfg(Map<String, Object> llamaBlock) {
        Config cfg = new Config(null);
        cfg.data.put("engines", new LinkedHashMap<>(Map.of(
                "llama_cpp", new LinkedHashMap<>(llamaBlock))));
        return cfg;
    }
}
