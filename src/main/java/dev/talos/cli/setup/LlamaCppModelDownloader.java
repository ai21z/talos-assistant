package dev.talos.cli.setup;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

public final class LlamaCppModelDownloader {
    private final Downloader downloader;

    public LlamaCppModelDownloader() {
        this(new HttpDownloader());
    }

    public LlamaCppModelDownloader(Downloader downloader) {
        this.downloader = Objects.requireNonNull(downloader, "downloader");
    }

    public Result download(LlamaCppModelManifest.Entry entry, Path userHome) {
        Objects.requireNonNull(entry, "entry is required");
        Path target = entry.modelPath(userHome);
        Path partial = partialPath(target);
        try {
            if (Files.exists(target)) {
                if (!Files.isRegularFile(target)) {
                    return new Result(Status.FAILED, target, "Existing model path is not a file: " + target);
                }
                String actual = sha256Hex(target);
                if (actual.equalsIgnoreCase(entry.sha256())) {
                    return new Result(Status.REUSED, target, "Reusing existing model file at " + target);
                }
                return new Result(Status.EXISTING_MISMATCH, target,
                        "Existing model file does not match pinned SHA-256 at " + target
                                + ": expected " + entry.sha256() + " but got " + actual);
            }

            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(partial);
            downloader.download(entry.url(), partial);
            String actual = sha256Hex(partial);
            if (!actual.equalsIgnoreCase(entry.sha256())) {
                Files.deleteIfExists(partial);
                return new Result(Status.FAILED, target,
                        "SHA-256 mismatch for " + entry.hfFile() + ": expected "
                                + entry.sha256() + " but got " + actual);
            }
            promote(partial, target);
            return new Result(Status.DOWNLOADED, target, "Downloaded verified model to " + target);
        } catch (Exception error) {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException ignored) {
                // Best-effort cleanup only; the failed result below is the durable signal.
            }
            return new Result(Status.FAILED, target, safeMessage(error));
        }
    }

    private static Path partialPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".part");
    }

    private static void promote(Path partial, Path target) throws IOException {
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(partial, target);
        }
    }

    static String sha256Hex(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message.replace('\n', ' ');
    }

    public interface Downloader {
        void download(URI uri, Path target) throws Exception;
    }

    public enum Status {
        DOWNLOADED,
        REUSED,
        EXISTING_MISMATCH,
        FAILED
    }

    public record Result(Status status, Path modelPath, String message) {}

    static final class HttpDownloader implements Downloader {
        @Override
        public void download(URI uri, Path target) throws Exception {
            Files.createDirectories(target.getParent());
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("User-Agent", "Talos setup wizard")
                    .GET()
                    .build();
            HttpResponse<Path> response = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("download failed with HTTP " + response.statusCode());
            }
        }
    }
}
