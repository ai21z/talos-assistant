package dev.talos.runtime.toolcall;

import dev.talos.runtime.capability.StaticWebCapabilityProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class StaticRepairReadbackContext {
    private static final long MAX_READBACK_BYTES = 64 * 1024L;

    private StaticRepairReadbackContext() {}

    static Optional<String> render(LoopState state, List<String> remainingRepairTargets) {
        if (state == null || remainingRepairTargets == null || remainingRepairTargets.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder out = new StringBuilder();
        for (String target : remainingRepairTargets) {
            String normalized = ToolCallSupport.normalizePath(target);
            if (normalized.isBlank() || !StaticWebCapabilityProfile.isSmallWebFile(normalized)) continue;
            String body = currentReadbackForPath(state, normalized);
            if (body.isBlank()) continue;
            if (out.isEmpty()) {
                out.append("[StaticRepairReadbacks]\n")
                        .append("Use these current file contents while rewriting the static-web repair targets. ")
                        .append("Line-number prefixes are display-only; do not copy them into files.\n");
            }
            out.append("Path: ").append(normalized).append('\n')
                    .append(body.strip())
                    .append("\n---\n");
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(out.toString().strip());
    }

    private static String currentReadbackForPath(LoopState state, String normalizedPath) {
        String cached = successfulReadbackForPath(state, normalizedPath);
        if (!cached.isBlank()) return cached;
        return workspaceFileReadbackForPath(state, normalizedPath);
    }

    private static String successfulReadbackForPath(LoopState state, String normalizedPath) {
        if (state == null || normalizedPath == null || normalizedPath.isBlank()) return "";
        String keyNeedle = "path=" + normalizedPath.toLowerCase(Locale.ROOT) + ";";
        for (var entry : state.successfulReadCallBodies.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            if (key.contains(keyNeedle)) {
                return entry.getValue() == null ? "" : entry.getValue();
            }
        }
        return "";
    }

    private static String workspaceFileReadbackForPath(LoopState state, String normalizedPath) {
        if (state == null
                || state.workspace == null
                || normalizedPath == null
                || normalizedPath.isBlank()) {
            return "";
        }
        try {
            Path root = state.workspace.toAbsolutePath().normalize();
            Path resolved = root.resolve(normalizedPath).toAbsolutePath().normalize();
            if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) return "";
            if (Files.size(resolved) > MAX_READBACK_BYTES) return "";
            return Files.readString(resolved);
        } catch (Exception ignored) {
            return "";
        }
    }
}
