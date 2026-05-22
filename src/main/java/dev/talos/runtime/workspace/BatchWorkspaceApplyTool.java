package dev.talos.runtime.workspace;

import dev.talos.core.capability.CapabilityKind;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolOperationMetadata;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;
import dev.talos.tools.impl.CopyPathTool;
import dev.talos.tools.impl.DeletePathTool;
import dev.talos.tools.impl.MakeDirectoryTool;
import dev.talos.tools.impl.MovePathTool;
import dev.talos.tools.impl.RenamePathTool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Applies a coherent workspace batch after one approval. */
public final class BatchWorkspaceApplyTool implements TalosTool {
    private static final String NAME = "talos.apply_workspace_batch";

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Apply a batch of workspace operations from an operations_json string.";
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(NAME, description(),
                """
                {"type":"object","properties":{
                  "operations_json":{"type":"string","description":"JSON array of operations. Supported op values: mkdir, move_path, copy_path, rename_path, delete_path. Use overwrite/recursive booleans when needed."}
                },"required":["operations_json"]}""",
                ToolRiskLevel.WRITE,
                ToolOperationMetadata.workspaceMutation(
                        NAME,
                        CapabilityKind.ORGANIZE,
                        ToolRiskLevel.WRITE,
                        Map.of("operations_json", ToolOperationMetadata.PathRole.TARGET_PATH),
                        true,
                        true,
                        "WORKSPACE_BATCH_APPLIED",
                        "WORKSPACE_BATCH_VERIFY"));
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        if (ctx == null) return ToolResult.fail(ToolError.internal(NAME + " requires a ToolContext"));

        WorkspaceBatchPlan plan;
        try {
            plan = WorkspaceBatchPlanParser.parse(call)
                    .orElseThrow(() -> new IllegalArgumentException("Missing required parameter: operations_json"));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(ToolError.invalidParams(e.getMessage()));
        }

        ToolResult sandboxValidation = validateSandbox(ctx, plan);
        if (sandboxValidation != null) return sandboxValidation;

        List<String> applied = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        for (WorkspaceBatchOperation operation : plan.operations()) {
            ToolResult result = applyOne(operation, ctx);
            if (!result.success()) {
                String failed = operation.appliedPathSummary();
                String message = (applied.isEmpty()
                        ? "Batch workspace operation failed."
                        : "Batch partially applied.")
                        + " Applied: " + (applied.isEmpty() ? "(none)" : String.join(", ", applied))
                        + ". Failed: " + failed
                        + ". Reason: " + result.errorMessage();
                return ToolResult.fail(ToolError.internal(message));
            }
            applied.add(operation.appliedPathSummary());
            summaries.add(firstLine(result.output()));
        }

        return ToolResult.ok("Applied batch workspace operation: " + plan.previewSummary()
                + "\n" + String.join("\n", summaries));
    }

    private static ToolResult validateSandbox(ToolContext ctx, WorkspaceBatchPlan plan) {
        for (String path : plan.pathValues()) {
            Path resolved;
            try {
                resolved = ctx.resolve(path);
            } catch (Exception e) {
                return ToolResult.fail(ToolError.invalidParams("Invalid path: " + path));
            }
            if (!ctx.sandbox().allowedPath(resolved)) {
                return ToolResult.fail(ToolError.invalidParams(
                        "Path not allowed: " + ctx.sandbox().explain(resolved)));
            }
        }
        return null;
    }

    private static ToolResult applyOne(WorkspaceBatchOperation operation, ToolContext ctx) {
        return switch (operation.kind()) {
            case MKDIR -> new MakeDirectoryTool().execute(
                    new ToolCall("talos.mkdir", Map.of("path", operation.targetPath())),
                    ctx);
            case MOVE_PATH -> new MovePathTool().execute(
                    new ToolCall("talos.move_path", Map.of(
                            "from", operation.sourcePath(),
                            "to", operation.destinationPath(),
                            "overwrite", String.valueOf(operation.overwrite()))),
                    ctx);
            case COPY_PATH -> new CopyPathTool().execute(
                    new ToolCall("talos.copy_path", Map.of(
                            "from", operation.sourcePath(),
                            "to", operation.destinationPath(),
                            "overwrite", String.valueOf(operation.overwrite()),
                            "recursive", String.valueOf(operation.recursive()))),
                    ctx);
            case RENAME_PATH -> new RenamePathTool().execute(
                    new ToolCall("talos.rename_path", Map.of(
                            "path", operation.sourcePath(),
                            "new_name", operation.newName(),
                            "overwrite", String.valueOf(operation.overwrite()))),
                    ctx);
            case DELETE_PATH -> new DeletePathTool().execute(
                    new ToolCall("talos.delete_path", Map.of(
                            "path", operation.targetPath(),
                            "recursive", String.valueOf(operation.recursive()))),
                    ctx);
        };
    }

    private static String firstLine(String value) {
        if (value == null || value.isBlank()) return "";
        int newline = value.indexOf('\n');
        return newline < 0 ? value.strip() : value.substring(0, newline).strip();
    }
}
