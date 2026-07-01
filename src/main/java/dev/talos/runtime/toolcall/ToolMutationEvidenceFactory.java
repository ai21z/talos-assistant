package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;

final class ToolMutationEvidenceFactory {
    private ToolMutationEvidenceFactory() {}

    static ToolMutationEvidence from(
            ToolCall call,
            LoopState state,
            String pathHint
    ) {
        if (call == null) {
            return ToolMutationEvidence.none();
        }
        String canonicalTool = ToolAliasPolicy.localCanonicalName(call.toolName());
        if ("write_file".equals(canonicalTool)) {
            String content = firstParam(call, "content", "text", "body", "data", "file_content");
            String previousContent = priorReadContentForPath(state, pathHint);
            if (content == null || previousContent == null) {
                return ToolMutationEvidence.none();
            }
            return ToolMutationEvidence.fullWriteReplacement(previousContent, content);
        }
        if (!"edit_file".equals(canonicalTool)) {
            return ToolMutationEvidence.none();
        }
        String oldString = firstParam(call,
                "old_string", "oldString", "old_text", "search", "find", "original");
        String newString = firstParam(call,
                "new_string", "newString", "new_text", "replace", "replacement");
        if (oldString == null || oldString.isEmpty() || newString == null) {
            return ToolMutationEvidence.none();
        }
        return ToolMutationEvidence.exactEdit(oldString, newString);
    }

    private static String priorReadContentForPath(LoopState state, String pathHint) {
        if (state == null || pathHint == null || pathHint.isBlank()) return null;
        String target = ToolCallSupport.canonicalizeReadPath(pathHint);
        if (target.isBlank() || state.successfulReadCallBodies.isEmpty()) return null;
        String out = null;
        for (var entry : state.successfulReadCallBodies.entrySet()) {
            String signature = entry.getKey();
            if (!readSignatureIsCompleteReadForPath(signature, target)) continue;
            String parsed = parseCompleteReadFileBody(entry.getValue());
            if (parsed != null) {
                out = parsed;
            }
        }
        return out;
    }

    private static boolean readSignatureIsCompleteReadForPath(String signature, String target) {
        if (signature == null || target == null || target.isBlank()) return false;
        String normalized = target.replace('\\', '/');
        int separator = signature.indexOf(':');
        if (separator <= 0) return false;
        String toolName = signature.substring(0, separator);
        return "read_file".equals(ToolAliasPolicy.localCanonicalName(toolName))
                && signature.contains("path=" + normalized + ";")
                && !signature.contains("offset=");
    }

    private static String parseCompleteReadFileBody(String body) {
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
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }

    private static String firstParam(ToolCall call, String... keys) {
        if (call == null || keys == null) return null;
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            String value = call.param(key);
            if (value != null) return value;
        }
        return null;
    }
}
