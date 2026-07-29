package dev.talos.runtime.trace;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.toolcall.ToolMutationEvidence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Emits one closed, call-bound classification of a settled tool outcome. */
final class ToolOutcomeClassificationTraceEventFactory {
    static final String EVENT_TYPE = "TOOL_OUTCOME_CLASSIFIED";
    private static final Set<String> MUTATION_EVIDENCE_KINDS = Set.of(
            "NONE",
            "EXACT_EDIT_REPLACEMENT",
            "FULL_WRITE_REPLACEMENT");

    private ToolOutcomeClassificationTraceEventFactory() {}

    static TurnTraceEvent classified(
            ToolCallLoop.ToolOutcome outcome,
            int outcomeOrdinal
    ) {
        if (outcome == null || outcome.toolCallId().isBlank()) {
            throw new IllegalArgumentException(
                    "A classified tool outcome requires exact parsed-call identity");
        }
        if (outcomeOrdinal <= 0) {
            throw new IllegalArgumentException(
                    "A classified tool outcome requires a positive outcome ordinal");
        }
        String mutationEvidenceKind = mutationEvidenceKind(outcome.mutationEvidence());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", 2);
        data.put("outcomeOrdinal", outcomeOrdinal);
        data.put("toolCallId", outcome.toolCallId());
        data.put("success", outcome.success());
        data.put("mutating", outcome.mutating());
        data.put("denied", outcome.denied());
        data.put("errorCode", outcome.errorCode());
        data.put("failureReason", outcome.failureReason().name());
        data.put("mutationEvidenceKind", mutationEvidenceKind);
        return TurnTraceEvent.simple(
                EVENT_TYPE,
                Instant.now().toString(),
                Map.copyOf(data));
    }

    private static String mutationEvidenceKind(ToolMutationEvidence evidence) {
        String kind = evidence == null || evidence.kind().isBlank()
                ? "NONE"
                : evidence.kind();
        if (!MUTATION_EVIDENCE_KINDS.contains(kind)) {
            throw new IllegalArgumentException(
                    "Unsupported mutation-evidence kind: " + kind);
        }
        return kind;
    }
}
