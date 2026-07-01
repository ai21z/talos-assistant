package dev.talos.tools.impl;

import dev.talos.core.capability.CapabilityKind;
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
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

public final class RenamePathTool implements TalosTool {
    private static final String NAME = "talos.rename_path";

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Rename a file or directory within its current parent directory.";
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(NAME, description(),
                """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Relative file or directory path to rename"},
                  "new_name":{"type":"string","description":"New filename or directory name only; no path separators"},
                  "overwrite":{"type":"boolean","description":"Set true to replace an existing sibling path"}
                },"required":["path","new_name"]}""",
                ToolRiskLevel.WRITE,
                ToolOperationMetadata.workspaceMutation(
                        NAME,
                        CapabilityKind.ORGANIZE,
                        ToolRiskLevel.WRITE,
                        Map.of("path", ToolOperationMetadata.PathRole.SOURCE_PATH),
                        true,
                        true,
                        "PATH_RENAMED",
                        "PATH_RENAMED"));
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        if (ctx == null) return WorkspaceOperationToolSupport.contextRequired(NAME);
        String pathParam = WorkspaceOperationToolSupport.param(call, "path", "from", "source", "source_path");
        String newName = WorkspaceOperationToolSupport.param(call, "new_name", "newName", "name", "to_name");
        if (pathParam == null || pathParam.isBlank()) {
            return ToolResult.fail(ToolError.invalidParams("Missing required parameter: path"));
        }
        String validation = validateNewName(newName);
        if (!validation.isBlank()) {
            return ToolResult.fail(ToolError.invalidParams(validation));
        }
        WorkspaceOperationToolSupport.ResolvedPath source =
                WorkspaceOperationToolSupport.resolveAllowed(ctx, pathParam);
        if (!source.valid()) return ToolResult.fail(ToolError.invalidParams(source.error()));
        if (!Files.exists(source.path())) {
            return ToolResult.fail(ToolError.notFound("Source not found: " + pathParam));
        }
        Path parent = source.path().getParent();
        if (parent == null) {
            return ToolResult.fail(ToolError.invalidParams("Cannot rename path without a parent: " + pathParam));
        }
        Path destination = parent.resolve(newName).normalize();
        if (!ctx.sandbox().allowedPath(destination)) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Path not allowed: " + ctx.sandbox().explain(destination)));
        }
        boolean overwrite = WorkspaceOperationToolSupport.boolParam(call, "overwrite", false);
        if (Files.exists(destination) && !overwrite) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Destination already exists: " + newName + ". Set overwrite=true to replace it."));
        }
        try {
            if (overwrite) {
                Files.move(source.path(), destination, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source.path(), destination);
            }
            String displayDestination = displaySiblingPath(pathParam, newName);
            return ToolResult.ok("Renamed " + pathParam + " -> " + displayDestination);
        } catch (IOException e) {
            return ToolResult.fail(ToolError.internal("Failed to rename path: " + e.getMessage()));
        }
    }

    private static String validateNewName(String newName) {
        if (newName == null || newName.isBlank()) {
            return "Missing required parameter: new_name";
        }
        String value = newName.strip();
        if (".".equals(value)
                || "..".equals(value)
                || value.contains("/")
                || value.contains("\\")) {
            return "new_name must be a single path segment";
        }
        try {
            if (Path.of(value).isAbsolute()) {
                return "new_name must be a single path segment";
            }
        } catch (Exception e) {
            return "new_name must be a single path segment";
        }
        return "";
    }

    private static String displaySiblingPath(String oldPath, String newName) {
        String normalized = oldPath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash < 0) return newName;
        return normalized.substring(0, slash + 1) + newName;
    }
}
