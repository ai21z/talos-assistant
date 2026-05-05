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
import java.nio.file.StandardCopyOption;
import java.util.Map;

public final class CopyPathTool implements TalosTool {
    private static final String NAME = "talos.copy_path";

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Copy a file or directory to another workspace path. Directories require recursive=true.";
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(NAME, description(),
                """
                {"type":"object","properties":{
                  "from":{"type":"string","description":"Relative source file or directory path"},
                  "to":{"type":"string","description":"Relative destination path"},
                  "recursive":{"type":"boolean","description":"Set true to copy directories recursively"},
                  "overwrite":{"type":"boolean","description":"Set true to replace an existing destination"}
                },"required":["from","to"]}""",
                ToolRiskLevel.WRITE,
                ToolOperationMetadata.workspaceMutation(
                        NAME,
                        CapabilityKind.ORGANIZE,
                        ToolRiskLevel.WRITE,
                        Map.of(
                                "from", ToolOperationMetadata.PathRole.SOURCE_PATH,
                                "to", ToolOperationMetadata.PathRole.DESTINATION_PATH),
                        true,
                        true,
                        "PATH_COPIED",
                        "PATH_COPIED"));
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        if (ctx == null) return WorkspaceOperationToolSupport.contextRequired(NAME);
        String from = WorkspaceOperationToolSupport.param(call, "from", "source", "source_path", "src", "path");
        String to = WorkspaceOperationToolSupport.param(call, "to", "destination", "destination_path", "dest", "target");
        if (from == null || from.isBlank()) {
            return ToolResult.fail(ToolError.invalidParams("Missing required parameter: from"));
        }
        if (to == null || to.isBlank()) {
            return ToolResult.fail(ToolError.invalidParams("Missing required parameter: to"));
        }
        WorkspaceOperationToolSupport.ResolvedPath source =
                WorkspaceOperationToolSupport.resolveAllowed(ctx, from);
        if (!source.valid()) return ToolResult.fail(ToolError.invalidParams(source.error()));
        WorkspaceOperationToolSupport.ResolvedPath destination =
                WorkspaceOperationToolSupport.resolveAllowed(ctx, to);
        if (!destination.valid()) return ToolResult.fail(ToolError.invalidParams(destination.error()));
        if (!Files.exists(source.path())) {
            return ToolResult.fail(ToolError.notFound("Source not found: " + from));
        }
        boolean overwrite = WorkspaceOperationToolSupport.boolParam(call, "overwrite", false);
        boolean recursive = WorkspaceOperationToolSupport.boolParam(call, "recursive", false);
        if (Files.exists(destination.path()) && !overwrite) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Destination already exists: " + to + ". Set overwrite=true to replace it."));
        }
        if (Files.isDirectory(source.path()) && !recursive) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Source is a directory; set recursive=true to copy directories."));
        }
        ToolResult parentResult = WorkspaceOperationToolSupport.createParentDirectories(ctx, destination.path());
        if (parentResult != null) return parentResult;
        try {
            if (Files.isDirectory(source.path())) {
                if (Files.exists(destination.path()) && overwrite && !Files.isDirectory(destination.path())) {
                    Files.deleteIfExists(destination.path());
                }
                WorkspaceOperationToolSupport.copyDirectory(source.path(), destination.path(), overwrite);
            } else if (overwrite) {
                Files.copy(source.path(), destination.path(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.copy(source.path(), destination.path());
            }
            return ToolResult.ok("Copied " + from + " -> " + to);
        } catch (IOException e) {
            return ToolResult.fail(ToolError.internal("Failed to copy path: " + e.getMessage()));
        }
    }
}
