package dev.talos.tools.impl;

import dev.talos.core.capability.CapabilityKind;
import dev.talos.core.security.NoFollowPathPolicy;
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
import java.util.List;
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
        boolean overwrite = WorkspaceOperationToolSupport.boolParam(call, "overwrite", false);
        boolean recursive = WorkspaceOperationToolSupport.boolParam(call, "recursive", false);
        try {
            NoFollowPathPolicy.GuardedRoot workspace =
                    NoFollowPathPolicy.bindExistingDirectory(ctx.workspace());
            NoFollowPathPolicy.EntryType sourceType = workspace.inspect(source.path());
            if (sourceType == NoFollowPathPolicy.EntryType.MISSING) {
                return ToolResult.fail(ToolError.notFound("Source not found: " + from));
            }
            NoFollowPathPolicy.EntryType destinationType = workspace.inspect(destination.path());
            if (destinationType != NoFollowPathPolicy.EntryType.MISSING && !overwrite) {
                return ToolResult.fail(ToolError.invalidParams(
                        "Destination already exists: " + to + ". Set overwrite=true to replace it."));
            }
            if (sourceType == NoFollowPathPolicy.EntryType.DIRECTORY && !recursive) {
                return ToolResult.fail(ToolError.invalidParams(
                        "Source is a directory; set recursive=true to copy directories."));
            }

            List<NoFollowPathPolicy.TreeEntry> sourceTree =
                    sourceType == NoFollowPathPolicy.EntryType.DIRECTORY
                            ? workspace.walk(source.path())
                            : List.of(new NoFollowPathPolicy.TreeEntry(
                                    workspace.requireRegularFile(source.path()),
                                    NoFollowPathPolicy.EntryType.REGULAR_FILE));
            if (destinationType == NoFollowPathPolicy.EntryType.DIRECTORY) {
                workspace.walk(destination.path());
            }

            Path parent = destination.path().getParent();
            if (parent != null) workspace.createDirectories(parent);
            if (sourceType == NoFollowPathPolicy.EntryType.DIRECTORY) {
                if (destinationType == NoFollowPathPolicy.EntryType.REGULAR_FILE && overwrite) {
                    Files.deleteIfExists(destination.path());
                }
                WorkspaceOperationToolSupport.copyDirectory(
                        workspace, source.path(), destination.path(), overwrite, sourceTree);
            } else {
                WorkspaceOperationToolSupport.copyRegularFile(
                        workspace, source.path(), destination.path(), overwrite);
            }
            return ToolResult.ok("Copied " + from + " -> " + to);
        } catch (IOException e) {
            return ToolResult.fail(ToolError.internal("Failed to copy path: " + e.getMessage()));
        }
    }
}
