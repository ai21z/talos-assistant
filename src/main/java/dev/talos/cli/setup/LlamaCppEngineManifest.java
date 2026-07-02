package dev.talos.cli.setup;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

public final class LlamaCppEngineManifest {
    private LlamaCppEngineManifest() {}

    private static final Entry UBUNTU_X64_CPU = new Entry(
            "ubuntu-x64-cpu",
            "Linux",
            "Ubuntu",
            "x64",
            "cpu",
            "b9860",
            "llama-b9860-bin-ubuntu-x64.tar.gz",
            URI.create("https://github.com/ggml-org/llama.cpp/releases/download/b9860/llama-b9860-bin-ubuntu-x64.tar.gz"),
            "b68e8072eb88d1cc8b8e9d6ea8237aae87b34c6d8bbffda958c870e4dc949714",
            15_851_454L,
            Path.of(".talos", "engines", "llama.cpp", "b9860", "ubuntu-x64-cpu"),
            "llama-server");

    public static Optional<Entry> select(SetupWizardSnapshot snapshot) {
        if (snapshot == null) {
            return Optional.empty();
        }
        if (!contains(snapshot.osName(), "linux")) {
            return Optional.empty();
        }
        if (!isX64(snapshot.osArch())) {
            return Optional.empty();
        }
        if (!contains(snapshot.distroName(), "ubuntu")) {
            return Optional.empty();
        }
        return Optional.of(UBUNTU_X64_CPU);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static boolean isX64(String arch) {
        String normalized = arch == null ? "" : arch.toLowerCase(Locale.ROOT);
        return normalized.equals("amd64") || normalized.equals("x86_64") || normalized.equals("x64");
    }

    public record Entry(
            String variant,
            String os,
            String distroFamily,
            String arch,
            String backend,
            String upstreamTag,
            String assetName,
            URI url,
            String sha256,
            long sizeBytes,
            Path installSubdir,
            String executableName) {

        public Path installDir(Path home) {
            Path base = home == null ? Path.of(System.getProperty("user.home", ".")) : home;
            return base.resolve(installSubdir).toAbsolutePath().normalize();
        }
    }
}
