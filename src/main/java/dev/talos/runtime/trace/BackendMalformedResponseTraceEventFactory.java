package dev.talos.runtime.trace;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds backend malformed response trace events without storing raw response bodies. */
final class BackendMalformedResponseTraceEventFactory {
    private BackendMalformedResponseTraceEventFactory() {}

    static TurnTraceEvent captured(String context, String bodyHash, int bodyChars) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("context", safe(context));
        data.put("bodyHash", safe(bodyHash));
        data.put("bodyChars", Math.max(0, bodyChars));
        return TurnTraceEvent.simple(
                "BACKEND_MALFORMED_RESPONSE_CAPTURED",
                Instant.now().toString(),
                data);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
