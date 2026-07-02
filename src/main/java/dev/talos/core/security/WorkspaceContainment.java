package dev.talos.core.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Workspace-bound containment primitive for security-sensitive path checks.
 */
public final class WorkspaceContainment {
    private WorkspaceContainment() {}

    public static boolean contains(Path workspace, Path candidate) {
        if (workspace == null || candidate == null) return false;
        try {
            Path canonicalWorkspace = canonicalizeExisting(workspace);
            Path canonicalCandidate = canonicalizeThroughExistingParent(candidate);
            return startsWithBoundary(canonicalCandidate, canonicalWorkspace);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static boolean samePath(Path first, Path second) {
        if (first == null || second == null) return false;
        try {
            Path canonicalFirst = canonicalizeThroughExistingParent(first);
            Path canonicalSecond = canonicalizeThroughExistingParent(second);
            return sameNormalizedPath(canonicalFirst, canonicalSecond);
        } catch (IOException | RuntimeException e) {
            return sameNormalizedPath(
                    first.toAbsolutePath().normalize(),
                    second.toAbsolutePath().normalize());
        }
    }

    private static Path canonicalizeExisting(Path path) throws IOException {
        return path.toAbsolutePath().normalize().toRealPath();
    }

    private static Path canonicalizeThroughExistingParent(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        List<Path> missingSegments = new ArrayList<>();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path fileName = existing.getFileName();
            if (fileName != null) {
                missingSegments.add(0, fileName);
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("unable to resolve containing parent for " + path);
        }
        Path canonical = existing.toRealPath();
        for (Path segment : missingSegments) {
            canonical = canonical.resolve(segment.toString()).normalize();
        }
        return canonical;
    }

    private static boolean startsWithBoundary(Path candidate, Path workspace) {
        if (candidate.startsWith(workspace)) return true;
        if (!isWindows()) return false;
        String c = normalizeAbsolute(candidate);
        String w = normalizeAbsolute(workspace);
        return c.equals(w) || c.startsWith(w.endsWith("/") ? w : w + "/");
    }

    private static boolean sameNormalizedPath(Path first, Path second) {
        if (first.equals(second)) return true;
        return isWindows() && normalizeAbsolute(first).equals(normalizeAbsolute(second));
    }

    private static String normalizeAbsolute(Path path) {
        return path.toAbsolutePath().normalize().toString()
                .replace('\\', '/')
                .toLowerCase(Locale.ROOT);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
