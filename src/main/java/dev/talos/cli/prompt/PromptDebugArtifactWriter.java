package dev.talos.cli.prompt;

import dev.talos.spi.types.PromptDebugSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Writes redacted prompt-debug artifacts while preserving the CLI command output contract. */
public final class PromptDebugArtifactWriter {
    private PromptDebugArtifactWriter() {}

    public static LatestArtifact writeLatest(Path directory, PromptDebugSnapshot snapshot) throws IOException {
        return writeLatest(directory, snapshot, PromptDebugArtifactWriter::writeNewFile);
    }

    static LatestArtifact writeLatest(Path directory, PromptDebugSnapshot snapshot, ArtifactWrite writer)
            throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        HistoryArtifact group = writeGroup(directory, List.of(snapshot), writer);
        CaptureArtifact capture = group.captures().getFirst();
        return new LatestArtifact(capture.renderPath(), capture.providerBodyPath(), group.indexPath());
    }

    public static HistoryArtifact writeHistory(Path directory, List<PromptDebugSnapshot> snapshots)
            throws IOException {
        return writeHistory(directory, snapshots, PromptDebugArtifactWriter::writeNewFile);
    }

    static HistoryArtifact writeHistory(Path directory, List<PromptDebugSnapshot> snapshots, ArtifactWrite writer)
            throws IOException {
        return writeGroup(directory, snapshots, writer);
    }

    private static HistoryArtifact writeGroup(Path directory, List<PromptDebugSnapshot> snapshots,
                                               ArtifactWrite writer) throws IOException {
        List<PromptDebugSnapshot> selected = List.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
        Objects.requireNonNull(writer, "writer");
        Path dir = prepareGroup(directory);
        List<CaptureArtifact> captures = new ArrayList<>();
        List<String> indexLines = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            PromptDebugSnapshot snapshot = selected.get(i);
            String prefix = "capture-" + (i + 1);
            Path render = dir.resolve(prefix + ".md");
            writer.write(render, PromptDebugInspector.format(snapshot));
            indexLines.add((i + 1) + ". render: " + render.getFileName());

            Path providerBody = null;
            if (!snapshot.providerBodyJson().isBlank()) {
                providerBody = dir.resolve(prefix + ".provider-body.json");
                writer.write(providerBody, PromptDebugInspector.redactedProviderBodyJson(snapshot));
                indexLines.add("   provider: " + providerBody.getFileName());
            } else {
                indexLines.add("   provider: absent");
            }
            captures.add(new CaptureArtifact(render, Optional.ofNullable(providerBody)));
        }

        // Listed members must finish writing before the index is exposed. On failure, retain
        // this partial group and all prior groups; this is not crash-atomic multi-file storage.
        Path index = dir.resolve("index.md");
        writer.write(index, "# Talos Prompt Debug Export\n\n"
                + "Capture count: " + captures.size() + "\n"
                + "Member paths are relative to this index. Provider bodies are redacted.\n\n"
                + String.join("\n", indexLines) + "\n");
        return new HistoryArtifact(captures, index);
    }

    private static Path prepareGroup(Path directory) throws IOException {
        Path dir = Objects.requireNonNull(directory, "directory");
        Files.createDirectories(dir);
        // The directory is a persistent export, despite the JDK allocator's name. Allocation
        // is atomic/no-clobber and does not depend on a timestamp or shared process counter.
        return Files.createTempDirectory(dir, "prompt-debug-");
    }

    static void writeNewFile(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    @FunctionalInterface
    interface ArtifactWrite {
        void write(Path path, String content) throws IOException;
    }

    public record LatestArtifact(Path renderPath, Optional<Path> providerBodyPath, Path indexPath) {
        public LatestArtifact(Path renderPath, Optional<Path> providerBodyPath) {
            this(renderPath, providerBodyPath,
                    Objects.requireNonNull(renderPath, "renderPath").resolveSibling("index.md"));
        }

        public LatestArtifact {
            Objects.requireNonNull(renderPath, "renderPath");
            providerBodyPath = Objects.requireNonNullElse(providerBodyPath, Optional.empty());
            Objects.requireNonNull(indexPath, "indexPath");
        }
    }

    public record CaptureArtifact(Path renderPath, Optional<Path> providerBodyPath) {
        public CaptureArtifact {
            Objects.requireNonNull(renderPath, "renderPath");
            providerBodyPath = Objects.requireNonNullElse(providerBodyPath, Optional.empty());
        }
    }

    public record HistoryArtifact(List<CaptureArtifact> captures, Path indexPath) {
        public HistoryArtifact {
            captures = List.copyOf(Objects.requireNonNull(captures, "captures"));
            Objects.requireNonNull(indexPath, "indexPath");
        }
    }
}
