package dev.talos.tools.impl;

import dev.talos.tools.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Tool that lists directory contents within the workspace.
 *
 * <p>Enforces sandbox policy: the target directory must resolve inside the
 * workspace and pass the sandbox allow/deny checks.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code path} — relative path to the directory within the workspace (required)</li>
 *   <li>{@code max_depth} — maximum directory depth to traverse (optional, default: 1)</li>
 *   <li>{@code max_entries} — maximum number of entries to return (optional, default: 200)</li>
 * </ul>
 *
 * <p>Output format: one entry per line. Directories are suffixed with {@code /}.
 * Entries are relative to the queried directory.
 */
public final class ListDirTool implements TalosTool {

    private static final String NAME = "talos.list_dir";
    private static final int DEFAULT_MAX_DEPTH = 1;
    private static final int DEFAULT_MAX_ENTRIES = 200;
    private static final int ABSOLUTE_MAX_ENTRIES = 2000;

    @Override public String name() { return NAME; }
    @Override public String description() { return "List directory contents within the workspace."; }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(NAME, description(),
                """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Relative path to the directory in the workspace"},
                  "max_depth":{"type":"integer","description":"Max directory depth (default 1, max 5)"},
                  "max_entries":{"type":"integer","description":"Max entries to return (default 200)"}
                },"required":["path"]}""",
                ToolRiskLevel.READ_ONLY,
                ToolOperationMetadata.inspect(
                        NAME,
                        Map.of("path", ToolOperationMetadata.PathRole.TARGET_DIRECTORY),
                        "DIRECTORY_LISTED"));
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        if (ctx == null) {
            return ToolResult.fail(ToolError.internal("ListDirTool requires a ToolContext"));
        }

        String pathParam = resolveParam(call, "path", "dir", "directory", "dir_path", "folder");
        if (pathParam == null || pathParam.isBlank()) {
            pathParam = "."; // default to workspace root
        }

        // Resolve and sandbox-check the path
        Path resolved = ctx.resolve(pathParam);
        if (!ctx.sandbox().allowedPath(resolved)) {
            return ToolResult.fail(ToolError.invalidParams(
                    "Path not allowed: " + ctx.sandbox().explain(resolved)));
        }

        if (!Files.exists(resolved)) {
            return ToolResult.fail(ToolError.notFound("Directory not found: " + pathParam));
        }
        if (!Files.isDirectory(resolved)) {
            return ToolResult.fail(ToolError.invalidParams("Path is not a directory: " + pathParam));
        }

        // Parse optional parameters
        int maxDepth = Math.clamp(parseIntParam(call, "max_depth", DEFAULT_MAX_DEPTH), 1, 5);
        int maxEntries = Math.clamp(parseIntParam(call, "max_entries", DEFAULT_MAX_ENTRIES), 1, ABSOLUTE_MAX_ENTRIES);

        try {
            var sb = new StringBuilder();
            int[] count = {0};
            boolean[] truncated = {false};

            try (Stream<Path> stream = Files.walk(resolved, maxDepth)) {
                stream
                    .filter(p -> !p.equals(resolved)) // skip the root itself
                    .sorted()
                    .forEach(p -> {
                        if (count[0] >= maxEntries) {
                            truncated[0] = true;
                            return;
                        }
                        // Show path relative to the queried directory
                        Path rel = resolved.relativize(p);
                        if (Files.isDirectory(p)) {
                            sb.append(rel).append("/\n");
                        } else {
                            sb.append(rel).append('\n');
                        }
                        count[0]++;
                    });
            }

            if (count[0] == 0) {
                return ToolResult.ok("(empty directory)");
            }

            if (truncated[0]) {
                sb.append("... (truncated at ").append(maxEntries).append(" entries)\n");
            }

            return ToolResult.ok(sb.toString());
        } catch (IOException e) {
            return ToolResult.fail(ToolError.internal("Failed to list directory: " + e.getMessage()));
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


