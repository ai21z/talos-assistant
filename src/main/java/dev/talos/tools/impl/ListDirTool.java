package dev.talos.tools.impl;

import dev.talos.tools.*;

import java.io.IOException;
import java.nio.file.Files;
import dev.talos.spi.WorkspaceObservationBoundary;
import dev.talos.spi.WorkspaceObservationBoundary.Action;
import dev.talos.spi.WorkspaceObservationBoundary.Publication;
import java.nio.file.Path;
import java.nio.file.LinkOption;
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
 *   <li>{@code path} - relative path to the directory within the workspace (required)</li>
 *   <li>{@code max_depth} - maximum directory depth to traverse (optional, default: 1)</li>
 *   <li>{@code max_entries} - maximum number of entries to return (optional, default: 200)</li>
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
        var boundary = ctx.observationBoundary();
        boolean controlled = boundary.controlled();

        try {
            String pathParam = resolveParam(call, "path", "dir", "directory", "dir_path", "folder");
            if (pathParam == null || pathParam.isBlank()) {
                pathParam = "."; // default to workspace root
            }

            Path resolved = ctx.resolve(pathParam);
            if (!ctx.sandbox().allowedPath(resolved)) {
                return ToolResult.fail(ToolError.invalidParams(
                        "Path not allowed: " + ctx.sandbox().explain(resolved)));
            }

            if (!boundary.observe(Action.METADATA, resolved, () -> Files.exists(resolved))) {
                return ToolResult.fail(ToolError.notFound("Directory not found: " + pathParam));
            }
            if (!boundary.observe(Action.METADATA, resolved, () -> Files.isDirectory(resolved))) {
                return ToolResult.fail(ToolError.invalidParams("Path is not a directory: " + pathParam));
            }

            int maxDepth = Math.clamp(parseIntParam(call, "max_depth", DEFAULT_MAX_DEPTH), 1, 5);
            int maxEntries = Math.clamp(parseIntParam(call, "max_entries", DEFAULT_MAX_ENTRIES), 1, ABSOLUTE_MAX_ENTRIES);

            var sb = new StringBuilder();
            int[] count = {0};
            boolean[] truncated = {false};
            var fields = boundary.fields();

            try (Stream<Path> stream = boundary.observe(Action.ENUMERATE, resolved, () -> Files.walk(resolved, maxDepth))) {
                stream
                    .filter(p -> !p.equals(resolved)) // skip the root itself
                    .sorted()
                    .forEach(p -> {
                        Path rel = resolved.relativize(p);
                        if (controlled && !fields.hiddenFactsPublic()
                                && (rel.getFileName().toString().startsWith(".") || hidden(p, boundary))) return;
                        if (count[0] >= maxEntries) {
                            truncated[0] = true;
                            return;
                        }
                        // Show path relative to the queried directory
                        count[0]++;
                        if (controlled
                                && (boundary.publication() == Publication.NO_NAMES
                                || boundary.publication() == Publication.VISIBLE_NAMES
                                && (rel.getFileName().toString().startsWith(".") || hidden(p, boundary)))) return;
                        if ((!controlled || fields.typesPublic()) && directory(p, boundary, controlled)) {
                            sb.append(rel).append("/\n");
                        } else {
                            sb.append(rel).append('\n');
                        }
                    });
            }

            ToolContentMetadata metadata = ToolContentMetadata.normal().withDirectoryObservation(
                    new DirectoryObservation(resolved.toAbsolutePath().normalize().toString(),
                            maxDepth, count[0], !truncated[0], !controlled || fields.hiddenFactsPublic()));
            if (controlled && boundary.publication() == Publication.NO_NAMES)
                return ToolResult.ok("Directory observation recorded without entry names.", metadata);
            if (count[0] == 0) {
                return ToolResult.ok(controlled && (!fields.existencePublic() || !fields.hiddenFactsPublic())
                        ? "Visible directory observation recorded." : "(empty directory)", metadata);
            }

            if (truncated[0]) {
                if (!controlled || fields.countPublic())
                    sb.append("... (truncated at ").append(maxEntries).append(" entries)\n");
                else sb.append("... (observation limited)\n");
            }

            return ToolResult.ok(sb.isEmpty() ? "Directory observation recorded without entry names." : sb.toString(), metadata);
        } catch (IOException | java.io.UncheckedIOException e) {
            return ToolResult.fail(ToolError.internal(controlled
                    ? "The admitted directory observation failed. No complete result was established."
                    : "Failed to list directory: " + e.getMessage()));
        }
    }

    private static boolean directory(Path path, WorkspaceObservationBoundary boundary, boolean controlled) {
        try {
            return boundary.observe(Action.METADATA, path, () -> controlled
                    ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) : Files.isDirectory(path));
        } catch (IOException unavailable) { throw new java.io.UncheckedIOException(unavailable); }
    }

    private static boolean hidden(Path path, WorkspaceObservationBoundary boundary) {
        try {
            return boundary.observe(Action.METADATA, path, () -> {
                if (path.getFileSystem().supportedFileAttributeViews().contains("dos"))
                    return Files.readAttributes(path, java.nio.file.attribute.DosFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS).isHidden();
                return path.getFileName().toString().startsWith(".");
            });
        } catch (IOException unavailable) {
            throw new java.io.UncheckedIOException(unavailable);
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


