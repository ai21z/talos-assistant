package dev.talos.runtime.trace;

import dev.talos.tools.ToolCall;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds trace events for pre-approval markdown-commentary sanitization of
 * write/edit content parameters (T755). Stores summaries only (hash, bytes,
 * lines, stripped char count) — never the raw content.
 */
final class ContentSanitizationTraceEventFactory {
    private ContentSanitizationTraceEventFactory() {}

    static TurnTraceEvent sanitized(
            String phase,
            ToolCall call,
            String key,
            int strippedChars,
            String beforeValue,
            String afterValue
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", key == null ? "" : key.strip());
        data.put("strippedChars", strippedChars);
        data.put("beforeHash", TraceRedactor.hash(beforeValue));
        data.put("beforeBytes", TraceRedactor.bytes(beforeValue));
        data.put("beforeLines", TraceRedactor.lines(beforeValue));
        data.put("afterHash", TraceRedactor.hash(afterValue));
        data.put("afterBytes", TraceRedactor.bytes(afterValue));
        data.put("afterLines", TraceRedactor.lines(afterValue));
        return new TurnTraceEvent(
                "TOOL_CONTENT_SANITIZED",
                Instant.now().toString(),
                phase == null ? "" : phase,
                call == null ? "" : call.toolName(),
                data);
    }
}
