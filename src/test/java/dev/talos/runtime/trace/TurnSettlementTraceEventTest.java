package dev.talos.runtime.trace;

import dev.talos.runtime.TurnExecutionStage;
import dev.talos.runtime.TurnFailureClassifier;
import dev.talos.runtime.TurnSettlement;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnSettlementTraceEventTest {

    @Test
    void exactTerminalEventDecodesThroughClosedVocabulary() {
        TurnFailureClassifier.Classification failure =
                TurnFailureClassifier.classify(new IllegalStateException("not persisted"));
        LocalTurnTrace trace = builder()
                .event(TurnSettlementTraceEvent.create(
                        "2026-07-21T00:00:01Z",
                        TurnSettlement.Kind.FAILED,
                        TurnExecutionStage.ASSISTANT_PREPARATION,
                        failure))
                .event(completed())
                .build();

        TurnSettlementTraceEvent.Decoded decoded = TurnSettlementTraceEvent.decode(trace);

        assertTrue(decoded.valid());
        assertEquals(TurnSettlement.Kind.FAILED, decoded.kind());
        assertEquals(TurnExecutionStage.ASSISTANT_PREPARATION, decoded.stage());
        assertEquals(TurnFailureClassifier.ErrorKind.UNEXPECTED, decoded.errorKind());
        assertEquals(500, decoded.errorCode());
        assertEquals("java.lang.IllegalStateException", decoded.exceptionClass());
    }

    @Test
    void malformedSchemaIsInvalid() {
        LocalTurnTrace trace = builder()
                .event(raw(Map.of(
                        "schemaVersion", 2,
                        "settlementKind", "FAILED",
                        "stage", "MODE_ROUTING",
                        "errorKind", "UNEXPECTED",
                        "errorCode", 500,
                        "exceptionClass", "java.lang.IllegalStateException",
                        "causalOwner", "UNKNOWN")))
                .event(completed())
                .build();

        assertEquals(TurnSettlementTraceEvent.DecodeStatus.INVALID,
                TurnSettlementTraceEvent.decode(trace).status());
    }

    @Test
    void multipleOrOutOfOrderSettlementsAreInvalid() {
        TurnTraceEvent settlement = validRaw();
        LocalTurnTrace multiple = builder()
                .event(settlement)
                .event(validRaw())
                .event(completed())
                .build();
        LocalTurnTrace outOfOrder = builder()
                .event(settlement)
                .event(TurnTraceEvent.simple("OTHER", "2026-07-21T00:00:02Z", Map.of()))
                .event(completed())
                .build();

        assertEquals(TurnSettlementTraceEvent.DecodeStatus.INVALID,
                TurnSettlementTraceEvent.decode(multiple).status());
        assertEquals(TurnSettlementTraceEvent.DecodeStatus.INVALID,
                TurnSettlementTraceEvent.decode(outOfOrder).status());
    }

    @Test
    void forgedOwnerAndUnsafeExceptionClassAreInvalid() {
        LocalTurnTrace forgedOwner = builder()
                .event(raw(Map.of(
                        "schemaVersion", 1,
                        "settlementKind", "FAILED",
                        "stage", "MODE_ROUTING",
                        "errorKind", "UNEXPECTED",
                        "errorCode", 500,
                        "exceptionClass", "java.lang.IllegalStateException",
                        "causalOwner", "MODEL")))
                .event(completed())
                .build();
        LocalTurnTrace unsafeClass = builder()
                .event(raw(Map.of(
                        "schemaVersion", 1,
                        "settlementKind", "FAILED",
                        "stage", "MODE_ROUTING",
                        "errorKind", "UNEXPECTED",
                        "errorCode", 500,
                        "exceptionClass", "secret path: C:/private",
                        "causalOwner", "UNKNOWN")))
                .event(completed())
                .build();

        assertFalse(TurnSettlementTraceEvent.decode(forgedOwner).valid());
        assertFalse(TurnSettlementTraceEvent.decode(unsafeClass).valid());
    }

    @Test
    void traceWithoutSettlementIsAbsent() {
        LocalTurnTrace trace = builder().event(completed()).build();
        assertEquals(TurnSettlementTraceEvent.DecodeStatus.ABSENT,
                TurnSettlementTraceEvent.decode(trace).status());
    }

    @Test
    void wrongFixedCodesAndDuplicateCompletionAreInvalid() {
        LocalTurnTrace wrongTimeout = builder()
                .event(raw(Map.of(
                        "schemaVersion", 1,
                        "settlementKind", "FAILED",
                        "stage", "GENERATION_COORDINATION",
                        "errorKind", "TIMEOUT",
                        "errorCode", 500,
                        "exceptionClass", "java.util.concurrent.TimeoutException",
                        "causalOwner", "UNKNOWN")))
                .event(completed())
                .build();
        LocalTurnTrace duplicateCompletion = builder()
                .event(completed())
                .event(validRaw())
                .event(completed())
                .build();

        assertFalse(TurnSettlementTraceEvent.decode(wrongTimeout).valid());
        assertFalse(TurnSettlementTraceEvent.decode(duplicateCompletion).valid());
    }

    @Test
    void contextBudgetUsesClosedTypedKindAndStableCode() {
        TurnFailureClassifier.Classification failure = TurnFailureClassifier.classify(
                new dev.talos.spi.EngineException.ContextBudgetExceeded(
                        9_000, 8_000, 8_192, 0, 413));
        LocalTurnTrace trace = builder()
                .event(TurnSettlementTraceEvent.create(
                        "2026-07-21T00:00:01Z",
                        TurnSettlement.Kind.FAILED,
                        TurnExecutionStage.GENERATION_COORDINATION,
                        failure))
                .event(completed())
                .build();

        TurnSettlementTraceEvent.Decoded decoded =
                TurnSettlementTraceEvent.decode(trace);
        assertTrue(decoded.valid());
        assertEquals(TurnFailureClassifier.ErrorKind.ENGINE_CONTEXT_BUDGET,
                decoded.errorKind());
        assertEquals(400, decoded.errorCode());
    }

    @Test
    void extraKeysAndNonblankEnvelopeFieldsAreInvalid() {
        Map<String, Object> extra = new LinkedHashMap<>(validRaw().data());
        extra.put("detail", "must-not-survive");
        LocalTurnTrace extraKey = builder()
                .event(raw(extra))
                .event(completed())
                .build();
        LocalTurnTrace nonblankSettlementPhase = builder()
                .event(new TurnTraceEvent(
                        "TURN_SETTLED", "2026-07-21T00:00:01Z",
                        "APPLY", "", validRaw().data()))
                .event(completed())
                .build();
        LocalTurnTrace nonemptyCompletion = builder()
                .event(validRaw())
                .event(TurnTraceEvent.simple(
                        "TRACE_COMPLETED", "2026-07-21T00:00:02Z",
                        Map.of("forged", true)))
                .build();

        assertFalse(TurnSettlementTraceEvent.decode(extraKey).valid());
        assertFalse(TurnSettlementTraceEvent.decode(nonblankSettlementPhase).valid());
        assertFalse(TurnSettlementTraceEvent.decode(nonemptyCompletion).valid());
    }

    private static LocalTurnTrace.Builder builder() {
        return LocalTurnTrace.builder("trace", "session", 1, "2026-07-21T00:00:00Z");
    }

    private static TurnTraceEvent validRaw() {
        return raw(Map.of(
                "schemaVersion", 1,
                "settlementKind", "FAILED",
                "stage", "MODE_ROUTING",
                "errorKind", "UNEXPECTED",
                "errorCode", 500,
                "exceptionClass", "java.lang.IllegalStateException",
                "causalOwner", "UNKNOWN"));
    }

    private static TurnTraceEvent raw(Map<String, Object> data) {
        return TurnTraceEvent.simple("TURN_SETTLED", "2026-07-21T00:00:01Z", data);
    }

    private static TurnTraceEvent completed() {
        return TurnTraceEvent.simple("TRACE_COMPLETED", "2026-07-21T00:00:02Z", Map.of());
    }
}
