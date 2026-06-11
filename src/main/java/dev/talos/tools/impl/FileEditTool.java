package dev.talos.tools.impl;

import dev.talos.core.capability.CapabilityKind;
import dev.talos.tools.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Tool that performs a targeted string replacement within a workspace file.
 *
 * <p>Modeled after Claude Code's FileEditTool: the caller provides the exact
 * text to find ({@code old_string}) and the replacement ({@code new_string}).
 * The match must be unique - if the old string appears zero or multiple times,
 * the edit is rejected to prevent ambiguous changes.
 *
 * <p>Enforces sandbox policy: the target path must resolve inside the workspace.
 *
 * <p>Risk level: {@link ToolRiskLevel#WRITE} - requires user approval
 * via the {@link dev.talos.runtime.ApprovalGate}.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code path} - relative path to the file (required)</li>
 *   <li>{@code old_string} - exact text to find (required, must appear exactly once)</li>
 *   <li>{@code new_string} - replacement text (required, may be empty for deletion)</li>
 * </ul>
 */
public final class FileEditTool implements TalosTool {

    private static final String NAME = "talos.edit_file";
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024L; // 2 MiB

    private final FileUndoStack undoStack;

    public FileEditTool() { this(null); }
    public FileEditTool(FileUndoStack undoStack) { this.undoStack = undoStack; }

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "Replace a unique string in a workspace file. "
                + "TIP: call talos.read_file first to see the exact content. "
                + "old_string must match the file exactly - strip any line-number prefixes from read_file output before using.";
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(NAME, description(),
                """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Relative path to the file in the workspace"},
                  "old_string":{"type":"string","description":"Exact file content to find and replace, character-for-character including whitespace and newlines. NOTE: talos.read_file output includes line-number prefixes like '1 | ' - do NOT include those prefixes in old_string. Copy only the actual file content, not the display formatting. Must appear exactly once in the file."},
                  "new_string":{"type":"string","description":"Replacement text (may be empty to delete the matched text)"}
                },"required":["path","old_string","new_string"]}""",
                ToolRiskLevel.WRITE,
                ToolOperationMetadata.workspaceMutation(
                        NAME,
                        CapabilityKind.EDIT,
                        ToolRiskLevel.WRITE,
                        Map.of("path", ToolOperationMetadata.PathRole.TARGET_FILE),
                        false,
                        true,
                        "FILE_EDITED",
                        "CONTENT_VERIFY"));
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        if (ctx == null) {
            return ToolResult.fail(ToolError.internal("FileEditTool requires a ToolContext"));
        }

        // --- Validate parameters (with alias resolution) ---
        String pathParam = resolveParam(call, "path", "file_path", "filepath", "file", "filename");
        if (pathParam == null || pathParam.isBlank()) {
            return ToolResult.fail(ToolError.invalidParams("Missing required parameter: path"));
        }

        String oldString = resolveParam(call, "old_string", "oldString", "old_text", "search", "find", "original");
        if (oldString == null || oldString.isEmpty()) {
            return ToolResult.fail(ToolError.invalidParams("Missing required parameter: old_string"));
        }

        String newString = resolveParam(call, "new_string", "newString", "new_text", "replace", "replacement");
        if (newString == null) {
            return ToolResult.fail(ToolError.invalidParams(
                    ToolFailureReason.EDIT_EMPTY_ARGUMENTS,
                    "Missing required parameter: new_string"));
        }

        // new_string arrives exactly as approved: markdown-commentary
        // sanitization happens once, pre-approval, in the runtime's call
        // normalization (T755). Re-sanitizing here would break the
        // approved-bytes == written-bytes invariant.

        // Reject no-op edits (old_string == new_string)
        if (oldString.equals(newString)) {
            return ToolResult.fail(ToolError.invalidParams(
                    "old_string and new_string are identical - no change would be made. "
                    + "Verify the intended edit and provide different replacement text."));
        }

        // --- Resolve and sandbox-check ---
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
            return ToolResult.fail(ToolError.invalidParams(
                    "Path is a directory, not a file: " + pathParam));
        }

        // --- Size guard ---
        try {
            long size = Files.size(resolved);
            if (size > MAX_FILE_SIZE) {
                return ToolResult.fail(ToolError.invalidParams(
                        "File too large (" + (size / 1024) + " KB). Max: " + (MAX_FILE_SIZE / 1024) + " KB"));
            }
        } catch (IOException e) {
            return ToolResult.fail(ToolError.internal("Cannot read file size: " + e.getMessage()));
        }

        // --- Read, validate uniqueness, replace ---
        try {
            String content = Files.readString(resolved);

            int count = countOccurrences(content, oldString);
            if (count == 0) {
                String snippet = buildFileSnippet(content, 20);
                return ToolResult.fail(ToolError.invalidParams(
                        ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND,
                        "old_string not found in " + pathParam + ". "
                        + "The exact text was not found in the file. "
                        + "Call talos.read_file to see the current content, then copy the exact text into old_string.\n"
                        + "File begins with:\n" + snippet));
            }
            if (count > 1) {
                return ToolResult.fail(ToolError.invalidParams(
                        ToolFailureReason.EDIT_OLD_STRING_AMBIGUOUS,
                        "old_string found " + count + " times in " + pathParam +
                        ". Provide more context to make the match unique."));
            }

            // Exactly one match - safe to replace
            String updated = content.replace(oldString, newString);

            // Snapshot for undo before mutating
            if (undoStack != null) {
                undoStack.push(new FileUndoStack.UndoEntry(
                        resolved, content, false, NAME, Instant.now()));
            }

            Files.writeString(resolved, updated);

            // Report what changed
            long oldLines = oldString.chars().filter(c -> c == '\n').count() + 1;
            long newLines = newString.chars().filter(c -> c == '\n').count() + (newString.isEmpty() ? 0 : 1);
            String base = "Edited " + pathParam + ": replaced " + oldLines + " line(s) with "
                    + newLines + " line(s) (" + updated.length() + " bytes total)";

            // Post-write verification
            ContentVerifier.VerifyResult vr = ContentVerifier.verify(resolved, updated);
            String statusTag = "[verification: " + vr.status().name() + "]";
            if (vr.ok()) {
                return ToolResult.ok(base + ". Verified: " + vr.summary() + ". " + statusTag, vr.status());
            } else {
                return ToolResult.ok(base + ". Warning: " + vr.summary() + ". " + statusTag, vr.status());
            }
        } catch (IOException e) {
            return ToolResult.fail(ToolError.internal("Failed to edit file: " + e.getMessage()));
        }
    }

    /**
     * Build a snippet of the first {@code maxLines} lines of a file for error feedback.
     * Gives the model ground truth to retry from when old_string is not found.
     */
    static String buildFileSnippet(String content, int maxLines) {
        if (content == null || content.isEmpty()) return "(empty file)";
        String[] lines = content.split("\n", -1);
        int limit = Math.min(lines.length, maxLines);
        // NOTE in the snippet header: line-number prefixes are display-only.
        var sb = new StringBuilder("(line numbers below are display-only - do NOT include '1 | ' prefixes in old_string)\n");
        for (int i = 0; i < limit; i++) {
            sb.append(i + 1).append(" | ").append(lines[i]).append('\n');
        }
        if (lines.length > maxLines) {
            sb.append("... (").append(lines.length - maxLines).append(" more lines - call talos.read_file to see all)");
        }
        return sb.toString();
    }

    /**
     * Count non-overlapping occurrences of {@code needle} in {@code haystack}.
     */
    static int countOccurrences(String haystack, String needle) {
        if (haystack.isEmpty() || needle.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /**
     * Resolve a parameter by trying the canonical key first, then known aliases.
     * Models frequently use alternative names (e.g. {@code file_path} instead of
     * {@code path}, {@code oldString} instead of {@code old_string}).
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

