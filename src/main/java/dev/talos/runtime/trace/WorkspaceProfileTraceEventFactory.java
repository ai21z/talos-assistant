package dev.talos.runtime.trace;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds trace events for workspace verification-profile declaration changes. */
final class WorkspaceProfileTraceEventFactory {
    private WorkspaceProfileTraceEventFactory() {}

    static TurnTraceEvent declarationConfigured(
            String profileId,
            String declarationSha256,
            String approval,
            String trustStateAfter,
            String checkpointStatus,
            String checkpointId,
            boolean replacedExisting
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profileId", safe(profileId));
        data.put("declarationSha256", safe(declarationSha256));
        data.put("approval", safe(approval));
        data.put("trustStateAfter", safe(trustStateAfter));
        data.put("checkpointStatus", safe(checkpointStatus));
        data.put("checkpointId", safe(checkpointId));
        data.put("replacedExisting", replacedExisting);
        return TurnTraceEvent.simple(
                "WORKSPACE_PROFILE_DECLARATION_CONFIGURED",
                Instant.now().toString(),
                data);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
