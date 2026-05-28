package dev.talos.runtime.trace;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds exact literal write correction trace events without storing raw payload content. */
final class ExactLiteralWriteCorrectionTraceEventFactory {
    private ExactLiteralWriteCorrectionTraceEventFactory() {}

    static TurnTraceEvent corrected(
            String path,
            String sourcePattern,
            String expectedHash,
            int expectedBytes,
            int expectedLines,
            String observedHash,
            int observedBytes,
            int observedLines
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pathHint", TraceRedactor.pathHint(path));
        data.put("sourcePattern", safe(sourcePattern));
        data.put("expectedHash", safe(expectedHash));
        data.put("expectedBytes", Math.max(0, expectedBytes));
        data.put("expectedLines", Math.max(0, expectedLines));
        data.put("observedHash", safe(observedHash));
        data.put("observedBytes", Math.max(0, observedBytes));
        data.put("observedLines", Math.max(0, observedLines));
        return TurnTraceEvent.simple("EXACT_LITERAL_WRITE_CORRECTED", Instant.now().toString(), data);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
