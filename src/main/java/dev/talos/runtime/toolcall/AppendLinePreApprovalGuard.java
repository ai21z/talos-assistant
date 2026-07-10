package dev.talos.runtime.toolcall;

import dev.talos.runtime.expectation.AppendLineExpectation;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.task.TaskContract;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;

import java.util.Map;

final class AppendLinePreApprovalGuard {
    private AppendLinePreApprovalGuard() {}

    static ToolCall steeredEditFile(
            ToolCall call,
            LoopState state,
            TaskContract contract,
            String pathHint
    ) {
        if (call == null || contract == null || pathHint == null || pathHint.isBlank()) return null;
        String canonicalTool = ToolAliasPolicy.localCanonicalName(call.toolName());
        if (!"write_file".equals(canonicalTool)) return null;
        AppendLineExpectation expectation = appendLineExpectationForPath(contract, pathHint);
        if (expectation == null) return null;
        String content = firstParam(call, "content", "text", "body", "data", "file_content");
        if (content == null) return null;
        String previousContent = priorReadContentForPath(state, pathHint);
        if (previousContent == null) return null;
        if (!appendLineContentPreservesReadback(previousContent, content, expectation.expectedLine())) return null;
        String path = firstParam(call, "path", "file_path", "filepath", "file", "filename");
        if (path == null || path.isBlank()) path = pathHint;
        return new ToolCall("talos.edit_file", Map.of(
                "path", path,
                "old_string", previousContent,
                "new_string", content));
    }

    static String diagnostic(
            ToolCall call,
            LoopState state,
            TaskContract contract,
            String pathHint
    ) {
        if (call == null || contract == null || pathHint == null || pathHint.isBlank()) return null;
        String canonicalTool = ToolAliasPolicy.localCanonicalName(call.toolName());
        if (!"write_file".equals(canonicalTool)) return null;
        AppendLineExpectation expectation = appendLineExpectationForPath(contract, pathHint);
        if (expectation == null) return null;
        String content = firstParam(call, "content", "text", "body", "data", "file_content");
        if (content == null) return null;
        String previousContent = priorReadContentForPath(state, pathHint);
        if (previousContent == null) {
            return "append-line write_file for " + pathHint
                    + " requires complete same-turn read evidence before approval.";
        }
        if (appendLineContentPreservesReadback(previousContent, content, expectation.expectedLine())) {
            return null;
        }
        return "append-line write_file for " + pathHint
                + " does not preserve the complete same-turn readback and append exactly `"
                + expectation.expectedLine() + "`.";
    }

    private static AppendLineExpectation appendLineExpectationForPath(TaskContract contract, String pathHint) {
        if (contract == null || pathHint == null || pathHint.isBlank()) return null;
        String target = ToolCallSupport.canonicalizeReadPath(pathHint);
        for (var expectation : TaskExpectationResolver.resolve(contract)) {
            if (expectation instanceof AppendLineExpectation appendLine
                    && ToolCallSupport.canonicalizeReadPath(appendLine.targetPath()).equals(target)) {
                return appendLine;
            }
        }
        return null;
    }

    private static boolean appendLineContentPreservesReadback(
            String previousContent,
            String content,
            String appendedLine
    ) {
        if (previousContent == null || content == null || appendedLine == null || appendedLine.isBlank()) {
            return false;
        }
        String previous = normalizeLineEndings(previousContent);
        String actual = normalizeLineEndings(content);
        String line = normalizeLineEndings(appendedLine).strip();
        if (line.isBlank() || line.contains("\n")) return false;
        String separator = previous.endsWith("\n") || previous.isEmpty() ? "" : "\n";
        String expected = previous + separator + line + "\n";
        String expectedWithoutTerminalNewline = stripSingleTerminalNewline(expected);
        return actual.equals(expected) || actual.equals(expectedWithoutTerminalNewline);
    }

    private static String priorReadContentForPath(LoopState state, String pathHint) {
        return completeReadBodyForPath(state, pathHint);
    }

    /**
     * The single complete-read evidence owner shared by the append-line
     * guard and the T1003 invalid-edit recovery planner. Content is only
     * ever taken from a successful read_file signature for the same
     * canonical path without an {@code offset} parameter and without
     * explicit or implicit truncation, so an offset read that reaches EOF
     * can never masquerade as the whole file. When multiple complete reads
     * of the same path disagree in content the evidence is ambiguous and
     * null is returned (fail closed).
     */
    static String completeReadBodyForPath(LoopState state, String pathHint) {
        if (state == null || pathHint == null || pathHint.isBlank()) return null;
        String target = ToolCallSupport.canonicalizeReadPath(pathHint);
        if (target.isBlank() || state.successfulReadCallBodies.isEmpty()) return null;
        String out = null;
        for (var entry : state.successfulReadCallBodies.entrySet()) {
            String signature = entry.getKey();
            if (!readSignatureIsCompleteReadForPath(signature, target)) continue;
            String parsed = parseCompleteReadFileBody(entry.getValue());
            if (parsed == null) continue;
            if (out != null && !out.equals(parsed)) return null;
            out = parsed;
        }
        return out;
    }

    private static boolean readSignatureIsCompleteReadForPath(String signature, String target) {
        if (signature == null || target == null || target.isBlank()) return false;
        int separator = signature.indexOf(':');
        if (separator <= 0) return false;
        String toolName = signature.substring(0, separator);
        if (!"read_file".equals(ToolAliasPolicy.localCanonicalName(toolName))) return false;
        // Segment-aware key lookups: a hedged alias key ("file_path=a.md")
        // must never satisfy the "path" lookup through substring overlap,
        // and a ranged read is identified by its exact "offset" key. The
        // value comparison is case-insensitive like every other same-path
        // check, so case-variant reads of one file compare against each
        // other and can trip the ambiguity refusal.
        if (ToolCallSupport.readCallSignatureParam(signature, "offset") != null) return false;
        String pathValue = ToolCallSupport.readCallSignatureParam(signature, "path");
        return pathValue != null
                && ToolCallSupport.canonicalizeReadPath(pathValue)
                        .equalsIgnoreCase(ToolCallSupport.canonicalizeReadPath(target));
    }

    /**
     * Reconstructs the real file content from a complete line-numbered
     * read_file body, or returns null when the body is truncated or not a
     * numbered read. Package-visible: the T1003 invalid-edit recovery
     * planner shares this single owner of the numbered-body format.
     */
    static String parseCompleteReadFileBody(String body) {
        if (body == null || body.isBlank()) return null;
        if (body.contains("... (") || body.contains("output truncated") || body.startsWith("(file has")) {
            return null;
        }
        String normalized = body.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder(normalized.length());
        boolean sawLine = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i == lines.length - 1 && line.isEmpty()) {
                continue;
            }
            int sep = line.indexOf(" | ");
            if (sep <= 0 || !allDigits(line.substring(0, sep))) {
                return null;
            }
            out.append(line.substring(sep + 3)).append('\n');
            sawLine = true;
        }
        return sawLine ? out.toString() : null;
    }

    private static boolean allDigits(String value) {
        if (value == null || value.isBlank()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }

    private static String firstParam(ToolCall call, String... keys) {
        if (call == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            String value = call.param(key);
            if (value != null) return value;
        }
        return null;
    }

    private static String normalizeLineEndings(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String stripSingleTerminalNewline(String value) {
        if (value == null || value.isEmpty()) return value;
        return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
    }
}
