package dev.talos.runtime.trace;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Builds protected-read postcondition trace events without exposing raw protected paths. */
final class ProtectedReadPostconditionTraceEventFactory {
    private ProtectedReadPostconditionTraceEventFactory() {}

    static TurnTraceEvent checked(String status, List<String> paths, String reason) {
        List<String> pathHints = paths == null
                ? List.of()
                : paths.stream()
                        .map(TraceRedactor::pathHint)
                        .toList();
        return TurnTraceEvent.simple("PROTECTED_READ_POSTCONDITION_CHECKED", Instant.now().toString(), Map.of(
                "status", safe(status),
                "pathHints", pathHints,
                "reason", safe(reason)));
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
