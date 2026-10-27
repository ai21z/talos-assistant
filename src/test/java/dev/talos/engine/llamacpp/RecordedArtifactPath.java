package dev.talos.engine.llamacpp;

import java.nio.file.Path;

/**
 * The model path text that the artifact verification store records, for fake
 * artifact identities. The store records the real path and the profile service
 * compares it with the real configured path, so a fake that returns the
 * configured spelling makes the resolver refuse the profile whenever that
 * spelling is an alias, such as an 8.3 temporary folder.
 */
final class RecordedArtifactPath {
    private RecordedArtifactPath() {}

    static String of(Path path) {
        if (path == null) return "";
        try {
            return path.toRealPath().toString();
        } catch (Exception unresolved) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    static String of(String path) {
        return of(Path.of(path));
    }
}
