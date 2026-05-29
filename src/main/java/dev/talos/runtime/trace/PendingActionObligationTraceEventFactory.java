package dev.talos.runtime.trace;

import java.time.Instant;
import java.util.List;
import java.util.Map;

final class PendingActionObligationTraceEventFactory {
    private PendingActionObligationTraceEventFactory() {}

    static TurnTraceEvent evaluated(
            String status,
            String kind,
            List<String> targets,
            String reason
    ) {
        String safeStatus = safe(status);
        String eventType = switch (safeStatus) {
            case "RAISED" -> "PENDING_ACTION_OBLIGATION_RAISED";
            case "BREACHED" -> "PENDING_ACTION_OBLIGATION_BREACHED";
            default -> "PENDING_ACTION_OBLIGATION_EVALUATED";
        };
        return TurnTraceEvent.simple(eventType, Instant.now().toString(), Map.of(
                "status", safeStatus,
                "kind", safe(kind),
                "targets", targets == null ? List.of() : List.copyOf(targets),
                "reason", safe(reason)));
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
