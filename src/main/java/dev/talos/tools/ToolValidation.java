package dev.talos.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared validation utilities for {@link TalosTool} implementations.
 *
 * <p>Extracts the common parameter-checking, path-resolution, sandbox-enforcement,
 * and size-guard patterns that are repeated across file-based tools
 * ({@code FileWriteTool}, {@code FileEditTool}, {@code ReadFileTool},
 * {@code ListDirTool}, {@code GrepTool}).
 *
 * <p>Usage pattern inside a tool's {@code execute(ToolCall, ToolContext)} method:
 * <pre>{@code
 *     ToolResult err;
 *     if ((err = requireNonBlank(call, "path")) != null) return err;
 *
 *     var rp = resolveFile(ctx, call.param("path"), MAX_FILE_SIZE);
 *     if (rp instanceof PathResult.Err e) return e.error();
 *     Path resolved = ((PathResult.Ok) rp).path();
 * }</pre>
 *
 * <p>All methods are stateless and thread-safe.
 *
 * @see ToolCall
 * @see ToolContext
 * @see ToolResult
 */
public final class ToolValidation {

    private ToolValidation() {} // utility class

    // ── Parameter validation ───────────────────────────────────────────

    /**
     * Require that the named parameter is present and non-blank.
     *
     * @return an error {@link ToolResult} if the param is null or blank; {@code null} if valid
     */
    public static ToolResult requireNonBlank(ToolCall call, String paramName) {
        String v = call.param(paramName);
        if (v == null || v.isBlank()) {
            return ToolResult.fail(ToolError.invalidParams("Missing required parameter: " + paramName));
        }
        return null;
    }

    /**
     * Require that the named parameter is present and non-empty
     * (allows whitespace-only values — useful for parameters like
     * {@code old_string} where whitespace is semantically significant).
     *
     * @return an error {@link ToolResult} if the param is null or empty; {@code null} if valid
     */
    public static ToolResult requireNonEmpty(ToolCall call, String paramName) {
        String v = call.param(paramName);
        if (v == null || v.isEmpty()) {
            return ToolResult.fail(ToolError.invalidParams("Missing required parameter: " + paramName));
        }
        return null;
    }

    /**
     * Require that the named parameter is present (non-null).
     * Empty and blank values are allowed (e.g. {@code new_string} can be empty
     * to delete text).
     *
     * @return an error {@link ToolResult} if the param is null; {@code null} if valid
     */
    public static ToolResult requirePresent(ToolCall call, String paramName) {
        if (call.param(paramName) == null) {
            return ToolResult.fail(ToolError.invalidParams("Missing required parameter: " + paramName));
        }
        return null;
    }

    // ── Path resolution with validation ────────────────────────────────

    /**
     * Result of a path resolution + validation chain.
     * Sealed so callers can pattern-match with {@code instanceof}.
     */
    public sealed interface PathResult permits PathResult.Ok, PathResult.Err {
        /** Path resolved and all checks passed. */
        record Ok(Path path) implements PathResult {}
        /** One of the checks failed — return this error to the caller. */
        record Err(ToolResult error) implements PathResult {}
    }

    /**
     * Resolve {@code pathParam} against the workspace root and sandbox-check it.
     * Does <em>not</em> verify existence or file/directory type.
     *
     * @param ctx       tool execution context (workspace + sandbox)
     * @param pathParam the raw path string from the tool call
     * @return {@link PathResult.Ok} with the resolved path, or {@link PathResult.Err}
     */
    public static PathResult resolveSandboxed(ToolContext ctx, String pathParam) {
        Path resolved = ctx.resolve(pathParam);
        if (!ctx.sandbox().allowedPath(resolved)) {
            return new PathResult.Err(ToolResult.fail(ToolError.invalidParams(
                    "Path not allowed: " + ctx.sandbox().explain(resolved))));
        }
        return new PathResult.Ok(resolved);
    }

    /**
     * Resolve + sandbox + verify the path exists and is a regular file
     * (not a directory).
     */
    public static PathResult resolveFile(ToolContext ctx, String pathParam) {
        PathResult base = resolveSandboxed(ctx, pathParam);
        if (base instanceof PathResult.Err) return base;
        Path p = ((PathResult.Ok) base).path();

        if (!Files.exists(p)) {
            return new PathResult.Err(ToolResult.fail(
                    ToolError.notFound("File not found: " + pathParam)));
        }
        if (Files.isDirectory(p)) {
            return new PathResult.Err(ToolResult.fail(
                    ToolError.invalidParams("Path is a directory, not a file: " + pathParam)));
        }
        return base;
    }

    /**
     * Resolve + sandbox + exists + not-directory + file-size guard.
     *
     * @param maxBytes maximum allowed file size in bytes
     */
    public static PathResult resolveFile(ToolContext ctx, String pathParam, long maxBytes) {
        PathResult base = resolveFile(ctx, pathParam);
        if (base instanceof PathResult.Err) return base;
        Path p = ((PathResult.Ok) base).path();

        try {
            long size = Files.size(p);
            if (size > maxBytes) {
                return new PathResult.Err(ToolResult.fail(ToolError.invalidParams(
                        "File too large (" + (size / 1024) + " KB). Max: "
                                + (maxBytes / 1024) + " KB")));
            }
        } catch (IOException e) {
            return new PathResult.Err(ToolResult.fail(
                    ToolError.internal("Cannot read file size: " + e.getMessage())));
        }
        return base;
    }

    /**
     * Resolve + sandbox + verify the path exists and <em>is</em> a directory.
     */
    public static PathResult resolveDirectory(ToolContext ctx, String pathParam) {
        PathResult base = resolveSandboxed(ctx, pathParam);
        if (base instanceof PathResult.Err) return base;
        Path p = ((PathResult.Ok) base).path();

        if (!Files.exists(p)) {
            return new PathResult.Err(ToolResult.fail(
                    ToolError.notFound("Directory not found: " + pathParam)));
        }
        if (!Files.isDirectory(p)) {
            return new PathResult.Err(ToolResult.fail(
                    ToolError.invalidParams("Path is not a directory: " + pathParam)));
        }
        return base;
    }

    // ── Integer parameter parsing ──────────────────────────────────────

    /**
     * Parse an integer parameter from the tool call, returning a default value
     * if the parameter is absent, blank, or not a valid integer.
     *
     * <p>Shared pattern extracted from {@code ReadFileTool}, {@code ListDirTool},
     * and {@code GrepTool} where it was duplicated three times.
     */
    public static int intParam(ToolCall call, String key, int defaultValue) {
        String v = call.param(key);
        if (v == null || v.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}

