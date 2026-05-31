package dev.talos.runtime.trace;

import dev.talos.runtime.verification.VerificationReport;

import java.time.Instant;
import java.util.LinkedHashMap;
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

    static void record(
            LocalTurnTrace.Builder builder,
            String status,
            String summary,
            List<String> problems,
            VerificationReport report
    ) {
        if (builder == null) return;
        VerificationReport safeReport = report == null ? VerificationReport.empty() : report;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", safe(status));
        data.put("problemCount", problems == null ? 0 : problems.size());
        data.put("requiredClaimCount", safeReport.requiredClaimCount());
        data.put("unsatisfiedRequiredClaimCount", safeReport.unsatisfiedRequiredClaimCount());
        data.put("authoritativeProofKinds", safeReport.authoritativeProofKinds());
        builder.event(TurnTraceEvent.simple("VERIFICATION_COMPLETED", Instant.now().toString(), data));
        builder.verification(
                status,
                summary,
                problems,
                safeReport.requiredClaimCount(),
                safeReport.unsatisfiedRequiredClaimCount(),
                safeReport.authoritativeProofKinds(),
                safeReport.limitations());
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
