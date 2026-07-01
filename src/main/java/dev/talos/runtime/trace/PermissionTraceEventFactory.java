package dev.talos.runtime.trace;

import dev.talos.tools.ToolCall;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds permission decision trace events without exposing raw tool payloads. */
final class PermissionTraceEventFactory {
    private PermissionTraceEventFactory() {}

    static TurnTraceEvent decision(
            String phase,
            ToolCall call,
            String action,
            String reasonCode,
            String relativePath,
            boolean protectedPath,
            String protectedKind,
            boolean rememberEligible
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", safe(action));
        data.put("reasonCode", safe(reasonCode));
        data.put("rememberEligible", rememberEligible);
        data.put("protectedPath", protectedPath);
        if (protectedPath && protectedKind != null && !protectedKind.isBlank()) {
            data.put("protectedKind", protectedKind.strip());
        }
        if (relativePath != null && !relativePath.isBlank()) {
            data.put("pathHint", TraceRedactor.pathHint(relativePath));
        }
        return new TurnTraceEvent(
                "PERMISSION_DECISION",
                Instant.now().toString(),
                phase == null ? "" : phase,
                call == null ? "" : call.toolName(),
                data);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
