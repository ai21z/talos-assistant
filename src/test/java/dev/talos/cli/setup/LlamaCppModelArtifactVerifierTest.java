package dev.talos.cli.setup;

import dev.talos.engine.llamacpp.LlamaCppModelCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppModelArtifactVerifierTest {
    @TempDir Path tempDir;

    @Test
    void acceptsExactSizeAndSha256() throws Exception {
        byte[] bytes = "qualified gguf bytes".getBytes(StandardCharsets.UTF_8);
        Path model = tempDir.resolve("model.gguf");
        Files.write(model, bytes);

        assertDoesNotThrow(() -> LlamaCppModelArtifactVerifier.requireMatch(
                model, "test-profile", artifact(bytes.length, sha256(bytes))));
    }

    @Test
    void rejectsWrongSha256EvenWhenSizeMatches() throws Exception {
        byte[] actual = "actual".getBytes(StandardCharsets.UTF_8);
        byte[] expected = "expect".getBytes(StandardCharsets.UTF_8);
        Path model = tempDir.resolve("model.gguf");
        Files.write(model, actual);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LlamaCppModelArtifactVerifier.requireMatch(
                        model, "test-profile", artifact(actual.length, sha256(expected))));

        assertTrue(error.getMessage().contains("SHA-256"), error.getMessage());
    }

    @Test
    void rejectsWrongSizeBeforeHashing() throws Exception {
        byte[] actual = "actual".getBytes(StandardCharsets.UTF_8);
        Path model = tempDir.resolve("model.gguf");
        Files.write(model, actual);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LlamaCppModelArtifactVerifier.requireMatch(
                        model, "test-profile", artifact(actual.length + 1, sha256(actual))));

        assertTrue(error.getMessage().contains("size"), error.getMessage());
    }

    private static LlamaCppModelCatalog.ModelArtifact artifact(long size, String sha256) {
        return new LlamaCppModelCatalog.ModelArtifact(
                "owner/repo",
                "0123456789abcdef0123456789abcdef01234567",
                "model.gguf",
                sha256,
                size,
                "test-license");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
