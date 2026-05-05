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

public final class MovePathTool implements TalosTool {
    private static final String NAME = "talos.move_path";

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Move a file or directory to another workspace path. Requires overwrite=true when the destination exists.";
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(NAME, description(),
                """
                {"type":"object","properties":{
                  "from":{"type":"string","description":"Relative source file or directory path"},
                  "to":{"type":"string","description":"Relative destination path"},
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
                        "PATH_MOVED",
                        "PATH_MOVED"));
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
        if (Files.exists(destination.path()) && !overwrite) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Destination already exists: " + to + ". Set overwrite=true to replace it."));
        }
        ToolResult parentResult = WorkspaceOperationToolSupport.createParentDirectories(ctx, destination.path());
        if (parentResult != null) return parentResult;
        try {
            if (overwrite) {
                Files.move(source.path(), destination.path(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source.path(), destination.path());
            }
            return ToolResult.ok("Moved " + from + " -> " + to);
        } catch (IOException e) {
            return ToolResult.fail(ToolError.internal("Failed to move path: " + e.getMessage()));
        }
    }
}
