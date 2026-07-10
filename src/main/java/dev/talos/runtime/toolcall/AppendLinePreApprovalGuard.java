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
        // T1012: derive the mutation from the CURRENT DISK BYTES, not the
        // LF-normalized readback, so the steered edit raw-matches the file
        // exactly and preserves its line-ending style and terminal-newline
        // state. The readback still gates eligibility above; the disk text
        // must agree with it (modulo the read surface's LF normalization
        // and forced terminal newline) or steering is refused and the
        // write path's own format preservation takes over.
        String disk = currentDiskTextForSteering(state, pathHint);
        if (disk == null) return null;
        if (!normalizedForComparison(disk).equals(normalizedForComparison(previousContent))) return null;
        String line = normalizeLineEndings(expectation.expectedLine()).strip();
        if (line.isBlank() || line.contains("\n")) return null;
        String appended = appendPreservingDiskFormat(disk, line);
        if (appended == null || appended.equals(disk)) return null;
        String path = firstParam(call, "path", "file_path", "filepath", "file", "filename");
        if (path == null || path.isBlank()) path = pathHint;
        return new ToolCall("talos.edit_file", Map.of(
                "path", path,
                "old_string", disk,
                "new_string", appended));
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
     * ever taken from the latest complete (unranged, untruncated,
     * numbered) read_file entry for the effective path, so an offset read
     * that reaches EOF can never masquerade as the whole file and a
     * hedged alias key can never satisfy the lookup. Recency is the
     * ledger's deterministic sequence order: a later fresh complete read
     * supersedes an older one, and consumers that need exact source
     * agreement (recovery, steering) keep their disk-agreement gate as
     * the fail-closed arbiter.
     */
    static String completeReadBodyForPath(LoopState state, String pathHint) {
        QualifiedRead read = qualifiedCompleteRead(state, pathHint);
        return read == null ? null : read.parsed();
    }

    /**
     * The RAW (line-numbered) body of the qualifying complete read for
     * the path, under the same qualification rules as
     * {@link #completeReadBodyForPath}. The T1011 recovery overlay uses it
     * to locate the real read tool-result message, which carries the file
     * bytes so they never occupy a system-role message.
     */
    static String completeReadRawBodyForPath(LoopState state, String pathHint) {
        QualifiedRead read = qualifiedCompleteRead(state, pathHint);
        return read == null ? null : read.raw();
    }

    private record QualifiedRead(String raw, String parsed) {}

    private static QualifiedRead qualifiedCompleteRead(LoopState state, String pathHint) {
        if (state == null || pathHint == null || pathHint.isBlank()) return null;
        ReadEvidence read = state.readEvidence.latestCompleteReadFileForPath(pathHint, true);
        if (read == null) return null;
        String parsed = parseCompleteReadFileBody(read.body());
        if (parsed == null) return null;
        return new QualifiedRead(read.body(), parsed);
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

    /** Read basis for disk-vs-readback agreement: LF-normalized, forced terminal newline. */
    private static String normalizedForComparison(String value) {
        String normalized = normalizeLineEndings(value);
        return normalized.endsWith("\n") || normalized.isEmpty() ? normalized : normalized + "\n";
    }

    private static final long MAX_STEERING_DISK_BYTES = 1024 * 1024L;

    private static String currentDiskTextForSteering(LoopState state, String pathHint) {
        if (state == null || state.workspace == null || pathHint == null || pathHint.isBlank()) {
            return null;
        }
        try {
            java.nio.file.Path root = state.workspace.normalize();
            java.nio.file.Path target = root.resolve(pathHint.strip()).normalize();
            if (!target.startsWith(root)) return null;
            if (!java.nio.file.Files.isRegularFile(target)) return null;
            if (java.nio.file.Files.size(target) > MAX_STEERING_DISK_BYTES) return null;
            return java.nio.file.Files.readString(target);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Appends one line to the exact disk text: the new line takes the
     * file's own separator style (uniform ending, or the last existing
     * ending when the file mixes styles, or LF when the file has none)
     * and the terminal-newline state is preserved as-is.
     */
    private static String appendPreservingDiskFormat(String disk, String line) {
        if (disk == null || line == null) return null;
        String ending = lastLineEnding(disk);
        boolean terminal = !disk.isEmpty()
                && (disk.charAt(disk.length() - 1) == '\n' || disk.charAt(disk.length() - 1) == '\r');
        if (disk.isEmpty()) {
            return line + "\n";
        }
        if (terminal) {
            return disk + line + ending;
        }
        return disk + ending + line;
    }

    private static String lastLineEnding(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n') {
                return i > 0 && text.charAt(i - 1) == '\r' ? "\r\n" : "\n";
            }
            if (c == '\r') {
                return "\r";
            }
        }
        return "\n";
    }
}
