package dev.talos.runtime.trace;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closed marker stating which evidence a safe terminal projection omitted. */
public final class TurnEvidenceProjectionTraceEvent {
    public static final String TYPE = "TURN_EVIDENCE_PROJECTION";
    private static final Set<String> KEYS = Set.of(
            "schemaVersion", "completeness", "reason", "providerLifecycle",
            "retrieval", "captureFinalizers", "omittedCategories");
    private static final List<String> OMITTED = List.of(
            "CHECKPOINT_VERIFICATION", "POLICY_CONTEXT", "TOOL_LIFECYCLE");

    private TurnEvidenceProjectionTraceEvent() {}

    public enum DecodeStatus { ABSENT, VALID, INVALID }

    public record Decoded(
            DecodeStatus status,
            String completeness,
            String reason,
            String providerLifecycle,
            String retrieval,
            String captureFinalizers
    ) {
        public boolean present() { return status != DecodeStatus.ABSENT; }
        public boolean valid() { return status == DecodeStatus.VALID; }

        static Decoded absent() {
            return new Decoded(DecodeStatus.ABSENT, "", "", "", "", "");
        }

        static Decoded invalid() {
            return new Decoded(
                    DecodeStatus.INVALID,
                    "UNKNOWN",
                    "UNKNOWN",
                    "UNKNOWN",
                    "UNKNOWN",
                    "UNKNOWN");
        }
    }

    static TurnTraceEvent create(
            String timestamp,
            boolean terminalizationRecovery,
            boolean providerLifecycleObserved,
            boolean providerLifecyclePreserved,
            boolean retrievalCaptureObserved
    ) {
        return TurnTraceEvent.simple(TYPE, timestamp, Map.of(
                "schemaVersion", 1,
                "completeness", "INCOMPLETE",
                "reason", terminalizationRecovery
                        ? "TERMINALIZATION_RECOVERY"
                        : "NON_CONVERSATIONAL_SAFE_PROJECTION",
                "providerLifecycle", providerStatus(
                        terminalizationRecovery,
                        providerLifecycleObserved,
                        providerLifecyclePreserved),
                "retrieval", retrievalCaptureObserved
                        ? "CAPTURE_OBSERVED_UNBOUND"
                        : "UNAVAILABLE",
                "captureFinalizers", terminalizationRecovery
                        ? "NOT_RETRIED"
                        : "OBSERVED_COMPLETE",
                "omittedCategories", OMITTED));
    }

    public static Decoded decode(LocalTurnTrace trace) {
        if (trace == null || trace.events().isEmpty()) return Decoded.absent();
        List<TurnTraceEvent> markers = trace.events().stream()
                .filter(event -> event != null && TYPE.equals(event.type()))
                .toList();
        if (markers.isEmpty()) return Decoded.absent();
        if (markers.size() != 1) return Decoded.invalid();
        TurnTraceEvent marker = markers.getFirst();
        int markerIndex = trace.events().indexOf(marker);
        if (markerIndex < 0 || markerIndex + 1 >= trace.events().size()
                || !"TURN_SETTLED".equals(trace.events().get(markerIndex + 1).type())
                || !marker.phase().isBlank() || !marker.toolName().isBlank()) {
            return Decoded.invalid();
        }
        Map<String, Object> data = marker.data();
        if (!KEYS.equals(data.keySet())
                || !integralOne(data.get("schemaVersion"))
                || !"INCOMPLETE".equals(text(data.get("completeness")))
                || !Set.of(
                        "NON_CONVERSATIONAL_SAFE_PROJECTION",
                        "TERMINALIZATION_RECOVERY")
                        .contains(text(data.get("reason")))
                || !Set.of("NOT_OBSERVED", "PRESERVED_TYPED", "UNKNOWN")
                        .contains(text(data.get("providerLifecycle")))
                || !Set.of("CAPTURE_OBSERVED_UNBOUND", "UNAVAILABLE")
                        .contains(text(data.get("retrieval")))
                || !Set.of("OBSERVED_COMPLETE", "NOT_RETRIED")
                        .contains(text(data.get("captureFinalizers")))
                || !(data.get("omittedCategories") instanceof List<?> omitted)
                || !OMITTED.equals(omitted)) {
            return Decoded.invalid();
        }
        if (!TurnSettlementTraceEvent.decode(trace).valid()) {
            return Decoded.invalid();
        }
        SafeTerminalTraceProjector.Projection projection =
                SafeTerminalTraceProjector.project(trace.events(), trace.traceId());
        List<TurnTraceEvent> withoutMarker = trace.events().stream()
                .filter(event -> event != null && !TYPE.equals(event.type()))
                .toList();
        if (!withoutMarker.equals(projection.events())) {
            return Decoded.invalid();
        }
        String reason = text(data.get("reason"));
        String provider = text(data.get("providerLifecycle"));
        String finalizers = text(data.get("captureFinalizers"));
        String expectedReason = projection.terminalizationRecovery()
                ? "TERMINALIZATION_RECOVERY"
                : "NON_CONVERSATIONAL_SAFE_PROJECTION";
        String expectedProvider = providerStatus(
                projection.terminalizationRecovery(),
                projection.providerLifecycleObserved(),
                projection.providerLifecyclePreserved());
        String expectedFinalizers = projection.terminalizationRecovery()
                ? "NOT_RETRIED"
                : "OBSERVED_COMPLETE";
        if (("TERMINALIZATION_RECOVERY".equals(reason)
                && (!"UNKNOWN".equals(provider) || !"NOT_RETRIED".equals(finalizers)))
                || ("NON_CONVERSATIONAL_SAFE_PROJECTION".equals(reason)
                && "NOT_RETRIED".equals(finalizers))
                || !expectedReason.equals(reason)
                || !providerStatusMatches(expectedProvider, provider)
                || !expectedFinalizers.equals(finalizers)) {
            return Decoded.invalid();
        }
        return new Decoded(
                DecodeStatus.VALID,
                "INCOMPLETE",
                reason,
                provider,
                text(data.get("retrieval")),
                finalizers);
    }

    private static String providerStatus(
            boolean terminalizationRecovery,
            boolean observed,
            boolean preserved
    ) {
        if (terminalizationRecovery || !preserved) return "UNKNOWN";
        return observed ? "PRESERVED_TYPED" : "NOT_OBSERVED";
    }

    /**
     * A writer may have removed an unsafe provider event before persistence.
     * The retained event list cannot reconstruct that omission, so UNKNOWN is
     * accepted only as a conservative downgrade. A marker may never upgrade or
     * contradict the status mechanically derived from retained safe events.
     */
    private static boolean providerStatusMatches(String retainedStatus, String markerStatus) {
        return retainedStatus.equals(markerStatus) || "UNKNOWN".equals(markerStatus);
    }

    private static String text(Object value) {
        return value instanceof String text ? text : "";
    }

    private static boolean integralOne(Object value) {
        return (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)
                && ((Number) value).longValue() == 1L;
    }
}
