package dev.talos.runtime.trace;

import dev.talos.runtime.TurnExecutionStage;
import dev.talos.runtime.TurnFailureClassifier;
import dev.talos.runtime.TurnSettlement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnEvidenceProjectionTraceEventTest {

    @Test
    void exactNormalAndRecoveryMarkersDecode() {
        LocalTurnTrace normal = trace(false, false, "UNAVAILABLE");
        LocalTurnTrace recovery = trace(true, false, "CAPTURE_OBSERVED_UNBOUND");

        TurnEvidenceProjectionTraceEvent.Decoded normalDecoded =
                TurnEvidenceProjectionTraceEvent.decode(normal);
        TurnEvidenceProjectionTraceEvent.Decoded recoveryDecoded =
                TurnEvidenceProjectionTraceEvent.decode(recovery);

        assertTrue(normalDecoded.valid());
        assertEquals("NOT_OBSERVED", normalDecoded.providerLifecycle());
        assertEquals("UNAVAILABLE", normalDecoded.retrieval());
        assertTrue(recoveryDecoded.valid());
        assertEquals("TERMINALIZATION_RECOVERY", recoveryDecoded.reason());
        assertEquals("NOT_RETRIED", recoveryDecoded.captureFinalizers());
    }

    @Test
    void fractionalSchemaExtraKeyAndForgedProviderClaimAreInvalid() {
        LocalTurnTrace base = trace(false, false, "UNAVAILABLE");
        Map<String, Object> fractional = markerData(base);
        fractional.put("schemaVersion", 1.5d);
        Map<String, Object> extra = markerData(base);
        extra.put("raw", "T1190_MARKER_CANARY");
        Map<String, Object> forgedProvider = markerData(base);
        forgedProvider.put("providerLifecycle", "PRESERVED_TYPED");

        assertFalse(TurnEvidenceProjectionTraceEvent.decode(
                replaceMarker(base, fractional)).valid());
        assertFalse(TurnEvidenceProjectionTraceEvent.decode(
                replaceMarker(base, extra)).valid());
        assertFalse(TurnEvidenceProjectionTraceEvent.decode(
                replaceMarker(base, forgedProvider)).valid());
    }

    @Test
    void unknownMayOnlyConservativelyDowngradeRetainedProviderStatus() {
        LocalTurnTrace withoutProvider = trace(false, false, "UNAVAILABLE");
        LocalTurnTrace withProvider = trace(false, true, "UNAVAILABLE");
        Map<String, Object> missingProviderEvidence = markerData(withoutProvider);
        missingProviderEvidence.put("providerLifecycle", "UNKNOWN");
        Map<String, Object> droppedProviderEvidence = markerData(withProvider);
        droppedProviderEvidence.put("providerLifecycle", "UNKNOWN");
        Map<String, Object> contradictoryDowngrade = markerData(withProvider);
        contradictoryDowngrade.put("providerLifecycle", "NOT_OBSERVED");

        assertTrue(TurnEvidenceProjectionTraceEvent.decode(
                replaceMarker(withoutProvider, missingProviderEvidence)).valid());
        assertTrue(TurnEvidenceProjectionTraceEvent.decode(
                replaceMarker(withProvider, droppedProviderEvidence)).valid());
        assertFalse(TurnEvidenceProjectionTraceEvent.decode(
                replaceMarker(withProvider, contradictoryDowngrade)).valid());
    }

    @Test
    void duplicateOutOfOrderAndUnknownEventsAreInvalid() {
        LocalTurnTrace base = trace(false, false, "UNAVAILABLE");
        List<TurnTraceEvent> events = new ArrayList<>(base.events());
        int marker = markerIndex(events);
        events.add(marker, events.get(marker));
        LocalTurnTrace duplicate = copyWithEvents(base, events);

        events = new ArrayList<>(base.events());
        TurnTraceEvent selected = events.remove(markerIndex(events));
        events.add(0, selected);
        LocalTurnTrace outOfOrder = copyWithEvents(base, events);

        events = new ArrayList<>(base.events());
        events.add(1, TurnTraceEvent.simple(
                "UNSAFE_FUTURE_EVENT", "2026-07-21T00:00:00Z",
                Map.of("raw", "T1190_UNSAFE_CANARY")));
        LocalTurnTrace unknown = copyWithEvents(base, events);

        assertFalse(TurnEvidenceProjectionTraceEvent.decode(duplicate).valid());
        assertFalse(TurnEvidenceProjectionTraceEvent.decode(outOfOrder).valid());
        assertFalse(TurnEvidenceProjectionTraceEvent.decode(unknown).valid());
    }

    private static LocalTurnTrace trace(
            boolean recovery,
            boolean provider,
            String retrieval
    ) {
        String traceId = "trc-projection-test";
        List<TurnTraceEvent> events = new ArrayList<>();
        events.add(TurnTraceEvent.simple(
                "TRACE_STARTED", "2026-07-21T00:00:00Z",
                recovery
                        ? Map.of(
                                "turnNumber", 1,
                                "redactionMode", "DEFAULT",
                                "initialization", "TERMINALIZATION_RECOVERY")
                        : Map.of("turnNumber", 1, "redactionMode", "DEFAULT")));
        if (provider) {
            events.add(TurnTraceEvent.simple(
                    "GENERATION_STARTED", "2026-07-21T00:00:00Z",
                    Map.of(
                            "providerLedgerVersion", 1,
                            "ledgerSequence", 1,
                            "generationId", traceId + "-generation-1",
                            "source", "FOREGROUND_PROVIDER",
                            "purpose", "TURN_RESPONSE",
                            "toolAuthorityMode", "NONE")));
        }
        if (recovery) {
            events.add(TurnTraceEvent.simple(
                    "TURN_TERMINALIZATION_RECOVERED", "2026-07-21T00:00:00Z",
                    Map.of(
                            "schemaVersion", 1,
                            "exceptionClass", "java.lang.IllegalStateException")));
        }
        TurnTraceEvent marker = TurnEvidenceProjectionTraceEvent.create(
                "2026-07-21T00:00:00Z", recovery, provider, true,
                "CAPTURE_OBSERVED_UNBOUND".equals(retrieval));
        events.add(marker);
        events.add(settlement());
        events.add(completed());
        LocalTurnTrace.Builder builder = LocalTurnTrace.builder(
                traceId, "session", 1, "2026-07-21T00:00:00Z");
        events.forEach(builder::event);
        return builder.build();
    }

    private static TurnTraceEvent settlement() {
        return TurnSettlementTraceEvent.create(
                "2026-07-21T00:00:01Z",
                TurnSettlement.Kind.FAILED,
                TurnExecutionStage.GENERATION_COORDINATION,
                TurnFailureClassifier.classify(new IllegalStateException()));
    }

    private static TurnTraceEvent completed() {
        return TurnTraceEvent.simple(
                "TRACE_COMPLETED", "2026-07-21T00:00:02Z", Map.of());
    }

    private static Map<String, Object> markerData(LocalTurnTrace trace) {
        return new LinkedHashMap<>(trace.events().get(markerIndex(trace.events())).data());
    }

    private static int markerIndex(List<TurnTraceEvent> events) {
        for (int index = 0; index < events.size(); index++) {
            if (TurnEvidenceProjectionTraceEvent.TYPE.equals(events.get(index).type())) {
                return index;
            }
        }
        throw new AssertionError("projection marker missing");
    }

    private static LocalTurnTrace replaceMarker(
            LocalTurnTrace trace,
            Map<String, Object> data
    ) {
        List<TurnTraceEvent> events = new ArrayList<>(trace.events());
        int index = markerIndex(events);
        events.set(index, TurnTraceEvent.simple(
                TurnEvidenceProjectionTraceEvent.TYPE,
                events.get(index).timestamp(), data));
        return copyWithEvents(trace, events);
    }

    private static LocalTurnTrace copyWithEvents(
            LocalTurnTrace trace,
            List<TurnTraceEvent> events
    ) {
        LocalTurnTrace.Builder builder = LocalTurnTrace.builder(
                trace.traceId(), trace.sessionId(), trace.turnNumber(), trace.timestamp());
        events.forEach(builder::event);
        return builder.build();
    }
}
