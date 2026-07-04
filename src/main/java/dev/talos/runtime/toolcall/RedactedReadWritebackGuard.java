package dev.talos.runtime.toolcall;

import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RedactedReadWritebackGuard {
    private static final List<String> WRITE_PAYLOAD_KEYS = List.of(
            "content", "text", "body", "data", "file_content");
    private static final List<String> EDIT_PAYLOAD_KEYS = List.of(
            "old_string", "oldString", "old_text", "search", "find", "original",
            "new_string", "newString", "new_text", "replace", "replacement");
    private static final List<String> REDACTION_SENTINELS = List.of(
            ProtectedContentPolicy.REDACTED_VALUE,
            ProtectedContentPolicy.REDACTED_CANARY,
            ProtectedContentPolicy.REDACTED_PRIVATE_DOCUMENT_CANARY,
            ProtectedContentPolicy.REDACTED_PATH,
            "[protected tool result redacted by prompt-debug policy]",
            "withheld from model context by privacy policy");

    private RedactedReadWritebackGuard() {}

    static String diagnostic(ToolCall call, LoopState state, String pathHint) {
        if (call == null || state == null || pathHint == null || pathHint.isBlank()) {
            return null;
        }
        String canonicalTool = ToolAliasPolicy.localCanonicalName(call.toolName());
        if (!"write_file".equals(canonicalTool) && !"edit_file".equals(canonicalTool)) {
            return null;
        }
        if (!hasSameTurnRedactedReadForPath(state, pathHint)) {
            return null;
        }
        Set<String> payloadSentinels = mutationPayloadSentinels(call, canonicalTool);
        if (payloadSentinels.isEmpty()) {
            return null;
        }
        if (literalSentinelsWereRequested(state) && currentFileContainsAll(state, pathHint, payloadSentinels)) {
            return null;
        }
        String path = ToolCallSupport.canonicalizeReadPath(pathHint);
        return "Refusing to write Talos redaction placeholders back into `" + path
                + "` after a redacted same-turn read. The replacement payload contains runtime redaction text, "
                + "so it is not safe full-file source. Use a targeted edit/append that preserves original bytes, "
                + "or retry after non-redacted evidence is available. No approval was requested and no file was changed.";
    }

    private static Set<String> mutationPayloadSentinels(ToolCall call, String canonicalTool) {
        List<String> keys = "edit_file".equals(canonicalTool) ? EDIT_PAYLOAD_KEYS : WRITE_PAYLOAD_KEYS;
        Set<String> sentinels = new LinkedHashSet<>();
        for (String key : keys) {
            String value = call.param(key);
            if (value == null || value.isBlank()) continue;
            for (String sentinel : REDACTION_SENTINELS) {
                if (value.contains(sentinel)) {
                    sentinels.add(sentinel);
                }
            }
        }
        return sentinels;
    }

    private static boolean hasSameTurnRedactedReadForPath(LoopState state, String pathHint) {
        String target = ToolCallSupport.canonicalizeReadPath(pathHint);
        if (target.isBlank()) return false;
        for (Map.Entry<String, String> entry : state.readFileBodiesThisTurn.entrySet()) {
            if (samePath(entry.getKey(), target) && containsRedactionSentinel(entry.getValue())) {
                return true;
            }
        }
        for (Map.Entry<String, String> entry : state.successfulReadCallBodies.entrySet()) {
            if (readSignatureMatchesPath(entry.getKey(), target)
                    && containsRedactionSentinel(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsRedactionSentinel(String value) {
        if (value == null || value.isBlank()) return false;
        for (String sentinel : REDACTION_SENTINELS) {
            if (value.contains(sentinel)) {
                return true;
            }
        }
        return false;
    }

    private static boolean readSignatureMatchesPath(String signature, String target) {
        if (signature == null || target == null || target.isBlank()) return false;
        int separator = signature.indexOf(':');
        if (separator <= 0) return false;
        String toolName = signature.substring(0, separator);
        if (!"read_file".equals(ToolAliasPolicy.localCanonicalName(toolName))) {
            return false;
        }
        String pathMarker = "path=";
        int start = signature.indexOf(pathMarker, separator + 1);
        if (start < 0) return false;
        start += pathMarker.length();
        int end = signature.indexOf(';', start);
        String path = end >= 0 ? signature.substring(start, end) : signature.substring(start);
        return samePath(path, target);
    }

    private static boolean samePath(String left, String right) {
        return ToolCallSupport.canonicalizeReadPath(left)
                .equalsIgnoreCase(ToolCallSupport.canonicalizeReadPath(right));
    }

    private static boolean literalSentinelsWereRequested(LoopState state) {
        String request = ToolCallSupport.latestUserRequestIn(state.messages);
        return containsRedactionSentinel(request);
    }

    private static boolean currentFileContainsAll(LoopState state, String pathHint, Set<String> sentinels) {
        if (state == null || state.workspace == null || pathHint == null || pathHint.isBlank()
                || sentinels == null || sentinels.isEmpty()) {
            return false;
        }
        try {
            Path resolved = state.workspace.resolve(pathHint).normalize();
            if (state.ctx != null && !state.ctx.sandbox().allowedPath(resolved)) {
                return false;
            }
            if (!Files.isRegularFile(resolved)) {
                return false;
            }
            String current = Files.readString(resolved);
            for (String sentinel : sentinels) {
                if (!current.contains(sentinel)) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }
}
