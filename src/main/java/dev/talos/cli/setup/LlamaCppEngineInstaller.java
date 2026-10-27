package dev.talos.cli.setup;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;

public final class LlamaCppEngineInstaller {
    private final Downloader downloader;
    private final Extractor extractor;
    private final DirectoryMover mover;

    public LlamaCppEngineInstaller() {
        this(new HttpDownloader(), new SystemTarExtractor());
    }

    public LlamaCppEngineInstaller(Downloader downloader, Extractor extractor) {
        this(downloader, extractor, (source, target, options) -> Files.move(source, target, options));
    }

    LlamaCppEngineInstaller(Downloader downloader, Extractor extractor, DirectoryMover mover) {
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.mover = Objects.requireNonNull(mover, "mover");
    }

    public Result install(LlamaCppEngineManifest.Entry entry, Path talosHome) {
        Path installDir = entry.installDir(talosHome);
        try {
            Path existing = findExecutable(installDir, entry.executableName());
            if (existing != null) {
                return new Result(Status.REUSED, existing, "Reusing existing llama.cpp engine at " + existing);
            }

            Path tmpRoot = Files.createTempDirectory("talos-llamacpp-install-");
            Path archive = tmpRoot.resolve(entry.assetName());
            Path staging = tmpRoot.resolve("staging");
            try {
                downloader.download(entry.url(), archive);
                String actual = sha256Hex(archive);
                if (!actual.equalsIgnoreCase(entry.sha256())) {
                    return new Result(Status.FAILED, null,
                            "SHA-256 mismatch for " + entry.assetName() + ": expected "
                                    + entry.sha256() + " but got " + actual);
                }

                Files.createDirectories(staging);
                extractor.extract(archive, staging);
                Path server = findExecutable(staging, entry.executableName());
                if (server == null) {
                    return new Result(Status.FAILED, null,
                            "Extracted archive did not contain " + entry.executableName());
                }

                LlamaCppEngineManifest.CompanionAsset companion = entry.companion();
                if (companion != null) {
                    // The driver runtime is a separate upstream archive. It is
                    // verified with its own digest and extracted into the
                    // directory containing the server executable, all before
                    // promotion, so a failure here leaves no partial install.
                    Path companionArchive = tmpRoot.resolve(companion.assetName());
                    downloader.download(companion.url(), companionArchive);
                    String companionActual = sha256Hex(companionArchive);
                    if (!companionActual.equalsIgnoreCase(companion.sha256())) {
                        return new Result(Status.FAILED, null,
                                "SHA-256 mismatch for " + companion.assetName() + ": expected "
                                        + companion.sha256() + " but got " + companionActual);
                    }
                    extractor.extract(companionArchive, server.getParent());
                }

                deleteRecursivelyIfExists(installDir);
                Files.createDirectories(installDir.getParent());
                promoteDirectory(staging, installDir);
                Path installedServer = findExecutable(installDir, entry.executableName());
                if (installedServer == null) {
                    return new Result(Status.FAILED, null,
                            "Installed engine did not contain " + entry.executableName());
                }
                return new Result(Status.INSTALLED, installedServer,
                        "Installed pinned llama.cpp engine at " + installDir);
            } finally {
                deleteRecursivelyIfExists(tmpRoot);
            }
        } catch (Exception error) {
            return new Result(Status.FAILED, null, safeMessage(error));
        }
    }

    static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
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

    /**
     * Read-only recursive search for a lane executable under an install
     * root. Single owner of the installed-layout knowledge: Windows zips
     * extract the exe at the top level, the Ubuntu tar nests it (build/bin),
     * and both the installer's reuse check and {@code talos tune}'s
     * installed-lane detection must recognize the same layouts.
     */
    public static java.util.Optional<Path> locateInstalledExecutable(Path root, String name) {
        if (root == null || name == null || name.isBlank() || !Files.isDirectory(root)) {
            return java.util.Optional.empty();
        }
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(path -> path.getFileName() != null && path.getFileName().toString().equals(name))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .map(path -> path.toAbsolutePath().normalize());
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    private static Path findExecutable(Path root, String name) {
        return locateInstalledExecutable(root, name)
                .map(path -> {
                    path.toFile().setExecutable(true, false);
                    return path;
                })
                .orElse(null);
    }

    private void promoteDirectory(Path source, Path target) throws IOException {
        try {
            mover.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (IOException ignored) {
            // Cross-filesystem directory moves can fail even after checksum and extraction succeed.
        }
        try {
            mover.move(source, target);
            return;
        } catch (IOException ignored) {
            // Fall through to recursive copy for source/developer Linux installs.
        }

        try {
            copyRecursively(source, target);
            deleteRecursivelyIfExists(source);
        } catch (IOException error) {
            deleteRecursivelyIfExists(target);
            throw error;
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative.toString()).normalize();
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else {
                    Path parent = destination.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(
                            path,
                            destination,
                            LinkOption.NOFOLLOW_LINKS,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursivelyIfExists(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
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

    public interface Extractor {
        void extract(Path archive, Path destination) throws Exception;
    }

    public interface DirectoryMover {
        void move(Path source, Path target, StandardCopyOption... options) throws IOException;
    }

    public enum Status {
        INSTALLED,
        REUSED,
        FAILED
    }

    public record Result(Status status, Path serverPath, String message) {}

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

    private static final class SystemTarExtractor implements Extractor {
        @Override
        public void extract(Path archive, Path destination) throws Exception {
            Process process = new ProcessBuilder(
                    "tar",
                    "-xzf",
                    archive.toAbsolutePath().normalize().toString(),
                    "-C",
                    destination.toAbsolutePath().normalize().toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("tar extraction failed with exit " + exit + ": " + output.strip());
            }
        }
    }
}
