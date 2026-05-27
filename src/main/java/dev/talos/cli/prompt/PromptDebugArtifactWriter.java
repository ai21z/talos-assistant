package dev.talos.cli.prompt;

import dev.talos.spi.types.PromptDebugSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Writes redacted prompt-debug artifacts while preserving the CLI command output contract. */
public final class PromptDebugArtifactWriter {
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private PromptDebugArtifactWriter() {}

    public static LatestArtifact writeLatest(Path directory, PromptDebugSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Path dir = prepareDirectory(directory);

        String ts = FILE_TS.format(LocalDateTime.now());
        Path render = dir.resolve("prompt-debug-" + ts + ".md");
        Files.writeString(render, PromptDebugInspector.format(snapshot), StandardCharsets.UTF_8);

        Path providerBody = null;
        if (!snapshot.providerBodyJson().isBlank()) {
            providerBody = dir.resolve("prompt-debug-" + ts + ".provider-body.json");
            Files.writeString(providerBody, PromptDebugInspector.redactedProviderBodyJson(snapshot),
                    StandardCharsets.UTF_8);
        }
        return new LatestArtifact(render, Optional.ofNullable(providerBody));
    }

    public static HistoryArtifact writeHistory(Path directory, List<PromptDebugSnapshot> snapshots)
            throws IOException {
        Objects.requireNonNull(snapshots, "snapshots");
        Path dir = prepareDirectory(directory);

        String ts = FILE_TS.format(LocalDateTime.now());
        List<CaptureArtifact> captures = new ArrayList<>();
        List<String> indexLines = new ArrayList<>();
        for (int i = 0; i < snapshots.size(); i++) {
            PromptDebugSnapshot snapshot = snapshots.get(i);
            String prefix = "prompt-debug-" + ts + "-" + String.format("%02d", i + 1);
            Path render = dir.resolve(prefix + ".md");
            Files.writeString(render, PromptDebugInspector.format(snapshot), StandardCharsets.UTF_8);
            indexLines.add((i + 1) + ". " + render.toAbsolutePath().normalize());

            Path providerBody = null;
            if (!snapshot.providerBodyJson().isBlank()) {
                providerBody = dir.resolve(prefix + ".provider-body.json");
                Files.writeString(providerBody, PromptDebugInspector.redactedProviderBodyJson(snapshot),
                        StandardCharsets.UTF_8);
                indexLines.add("   provider: " + providerBody.toAbsolutePath().normalize());
            }
            captures.add(new CaptureArtifact(render, Optional.ofNullable(providerBody)));
        }

        Path index = dir.resolve("prompt-debug-" + ts + "-index.md");
        Files.writeString(index,
                "# Talos Prompt Debug History\n\n" + String.join("\n", indexLines) + "\n",
                StandardCharsets.UTF_8);
        return new HistoryArtifact(captures, index);
    }

    private static Path prepareDirectory(Path directory) throws IOException {
        Path dir = Objects.requireNonNull(directory, "directory");
        Files.createDirectories(dir);
        return dir;
    }

    public record LatestArtifact(Path renderPath, Optional<Path> providerBodyPath) {
        public LatestArtifact {
            Objects.requireNonNull(renderPath, "renderPath");
            providerBodyPath = providerBodyPath == null ? Optional.empty() : providerBodyPath;
        }
    }

    public record CaptureArtifact(Path renderPath, Optional<Path> providerBodyPath) {
        public CaptureArtifact {
            Objects.requireNonNull(renderPath, "renderPath");
            providerBodyPath = providerBodyPath == null ? Optional.empty() : providerBodyPath;
        }
    }

    public record HistoryArtifact(List<CaptureArtifact> captures, Path indexPath) {
        public HistoryArtifact {
            captures = List.copyOf(Objects.requireNonNull(captures, "captures"));
            Objects.requireNonNull(indexPath, "indexPath");
        }
    }
}
