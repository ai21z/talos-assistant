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
import java.util.Map;

public final class MakeDirectoryTool implements TalosTool {
    private static final String NAME = "talos.mkdir";

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Create a directory in the workspace, including missing parent directories.";
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(NAME, description(),
                """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Relative directory path to create"}
                },"required":["path"]}""",
                ToolRiskLevel.WRITE,
                ToolOperationMetadata.workspaceMutation(
                        NAME,
                        CapabilityKind.CREATE,
                        ToolRiskLevel.WRITE,
                        Map.of("path", ToolOperationMetadata.PathRole.TARGET_DIRECTORY),
                        false,
                        true,
                        "DIRECTORY_CREATED",
                        "DIRECTORY_EXISTS"));
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        if (ctx == null) return WorkspaceOperationToolSupport.contextRequired(NAME);
        String pathParam = WorkspaceOperationToolSupport.param(call, "path", "dir", "directory");
        if (pathParam == null || pathParam.isBlank()) {
            return ToolResult.fail(ToolError.invalidParams("Missing required parameter: path"));
        }
        WorkspaceOperationToolSupport.ResolvedPath target =
                WorkspaceOperationToolSupport.resolveAllowed(ctx, pathParam);
        if (!target.valid()) {
            return ToolResult.fail(ToolError.invalidParams(target.error()));
        }
        if (Files.isRegularFile(target.path())) {
            return ToolResult.fail(ToolError.invalidParams("Cannot create directory because a file already exists: "
                    + pathParam));
        }
        try {
            Files.createDirectories(target.path());
            return ToolResult.ok("Created directory " + pathParam);
        } catch (IOException e) {
            return ToolResult.fail(ToolError.internal("Failed to create directory: " + e.getMessage()));
        }
    }
}
