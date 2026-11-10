package dev.talos.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonicalizes accidental leading/trailing whitespace in model-supplied path
 * arguments without doing fuzzy filename correction.
 */
public final class PathArgumentCanonicalizer {
    private PathArgumentCanonicalizer() {}

    public record Resolution(String rawPath, String effectivePath, Path resolvedPath, boolean normalized) {
        public Resolution {
            rawPath = rawPath == null ? "" : rawPath;
            effectivePath = effectivePath == null ? "" : effectivePath;
        }
    }

    public record PathParameterChange(String key, String rawPath, String normalizedPath) {
        public PathParameterChange {
            key = key == null ? "" : key;
            rawPath = rawPath == null ? "" : rawPath;
            normalizedPath = normalizedPath == null ? "" : normalizedPath;
        }
    }

    public record ToolCallNormalization(ToolCall call, List<PathParameterChange> changes) {
        public ToolCallNormalization {
            changes = changes == null ? List.of() : List.copyOf(changes);
        }

        public boolean changed() {
            return !changes.isEmpty();
        }
    }

    public static Resolution canonicalizeExistingPathWhitespace(Path workspace, String rawPath) {
        String raw = rawPath == null ? "" : rawPath;
        Path rawResolved = resolve(workspace, raw);
        if (workspace == null || raw.isBlank()) {
            return new Resolution(raw, raw, rawResolved, false);
        }

        String trimmed = raw.strip();
        if (trimmed.equals(raw) || trimmed.isBlank()) {
            return new Resolution(raw, raw, rawResolved, false);
        }

        Path trimmedResolved = resolve(workspace, trimmed);
        // Rounds 7 and 8: the file system is probed for a model-supplied path only inside the workspace or its real
        // path, and only through link-free resolution, so no host or volume that the path or a link on it names is
        // contacted. A padded path whose trimmed form lies outside is trimmed from its text alone, so the sandbox
        // refuses the trimmed path from its text, as it refused it before round 7.
        dev.talos.core.security.LinkFreePath.Roots roots = dev.talos.core.security.LinkFreePath.Roots.of(workspace);
        if (exists(rawResolved, roots)) return new Resolution(raw, raw, rawResolved, false);
        if (trimmedResolved != null && !roots.admits(trimmedResolved)) {
            return new Resolution(raw, trimmed, trimmedResolved, true);
        }
        if (exists(trimmedResolved, roots)) {
            return new Resolution(raw, trimmed, trimmedResolved, true);
        }
        return new Resolution(raw, raw, rawResolved, false);
    }

    /**
     * True when a path inside the workspace or its real path exists, found without following a symbolic link. A
     * junction is followed as the sandbox follows it, because its target can be only a local volume.
     */
    private static boolean exists(Path resolved, dev.talos.core.security.LinkFreePath.Roots roots) {
        if (resolved == null || !roots.admits(resolved)) return false;
        var walk = dev.talos.core.security.LinkFreePath.resolve(resolved, roots);
        try {
            if (walk.resolved()) return Files.exists(walk.path(), java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return walk.status() == dev.talos.core.security.LinkFreePath.Status.REPARSE_POINT
                    && Files.exists(resolved);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    public static ToolCallNormalization canonicalizeToolCall(
            Path workspace,
            ToolCall call,
            List<String> pathKeys
    ) {
        if (call == null || call.parameters().isEmpty() || pathKeys == null || pathKeys.isEmpty()) {
            return new ToolCallNormalization(call, List.of());
        }
        Map<String, String> updated = new LinkedHashMap<>(call.parameters());
        List<PathParameterChange> changes = new ArrayList<>();
        for (String key : pathKeys) {
            if (key == null || key.isBlank() || !updated.containsKey(key)) continue;
            String value = updated.get(key);
            if (value == null || value.isBlank()) continue;
            Resolution resolution = canonicalizeExistingPathWhitespace(workspace, value);
            if (!resolution.normalized()) continue;
            updated.put(key, resolution.effectivePath());
            changes.add(new PathParameterChange(key, value, resolution.effectivePath()));
        }
        if (changes.isEmpty()) {
            return new ToolCallNormalization(call, List.of());
        }
        return new ToolCallNormalization(new ToolCall(call.toolName(), updated), changes);
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
}
