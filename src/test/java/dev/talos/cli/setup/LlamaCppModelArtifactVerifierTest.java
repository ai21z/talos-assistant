package dev.talos.cli.setup;

import dev.talos.engine.llamacpp.LlamaCppModelCatalog;
import dev.talos.engine.llamacpp.profile.LlamaCppArtifactVerificationStore;
import dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void controlledConversionMismatchDoesNotClaimTheGgufIsPublishedInTheSourceRepository() throws Exception {
        byte[] actual = "actual".getBytes(StandardCharsets.UTF_8);
        byte[] expected = "expect".getBytes(StandardCharsets.UTF_8);
        Path model = tempDir.resolve("qwen3-4b-instruct-2507-Q4_K_M.gguf");
        Files.write(model, actual);
        var controlled = new LlamaCppModelCatalog.ModelArtifact(
                "Qwen/Qwen3-4B-Instruct-2507",
                "cdbee75f17c01a7cc42f958dc650907174af0554",
                "qwen3-4b-instruct-2507-Q4_K_M.gguf",
                sha256(expected),
                actual.length,
                "Apache-2.0");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LlamaCppModelArtifactVerifier.requireMatch(model, "qwen3-4b", controlled));

        assertTrue(error.getMessage().contains("exact artifact described by profile qwen3-4b"),
                error.getMessage());
        assertTrue(error.getMessage().contains("source Qwen/Qwen3-4B-Instruct-2507@cdbee75f"),
                error.getMessage());
        assertFalse(error.getMessage().contains("from repository revision"), error.getMessage());
    }

    @Test
    void exactSetupVerificationPersistsMetadataBoundRuntimeEvidence() throws Exception {
        byte[] bytes = "qualified gguf bytes".getBytes(StandardCharsets.UTF_8);
        Path model = tempDir.resolve("model.gguf");
        Files.write(model, bytes);
        var expected = artifact(bytes.length, sha256(bytes));
        Path talosHome = tempDir.resolve("talos-home");

        var verified = LlamaCppModelArtifactVerifier.requireMatchAndRecord(
                model, "test-profile", expected, talosHome);
        var cached = new LlamaCppArtifactVerificationStore().inspect(
                model, "test-profile", expected, talosHome);

        assertTrue(verified.verificationIdentity().matches("[0-9a-f]{64}"));
        assertTrue(cached.verificationIdentity().matches("[0-9a-f]{64}"));
        org.junit.jupiter.api.Assertions.assertEquals(
                LlamaCppProfileResolver.ArtifactState.EXACT, cached.state());
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
