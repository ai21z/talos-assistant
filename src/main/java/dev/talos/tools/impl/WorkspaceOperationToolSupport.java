package dev.talos.tools.impl;

import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class WorkspaceOperationToolSupport {
    private WorkspaceOperationToolSupport() {}

    static String param(ToolCall call, String canonical, String... aliases) {
        if (call == null) return null;
        String value = call.param(canonical);
        if (value != null) return value;
        for (String alias : aliases) {
            value = call.param(alias);
            if (value != null) return value;
        }
        return null;
    }

    static boolean boolParam(ToolCall call, String key, boolean defaultValue) {
        String value = call == null ? null : call.param(key);
        if (value == null || value.isBlank()) return defaultValue;
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "yes", "y", "1", "on" -> true;
            case "false", "no", "n", "0", "off" -> false;
            default -> defaultValue;
        };
    }

    static ToolResult contextRequired(String toolName) {
        return ToolResult.fail(ToolError.internal(toolName + " requires a ToolContext"));
    }

    static ResolvedPath resolveAllowed(ToolContext ctx, String displayPath) {
        if (displayPath == null || displayPath.isBlank()) {
            return ResolvedPath.invalid("Missing required path parameter");
        }
        Path resolved;
        try {
            resolved = ctx.resolve(displayPath);
        } catch (Exception e) {
            return ResolvedPath.invalid("Invalid path: " + displayPath);
        }
        if (!ctx.sandbox().allowedPath(resolved)) {
            return ResolvedPath.invalid("Path not allowed: " + ctx.sandbox().explain(resolved));
        }
        return new ResolvedPath(displayPath, resolved, "");
    }

    static ToolResult createParentDirectories(ToolContext ctx, Path target) {
        Path parent = target.getParent();
        if (parent == null || Files.exists(parent)) return null;
        if (!ctx.sandbox().allowedPath(parent)) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Parent directory not allowed: " + ctx.sandbox().explain(parent)));
        }
        try {
            Files.createDirectories(parent);
            return null;
        } catch (IOException e) {
            return ToolResult.fail(ToolError.internal("Failed to create parent directories: " + e.getMessage()));
        }
    }

    static void copyDirectory(Path source, Path destination, boolean overwrite) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path current : stream.sorted().toList()) {
                Path relative = source.relativize(current);
                Path target = destination.resolve(relative).normalize();
                if (Files.isDirectory(current)) {
                    Files.createDirectories(target);
                } else {
                    if (overwrite) {
                        Files.copy(current, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.copy(current, target);
                    }
                }
            }
        }
    }

    record ResolvedPath(String displayPath, Path path, String error) {
        static ResolvedPath invalid(String error) {
            return new ResolvedPath("", null, error == null ? "Invalid path" : error);
        }

        boolean valid() {
            return path != null && error.isBlank();
        }
    }
}
