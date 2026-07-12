package dev.talos.cli.setup;

import dev.talos.engine.llamacpp.LlamaCppModelCatalog;

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

    private static final List<Entry> ACCEPTED_BETA = LlamaCppModelCatalog.selectableProfiles().stream()
            .filter(LlamaCppModelCatalog.Profile::wizardDownload)
            .map(Entry::from)
            .toList();

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
            String revision,
            String hfFile,
            String sha256,
            long sizeBytes,
            String supportLevel,
            String ramGuidance) {

        public URI url() {
            return URI.create("https://huggingface.co/" + hfRepo + "/resolve/" + revision + "/" + hfFile);
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

        private static Entry from(LlamaCppModelCatalog.Profile profile) {
            return new Entry(
                    profile.alias(),
                    profile.repository(),
                    profile.revision(),
                    profile.artifactFile(),
                    profile.artifactSha256(),
                    profile.artifactSizeBytes(),
                    profile.disposition().label(),
                    profile.ramGuidance());
        }
    }
}
