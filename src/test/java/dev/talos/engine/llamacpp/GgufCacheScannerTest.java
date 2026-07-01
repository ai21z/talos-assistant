package dev.talos.engine.llamacpp;

import dev.talos.spi.EngineConfig;
import dev.talos.spi.types.ModelRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GgufCacheScannerTest {

    @Test
    void scanFindsGgufsInHfLayoutAndFlatDirAndIgnoresNonGguf(@TempDir Path cache) throws IOException {
        // HF cache layout: <cache>/models--Org--Repo/snapshots/<hash>/<file>.gguf
        Path snap = cache.resolve("models--Org--Repo").resolve("snapshots").resolve("abc123");
        Files.createDirectories(snap);
        Files.writeString(snap.resolve("model-a-q4km.gguf"), "x");
        // a flat gguf directly under the cache root
        Files.writeString(cache.resolve("model-b.gguf"), "x");
        // a non-gguf file must be ignored
        Files.writeString(cache.resolve("README.md"), "not a model");

        List<ModelRef> found = GgufCacheScanner.scanDownloaded(cache);
        List<String> names = found.stream().map(ModelRef::name).toList();

        assertEquals(List.of("model-a-q4km", "model-b"), names, names.toString());
        assertTrue(found.stream().allMatch(m -> "llama_cpp".equals(m.backend())), found.toString());
    }

    @Test
    void scanIsEmptyForNullOrMissingDirectory(@TempDir Path cache) {
        assertTrue(GgufCacheScanner.scanDownloaded(null).isEmpty());
        assertTrue(GgufCacheScanner.scanDownloaded(cache.resolve("does-not-exist")).isEmpty());
    }

    @Test
    void scanDedupesARepeatedGgufNameAcrossSnapshots(@TempDir Path cache) throws IOException {
        Path s1 = cache.resolve("models--Org--Repo").resolve("snapshots").resolve("hash1");
        Path s2 = cache.resolve("models--Org--Repo").resolve("snapshots").resolve("hash2");
        Files.createDirectories(s1);
        Files.createDirectories(s2);
        Files.writeString(s1.resolve("dup.gguf"), "x");
        Files.writeString(s2.resolve("dup.gguf"), "x");

        List<ModelRef> found = GgufCacheScanner.scanDownloaded(cache);
        assertEquals(1, found.size(), found.toString());
        assertEquals("dup", found.get(0).name());
    }

    @Test
    void downloadedNotConfiguredExcludesTheConfiguredModel(@TempDir Path cache) throws IOException {
        Files.writeString(cache.resolve("model-a.gguf"), "x");   // the configured/running one
        Files.writeString(cache.resolve("model-b.gguf"), "x");   // a downloaded extra

        EngineConfig cfg = () -> Map.of("engines", Map.of("llama_cpp", Map.of(
                "hf_cache_dir", cache.toString(),
                "model", "model-a")));

        List<String> names = GgufCacheScanner.downloadedNotConfigured(cfg).stream()
                .map(ModelRef::name).toList();

        assertEquals(List.of("model-b"), names, names.toString());
    }

    @Test
    void downloadedNotConfiguredIgnoresInvalidConfiguredCacheDir() {
        EngineConfig cfg = () -> Map.of("engines", Map.of("llama_cpp", Map.of(
                "hf_cache_dir", "bad\u0000path",
                "model", "model-a")));

        List<ModelRef> found = assertDoesNotThrow(() -> GgufCacheScanner.downloadedNotConfigured(cfg));

        assertTrue(found.isEmpty(), found.toString());
    }
}
