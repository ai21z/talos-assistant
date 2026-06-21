package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class ReadDisplayWriteContainmentGuard {
    private static final Pattern READ_DISPLAY_LINE = Pattern.compile("^\\s*\\d+\\s\\|\\s?.*$");
    private static final List<String> WRITE_PAYLOAD_KEYS = List.of(
            "content", "text", "body", "data", "file_content");
    private static final List<String> EDIT_REPLACEMENT_KEYS = List.of(
            "new_string", "newString", "new_text", "replace", "replacement");

    private ReadDisplayWriteContainmentGuard() {}

    static String diagnostic(ToolCall call, LoopState state, String pathHint) {
        if (call == null || state == null || pathHint == null || pathHint.isBlank()) {
            return null;
        }
        String canonicalTool = ToolAliasPolicy.localCanonicalName(call.toolName());
        if (!"write_file".equals(canonicalTool) && !"edit_file".equals(canonicalTool)) {
            return null;
        }
        if (!hasSameTurnReadDisplayForPath(state, pathHint)) {
            return null;
        }
        if (!mutationPayloadCarriesReadDisplayPrefix(call, canonicalTool)) {
            return null;
        }
        String path = ToolCallSupport.canonicalizeReadPath(pathHint);
        return "Refusing to write Talos read-display line prefixes back into `"
                + path + "`. The payload contains lines like `N | ...` from a same-turn read_file display. "
                + "Remove the line-number prefixes and retry. No approval was requested and no file was changed.";
    }

    private static boolean mutationPayloadCarriesReadDisplayPrefix(ToolCall call, String canonicalTool) {
        List<String> keys = "edit_file".equals(canonicalTool) ? EDIT_REPLACEMENT_KEYS : WRITE_PAYLOAD_KEYS;
        for (String key : keys) {
            String value = call.param(key);
            if (containsReadDisplayLine(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSameTurnReadDisplayForPath(LoopState state, String pathHint) {
        String target = ToolCallSupport.canonicalizeReadPath(pathHint);
        if (target.isBlank()) return false;

        for (Map.Entry<String, String> entry : state.readFileBodiesThisTurn.entrySet()) {
            if (samePath(entry.getKey(), target) && containsReadDisplayLine(entry.getValue())) {
                return true;
            }
        }
        for (Map.Entry<String, String> entry : state.successfulReadCallBodies.entrySet()) {
            if (readSignatureMatchesPath(entry.getKey(), target) && containsReadDisplayLine(entry.getValue())) {
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

    private static boolean containsReadDisplayLine(String value) {
        if (value == null || value.isBlank()) return false;
        String[] lines = value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String line : lines) {
            if (READ_DISPLAY_LINE.matcher(line).matches()) {
                return true;
            }
        }
        return false;
    }
}
