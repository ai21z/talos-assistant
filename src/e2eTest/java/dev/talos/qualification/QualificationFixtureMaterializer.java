package dev.talos.qualification;

import dev.talos.core.security.NoFollowPathPolicy;
import dev.talos.core.security.WorkspaceContainment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
        Path root = NoFollowPathPolicy.createUnlinkedDirectories(workspace);
        NoFollowPathPolicy.GuardedRoot workspaceFiles =
                NoFollowPathPolicy.bindExistingDirectory(root);
        if (workspaceFiles.walk(root).size() != 1) {
            throw new IllegalArgumentException("qualification workspace must be empty");
        }
        for (Map.Entry<String, String> entry : fixture.manifest().files().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            Path target = root.resolve(entry.getKey()).normalize();
            if (!WorkspaceContainment.contains(root, target) || target.equals(root)) {
                throw new IllegalArgumentException("fixture path escapes workspace: " + entry.getKey());
            }
            Path parent = target.getParent();
            if (parent != null) workspaceFiles.createDirectories(parent);
            QualificationArtifactPublication.publishNewOrVerifyExact(
                    workspaceFiles,
                    target,
                    entry.getValue().getBytes(StandardCharsets.UTF_8),
                    "qualification fixture");
        }
        Snapshot snapshot = snapshot(root);
        if (snapshot.files().size() != fixture.manifest().files().size()) {
            throw new IllegalStateException("fixture materialization file-count mismatch");
        }
        return snapshot;
    }

    static Snapshot snapshot(Path workspace) throws IOException {
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        NoFollowPathPolicy.GuardedRoot workspaceFiles =
                NoFollowPathPolicy.bindExistingDirectory(workspace);
        Path root = workspaceFiles.path();
        LinkedHashMap<String, Long> sizes = new LinkedHashMap<>();
        LinkedHashMap<String, String> hashes = new LinkedHashMap<>();
        List<QualificationScenarioBank.NamedBytes> aggregate = new ArrayList<>();
        for (NoFollowPathPolicy.TreeEntry item : workspaceFiles.walk(root)) {
            Path path = item.path();
            if (path.equals(root)
                    || item.type() != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                continue;
            }
            if (!WorkspaceContainment.contains(root, path)) {
                throw new IllegalArgumentException("workspace snapshot path escapes root: " + path);
            }
            String relative = root.relativize(path).toString().replace('\\', '/');
            byte[] bytes = workspaceFiles.readAllBytes(path);
            sizes.put(relative, (long) bytes.length);
            hashes.put(relative, QualificationScenarioBank.sha256(bytes));
            aggregate.add(new QualificationScenarioBank.NamedBytes(relative, bytes));
        }
        String aggregateHash = aggregate.isEmpty()
                ? QualificationScenarioBank.sha256(new byte[0])
                : QualificationScenarioBank.hash(aggregate);
        return new Snapshot(sizes, hashes, aggregateHash);
    }
}
