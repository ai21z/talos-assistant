package dev.talos.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import dev.talos.runtime.TurnSettlement;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.TurnEvidenceProjectionTraceEvent;
import dev.talos.runtime.trace.TurnSettlementTraceEvent;
import dev.talos.runtime.trace.TurnTraceEvent;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Selects the score-bearing or incomplete-terminal trace validation boundary. */
final class QualificationTraceEvidenceEnvelope {
    enum Kind {
        SCORE_BEARING,
        INCOMPLETE_TERMINAL
    }

    record Result(
            Kind kind,
            String traceId,
            QualificationTraceEvidence.Facts scoreBearingFacts
    ) {
        Result {
            kind = Objects.requireNonNull(kind, "trace evidence kind");
            traceId = traceId == null ? "" : traceId;
            if (traceId.isBlank()) {
                throw new IllegalArgumentException("trace evidence id is blank");
            }
            if ((kind == Kind.SCORE_BEARING) != (scoreBearingFacts != null)) {
                throw new IllegalArgumentException(
                        "only score-bearing trace evidence may expose score facts");
            }
            if (scoreBearingFacts != null
                    && !traceId.equals(scoreBearingFacts.traceId())) {
                throw new IllegalArgumentException(
                        "trace envelope identity differs from score-bearing facts");
            }
        }

        boolean incompleteTerminal() {
            return kind == Kind.INCOMPLETE_TERMINAL;
        }
    }

    private QualificationTraceEvidenceEnvelope() {}

    static Result validate(
            byte[] bytes,
            QualificationProtocol.Scenario scenario
    ) throws IOException {
        JsonNode root = QualificationJson.readTree(bytes);
        if (!hasExactProjectionMarker(root)) {
            QualificationTraceEvidence.Facts facts =
                    QualificationTraceEvidence.read(bytes, scenario);
            return new Result(Kind.SCORE_BEARING, facts.traceId(), facts);
        }
        LocalTurnTrace trace = validateIncompleteTerminal(bytes, root);
        return new Result(Kind.INCOMPLETE_TERMINAL, trace.traceId(), null);
    }

    private static boolean hasExactProjectionMarker(JsonNode root) {
        if (root == null || !root.isObject()) return false;
        JsonNode events = root.get("events");
        if (events == null || !events.isArray()) return false;
        for (JsonNode event : events) {
            JsonNode type = event == null ? null : event.get("type");
            if (type != null && type.isTextual()
                    && TurnEvidenceProjectionTraceEvent.TYPE.equals(type.textValue())) {
                return true;
            }
        }
        return false;
    }

    private static LocalTurnTrace validateIncompleteTerminal(byte[] bytes, JsonNode root)
            throws IOException {
        int schemaVersion = QualificationJson.requiredInt(
                root.get("schemaVersion"), "trace schemaVersion");
        if (schemaVersion
                != QualificationProviderAttemptLedger.PROCESS_TRACE_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "incomplete terminal trace must use trace schema v5");
        }

        LocalTurnTrace trace = QualificationJson.read(bytes, LocalTurnTrace.class);
        if (!root.equals(QualificationJson.tree(trace))) {
            throw new IllegalArgumentException(
                    "incomplete terminal trace is not an exact LocalTurnTrace value");
        }
        requireIdentity(trace);
        requireTraceStart(trace);

        TurnEvidenceProjectionTraceEvent.Decoded projection =
                TurnEvidenceProjectionTraceEvent.decode(trace);
        if (!projection.valid() || !"INCOMPLETE".equals(projection.completeness())) {
            throw new IllegalArgumentException(
                    "terminal evidence projection must be VALID and INCOMPLETE");
        }

        TurnSettlementTraceEvent.Decoded settlement =
                TurnSettlementTraceEvent.decode(trace);
        if (!settlement.valid()
                || settlement.kind() == null
                || settlement.kind() == TurnSettlement.Kind.COMPLETED) {
            throw new IllegalArgumentException(
                    "incomplete terminal trace requires one non-COMPLETED settlement");
        }
        requireOutcome(trace, settlement.kind());
        requireCanonicalSafeProjection(trace, settlement.kind(), projection);

        // This reader closes generation/attempt state and validates transport and
        // managed-process references against the exact retained provider events.
        QualificationProviderAttemptLedger.parse(root);
        return trace;
    }

    private static void requireIdentity(LocalTurnTrace trace) {
        if (!safeToken(trace.traceId(), 128) || trace.traceId().isBlank()
                || !safeToken(trace.sessionId(), 128) || trace.sessionId().isBlank()
                || trace.turnNumber() < 1 || trace.turnNumber() > 999_999) {
            throw new IllegalArgumentException(
                    "incomplete terminal trace identity is invalid");
        }
        try {
            Instant timestamp = Instant.parse(trace.timestamp());
            if (!timestamp.toString().equals(trace.timestamp())) {
                throw new IllegalArgumentException(
                        "incomplete terminal trace timestamp is not canonical");
            }
        } catch (DateTimeParseException malformed) {
            throw new IllegalArgumentException(
                    "incomplete terminal trace timestamp is invalid", malformed);
        }
    }

    private static void requireTraceStart(LocalTurnTrace trace) {
        List<TurnTraceEvent> starts = trace.events().stream()
                .filter(event -> event != null && "TRACE_STARTED".equals(event.type()))
                .toList();
        if (starts.size() != 1 || trace.events().isEmpty()
                || trace.events().getFirst() != starts.getFirst()) {
            throw new IllegalArgumentException(
                    "incomplete terminal trace requires one leading TRACE_STARTED event");
        }
        Map<String, Object> data = starts.getFirst().data();
        if (integralInt(data.get("turnNumber")) != trace.turnNumber()
                || !trace.redaction().mode().name().equals(data.get("redactionMode"))) {
            throw new IllegalArgumentException(
                    "TRACE_STARTED identity differs from the terminal trace");
        }
    }

    private static void requireOutcome(
            LocalTurnTrace trace,
            TurnSettlement.Kind kind
    ) {
        String status = switch (kind) {
            case CANCELLED -> "ABORTED";
            case FAILED -> "ERROR";
            case NO_RESULT -> "NO_RESULT";
            case COMPLETED -> throw new IllegalArgumentException(
                    "completed settlement cannot be an incomplete terminal trace");
        };
        String classification = switch (kind) {
            case CANCELLED -> "TURN_CANCELLED";
            case FAILED -> "TURN_FAILED";
            case NO_RESULT -> "NO_MODE_RESULT";
            case COMPLETED -> throw new IllegalArgumentException(
                    "completed settlement cannot be an incomplete terminal trace");
        };
        LocalTurnTrace.OutcomeSummary expected = new LocalTurnTrace.OutcomeSummary(
                status, "NOT_RUN", "UNKNOWN", "UNKNOWN", classification);
        if (!expected.equals(trace.outcome())) {
            throw new IllegalArgumentException(
                    "terminal outcome contradicts the non-COMPLETED settlement");
        }
    }

    private static void requireCanonicalSafeProjection(
            LocalTurnTrace trace,
            TurnSettlement.Kind kind,
            TurnEvidenceProjectionTraceEvent.Decoded projection
    ) {
        LocalTurnTrace canonical = trace.safeTerminalProjection(
                kind,
                "CAPTURE_OBSERVED_UNBOUND".equals(projection.retrieval()));
        if (trace.schemaVersion() != canonical.schemaVersion()
                || !trace.traceId().equals(canonical.traceId())
                || !trace.sessionId().equals(canonical.sessionId())
                || trace.turnNumber() != canonical.turnNumber()
                || !trace.timestamp().equals(canonical.timestamp())
                || !trace.workspaceHash().equals(canonical.workspaceHash())
                || !trace.mode().equals(canonical.mode())
                || !trace.model().equals(canonical.model())
                || !trace.taskContract().equals(canonical.taskContract())
                || !trace.phaseTransitions().equals(canonical.phaseTransitions())
                || !trace.toolSurface().equals(canonical.toolSurface())
                || !trace.promptAudit().equals(canonical.promptAudit())
                || !trace.events().equals(canonical.events())
                || !trace.verification().equals(canonical.verification())
                || !trace.repair().equals(canonical.repair())
                || !trace.checkpoint().equals(canonical.checkpoint())
                || !trace.outcome().equals(canonical.outcome())
                || !trace.warnings().equals(canonical.warnings())
                || !trace.contextLedgerSummary().equals(
                        canonical.contextLedgerSummary())
                || !trace.redaction().equals(canonical.redaction())) {
            throw new IllegalArgumentException(
                    "incomplete terminal trace is not a canonical safe projection");
        }
        String taskType = trace.taskContract().type();
        if (!taskType.isEmpty() && !safeToken(taskType, 64)) {
            throw new IllegalArgumentException(
                    "terminal task type is not a bounded token");
        }
    }

    private static int integralInt(Object value) {
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) {
            return Integer.MIN_VALUE;
        }
        long number = ((Number) value).longValue();
        return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE
                ? (int) number
                : Integer.MIN_VALUE;
    }

    private static boolean safeToken(String value, int maxChars) {
        return value != null
                && value.length() <= maxChars
                && value.matches("[A-Za-z0-9._:$+\\-]*");
    }
}
