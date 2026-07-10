package dev.talos.qualification;

import dev.talos.core.security.WorkspaceContainment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class QualificationFixtureMaterializer {
    private QualificationFixtureMaterializer() {}

    record Snapshot(Map<String, Long> files, Map<String, String> sha256, String aggregateSha256) {
        Snapshot {
            files = Map.copyOf(files);
            sha256 = Map.copyOf(sha256);
        }
    }

    static Snapshot materialize(
            QualificationScenarioBank.Catalog catalog,
            String fixtureName,
            Path workspace) throws IOException {
        if (catalog == null) throw new IllegalArgumentException("catalog is required");
        QualificationScenarioBank.FixtureResource fixture = catalog.fixtures().get(fixtureName);
        if (fixture == null) throw new IllegalArgumentException("unknown frozen fixture: " + fixtureName);
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        Path root = workspace.toAbsolutePath().normalize();
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) {
                throw new IllegalArgumentException("workspace must be a normal directory: " + root);
            }
            try (var existing = Files.list(root)) {
                if (existing.findAny().isPresent()) {
                    throw new IllegalArgumentException("qualification workspace must be empty: " + root);
                }
            }
        } else {
            Files.createDirectories(root);
        }
        for (Map.Entry<String, String> entry : fixture.manifest().files().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            Path target = root.resolve(entry.getKey()).normalize();
            if (!WorkspaceContainment.contains(root, target) || target.equals(root)) {
                throw new IllegalArgumentException("fixture path escapes workspace: " + entry.getKey());
            }
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
        }
        Snapshot snapshot = snapshot(root);
        if (snapshot.files().size() != fixture.manifest().files().size()) {
            throw new IllegalStateException("fixture materialization file-count mismatch");
        }
        return snapshot;
    }

    static Snapshot snapshot(Path workspace) throws IOException {
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        Path root = workspace.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("workspace is not a normal directory: " + root);
        }
        LinkedHashMap<String, Long> sizes = new LinkedHashMap<>();
        LinkedHashMap<String, String> hashes = new LinkedHashMap<>();
        List<QualificationScenarioBank.NamedBytes> aggregate = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(root)) continue;
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalArgumentException("qualification workspace contains a symbolic link: " + path);
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!WorkspaceContainment.contains(root, path)) {
                    throw new IllegalArgumentException("workspace snapshot path escapes root: " + path);
                }
                String relative = root.relativize(path).toString().replace('\\', '/');
                byte[] bytes = Files.readAllBytes(path);
                sizes.put(relative, (long) bytes.length);
                hashes.put(relative, QualificationScenarioBank.sha256(bytes));
                aggregate.add(new QualificationScenarioBank.NamedBytes(relative, bytes));
            }
        }
        String aggregateHash = aggregate.isEmpty()
                ? QualificationScenarioBank.sha256(new byte[0])
                : QualificationScenarioBank.hash(aggregate);
        return new Snapshot(sizes, hashes, aggregateHash);
    }
}
