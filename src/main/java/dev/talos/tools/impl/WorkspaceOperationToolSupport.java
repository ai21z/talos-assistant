package dev.talos.tools.impl;

import dev.talos.core.security.NoFollowPathPolicy;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
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

    static ToolResult requireExistingOrdinaryParent(
            ToolContext ctx,
            Path target
    ) {
        Path parent = target.getParent();
        if (parent == null) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Destination requires an existing ordinary parent directory"));
        }
        try {
            NoFollowPathPolicy.bindExistingDirectory(ctx.workspace())
                    .requireDirectory(parent);
            return null;
        } catch (IOException e) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Destination requires an existing ordinary parent directory"));
        }
    }

    static String firstLine(String value) {
        if (value == null || value.isBlank()) return "";
        int newline = value.indexOf('\n');
        return newline < 0 ? value.strip() : value.substring(0, newline).strip();
    }

    static void copyDirectory(
            NoFollowPathPolicy.GuardedRoot workspace,
            Path source,
            Path destination,
            boolean overwrite,
            List<NoFollowPathPolicy.TreeEntry> sourceTree
    ) throws IOException {
        for (NoFollowPathPolicy.TreeEntry entry : sourceTree) {
            Path relative = source.relativize(entry.path());
            Path target = destination.resolve(relative).normalize();
            if (entry.type() == NoFollowPathPolicy.EntryType.DIRECTORY) {
                workspace.createDirectories(target);
            } else {
                Path parent = target.getParent();
                if (parent != null) workspace.createDirectories(parent);
                copyRegularFile(workspace, entry.path(), target, overwrite);
            }
        }
    }

    static void copyRegularFile(
            NoFollowPathPolicy.GuardedRoot workspace,
            Path source,
            Path destination,
            boolean overwrite
    ) throws IOException {
        Path safeSource = workspace.requireRegularFile(source);
        workspace.requireRegularFileTarget(destination);
        if (workspace.inspect(destination) == NoFollowPathPolicy.EntryType.REGULAR_FILE
                && Files.isSameFile(safeSource, destination)) {
            return;
        }
        safeSource = workspace.requireRegularFile(safeSource);
        workspace.requireRegularFileTarget(destination);
        if (overwrite) {
            Files.copy(safeSource, destination, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.copy(safeSource, destination);
        }
        workspace.requireRegularFile(destination);
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
