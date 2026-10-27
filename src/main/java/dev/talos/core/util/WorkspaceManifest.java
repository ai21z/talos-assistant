package dev.talos.core.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Builds a lightweight workspace manifest for system prompt injection.
 *
 * <p>Provides the model with immediate workspace awareness on session start,
 * without requiring a full index. The manifest is the depth-limited file
 * tree only: paths are bounded metadata, while file CONTENT (including the
 * README) is workspace data and must never ride in a system-role prompt
 * (T1011) - the model reads files on demand through the read tool instead.
 *
 * <p>Total output is capped at ~2000 chars to avoid consuming too much
 * of the context window.
 */
public final class WorkspaceManifest {

    private WorkspaceManifest() {}

    /** Directories to skip during tree walk. */
    private static final Set<String> SKIP = Set.of(
            ".git", ".svn", ".hg", ".idea", ".vscode", ".talos", ".loqj",
            "node_modules", "__pycache__", ".gradle", "build", "dist",
            "target", ".next", ".nuxt", "out", "coverage", ".cache"
    );

    /** Max depth for the file tree. */
    private static final int MAX_DEPTH = 3;

    /** Max entries in the tree listing. */
    private static final int MAX_ENTRIES = 80;

    /** Max total chars for the entire manifest. */
    private static final int MANIFEST_MAX_CHARS = 2000;

    /**
     * Build a workspace manifest string for system prompt injection.
     *
     * @param workspace the workspace root path
     * @return a compact manifest string, or empty string if workspace is invalid
     */
    public static String build(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) return "";

        var sb = new StringBuilder();
        sb.append("Workspace: ").append(workspace.toAbsolutePath().toString().replace('\\', '/'));

        // File tree
        String tree = buildTree(workspace);
        if (!tree.isEmpty()) {
            sb.append("\n\nFile structure:\n").append(tree);
        }

        // Hard cap
        if (sb.length() > MANIFEST_MAX_CHARS) {
            return sb.substring(0, MANIFEST_MAX_CHARS) + "\n...";
        }
        return sb.toString();
    }

    /** Build a compact file tree listing. */
    static String buildTree(Path root) {
        List<Path> collected = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            walk.filter(p -> !p.equals(root))
                .filter(p -> !isSkipped(root, p))
                .sorted()
                .limit(MAX_ENTRIES + 1L)
                .forEach(collected::add);
        } catch (IOException e) {
            return "";
        }

        boolean truncated = collected.size() > MAX_ENTRIES;
        var sb = new StringBuilder();
        int limit = Math.min(collected.size(), MAX_ENTRIES);
        for (int i = 0; i < limit; i++) {
            Path p = collected.get(i);
            String rel = root.relativize(p).toString().replace('\\', '/');
            if (Files.isDirectory(p)) {
                sb.append("  ").append(rel).append("/\n");
            } else {
                sb.append("  ").append(rel).append('\n');
            }
        }
        if (truncated) {
            sb.append("  ... (truncated)\n");
        }
        return sb.toString();
    }

    /** Check if a path should be skipped (noise directory or hidden). */
    private static boolean isSkipped(Path root, Path p) {
        // Check each path segment for skip directories
        Path rel = root.relativize(p);
        for (int i = 0; i < rel.getNameCount(); i++) {
            String segment = rel.getName(i).toString();
            if (SKIP.contains(segment)) return true;
            // Skip hidden dirs/files (starting with .) except known useful ones
            if (segment.startsWith(".") && !segment.equals(".github")) {
                return true;
            }
        }
        return false;
    }

}


