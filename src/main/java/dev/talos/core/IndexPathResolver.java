package dev.talos.core;

import dev.talos.core.util.Hash;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Centralized utility for resolving index directory paths.
 * Recommended by technical analysis to reduce duplication.
 */
public final class IndexPathResolver {
    private IndexPathResolver() {} // utility class

    /**
     * Get the index directory for a given workspace.
     * Uses SHA-1 hash of absolute workspace path for isolation.
     */
    public static Path getIndexDirectory(Path workspace) {
        Path talosHome = Paths.get(System.getProperty("user.home"), ".talos");
        return getIndexDirectory(talosHome, workspace);
    }

    /**
     * Same layout under an explicit Talos home — lets diagnostics probe the
     * index location without touching the real {@code ~/.talos} in tests.
     */
    public static Path getIndexDirectory(Path talosHome, Path workspace) {
        Path absWorkspace = workspace.toAbsolutePath().normalize();
        String hash = Hash.sha1Hex(absWorkspace.toString());
        return talosHome.resolve("indices").resolve(hash);
    }
}
