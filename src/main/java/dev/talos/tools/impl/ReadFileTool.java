package dev.talos.tools.impl;

import dev.talos.core.ingest.UnsupportedDocumentFormats;
import dev.talos.tools.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Tool that reads a workspace file and returns its content.
 *
 * <p>Enforces sandbox policy: the requested path must resolve inside the
 * workspace and pass the sandbox allow/deny checks.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code path} — relative path to the file within the workspace (required)</li>
 *   <li>{@code max_lines} — maximum number of lines to return (optional, default: 500)</li>
 *   <li>{@code offset} — 1-based starting line number (optional, default: 1)</li>
 * </ul>
 */
public final class ReadFileTool implements TalosTool {

    private static final String NAME = "talos.read_file";
    private static final int DEFAULT_MAX_LINES = 500;
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024L; // 2 MiB safety cap
    /** Character-based output cap. Large reads crowd out context for subsequent calls. */
    static final int MAX_OUTPUT_CHARS = 16_000;

    @Override public String name() { return NAME; }
    @Override public String description() { return "Read a file from the workspace by path."; }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(NAME, description(),
                """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Relative path to the file in the workspace"},
                  "max_lines":{"type":"integer","description":"Max lines to return (default 500)"},
                  "offset":{"type":"integer","description":"1-based starting line (default 1)"}
                },"required":["path"]}""",
                ToolRiskLevel.READ_ONLY,
                ToolOperationMetadata.inspect(
                        NAME,
                        Map.of("path", ToolOperationMetadata.PathRole.TARGET_FILE),
                        "FILE_READ"));
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        if (ctx == null) {
            return ToolResult.fail(ToolError.internal("ReadFileTool requires a ToolContext"));
        }

        String pathParam = resolveParam(call, "path", "file_path", "filepath", "file", "filename");
        if (pathParam == null || pathParam.isBlank()) {
            return ToolResult.fail(ToolError.invalidParams("Missing required parameter: path"));
        }

        // Resolve and sandbox-check the path
        Path resolved = ctx.resolve(pathParam);
        if (!ctx.sandbox().allowedPath(resolved)) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Path not allowed: " + ctx.sandbox().explain(resolved)));
        }

        if (!Files.exists(resolved)) {
            return ToolResult.fail(ToolError.notFound(
                    NotFoundHint.build(pathParam, resolved, ctx.workspace())));
        }
        if (Files.isDirectory(resolved)) {
            return ToolResult.fail(ToolError.invalidParams("Path is a directory, not a file: " + pathParam));
        }
        if (UnsupportedDocumentFormats.isUnsupported(resolved)) {
            return ToolResult.fail(ToolError.unsupportedFormat(
                    UnsupportedDocumentFormats.capabilityMessage(resolved)));
        }

        // Size guard
        try {
            long size = Files.size(resolved);
            if (size > MAX_FILE_SIZE) {
                return ToolResult.fail(ToolError.invalidParams(
                        "File too large (" + (size / 1024) + " KB). Max: " + (MAX_FILE_SIZE / 1024) + " KB"));
            }
        } catch (IOException e) {
            return ToolResult.fail(ToolError.internal("Cannot read file size: " + e.getMessage()));
        }

        // Parse optional line range
        int maxLines = parseIntParam(call, "max_lines", DEFAULT_MAX_LINES);
        int offset = Math.max(1, parseIntParam(call, "offset", 1));

        try {
            var allLines = Files.readAllLines(resolved);
            int startIdx = offset - 1; // 0-based
            if (startIdx >= allLines.size()) {
                return ToolResult.ok("(file has " + allLines.size() + " lines; offset " + offset + " is past end)");
            }

            int endIdx = Math.min(startIdx + maxLines, allLines.size());
            var sb = new StringBuilder();
            for (int i = startIdx; i < endIdx; i++) {
                sb.append(i + 1).append(" | ").append(allLines.get(i)).append('\n');
            }

            if (endIdx < allLines.size()) {
                sb.append("... (").append(allLines.size() - endIdx).append(" more lines)\n");
            }

            String output = sb.toString();
            if (output.length() > MAX_OUTPUT_CHARS) {
                output = output.substring(0, MAX_OUTPUT_CHARS)
                        + "\n... [output truncated at 16K chars — use talos.grep to search for specific content, "
                        + "or request a specific range with offset + max_lines]";
            }

            return ToolResult.ok(output);
        } catch (IOException e) {
            return ToolResult.fail(ToolError.internal("Failed to read file: " + e.getMessage()));
        }
    }

    private static int parseIntParam(ToolCall call, String key, int defaultValue) {
        String v = call.param(key);
        if (v == null || v.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Resolve a parameter by trying the canonical key first, then known aliases. */
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

