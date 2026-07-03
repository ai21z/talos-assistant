package dev.talos.cli.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppModelDownloaderTest {

    @TempDir Path tempDir;

    @Test
    void reusesExistingTargetWhenSha256Matches() throws Exception {
        byte[] model = "model".getBytes(StandardCharsets.UTF_8);
        var entry = entry("agent", "agent.gguf", sha256(model));
        Path target = entry.modelPath(tempDir);
        Files.createDirectories(target.getParent());
        Files.write(target, model);
        RecordingDownloader downloader = new RecordingDownloader(model);

        var result = new LlamaCppModelDownloader(downloader).download(entry, tempDir);

        assertEquals(LlamaCppModelDownloader.Status.REUSED, result.status());
        assertEquals(target, result.modelPath());
        assertEquals(0, downloader.calls);
    }

    @Test
    void downloadsToPartFileAndPromotesOnlyAfterChecksumPasses() throws Exception {
        byte[] model = "verified-model".getBytes(StandardCharsets.UTF_8);
        var entry = entry("agent", "agent.gguf", sha256(model));
        Path target = entry.modelPath(tempDir);

        var result = new LlamaCppModelDownloader(new RecordingDownloader(model)).download(entry, tempDir);

        assertEquals(LlamaCppModelDownloader.Status.DOWNLOADED, result.status());
        assertEquals(target, result.modelPath());
        assertEquals("verified-model", Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(Files.exists(part(target)), "verified download must not leave a .part file");
    }

    @Test
    void deletesPartialAndLeavesFinalAbsentOnChecksumMismatch() throws Exception {
        var entry = entry("agent", "agent.gguf", sha256("expected".getBytes(StandardCharsets.UTF_8)));
        Path target = entry.modelPath(tempDir);

        var result = new LlamaCppModelDownloader(new RecordingDownloader("actual".getBytes(StandardCharsets.UTF_8)))
                .download(entry, tempDir);

        assertEquals(LlamaCppModelDownloader.Status.FAILED, result.status());
        assertTrue(result.message().contains("SHA-256 mismatch"), result.message());
        assertFalse(Files.exists(target), "failed checksum must not leave a final model file");
        assertFalse(Files.exists(part(target)), "failed checksum must not leave a .part file");
    }

    @Test
    void failsClosedOnDownloadException() throws Exception {
        var entry = entry("agent", "agent.gguf", sha256("expected".getBytes(StandardCharsets.UTF_8)));
        Path target = entry.modelPath(tempDir);

        var result = new LlamaCppModelDownloader((uri, path) -> {
            throw new IOException("network down");
        }).download(entry, tempDir);

        assertEquals(LlamaCppModelDownloader.Status.FAILED, result.status());
        assertTrue(result.message().contains("network down"), result.message());
        assertFalse(Files.exists(target), "download failure must not leave a final model file");
        assertFalse(Files.exists(part(target)), "download failure must not leave a .part file");
    }

    @Test
    void existingWrongChecksumIsReportedWithoutOverwrite() throws Exception {
        var entry = entry("agent", "agent.gguf", sha256("expected".getBytes(StandardCharsets.UTF_8)));
        Path target = entry.modelPath(tempDir);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "wrong", StandardCharsets.UTF_8);
        RecordingDownloader downloader = new RecordingDownloader("expected".getBytes(StandardCharsets.UTF_8));

        var result = new LlamaCppModelDownloader(downloader).download(entry, tempDir);

        assertEquals(LlamaCppModelDownloader.Status.EXISTING_MISMATCH, result.status());
        assertEquals(0, downloader.calls);
        assertEquals("wrong", Files.readString(target, StandardCharsets.UTF_8));
        assertTrue(result.message().contains("Existing model file does not match"), result.message());
    }

    private static LlamaCppModelManifest.Entry entry(String alias, String file, String sha256) {
        return new LlamaCppModelManifest.Entry(
                alias,
                "owner/repo",
                file,
                sha256,
                123L,
                "accepted beta",
                "16 GB RAM minimum; 24 GB+ comfortable for CPU-only");
    }

    private static Path part(Path target) {
        return target.resolveSibling(target.getFileName() + ".part");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class RecordingDownloader implements LlamaCppModelDownloader.Downloader {
        private final byte[] payload;
        int calls;

        RecordingDownloader(byte[] payload) {
            this.payload = payload;
        }

        @Override
        public void download(URI uri, Path target) throws Exception {
            calls++;
            Files.createDirectories(target.getParent());
            Files.write(target, payload);
        }
    }
}
