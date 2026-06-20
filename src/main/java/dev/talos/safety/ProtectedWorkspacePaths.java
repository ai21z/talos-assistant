package dev.talos.safety;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Direct workspace-path classifier for protected local paths. */
public final class ProtectedWorkspacePaths {
    private ProtectedWorkspacePaths() {}

    /** Index freshness version for protected workspace path classification.
     *  v3 (T759): equals-or-suffix word-run matching replaced substring
     *  matching — stale RAG indexes must rebuild their privacy partition.
     *  v4 (T788): the workspace .talos directory became a CONTROL segment
     *  (it holds verification-profile declarations and template commands) —
     *  stale indexes would misclassify .talos content in the privacy
     *  partition, so they must rebuild.
     *  v5 (T836): Windows trailing-dot/trailing-space aliases and reserved
     *  device names are canonicalized before protected-path classification. */
    public static final String POLICY_VERSION = "protected-content-policy-v5";

    public record Decision(
            String rawPath,
            String relativePath,
            boolean hasPath,
            boolean insideWorkspace,
            boolean workspaceEscape,
            boolean protectedPath,
            String protectedKind
    ) {
        public Decision {
            rawPath = rawPath == null ? "" : rawPath;
            relativePath = relativePath == null ? "" : relativePath;
            protectedKind = protectedKind == null ? "" : protectedKind;
        }

        public static Decision noPath() {
            return new Decision("", "", false, true, false, false, "");
        }
    }

    public static Decision classify(Path workspace, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return Decision.noPath();
        }
        if (workspace == null) {
            return new Decision(rawPath, "", true, false, true, false, "");
        }

        Path ws;
        Path resolved;
        String effectivePath = effectivePath(workspace, rawPath);
        try {
            ws = workspace.toAbsolutePath().normalize();
            Path candidate = Path.of(effectivePath);
            resolved = (candidate.isAbsolute() ? candidate : ws.resolve(candidate)).normalize();
        } catch (Exception e) {
            return new Decision(rawPath, "", true, false, true, false, "");
        }

        if (!startsWithWorkspace(resolved, ws)) {
            return new Decision(rawPath, "", true, false, true, false, "");
        }

        String relative = normalizeRelative(ws.relativize(resolved));
        String kind = ProtectedPathTokens.protectedKind(relative.toLowerCase(Locale.ROOT));
        return new Decision(rawPath, relative, true, true, false, !kind.isBlank(), kind);
    }

    public static boolean isProtectedPath(Path workspace, Path path) {
        if (workspace == null || path == null) return false;
        try {
            Path ws = workspace.toAbsolutePath().normalize();
            Path resolved = path.toAbsolutePath().normalize();
            if (!startsWithWorkspace(resolved, ws)) return false;
            String relative = normalizeRelative(ws.relativize(resolved));
            return !ProtectedPathTokens.protectedKind(relative.toLowerCase(Locale.ROOT)).isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String effectivePath(Path workspace, String rawPath) {
        String raw = rawPath == null ? "" : rawPath;
        if (workspace == null || raw.isBlank()) {
            return raw;
        }
        String trimmed = raw.strip();
        String canonical = canonicalizeWindowsAliasPath(trimmed);
        if ((trimmed.equals(raw) && canonical.equals(raw)) || trimmed.isBlank()) {
            return raw;
        }
        Path rawResolved = resolve(workspace, raw);
        Path trimmedResolved = resolve(workspace, trimmed);
        Path canonicalResolved = resolve(workspace, canonical);
        boolean rawExists = rawResolved != null && Files.exists(rawResolved);
        boolean trimmedExists = trimmedResolved != null && Files.exists(trimmedResolved);
        boolean canonicalExists = canonicalResolved != null && Files.exists(canonicalResolved);
        if (!rawExists && canonicalExists) {
            return canonical;
        }
        return !rawExists && trimmedExists ? trimmed : raw;
    }

    private static String canonicalizeWindowsAliasPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return rawPath == null ? "" : rawPath;
        String[] segments = rawPath.replace('\\', '/').split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            segments[i] = stripWindowsTrailingDotsAndSpaces(segments[i]);
        }
        return String.join("/", segments);
    }

    private static String stripWindowsTrailingDotsAndSpaces(String segment) {
        int end = segment == null ? 0 : segment.length();
        while (end > 0) {
            char c = segment.charAt(end - 1);
            if (c != '.' && c != ' ') break;
            end--;
        }
        return segment == null ? "" : segment.substring(0, end);
    }

    private static Path resolve(Path workspace, String value) {
        try {
            Path candidate = Path.of(value == null ? "" : value);
            if (candidate.isAbsolute()) {
                return candidate.normalize();
            }
            Path base = workspace == null ? Path.of("").toAbsolutePath().normalize() : workspace;
            return base.resolve(candidate).normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean startsWithWorkspace(Path resolved, Path workspace) {
        if (resolved.startsWith(workspace)) return true;
        String r = normalizeAbsolute(resolved);
        String w = normalizeAbsolute(workspace);
        return isWindows() && (r.equals(w) || r.startsWith(w.endsWith("/") ? w : w + "/"));
    }

    private static String normalizeAbsolute(Path path) {
        return path.toAbsolutePath().normalize().toString()
                .replace('\\', '/')
                .toLowerCase(Locale.ROOT);
    }

    private static String normalizeRelative(Path relative) {
        String s = relative.toString().replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        return s;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
