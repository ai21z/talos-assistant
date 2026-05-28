package dev.talos.runtime.trace;

import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContentMetadata;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds private-document model-handoff trace events without storing raw document text. */
final class PrivateDocumentHandoffTraceEventFactory {
    private PrivateDocumentHandoffTraceEventFactory() {}

    static TurnTraceEvent approvalRequired(String phase, ToolCall call, ToolContentMetadata metadata) {
        return approval(
                "PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_REQUIRED",
                phase,
                call,
                metadata,
                false);
    }

    static TurnTraceEvent approvalGranted(
            String phase,
            ToolCall call,
            ToolContentMetadata metadata,
            boolean rememberIgnored
    ) {
        return approval(
                "PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_GRANTED",
                phase,
                call,
                metadata,
                rememberIgnored);
    }

    static TurnTraceEvent approvalDenied(String phase, ToolCall call, ToolContentMetadata metadata) {
        return approval(
                "PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_DENIED",
                phase,
                call,
                metadata,
                false);
    }

    private static TurnTraceEvent approval(
            String eventType,
            String phase,
            ToolCall call,
            ToolContentMetadata metadata,
            boolean rememberIgnored
    ) {
        Map<String, Object> data = new LinkedHashMap<>(TurnTraceEvent.toolPayloadSummary(call));
        data.put("scope", "SEND_TO_MODEL_CONTEXT");
        data.put("perTurn", true);
        data.put("rememberIgnored", rememberIgnored);
        if (metadata != null) {
            data.put("privacyClass", metadata.privacyClass().name());
            data.put("source", metadata.source().name());
            data.put("rawArtifactPersistenceAllowed", metadata.rawArtifactPersistenceAllowed());
            data.put("ragIndexAllowed", metadata.ragIndexAllowed());
            data.put("decisionReason", safe(metadata.decisionReason()));
            if (metadata.sourcePath() != null && !metadata.sourcePath().isBlank()) {
                data.put("pathHint", TraceRedactor.pathHint(metadata.sourcePath()));
            }
        }
        return new TurnTraceEvent(
                eventType,
                Instant.now().toString(),
                phase == null ? "" : phase,
                call == null ? "" : call.toolName(),
                data);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
