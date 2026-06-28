package dev.talos.runtime.toolcall;

import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ToolCallSupport {
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallSupport.class);
    public static final int KEEP_RECENT_TOOL_RESULTS = 2;

    // T757: the duplicate READ_ONLY_TOOLS/MUTATING_TOOLS name sets are gone.
    // Static name classification delegates to ToolAliasPolicy (the alias
    // policy's single canonical-name source); trust gates in TurnProcessor
    // read ToolOperationMetadata from the resolved tool, and name-only gate
    // callers use ToolMutationGate (fail-closed for unknown names).
    // PATH_REQUIRED_TOOLS stays: it is a path-repair concern, not gating
    // (future derivation from ToolOperationMetadata.pathRoles is possible).
    private static final Set<String> PATH_REQUIRED_TOOLS = Set.of(
            "write_file", "file_write", "writefile",
            "create_file", "file_create", "createfile",
            "edit_file", "file_edit", "editfile",
            "mkdir", "make_dir", "make_directory", "create_dir", "create_directory",
            "move_path", "move", "mv",
            "copy_path", "copy", "cp",
            "rename_path", "rename",
            "delete_path", "delete", "remove_path", "remove", "rm"
    );
    private static final List<String> PATH_PARAM_KEYS = List.of(
            "path", "file_path", "filepath", "file", "filename",
            "from", "to", "source", "source_path", "destination", "destination_path",
            "target", "dir", "directory"
    );

    private ToolCallSupport() {}

    public static List<ToolCall> convertNativeToolCalls(List<NativeToolCall> nativeCalls) {
        return NativeToolCallConverter.convert(nativeCalls);
    }

    public static String formatToolResult(ToolCall call, ToolResult result) {
        return ToolResultFormatter.formatToolResult(call, result);
    }

    public static String formatToolResult(ToolCall call, ToolResult result, boolean preserveSuccessOutput) {
        return ToolResultFormatter.formatToolResult(call, result, preserveSuccessOutput);
    }

    public static String extractVerificationSummary(String output) {
        return ToolResultFormatter.extractVerificationSummary(output);
    }

    public static String latestUserRequestIn(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if ("user".equals(m.role())) {
                String c = m.content();
                if (isSyntheticToolResultContent(c)) continue;
                return (c == null || c.isBlank()) ? null : c;
            }
        }
        return null;
    }

    public static String embeddedRetryTaskType(String content) {
        return embeddedLineValue(content, "Task type:");
    }

    public static String embeddedRetryUserRequest(String content) {
        String value = embeddedLineValue(content, "User request:");
        if (value == null || value.isBlank()) return null;
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.isBlank() ? null : value;
    }

    public static String effectiveUserRequestForRetryWrappedPrompt(String content) {
        String embedded = embeddedRetryUserRequest(content);
        return embedded == null || embedded.isBlank() ? content : embedded;
    }

    private static String embeddedLineValue(String content, String marker) {
        if (content == null || marker == null || marker.isBlank()) return null;
        int idx = content.indexOf(marker);
        if (idx < 0) return null;
        int start = idx + marker.length();
        int end = content.indexOf('\n', start);
        String line = end >= 0 ? content.substring(start, end) : content.substring(start);
        line = line.strip();
        return line.isBlank() ? null : line;
    }

    public static boolean isSyntheticToolResultContent(String content) {
        if (content == null) return false;
        String c = content.stripLeading();
        return c.startsWith("[tool_result:")
                || c.startsWith("[compacted:")
                || c.startsWith("[tool_result]");
    }

    public static String summarizeToolResult(String body) {
        String tool = "unknown";
        if (body.startsWith("[tool_result:")) {
            int close = body.indexOf(']');
            if (close > "[tool_result:".length()) {
                tool = body.substring("[tool_result:".length(), close).trim();
            }
        }
        boolean isError = body.contains("[error]");
        int len = body.length();
        return "[compacted: " + tool + (isError ? " error" : " result")
                + ", " + len + " chars - full output elided to keep context focused]";
    }

    public static String firstSentenceSummary(String output) {
        return ToolResultFormatter.firstSentenceSummary(output);
    }

    public static String buildCallSignature(ToolCall call) {
        String path = resolvePathHint(call);
        String oldStr = call.param("old_string");
        if (oldStr == null) oldStr = call.param("oldString");
        int oldHash = oldStr != null ? oldStr.hashCode() : 0;
        return call.toolName() + ":" + (path != null ? path : "") + ":" + oldHash;
    }

    public static boolean hasEmptyEditArguments(ToolCall call) {
        if (call == null || !isEditFileTool(call.toolName())) return false;
        String oldString = firstPresentParam(
                call,
                "old_string",
                "oldString",
                "old_text",
                "search",
                "find",
                "original");
        String newString = firstPresentParam(
                call,
                "new_string",
                "newString",
                "new_text",
                "replace",
                "replacement");
        boolean missingOldString = oldString == null || oldString.isBlank();
        boolean missingNewString = newString == null;
        return missingOldString || missingNewString;
    }

    private static String firstPresentParam(ToolCall call, String... keys) {
        if (call == null || keys == null) return null;
        for (String key : keys) {
            String value = call.param(key);
            if (value != null) return value;
        }
        return null;
    }

    public static String canonicalizeReadPath(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty() || ".".equals(p)) return ".";
        if (p.startsWith("./") && p.length() > 2) {
            p = p.substring(2);
        }
        return p;
    }

    public static boolean isReadOnlyTool(String toolName) {
        return ToolAliasPolicy.isReadOnly(toolName);
    }

    public static boolean isMutatingTool(String toolName) {
        return ToolAliasPolicy.isMutating(toolName);
    }

    private static boolean isEditFileTool(String toolName) {
        String normalized = ToolAliasPolicy.localCanonicalName(toolName);
        return "edit_file".equals(normalized)
                || "file_edit".equals(normalized)
                || "editfile".equals(normalized);
    }

    public static String buildReadCallSignature(ToolCall call) {
        var sb = new StringBuilder(call.toolName()).append(":");
        if (call.parameters() != null) {
            call.parameters().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append(e.getKey()).append("=")
                            .append(canonicalizeReadPath(e.getValue())).append(";"));
        }
        return sb.toString();
    }

    public static ToolCall repairMissingPath(ToolCall call) {
        if (!PATH_REQUIRED_TOOLS.contains(ToolAliasPolicy.localCanonicalName(call.toolName()))) {
            return call;
        }
        for (String key : PATH_PARAM_KEYS) {
            String v = call.param(key);
            if (v != null && !v.isBlank()) return call;
        }
        LOG.warn("{} call is missing required 'path' parameter. "
                + "Returning call as-is so the tool produces an error. "
                + "The model must provide the target file path explicitly.",
                SafeLogFormatter.value(call.toolName()));
        return call;
    }

    public static void compactOlderToolResultsInPlace(List<ChatMessage> messages) {
        if (messages == null || messages.size() < 4) return;
        List<Integer> toolResultIndices = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if ("tool".equals(messages.get(i).role())) {
                toolResultIndices.add(i);
            }
        }
        int keepFrom = toolResultIndices.size() - KEEP_RECENT_TOOL_RESULTS;
        if (keepFrom <= 0) return;
        for (int k = 0; k < keepFrom; k++) {
            int idx = toolResultIndices.get(k);
            ChatMessage m = messages.get(idx);
            String content = m.content();
            if (content == null || content.isBlank()) continue;
            if (content.startsWith("[compacted:")) continue;
            String summary = summarizeToolResult(content);
            messages.set(idx, ChatMessage.toolResult(m.toolCallId(), summary));
        }
    }

    public static String resolvePathHint(ToolCall call) {
        for (String key : List.of(
                "path", "file_path", "filepath", "file", "filename",
                "from", "to", "source", "source_path", "destination", "destination_path",
                "dir", "directory", "pattern")) {
            String v = call.param(key);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    public static String truncateForLog(String s) {
        if (s == null) return "null";
        return s.length() <= 80 ? s : s.substring(0, 77) + "...";
    }

    public static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }
}
