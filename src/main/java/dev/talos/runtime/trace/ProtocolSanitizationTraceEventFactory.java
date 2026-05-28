package dev.talos.runtime.trace;

import java.time.Instant;
import java.util.Map;

/** Builds protocol sanitization trace events. */
final class ProtocolSanitizationTraceEventFactory {
    private ProtocolSanitizationTraceEventFactory() {}

    static TurnTraceEvent sanitized(String reason) {
        return TurnTraceEvent.simple("PROTOCOL_SANITIZED", Instant.now().toString(), Map.of(
                "reason", safe(reason)));
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
