package dev.talos.runtime.trace;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class ActionObligationTraceEventFactory {
    private ActionObligationTraceEventFactory() {}

    static TurnTraceEvent evaluated(String obligation, String status, String reason) {
        return evaluated(obligation, status, reason, "");
    }

    static TurnTraceEvent evaluated(
            String obligation,
            String status,
            String reason,
            String failureKind
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("obligation", safe(obligation));
        data.put("status", safe(status));
        data.put("reason", safe(reason));
        if (failureKind != null && !failureKind.isBlank()) {
            data.put("failureKind", failureKind.strip());
        }
        return TurnTraceEvent.simple("ACTION_OBLIGATION_EVALUATED", Instant.now().toString(), data);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
