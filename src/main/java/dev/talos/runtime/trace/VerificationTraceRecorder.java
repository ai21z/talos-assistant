package dev.talos.runtime.trace;

import java.time.Instant;
import java.util.List;
import java.util.Map;

final class VerificationTraceRecorder {
    private VerificationTraceRecorder() {}

    static void record(LocalTurnTrace.Builder builder, String status, String summary, List<String> problems) {
        if (builder == null) return;
        builder.event(TurnTraceEvent.simple("VERIFICATION_COMPLETED", Instant.now().toString(), Map.of(
                "status", safe(status),
                "problemCount", problems == null ? 0 : problems.size())));
        builder.verification(status, summary, problems);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
