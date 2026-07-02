package dev.talos.tools.impl;

import dev.talos.core.capability.CapabilityKind;
import dev.talos.core.security.WorkspaceContainment;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolOperationMetadata;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

public final class DeletePathTool implements TalosTool {
    private static final String NAME = "talos.delete_path";

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Delete a file or directory inside the workspace. Directories require recursive=true.";
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(NAME, description(),
                """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Relative file or directory path to delete"},
                  "recursive":{"type":"boolean","description":"Set true to delete directories recursively"}
                },"required":["path"]}""",
                ToolRiskLevel.DESTRUCTIVE,
                ToolOperationMetadata.workspaceMutation(
                        NAME,
                        CapabilityKind.DELETE,
                        ToolRiskLevel.DESTRUCTIVE,
                        Map.of("path", ToolOperationMetadata.PathRole.TARGET_PATH),
                        true,
                        true,
                        "PATH_DELETED",
                        "PATH_ABSENT"));
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        if (ctx == null) return WorkspaceOperationToolSupport.contextRequired(NAME);
        String pathParam = WorkspaceOperationToolSupport.param(call, "path", "target", "file", "filename");
        if (pathParam == null || pathParam.isBlank()) {
            return ToolResult.fail(ToolError.invalidParams("Missing required parameter: path"));
        }
        WorkspaceOperationToolSupport.ResolvedPath target =
                WorkspaceOperationToolSupport.resolveAllowed(ctx, pathParam);
        if (!target.valid()) return ToolResult.fail(ToolError.invalidParams(target.error()));

        ToolResult rootGuard = rejectWorkspaceRoot(ctx, target.path());
        if (rootGuard != null) return rootGuard;

        if (!Files.exists(target.path(), LinkOption.NOFOLLOW_LINKS)) {
            return ToolResult.fail(ToolError.notFound("Path not found: " + pathParam));
        }

        boolean recursive = WorkspaceOperationToolSupport.boolParam(call, "recursive", false);
        try {
            if (Files.isDirectory(target.path(), LinkOption.NOFOLLOW_LINKS)) {
                if (!recursive) {
                    return ToolResult.fail(ToolError.invalidParams(
                            "Target is a directory; set recursive=true to delete directories."));
                }
                deleteDirectory(target.path());
            } else {
                Files.deleteIfExists(target.path());
            }
            return ToolResult.ok("Deleted " + pathParam);
        } catch (IOException e) {
            return ToolResult.fail(ToolError.internal("Failed to delete path: " + e.getMessage()));
        }
    }

    private static ToolResult rejectWorkspaceRoot(ToolContext ctx, Path target) {
        Path root = ctx.workspace().toAbsolutePath().normalize();
        Path resolved = target.toAbsolutePath().normalize();
        if (!WorkspaceContainment.contains(root, resolved)) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Path not allowed: target is outside the workspace."));
        }
        if (WorkspaceContainment.samePath(root, resolved)) {
            return ToolResult.fail(ToolError.invalidParams("Refusing to delete the workspace root."));
        }
        return null;
    }

    private static void deleteDirectory(Path target) throws IOException {
        try (var walk = Files.walk(target)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
