package dev.talos.runtime.toolcall;

import dev.talos.runtime.task.TaskContract;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;

import java.util.Locale;
import java.util.regex.Pattern;

final class NamedTargetExistenceGuard {
    private static final Pattern FUNCTION_TARGET_IN_FILE = Pattern.compile(
            "(?i)\\b(?:modify|change|update|fix|repair|edit)\\s+"
                    + "(?:(?:the|a|an|this|that|existing|current|broken|target|requested)\\s+){0,5}"
                    + "(?:(?:function|method|def|routine|symbol)\\s+)?`?"
                    + "([A-Za-z_][A-Za-z0-9_]*)\\s*\\(\\)\\s*`?\\s+"
                    + "(?:in|inside|within)\\s+(?:the\\s+)?(?:file\\s+)?`?"
                    + "([^`\\s,;:!?]+\\.[A-Za-z0-9]{1,12})`?");

    private NamedTargetExistenceGuard() {}

    static String diagnostic(
            ToolCall call,
            LoopState state,
            TaskContract contract,
            String pathHint
    ) {
        if (call == null || state == null || contract == null || pathHint == null || pathHint.isBlank()) {
            return null;
        }
        if (!isFileMutationTool(call.toolName())) return null;

        NamedFunctionTarget target = namedFunctionTarget(contract.originalUserRequest());
        if (target == null) return null;

        String actualPath = ToolCallSupport.normalizePath(pathHint);
        if (!samePath(actualPath, target.path())) return null;

        String readback = latestCompleteReadbackForPath(state, target.path());
        if (readback == null) {
            return "Named target `" + target.name() + "()` in `" + target.path()
                    + "` requires complete same-turn read evidence before approval. "
                    + "No approval was requested and no file was changed.";
        }
        if (functionExists(target, readback)) return null;

        return "Named target `" + target.name() + "()` was not found in `" + target.path()
                + "`. Do not mutate another function or rewrite the file. "
                + "No approval was requested and no file was changed.";
    }

    private static boolean isFileMutationTool(String toolName) {
        String canonical = ToolAliasPolicy.localCanonicalName(toolName);
        return "write_file".equals(canonical) || "edit_file".equals(canonical);
    }

    private static NamedFunctionTarget namedFunctionTarget(String request) {
        if (request == null || request.isBlank()) return null;
        var matcher = FUNCTION_TARGET_IN_FILE.matcher(request);
        if (!matcher.find()) return null;
        String name = matcher.group(1);
        String path = ToolCallSupport.canonicalizeReadPath(matcher.group(2));
        if (name == null || name.isBlank() || path.isBlank()) return null;
        return new NamedFunctionTarget(name, path);
    }

    private static boolean samePath(String left, String right) {
        return ToolCallSupport.canonicalizeReadPath(left)
                .equalsIgnoreCase(ToolCallSupport.canonicalizeReadPath(right));
    }

    private static String latestCompleteReadbackForPath(LoopState state, String path) {
        if (state == null || path == null || path.isBlank()) return null;
        String target = ToolCallSupport.canonicalizeReadPath(path);
        if (target.isBlank()) return null;

        String out = null;
        for (var entry : state.successfulReadCallBodies.entrySet()) {
            String signature = entry.getKey();
            if (!readSignatureIsCompleteReadForPath(signature, target)) continue;
            String parsed = parseCompleteReadFileBody(entry.getValue());
            if (parsed != null) {
                out = parsed;
            }
        }
        if (out != null) return out;

        String body = state.readFileBodiesThisTurn.get(target);
        if (body == null) {
            body = state.readFileBodiesThisTurn.get(ToolCallSupport.normalizePath(target));
        }
        return parseCompleteReadFileBody(body);
    }

    private static boolean readSignatureIsCompleteReadForPath(String signature, String target) {
        if (signature == null || target == null || target.isBlank()) return false;
        int separator = signature.indexOf(':');
        if (separator <= 0) return false;
        String toolName = signature.substring(0, separator);
        return "read_file".equals(ToolAliasPolicy.localCanonicalName(toolName))
                && signature.contains("path=" + target + ";")
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
        boolean sawLinePrefixed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i == lines.length - 1 && line.isEmpty()) {
                continue;
            }
            int sep = line.indexOf(" | ");
            if (sep > 0 && allDigits(line.substring(0, sep))) {
                out.append(line.substring(sep + 3)).append('\n');
                sawLinePrefixed = true;
                continue;
            }
            if (sawLinePrefixed) return null;
        }
        return sawLinePrefixed ? out.toString() : normalized;
    }

    private static boolean functionExists(NamedFunctionTarget target, String content) {
        if (target == null || content == null) return false;
        String escaped = Pattern.quote(target.name());
        String path = target.path().toLowerCase(Locale.ROOT);
        if (path.endsWith(".py")) {
            return Pattern.compile("(?m)^\\s*(?:async\\s+)?def\\s+" + escaped + "\\s*\\(")
                    .matcher(content)
                    .find();
        }
        return Pattern.compile("\\b" + escaped + "\\s*\\(")
                .matcher(content)
                .find();
    }

    private static boolean allDigits(String value) {
        if (value == null || value.isBlank()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }

    private record NamedFunctionTarget(String name, String path) {}
}
