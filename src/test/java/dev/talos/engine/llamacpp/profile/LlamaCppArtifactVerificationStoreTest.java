package dev.talos.engine.llamacpp.profile;

import dev.talos.engine.llamacpp.LlamaCppModelCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppArtifactVerificationStoreTest {
    @TempDir
    Path temp;

    @Test
    void hashesOnceThenReusesExactMetadataBoundRecord() throws Exception {
        Path model = temp.resolve("model.gguf");
        Files.writeString(model, "exact model bytes", StandardCharsets.UTF_8);
        var expected = artifact(model);
        AtomicInteger hashes = new AtomicInteger();
        var store = store(hashes);

        var first = store.verify(model, "test-profile", expected, temp.resolve("home"));
        var second = store.verify(model, "test-profile", expected, temp.resolve("home"));

        assertEquals(LlamaCppProfileResolver.ArtifactState.EXACT, first.state());
        assertEquals(LlamaCppProfileResolver.ArtifactState.EXACT, second.state());
        assertEquals(1, hashes.get(), "unchanged bytes must not be rehashed on each resolution");
        assertFalse(first.verificationIdentity().isBlank());
        assertEquals(first.verificationIdentity(), second.verificationIdentity());
    }

    @Test
    void cachedInspectionNeverHashesAndFailsClosedWhenMetadataChanges() throws Exception {
        Path model = temp.resolve("model.gguf");
        Files.writeString(model, "exact model bytes", StandardCharsets.UTF_8);
        var expected = artifact(model);
        AtomicInteger hashes = new AtomicInteger();
        var store = store(hashes);
        store.verify(model, "test-profile", expected, temp.resolve("home"));
        hashes.set(0);

        var cached = store.inspect(model, "test-profile", expected, temp.resolve("home"));
        Files.setLastModifiedTime(model, FileTime.fromMillis(
                Files.getLastModifiedTime(model).toMillis() + 5_000));
        var stale = store.inspect(model, "test-profile", expected, temp.resolve("home"));

        assertEquals(LlamaCppProfileResolver.ArtifactState.EXACT, cached.state());
        assertEquals(LlamaCppProfileResolver.ArtifactState.UNCHECKED, stale.state());
        assertEquals(0, hashes.get());
    }

    @Test
    void changedMetadataForcesFreshHashAndMismatch() throws Exception {
        Path model = temp.resolve("model.gguf");
        Files.writeString(model, "exact model bytes", StandardCharsets.UTF_8);
        var expected = artifact(model);
        AtomicInteger hashes = new AtomicInteger();
        var store = store(hashes);
        store.verify(model, "test-profile", expected, temp.resolve("home"));

        Files.writeString(model, "other model bytes", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(model, FileTime.fromMillis(
                Files.getLastModifiedTime(model).toMillis() + 5_000));
        var changed = store.verify(model, "test-profile", expected, temp.resolve("home"));

        assertEquals(LlamaCppProfileResolver.ArtifactState.SHA256_MISMATCH, changed.state());
        assertEquals(2, hashes.get());
        assertTrue(changed.verificationIdentity().isBlank());
    }

    @Test
    void corruptCacheCannotPreserveExactState() throws Exception {
        Path model = temp.resolve("model.gguf");
        Files.writeString(model, "exact model bytes", StandardCharsets.UTF_8);
        var expected = artifact(model);
        AtomicInteger hashes = new AtomicInteger();
        var store = store(hashes);
        Path home = temp.resolve("home");
        store.verify(model, "test-profile", expected, home);
        Path record = Files.walk(home.resolve("identity")).filter(Files::isRegularFile).findFirst().orElseThrow();
        Files.writeString(record, "{not-json", StandardCharsets.UTF_8);
        hashes.set(0);

        var inspection = store.inspect(model, "test-profile", expected, home);
        var verified = store.verify(model, "test-profile", expected, home);

        assertEquals(LlamaCppProfileResolver.ArtifactState.UNCHECKED, inspection.state());
        assertEquals(LlamaCppProfileResolver.ArtifactState.EXACT, verified.state());
        assertEquals(1, hashes.get());
    }

    @Test
    void missingAndSizeMismatchDoNotHash() throws Exception {
        Path model = temp.resolve("model.gguf");
        Files.writeString(model, "exact model bytes", StandardCharsets.UTF_8);
        var expected = new LlamaCppModelCatalog.ModelArtifact(
                "example/repo", "a".repeat(40), model.getFileName().toString(), "b".repeat(64),
                Files.size(model) + 1, "Apache-2.0");
        AtomicInteger hashes = new AtomicInteger();
        var store = store(hashes);

        var size = store.verify(model, "test-profile", expected, temp.resolve("home"));
        var missing = store.verify(temp.resolve("missing.gguf"), "test-profile", expected, temp.resolve("home"));

        assertEquals(LlamaCppProfileResolver.ArtifactState.SIZE_MISMATCH, size.state());
        assertEquals(LlamaCppProfileResolver.ArtifactState.MISSING, missing.state());
        assertEquals(0, hashes.get());
    }

    @Test
    void recordsDownloaderVerifiedHashWithoutHashingLargeFileAgain() throws Exception {
        Path model = temp.resolve("model.gguf");
        Files.writeString(model, "exact model bytes", StandardCharsets.UTF_8);
        var expected = artifact(model);
        AtomicInteger hashes = new AtomicInteger();
        var store = store(hashes);
        Path home = temp.resolve("home");

        var recorded = store.recordVerified(
                model, "test-profile", expected, home, expected.sha256());
        var inspected = store.inspect(model, "test-profile", expected, home);

        assertEquals(LlamaCppProfileResolver.ArtifactState.EXACT, recorded.state());
        assertEquals(LlamaCppProfileResolver.ArtifactState.EXACT, inspected.state());
        assertEquals(0, hashes.get(), "the downloader's verified hash must not trigger a second GGUF read");
        assertFalse(recorded.verificationIdentity().isBlank());
    }

    private LlamaCppArtifactVerificationStore store(AtomicInteger hashes) {
        return new LlamaCppArtifactVerificationStore(path -> {
            hashes.incrementAndGet();
            return sha256(Files.readAllBytes(path));
        });
    }

    private LlamaCppModelCatalog.ModelArtifact artifact(Path model) throws Exception {
        return new LlamaCppModelCatalog.ModelArtifact(
                "example/repo",
                "a".repeat(40),
                model.getFileName().toString(),
                sha256(Files.readAllBytes(model)),
                Files.size(model),
                "Apache-2.0");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
