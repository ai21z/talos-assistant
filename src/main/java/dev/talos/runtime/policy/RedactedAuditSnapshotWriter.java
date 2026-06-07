package dev.talos.runtime.policy;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** Writes a canary-safe, content-redacted workspace snapshot for manual audit packets. */
public final class RedactedAuditSnapshotWriter {
    private static final long MAX_INCLUDED_TEXT_BYTES = 128_000L;
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".txt", ".md", ".markdown", ".json", ".jsonl", ".yaml", ".yml",
            ".toml", ".ini", ".properties", ".conf", ".config", ".xml",
            ".html", ".htm", ".css", ".js", ".mjs", ".cjs", ".ts", ".tsx",
            ".jsx", ".java", ".kt", ".gradle", ".kts", ".csv", ".tsv");

    private RedactedAuditSnapshotWriter() {}

    public record Options(Path workspace, Path output, String label) {
        public Options {
            if (workspace == null) throw new IllegalArgumentException("workspace is required");
            if (output == null) throw new IllegalArgumentException("output is required");
            label = label == null || label.isBlank() ? "snapshot" : label.strip();
        }
    }

    public record Summary(String label, Path output, int totalFiles, int safeTextFiles, int omittedFiles) {}

    private record FileEntry(
            String relativePath,
            String disposition,
            long bytes,
            String sanitizedContent
    ) {
        boolean included() {
            return sanitizedContent != null;
        }
    }

    public static Summary write(Options options) throws IOException {
        Path workspace = options.workspace().toRealPath();
        if (!Files.isDirectory(workspace)) {
            throw new IOException("workspace is not a directory: " + workspace);
        }
        Path output = options.output().toAbsolutePath().normalize();
        if (output.startsWith(workspace)) {
            throw new IOException("output directory must not be inside workspace");
        }
        if (Files.exists(output) && hasAnyEntry(output)) {
            throw new IOException("output directory already exists and is not empty: " + output);
        }

        Files.createDirectories(output);
        List<FileEntry> entries = collectEntries(workspace);
        writeSummary(options.label(), workspace, output, entries);
        writeTree(output, entries);
        writeContentDump(options.label(), output, entries);

        int included = (int) entries.stream().filter(FileEntry::included).count();
        int omitted = entries.size() - included;
        return new Summary(options.label(), output, entries.size(), included, omitted);
    }

    private static boolean hasAnyEntry(Path output) throws IOException {
        try (Stream<Path> stream = Files.list(output)) {
            return stream.findAny().isPresent();
        }
    }

    private static List<FileEntry> collectEntries(Path workspace) throws IOException {
        List<FileEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(workspace)) {
            for (Path path : stream
                    .filter(path -> !path.equals(workspace))
                    .sorted(Comparator.comparing(path -> relative(workspace, path)))
                    .toList()) {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                entries.add(classify(workspace, path));
            }
        }
        return List.copyOf(entries);
    }

    private static FileEntry classify(Path workspace, Path path) throws IOException {
        String relative = relative(workspace, path);
        if (Files.isSymbolicLink(path)) {
            return omitted(relative, "symlink", 0L);
        }
        Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(workspace)) {
            return omitted(relative, "workspace-escape", 0L);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return omitted(relative, "unsupported-file-type", 0L);
        }
        long bytes = Files.size(path);
        if (ProtectedContentPolicy.isProtectedPath(workspace, path)) {
            return omitted(relative, "protected", bytes);
        }
        if (bytes > MAX_INCLUDED_TEXT_BYTES) {
            return omitted(relative, "large-file", bytes);
        }
        if (!looksTextLike(path)) {
            return omitted(relative, "unsupported-or-binary", bytes);
        }
        String raw;
        try {
            raw = Files.readString(path, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            return omitted(relative, "unsupported-or-binary", bytes);
        }
        return new FileEntry(relative, "included:text", bytes, ProtectedContentPolicy.sanitizeText(raw));
    }

    private static FileEntry omitted(String relative, String reason, long bytes) {
        return new FileEntry(relative, "omitted:" + reason, bytes, null);
    }

    private static void writeSummary(String label, Path workspace, Path output, List<FileEntry> entries)
            throws IOException {
        long included = entries.stream().filter(FileEntry::included).count();
        long omitted = entries.size() - included;
        String summary = ""
                + "Redacted audit snapshot\n"
                + "label: " + ProtectedContentPolicy.sanitizeText(label) + "\n"
                + "workspaceName: " + ProtectedContentPolicy.sanitizeText(
                        workspace.getFileName() == null ? "" : workspace.getFileName().toString()) + "\n"
                + "totalFiles: " + entries.size() + "\n"
                + "safeTextFiles: " + included + "\n"
                + "omittedFiles: " + omitted + "\n";
        Files.writeString(output.resolve("summary.txt"), summary, StandardCharsets.UTF_8);
    }

    private static void writeTree(Path output, List<FileEntry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (FileEntry entry : entries) {
            sb.append(entry.relativePath())
                    .append(" [")
                    .append(displayDisposition(entry.disposition()))
                    .append("] bytes=")
                    .append(entry.bytes())
                    .append(System.lineSeparator());
        }
        Files.writeString(output.resolve("tree.txt"), sb.toString(), StandardCharsets.UTF_8);
    }

    private static String displayDisposition(String disposition) {
        if (disposition == null || disposition.isBlank()) return "unknown";
        return disposition.replace(":", ": ");
    }

    private static void writeContentDump(String label, Path output, List<FileEntry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Redacted Audit Snapshot Content").append(System.lineSeparator());
        sb.append("label: ").append(ProtectedContentPolicy.sanitizeText(label)).append(System.lineSeparator());
        for (FileEntry entry : entries) {
            if (!entry.included()) continue;
            sb.append(System.lineSeparator())
                    .append("--- file: ")
                    .append(entry.relativePath())
                    .append(" ---")
                    .append(System.lineSeparator())
                    .append(entry.sanitizedContent());
            if (!entry.sanitizedContent().endsWith("\n")) {
                sb.append(System.lineSeparator());
            }
        }
        Files.writeString(output.resolve("content-dump.txt"), sb.toString(), StandardCharsets.UTF_8);
    }

    private static boolean looksTextLike(Path path) {
        String name = path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : TEXT_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return name.equals("gradlew") || name.equals("license") || name.equals("readme");
    }

    private static String relative(Path workspace, Path path) {
        return workspace.relativize(path).toString().replace('\\', '/');
    }
}
