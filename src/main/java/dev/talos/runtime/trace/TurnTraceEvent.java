package dev.talos.runtime.trace;

import dev.talos.tools.ToolCall;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One redacted event in a local turn trace.
 *
 * <p>The event payload intentionally stores summaries rather than raw prompts,
 * file contents, or tool payloads in the default redaction mode.
 */
public record TurnTraceEvent(
        String type,
        String timestamp,
        String phase,
        String toolName,
        Map<String, Object> data
) {
    public TurnTraceEvent {
        type = type == null || type.isBlank() ? "UNKNOWN" : type;
        timestamp = timestamp == null ? "" : timestamp;
        phase = phase == null ? "" : phase;
        toolName = toolName == null ? "" : toolName;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static TurnTraceEvent simple(String type, String timestamp, Map<String, Object> data) {
        return new TurnTraceEvent(type, timestamp, "", "", data);
    }

    public static TurnTraceEvent toolCallParsed(String timestamp, String phase, ToolCall call) {
        return toolCallEvent("TOOL_CALL_PARSED", timestamp, phase, call, Map.of());
    }

    public static TurnTraceEvent toolCallBlocked(String timestamp, String phase, ToolCall call, String reason) {
        return toolCallEvent("TOOL_CALL_BLOCKED", timestamp, phase, call, Map.of("reason", safe(reason)));
    }

    public static TurnTraceEvent toolExecuted(String timestamp, String phase, ToolCall call, boolean success, String reason) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("success", success);
        if (reason != null && !reason.isBlank()) extra.put("reason", reason.strip());
        return toolCallEvent("TOOL_EXECUTED", timestamp, phase, call, extra);
    }

    public static TurnTraceEvent approval(String type, String timestamp, String phase, ToolCall call) {
        return toolCallEvent(type, timestamp, phase, call, Map.of());
    }

    private static TurnTraceEvent toolCallEvent(
            String type,
            String timestamp,
            String phase,
            ToolCall call,
            Map<String, Object> extra
    ) {
        Map<String, Object> data = toolPayloadSummary(call);
        data.putAll(extra);
        return new TurnTraceEvent(type, timestamp, phase, call == null ? "" : call.toolName(), data);
    }

    static Map<String, Object> toolPayloadSummary(ToolCall call) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (call == null || call.parameters() == null || call.parameters().isEmpty()) {
            out.put("parameterNames", java.util.List.of());
            return out;
        }
        java.util.List<String> names = call.parameters().keySet().stream()
                .sorted()
                .toList();
        out.put("parameterNames", names);

        String path = first(call, "path", "file_path", "filepath", "file", "filename", "from", "to");
        if (path != null && !path.isBlank()) {
            out.put("pathHint", TraceRedactor.pathHint(path));
        }

        summarizeTextParam(out, "content", first(call, "content", "text", "body", "data", "file_content"));
        summarizeTextParam(out, "oldString", first(call, "old_string", "oldString", "old_text", "search", "find", "original"));
        summarizeTextParam(out, "newString", first(call, "new_string", "newString", "new_text", "replace", "replacement"));
        return out;
    }

    private static void summarizeTextParam(Map<String, Object> out, String label, String value) {
        if (value == null) return;
        out.put(label + "Hash", TraceRedactor.hash(value));
        out.put(label + "Bytes", TraceRedactor.bytes(value));
        out.put(label + "Lines", TraceRedactor.lines(value));
    }

    private static String first(ToolCall call, String... keys) {
        for (String key : keys) {
            String value = call.param(key);
            if (value != null) return value;
        }
        return null;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
