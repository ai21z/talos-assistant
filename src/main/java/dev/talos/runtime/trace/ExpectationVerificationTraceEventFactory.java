package dev.talos.runtime.trace;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class ExpectationVerificationTraceEventFactory {
    private ExpectationVerificationTraceEventFactory() {}

    static TurnTraceEvent verified(
            String kind,
            String status,
            String pathHint,
            String sourcePattern,
            String expectedHash,
            int expectedBytes,
            int expectedChars,
            int expectedLines,
            String observedHash,
            int observedBytes,
            int observedChars,
            int observedLines
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", safe(kind));
        data.put("status", safe(status));
        data.put("pathHint", TraceRedactor.pathHint(pathHint));
        data.put("sourcePattern", safe(sourcePattern));
        data.put("expectedHash", safe(expectedHash));
        data.put("expectedBytes", Math.max(0, expectedBytes));
        data.put("expectedChars", Math.max(0, expectedChars));
        data.put("expectedLines", Math.max(0, expectedLines));
        data.put("observedHash", safe(observedHash));
        data.put("observedBytes", Math.max(0, observedBytes));
        data.put("observedChars", Math.max(0, observedChars));
        data.put("observedLines", Math.max(0, observedLines));
        return TurnTraceEvent.simple("EXPECTATION_VERIFIED", Instant.now().toString(), data);
    }

    /** Counts and kinds only. Neither the request nor the replacement texts enter the event. */
    static TurnTraceEvent replacementOccurrenceScope(
            String pathHint,
            String sourcePattern,
            String scopeKind,
            int priorOccurrences,
            int remainingOccurrences,
            boolean landed,
            String substitution
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pathHint", TraceRedactor.pathHint(pathHint));
        data.put("sourcePattern", safe(sourcePattern));
        data.put("scopeKind", safe(scopeKind));
        data.put("priorOccurrences", Math.max(0, priorOccurrences));
        data.put("remainingOccurrences", Math.max(0, remainingOccurrences));
        data.put("landed", landed);
        data.put("substitution", safe(substitution));
        return TurnTraceEvent.simple("REPLACEMENT_OCCURRENCE_SCOPE_RECORDED", Instant.now().toString(), data);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
