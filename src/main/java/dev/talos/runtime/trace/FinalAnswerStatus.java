package dev.talos.runtime.trace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Selected delivery facts are kept separate from operational and semantic outcomes. */
public final class FinalAnswerStatus {
    public static final String TYPE = "ANSWER_FINAL_STATUS";
    public enum Cause {
        PROVIDER_LENGTH, RUNTIME_SAFE_CAP, LOCAL_PRESENTATION_LIMIT, INCOMPLETE_PROVIDER_GENERATION,
        /** The provider generation ended before completion but held no text, so no provider prose was withheld. */
        INCOMPLETE_PROVIDER_GENERATION_WITHOUT_TEXT
    }
    public enum Delivery { NO_KNOWN_LIMIT, LIMITED, UNAVAILABLE }
    public enum Coverage { NOT_ASSESSED, UNAVAILABLE }
    public record Decoded(boolean available, LocalTurnTrace.OutcomeSummary operational,
                          Delivery delivery, boolean associationAvailable, List<Cause> causes,
                          Coverage coverage) {
        public Decoded { causes = List.copyOf(causes); }
    }
    private static final Set<String> KEYS = Set.of("version", "traceId", "turnNumber", "answerHash",
            "operationalStatus", "operationalClassification", "verification", "approval", "mutation",
            "combinedStatus", "combinedClassification", "associationAvailable", "delivery", "causes", "coverage");

    private FinalAnswerStatus() {}

    static TurnTraceEvent event(String timestamp, String traceId, int turnNumber, String answerHash,
                                LocalTurnTrace.OutcomeSummary operational,
                                LocalTurnTrace.OutcomeSummary combined, boolean associated, Set<Cause> causes) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1);
        data.put("traceId", traceId);
        data.put("turnNumber", turnNumber);
        data.put("answerHash", answerHash);
        data.put("operationalStatus", token(operational.status()));
        data.put("operationalClassification", token(operational.classification()));
        data.put("verification", token(operational.verificationStatus()));
        data.put("approval", token(operational.approvalStatus()));
        data.put("mutation", token(operational.mutationStatus()));
        data.put("combinedStatus", token(combined.status()));
        data.put("combinedClassification", token(combined.classification()));
        data.put("associationAvailable", associated);
        data.put("delivery", delivery(associated, causes).name());
        data.put("causes", causes.stream().map(Enum::name).sorted().toList());
        data.put("coverage", Coverage.NOT_ASSESSED.name());
        return TurnTraceEvent.simple(TYPE, timestamp, data);
    }

    public static Decoded unavailable() {
        return new Decoded(false, LocalTurnTrace.OutcomeSummary.empty(), Delivery.UNAVAILABLE,
                false, List.of(), Coverage.UNAVAILABLE);
    }

    public static Decoded decode(LocalTurnTrace trace) {
        if (trace == null) return unavailable();
        var markers = trace.events().stream().filter(e -> TYPE.equals(e.type())).toList();
        var routes = trace.events().stream().filter(e -> "ROUTED_ANSWER_CAPTURED".equals(e.type())).toList();
        var rendered = trace.events().stream().filter(e -> "OUTCOME_RENDERED".equals(e.type())).toList();
        var completed = trace.events().stream().filter(e -> "TRACE_COMPLETED".equals(e.type())).toList();
        if (markers.size() != 1 || routes.size() != 1 || rendered.size() != 1 || completed.size() != 1
                || trace.events().getLast() != completed.getFirst()
                || !completed.getFirst().data().isEmpty()
                || !completed.getFirst().phase().isBlank() || !completed.getFirst().toolName().isBlank()
                || !rendered.getFirst().phase().isBlank() || !rendered.getFirst().toolName().isBlank()
                || !Set.of("status", "classification").equals(rendered.getFirst().data().keySet())
                || !token(trace.outcome().status()).equals(rendered.getFirst().data().get("status"))
                || !token(trace.outcome().classification()).equals(rendered.getFirst().data().get("classification")))
            return unavailable();
        var marker = markers.getFirst();
        var data = marker.data();
        if (!KEYS.equals(data.keySet()) || !integer(data.get("version"), 1)
                || !integer(data.get("turnNumber"), trace.turnNumber())
                || !trace.traceId().equals(data.get("traceId"))
                || !marker.phase().isBlank() || !marker.toolName().isBlank()
                || trace.events().indexOf(routes.getFirst()) >= trace.events().indexOf(marker)
                || trace.events().indexOf(marker) >= trace.events().indexOf(rendered.getFirst())
                || !trace.redaction().assistantHash().equals(data.get("answerHash"))
                || !java.util.Objects.equals(routes.getFirst().data().get("answerHash"), data.get("answerHash"))
                || !(data.get("associationAvailable") instanceof Boolean associated)
                || !(data.get("causes") instanceof List<?> rawCauses)
                || rawCauses.size() > Cause.values().length
                || !Coverage.NOT_ASSESSED.name().equals(data.get("coverage"))) return unavailable();
        for (String key : List.of("operationalStatus", "operationalClassification", "verification", "approval",
                "mutation", "combinedStatus", "combinedClassification")) {
            if (!(data.get(key) instanceof String text) || !text.matches("[A-Z][A-Z0-9_]{0,127}")) return unavailable();
        }
        try {
            List<Cause> causes = rawCauses.stream().map(value -> Cause.valueOf((String) value)).toList();
            if (Set.copyOf(causes).size() != causes.size()
                    || !causes.stream().map(Enum::name).sorted().toList().equals(rawCauses)
                    || !delivery(associated, Set.copyOf(causes)).name().equals(data.get("delivery"))) return unavailable();
            var outcome = trace.outcome();
            if (!token(outcome.status()).equals(data.get("combinedStatus"))
                    || !token(outcome.classification()).equals(data.get("combinedClassification"))
                    || !token(outcome.verificationStatus()).equals(data.get("verification"))
                    || !token(outcome.approvalStatus()).equals(data.get("approval"))
                    || !token(outcome.mutationStatus()).equals(data.get("mutation"))) return unavailable();
            return new Decoded(true, new LocalTurnTrace.OutcomeSummary((String) data.get("operationalStatus"),
                    (String) data.get("verification"), (String) data.get("approval"), (String) data.get("mutation"),
                    (String) data.get("operationalClassification")), delivery(associated, Set.copyOf(causes)),
                    associated, causes, Coverage.NOT_ASSESSED);
        } catch (IllegalArgumentException | ClassCastException | NullPointerException invalid) {
            return unavailable();
        }
    }

    private static Delivery delivery(boolean associated, Set<Cause> causes) {
        return !causes.isEmpty() ? Delivery.LIMITED : associated ? Delivery.NO_KNOWN_LIMIT : Delivery.UNAVAILABLE;
    }

    private static boolean integer(Object value, long expected) {
        return (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                && ((Number) value).longValue() == expected;
    }

    private static String token(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{0,127}") ? value : "UNKNOWN";
    }
}
