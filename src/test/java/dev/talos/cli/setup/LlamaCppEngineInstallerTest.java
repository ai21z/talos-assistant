package dev.talos.cli.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppEngineInstallerTest {

    @TempDir Path tempDir;

    @Test
    void reusesExistingExecutableWithoutDownload() throws Exception {
        var entry = manifest("reuse", "not-used");
        Path server = entry.installDir(tempDir).resolve("bin").resolve("llama-server");
        Files.createDirectories(server.getParent());
        Files.writeString(server, "existing", StandardCharsets.UTF_8);

        var downloader = new FakeDownloader("ignored");
        var result = installer(downloader, extractingServer()).install(entry, tempDir);

        assertEquals(LlamaCppEngineInstaller.Status.REUSED, result.status());
        assertEquals(server.toAbsolutePath().normalize(), result.serverPath());
        assertEquals(0, downloader.calls);
    }

    @Test
    void checksumMismatchFailsClosedAndDoesNotPromoteInstallDir() throws Exception {
        var entry = manifest("mismatch", "0000000000000000000000000000000000000000000000000000000000000000");
        var result = installer(new FakeDownloader("wrong bytes"), extractingServer()).install(entry, tempDir);

        assertEquals(LlamaCppEngineInstaller.Status.FAILED, result.status());
        assertFalse(Files.exists(entry.installDir(tempDir)), "bad checksum must not promote install dir");
        assertTrue(result.message().contains("SHA-256 mismatch"), result.message());
    }

    @Test
    void successfulInstallPromotesExtractedServerPath() throws Exception {
        byte[] archiveBytes = "archive bytes".getBytes(StandardCharsets.UTF_8);
        String sha = LlamaCppEngineInstaller.sha256Hex(archiveBytes);
        var entry = manifest("success", sha);

        var result = installer(new FakeDownloader(archiveBytes), extractingServer()).install(entry, tempDir);

        assertEquals(LlamaCppEngineInstaller.Status.INSTALLED, result.status());
        assertTrue(Files.isRegularFile(result.serverPath()), "installed llama-server must exist");
        assertTrue(result.serverPath().toString().contains("llama-server"));
    }

    @Test
    void successfulInstallCopiesStagingWhenDirectoryMovesFail() throws Exception {
        byte[] archiveBytes = "archive bytes".getBytes(StandardCharsets.UTF_8);
        String sha = LlamaCppEngineInstaller.sha256Hex(archiveBytes);
        var entry = manifest("copy-fallback", sha);
        var mover = new FailingDirectoryMover();

        var result = new LlamaCppEngineInstaller(
                new FakeDownloader(archiveBytes),
                extractingServer(),
                mover).install(entry, tempDir);

        assertEquals(LlamaCppEngineInstaller.Status.INSTALLED, result.status());
        assertTrue(Files.isRegularFile(result.serverPath()), "copy fallback must install llama-server");
        assertTrue(mover.calls >= 2, "installer must try atomic and plain move before copy fallback");
        assertFalse(Files.exists(mover.lastSource), "staging source should be removed after copy fallback");
    }

    @Test
    void httpDownloaderFollowsReleaseAssetRedirects() throws Exception {
        byte[] archiveBytes = "archive bytes".getBytes(StandardCharsets.UTF_8);
        String sha = LlamaCppEngineInstaller.sha256Hex(archiveBytes);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/download", exchange -> {
            exchange.getResponseHeaders().add("Location", "/asset");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/asset", exchange -> {
            exchange.sendResponseHeaders(200, archiveBytes.length);
            exchange.getResponseBody().write(archiveBytes);
            exchange.close();
        });
        server.start();
        try {
            URI redirect = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/download");
            var entry = manifest("redirect", sha, redirect);

            var result = new LlamaCppEngineInstaller(
                    new LlamaCppEngineInstaller.HttpDownloader(),
                    extractingServer()).install(entry, tempDir);

            assertEquals(LlamaCppEngineInstaller.Status.INSTALLED, result.status());
            assertTrue(Files.isRegularFile(result.serverPath()), "redirected download must install llama-server");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingExecutableAfterExtractionFailsClosed() throws Exception {
        byte[] archiveBytes = "archive bytes".getBytes(StandardCharsets.UTF_8);
        String sha = LlamaCppEngineInstaller.sha256Hex(archiveBytes);
        var entry = manifest("missing-server", sha);

        var result = installer(new FakeDownloader(archiveBytes), (archive, destination) -> {
            Files.createDirectories(destination.resolve("bin"));
            Files.writeString(destination.resolve("bin").resolve("llama-cli"), "not server", StandardCharsets.UTF_8);
        }).install(entry, tempDir);

        assertEquals(LlamaCppEngineInstaller.Status.FAILED, result.status());
        assertFalse(Files.exists(entry.installDir(tempDir)), "missing server must not promote install dir");
        assertTrue(result.message().contains("llama-server"), result.message());
    }

    private LlamaCppEngineInstaller installer(
            LlamaCppEngineInstaller.Downloader downloader,
            LlamaCppEngineInstaller.Extractor extractor) {
        return new LlamaCppEngineInstaller(downloader, extractor);
    }

    private static LlamaCppEngineInstaller.Extractor extractingServer() {
        return (archive, destination) -> {
            Path server = destination.resolve("bin").resolve("llama-server");
            Files.createDirectories(server.getParent());
            Files.writeString(server, "server", StandardCharsets.UTF_8);
            server.toFile().setExecutable(true, false);
        };
    }

    private LlamaCppEngineManifest.Entry manifest(String variant, String sha) {
        return manifest(variant, sha, URI.create("https://example.invalid/llama.tar.gz"));
    }

    private LlamaCppEngineManifest.Entry manifest(String variant, String sha, URI uri) {
        return new LlamaCppEngineManifest.Entry(
                variant,
                "Linux",
                "Ubuntu",
                "x64",
                "cpu",
                "b9860",
                "llama-b9860-bin-ubuntu-x64.tar.gz",
                uri,
                sha,
                12L,
                Path.of(".talos", "engines", "llama.cpp", "b9860", variant),
                "llama-server");
    }

    private static final class FakeDownloader implements LlamaCppEngineInstaller.Downloader {
        private final byte[] bytes;
        int calls;

        FakeDownloader(String text) {
            this(text.getBytes(StandardCharsets.UTF_8));
        }

        FakeDownloader(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public void download(URI uri, Path target) throws Exception {
            calls++;
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        }
    }

    private static final class FailingDirectoryMover implements LlamaCppEngineInstaller.DirectoryMover {
        int calls;
        Path lastSource;

        @Override
        public void move(Path source, Path target, StandardCopyOption... options) throws IOException {
            calls++;
            lastSource = source;
            throw new IOException("simulated cross-device directory move");
        }
    }
}
