package dev.talos.cli.setup;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class LlamaCppModelManifest {
    private static final long GIB = 1024L * 1024L * 1024L;

    private static final List<Entry> ACCEPTED_BETA = List.of(
            new Entry(
                    "qwen2.5-coder-14b",
                    "Qwen/Qwen2.5-Coder-14B-Instruct-GGUF",
                    "qwen2.5-coder-14b-instruct-q4_k_m.gguf",
                    "c1e659736d89ac1065fb495330fb824d94001974a4bfa78e7270e43476a8d940",
                    8_988_110_272L,
                    "accepted beta",
                    "16 GB RAM minimum; 24 GB+ for CPU-only; large CPU model, not the low-resource lane"),
            new Entry(
                    "gpt-oss-20b",
                    "ggml-org/gpt-oss-20b-GGUF",
                    "gpt-oss-20b-mxfp4.gguf",
                    "be37a636aca0fc1aae0d32325f82f6b4d21495f06823b5fbc1898ae0303e9935",
                    12_109_566_560L,
                    "accepted beta",
                    "24 GB RAM minimum; 32 GB+ for CPU-only; large CPU model, not the low-resource lane"));

    private static final Map<String, Entry> BY_ALIAS = ACCEPTED_BETA.stream()
            .collect(Collectors.toUnmodifiableMap(
                    entry -> normalize(entry.alias()),
                    Function.identity()));

    private LlamaCppModelManifest() {}

    public static List<Entry> acceptedBeta() {
        return ACCEPTED_BETA;
    }

    public static Optional<Entry> byAlias(String alias) {
        return Optional.ofNullable(BY_ALIAS.get(normalize(alias)));
    }

    private static String normalize(String alias) {
        return alias == null ? "" : alias.trim().toLowerCase(Locale.ROOT);
    }

    public record Entry(
            String alias,
            String hfRepo,
            String hfFile,
            String sha256,
            long sizeBytes,
            String supportLevel,
            String ramGuidance) {

        public URI url() {
            return URI.create("https://huggingface.co/" + hfRepo + "/resolve/main/" + hfFile);
        }

        public Path modelPath(Path userHome) {
            Path base = userHome == null ? Path.of(System.getProperty("user.home", ".")) : userHome;
            return base.resolve(Path.of(".talos", "models", "gguf", alias, hfFile))
                    .toAbsolutePath()
                    .normalize();
        }

        public long requiredFreeDiskBytes() {
            return sizeBytes + GIB;
        }

        public String sizeGiB() {
            return gib(sizeBytes);
        }

        public String requiredFreeDiskGiB() {
            return gib(requiredFreeDiskBytes());
        }

        public String guidanceLine() {
            return "download: ~" + sizeGiB() + " GiB; disk needed: ~"
                    + requiredFreeDiskGiB() + " GiB free; RAM guidance: "
                    + ramGuidance + "; CPU-only";
        }

        private static String gib(long bytes) {
            return String.format(Locale.ROOT, "%.2f", bytes / (double) GIB);
        }
    }
}
