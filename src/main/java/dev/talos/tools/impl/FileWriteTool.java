package dev.talos.tools.impl;

import dev.talos.tools.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tool that creates or overwrites a file within the workspace.
 *
 * <p>Enforces sandbox policy: the target path must resolve inside the
 * workspace and pass the sandbox allow/deny checks. Parent directories
 * are created automatically if they don't exist.
 *
 * <p>Risk level: {@link ToolRiskLevel#WRITE} — requires user approval
 * via the {@link dev.talos.runtime.ApprovalGate}.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code path} — relative path to the file within the workspace (required)</li>
 *   <li>{@code content} — the full file content to write (required)</li>
 * </ul>
 */
public final class FileWriteTool implements TalosTool {

    private static final String NAME = "talos.write_file";
    private static final long MAX_CONTENT_SIZE = 1024 * 1024L; // 1 MiB content cap

    @Override public String name() { return NAME; }
    @Override public String description() { return "Create or overwrite a file in the workspace."; }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(NAME, description(),
                """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Relative path to the file in the workspace"},
                  "content":{"type":"string","description":"Full content to write to the file"}
                },"required":["path","content"]}""",
                ToolRiskLevel.WRITE);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        return ToolResult.fail(ToolError.internal("FileWriteTool requires a ToolContext"));
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        if (ctx == null) return execute(call);

        String pathParam = call.param("path");
        if (pathParam == null || pathParam.isBlank()) {
            return ToolResult.fail(ToolError.invalidParams("Missing required parameter: path"));
        }

        String content = call.param("content");
        if (content == null) {
            return ToolResult.fail(ToolError.invalidParams("Missing required parameter: content"));
        }

        // Content size guard
        if (content.length() > MAX_CONTENT_SIZE) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Content too large (" + (content.length() / 1024) + " KB). Max: " + (MAX_CONTENT_SIZE / 1024) + " KB"));
        }

        // Resolve and sandbox-check
        Path resolved = ctx.resolve(pathParam);
        if (!ctx.sandbox().allowedPath(resolved)) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Path not allowed: " + ctx.sandbox().explain(resolved)));
        }

        // Don't overwrite a directory
        if (Files.isDirectory(resolved)) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Path is a directory, not a file: " + pathParam));
        }

        try {
            // Create parent directories if needed
            Path parent = resolved.getParent();
            if (parent != null && !Files.exists(parent)) {
                // Verify parent is also inside workspace
                if (!ctx.sandbox().allowedPath(parent)) {
                    return ToolResult.fail(ToolError.invalidParams(
                            "Parent directory not allowed: " + ctx.sandbox().explain(parent)));
                }
                Files.createDirectories(parent);
            }

            boolean existed = Files.exists(resolved);
            Files.writeString(resolved, content);

            long lines = content.chars().filter(c -> c == '\n').count() + (content.isEmpty() ? 0 : 1);
            String verb = existed ? "Updated" : "Created";
            return ToolResult.ok(verb + " " + pathParam + " (" + lines + " lines, " + content.length() + " bytes)");
        } catch (IOException e) {
            return ToolResult.fail(ToolError.internal("Failed to write file: " + e.getMessage()));
        }
    }
}

