package dev.talos.runtime.trace;

import dev.talos.tools.ToolCall;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds tool path argument normalization trace events. */
final class PathArgumentNormalizationTraceEventFactory {
    private PathArgumentNormalizationTraceEventFactory() {}

    static TurnTraceEvent normalized(
            String phase,
            ToolCall call,
            String key,
            String rawPath,
            String normalizedPath
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", safe(key));
        data.put("rawPath", path(rawPath));
        data.put("normalizedPath", path(normalizedPath));
        return new TurnTraceEvent(
                "TOOL_PATH_ARGUMENT_NORMALIZED",
                Instant.now().toString(),
                phase == null ? "" : phase,
                call == null ? "" : call.toolName(),
                data);
    }

    private static String path(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
