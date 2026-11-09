package dev.talos.runtime.trace;

import java.time.Instant;
import java.util.LinkedHashMap;
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
        return evaluated(status, kind, targets, List.of(), reason);
    }

    /** {@code changedTargets} is written only when it holds a path, so every other event keeps its shape. */
    static TurnTraceEvent evaluated(
            String status,
            String kind,
            List<String> targets,
            List<String> changedTargets,
            String reason
    ) {
        String safeStatus = safe(status);
        String eventType = switch (safeStatus) {
            case "RAISED" -> "PENDING_ACTION_OBLIGATION_RAISED";
            case "BREACHED" -> "PENDING_ACTION_OBLIGATION_BREACHED";
            default -> "PENDING_ACTION_OBLIGATION_EVALUATED";
        };
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", safeStatus);
        data.put("kind", safe(kind));
        data.put("targets", targets == null ? List.of() : List.copyOf(targets));
        if (changedTargets != null && !changedTargets.isEmpty()) {
            data.put("changedTargets", List.copyOf(changedTargets));
        }
        data.put("reason", safe(reason));
        return TurnTraceEvent.simple(eventType, Instant.now().toString(), data);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
