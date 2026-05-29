package dev.talos.runtime.trace;

import java.time.Instant;
import java.util.Map;

final class OutcomeTraceRecorder {
    private OutcomeTraceRecorder() {}

    static void record(
            LocalTurnTrace.Builder builder,
            String status,
            String verificationStatus,
            String approvalStatus,
            String mutationStatus,
            String classification
    ) {
        if (builder == null) return;
        builder.outcome(status, verificationStatus, approvalStatus, mutationStatus, classification);
        builder.event(TurnTraceEvent.simple("OUTCOME_RENDERED", Instant.now().toString(), Map.of(
                "status", safe(status),
                "classification", safe(classification))));
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
