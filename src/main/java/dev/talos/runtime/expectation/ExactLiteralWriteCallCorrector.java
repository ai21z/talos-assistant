package dev.talos.runtime.expectation;

import dev.talos.runtime.task.TaskContract;
import dev.talos.tools.ToolCall;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rewrites exact complete-file write calls to the runtime-parsed literal payload.
 *
 * <p>The model may still choose the target path and issue the mutating tool call,
 * but for an unambiguous current-turn exact-write contract the runtime-owned
 * parsed payload is the source of truth for the write content.
 */
public final class ExactLiteralWriteCallCorrector {
    private static final List<String> PATH_KEYS = List.of("path", "file_path", "filepath", "file", "filename");
    private static final List<String> CONTENT_KEYS = List.of("content", "text", "body", "data", "file_content");

    private ExactLiteralWriteCallCorrector() {}

    public record Correction(
            ToolCall call,
            boolean corrected,
            String targetPath,
            String sourcePattern,
            String expectedHash,
            int expectedBytes,
            int expectedLines,
            String observedHash,
            int observedBytes,
            int observedLines
    ) {
        public static Correction unchanged(ToolCall call) {
            return new Correction(call, false, "", "", "", 0, 0, "", 0, 0);
        }
    }

    public static Correction correct(ToolCall call, TaskContract contract) {
        if (call == null || !"talos.write_file".equals(call.canonicalToolName())) {
            return Correction.unchanged(call);
        }
        LiteralContentExpectation literal = literalExpectation(contract);
        if (literal == null) return Correction.unchanged(call);

        String path = resolve(call.parameters(), PATH_KEYS);
        if (!normalizePath(path).equals(literal.targetPath())) {
            return Correction.unchanged(call);
        }

        String contentKey = firstPresentKey(call.parameters(), CONTENT_KEYS);
        if (contentKey.isBlank()) return Correction.unchanged(call);

        String observed = call.parameters().get(contentKey);
        String expected = literal.expectedContent();
        if (expected.equals(observed)) return Correction.unchanged(call);

        Map<String, String> corrected = new LinkedHashMap<>(call.parameters());
        corrected.put(contentKey, expected);
        return new Correction(
                new ToolCall(call.toolName(), corrected),
                true,
                literal.targetPath(),
                literal.sourcePattern(),
                literal.expectedHash(),
                literal.expectedBytes(),
                literal.expectedLines(),
                LiteralContentExpectation.hash(observed),
                LiteralContentExpectation.byteCount(observed),
                LiteralContentExpectation.lineCount(observed));
    }

    private static LiteralContentExpectation literalExpectation(TaskContract contract) {
        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);
        if (expectations.size() != 1) return null;
        TaskExpectation expectation = expectations.getFirst();
        return expectation instanceof LiteralContentExpectation literal ? literal : null;
    }

    private static String resolve(Map<String, String> params, List<String> keys) {
        if (params == null || params.isEmpty()) return "";
        for (String key : keys) {
            String value = params.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String firstPresentKey(Map<String, String> params, List<String> keys) {
        if (params == null || params.isEmpty()) return "";
        for (String key : keys) {
            if (params.containsKey(key)) return key;
        }
        return "";
    }

    private static String normalizePath(String path) {
        String normalized = path == null ? "" : path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
