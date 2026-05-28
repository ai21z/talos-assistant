package dev.talos.runtime.trace;

import java.time.Instant;
import java.util.Map;

final class RepairTraceRecorder {
    private RepairTraceRecorder() {}

    static void record(LocalTurnTrace.Builder builder, String status, String summary) {
        if (builder == null) return;
        String safeStatus = safe(status);
        String safeSummary = safe(summary);
        builder.repair(safeStatus, safeSummary);
        builder.event(TurnTraceEvent.simple("REPAIR_DECISION_RECORDED", Instant.now().toString(), Map.of(
                "status", safeStatus,
                "summary", safeSummary)));
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
