package dev.talos.tools.impl;

import dev.talos.core.capability.CapabilityKind;
import dev.talos.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

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

    private static final Logger LOG = LoggerFactory.getLogger(FileWriteTool.class);
    private static final String NAME = "talos.write_file";
    private static final long MAX_CONTENT_SIZE = 1024 * 1024L; // 1 MiB content cap

    private final FileUndoStack undoStack;

    public FileWriteTool() { this(null); }
    public FileWriteTool(FileUndoStack undoStack) { this.undoStack = undoStack; }

    @Override public String name() { return NAME; }
    @Override public String description() { return "Create or overwrite a file in the workspace."; }

    @Override
    public ToolDescriptor descriptor() {
        // IMPORTANT: 'path' is listed FIRST in the schema so the model generates
        // it before the (potentially very long) 'content' parameter. This prevents
        // the model from forgetting 'path' when generating large file content.
        return new ToolDescriptor(NAME, description(),
                """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Relative file path to write (REQUIRED, generate this FIRST)"},
                  "content":{"type":"string","description":"Full content to write to the file"}
                },"required":["path","content"]}""",
                ToolRiskLevel.WRITE,
                ToolOperationMetadata.workspaceMutation(
                        NAME,
                        CapabilityKind.CREATE,
                        ToolRiskLevel.WRITE,
                        Map.of("path", ToolOperationMetadata.PathRole.TARGET_FILE),
                        false,
                        true,
                        "FILE_WRITTEN",
                        "CONTENT_VERIFY"));
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        if (ctx == null) {
            return ToolResult.fail(ToolError.internal("FileWriteTool requires a ToolContext"));
        }

        String pathParam = resolveParam(call, "path", "file_path", "filepath", "file", "filename");
        if (pathParam == null || pathParam.isBlank()) {
            return ToolResult.fail(ToolError.invalidParams("Missing required parameter: path"));
        }

        String content = resolveParam(call, "content", "text", "body", "data", "file_content");
        if (content == null) {
            return ToolResult.fail(ToolError.invalidParams("Missing required parameter: content"));
        }

        // Strip trailing markdown commentary that LLMs accidentally include
        String sanitized = ContentSanitizer.sanitize(content, pathParam);
        if (sanitized.length() < content.length()) {
            LOG.debug("Stripped {} chars of trailing markdown commentary from write_file content for {}",
                    content.length() - sanitized.length(), pathParam);
            content = sanitized;
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

            // Snapshot for undo before mutating
            if (undoStack != null) {
                String prev = existed ? Files.readString(resolved) : null;
                undoStack.push(new FileUndoStack.UndoEntry(
                        resolved, prev, !existed, NAME, Instant.now()));
            }

            Files.writeString(resolved, content);

            long lines = content.chars().filter(c -> c == '\n').count() + (content.isEmpty() ? 0 : 1);
            String verb = existed ? "Updated" : "Created";
            String base = verb + " " + pathParam + " (" + lines + " lines, " + content.length() + " bytes)";

            // Post-write verification
            ContentVerifier.VerifyResult vr = ContentVerifier.verify(resolved, content);
            String statusTag = "[verification: " + vr.status().name() + "]";
            if (vr.ok()) {
                return ToolResult.ok(base + ". Verified: " + vr.summary() + ". " + statusTag, vr.status());
            } else {
                return ToolResult.ok(base + ". Warning: " + vr.summary() + ". " + statusTag, vr.status());
            }
        } catch (IOException e) {
            return ToolResult.fail(ToolError.internal("Failed to write file: " + e.getMessage()));
        }
    }

    /**
     * Resolve a parameter by trying the canonical key first, then known aliases.
     * Models frequently use alternative names (e.g. {@code file_path} instead of
     * {@code path}, {@code text} instead of {@code content}).
     */
    private static String resolveParam(ToolCall call, String canonical, String... aliases) {
        String value = call.param(canonical);
        if (value != null) return value;
        for (String alias : aliases) {
            value = call.param(alias);
            if (value != null) return value;
        }
        return null;
    }
}

