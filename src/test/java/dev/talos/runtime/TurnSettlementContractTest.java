package dev.talos.runtime;

import dev.talos.core.retrieval.RetrievalTrace;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.TurnEvidenceProjectionTraceEvent;
import dev.talos.runtime.trace.TurnSettlementTraceEvent;
import dev.talos.runtime.trace.TurnTraceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnSettlementContractTest {

    @Test
    void legacyListenerReceivesOnlyOrdinaryCompletion() {
        AtomicInteger calls = new AtomicInteger();
        SessionListener listener = new SessionListener() {
            @Override public void onTurnComplete(TurnResult result, String userInput) {
                calls.incrementAndGet();
            }
        };

        assertEquals(TurnPublicationReceipt.Status.COMPLETE,
                listener.onTurnSettled(completed(), "user").status());
        assertEquals(TurnPublicationReceipt.Status.SKIPPED,
                listener.onTurnSettled(noResult(), "user").status());
        assertEquals(TurnPublicationReceipt.Status.SKIPPED,
                listener.onTurnSettled(failed(), "user").status());
        assertEquals(1, calls.get());
    }

    @Test
    void contradictoryFailureEnvelopeIsRejected() {
        TurnFailureClassifier.Classification failure =
                TurnFailureClassifier.classify(new IllegalStateException("failure"));

        assertThrows(IllegalArgumentException.class, () -> new TurnSettlement(
                TurnSettlement.Kind.FAILED,
                TurnExecutionStage.MODE_ROUTING,
                turnResult(new Result.Ok("must not become replayable")),
                failure));
        assertThrows(IllegalArgumentException.class, () -> new TurnSettlement(
                TurnSettlement.Kind.COMPLETED,
                TurnExecutionStage.TURN_FINALIZATION,
                turnResult(new Result.Error("", 500)),
                failure));
    }

    @Test
    void publicationReceiptCannotCarryFreeFormOrContradictoryHealth() {
        assertThrows(IllegalArgumentException.class, () -> new TurnPublicationReceipt(
                TurnPublicationReceipt.Status.COMPLETE,
                TurnPublicationReceipt.Reason.LISTENER_EXCEPTION));
        assertThrows(IllegalArgumentException.class, () -> new TurnPublicationReceipt(
                TurnPublicationReceipt.Status.DEGRADED,
                TurnPublicationReceipt.Reason.NONE));
    }

    @Test
    void failureClassifierRejectsMissingThrowable() {
        assertThrows(NullPointerException.class, () -> TurnFailureClassifier.classify(null));
    }

    @Test
    void settlementOwnsAnIndependentImmutableRetrievalSnapshot() {
        RetrievalTrace mutable = new RetrievalTrace();
        mutable.record("first", 1, 2, 1);
        mutable.route("CODE_SYMBOL_FIRST");
        mutable.recordEvidence("FILE", "README.md", "readme", 1, "selected");
        TurnResult result = new TurnResult(
                new Result.Ok("answer"), mutable, 1, Duration.ZERO, TurnAudit.empty());

        TurnSettlement settlement = TurnSettlement.completed(
                TurnExecutionStage.TURN_FINALIZATION, result);
        mutable.record("later", 1, 1, 0);
        mutable.route("LATER_ROUTE");
        mutable.recordEvidence("FILE", "later.txt", "later", 1, "later");

        assertEquals(1, settlement.result().trace().entries().size());
        assertEquals("CODE_SYMBOL_FIRST", settlement.result().trace().route());
        assertEquals(1, settlement.result().trace().evidenceHits().size());
        assertThrows(UnsupportedOperationException.class, () ->
                settlement.result().trace().record("forged", 1, 1, 0));
        assertThrows(UnsupportedOperationException.class, () ->
                settlement.result().trace().route("FORGED"));
        assertThrows(UnsupportedOperationException.class, () ->
                settlement.result().trace().recordEvidence(
                        "FILE", "forged.txt", "forged", 1, "forged"));
    }

    @Test
    void hostileSameTypeEventPayloadIsOmittedAndMarksProviderEvidenceUnknown(
            @TempDir Path dir
    ) {
        String canary = "T1190_RAW_PROVIDER_BODY_CANARY";
        TurnFailureClassifier.Classification failure =
                TurnFailureClassifier.classify(new IllegalStateException("failure"));
        LocalTurnTrace raw = LocalTurnTrace.builder(
                        "trace-safe", "session", 1, "2026-07-21T00:00:00Z")
                .event(TurnTraceEvent.simple(
                        "GENERATION_STARTED",
                        "2026-07-21T00:00:00Z",
                        Map.of("providerBody", canary)))
                .event(TurnSettlementTraceEvent.create(
                        "2026-07-21T00:00:01Z",
                        TurnSettlement.Kind.FAILED,
                        TurnExecutionStage.GENERATION_COORDINATION,
                        failure))
                .event(TurnTraceEvent.simple(
                        "TRACE_COMPLETED", "2026-07-21T00:00:02Z", Map.of()))
                .build();
        TurnResult result = new TurnResult(
                new Result.Error("", 500),
                null,
                1,
                Duration.ZERO,
                TurnAudit.empty().withLocalTrace(raw));

        TurnSettlement settlement = TurnSettlement.abnormal(
                TurnExecutionStage.GENERATION_COORDINATION, result, failure);
        JsonSessionStore store = new JsonSessionStore(dir);
        assertEquals(TurnPublicationReceipt.Status.COMPLETE,
                new JsonTurnLogAppender(store, "hostile")
                        .onTurnSettled(settlement, "raw user input")
                        .status());
        TurnRecord row = store.loadTurns("hostile").getFirst();
        LocalTurnTrace projected = store.loadTrace(
                "hostile", row.traceId()).orElseThrow();

        assertEquals(0, projected.events().stream().filter(event ->
                "GENERATION_STARTED".equals(event.type())).count());
        var marker = projected.events().stream().filter(event ->
                TurnEvidenceProjectionTraceEvent.TYPE.equals(event.type()))
                .findFirst().orElseThrow();
        assertEquals("INCOMPLETE", marker.data().get("completeness"));
        assertEquals("UNKNOWN", marker.data().get("providerLifecycle"));
        assertEquals("OBSERVED_COMPLETE", marker.data().get("captureFinalizers"));
        assertTrue(TurnEvidenceProjectionTraceEvent.decode(projected).valid());
        assertEquals("UNKNOWN",
                TurnEvidenceProjectionTraceEvent.decode(projected).providerLifecycle());
        assertFalse(projected.toString().contains(canary));
    }

    @Test
    void unknownFutureProviderEventMarksLifecycleUnknown() {
        TurnFailureClassifier.Classification failure =
                TurnFailureClassifier.classify(new IllegalStateException("failure"));
        LocalTurnTrace raw = LocalTurnTrace.builder(
                        "trace-future", "session", 1, "2026-07-21T00:00:00Z")
                .event(TurnTraceEvent.simple(
                        "GENERATION_TRANSPORT_STARTED",
                        "2026-07-21T00:00:00Z",
                        Map.of("futureField", "T1190_FUTURE_CANARY")))
                .event(TurnSettlementTraceEvent.create(
                        "2026-07-21T00:00:01Z",
                        TurnSettlement.Kind.FAILED,
                        TurnExecutionStage.GENERATION_COORDINATION,
                        failure))
                .event(TurnTraceEvent.simple(
                        "TRACE_COMPLETED", "2026-07-21T00:00:02Z", Map.of()))
                .build();
        TurnSettlement settlement = TurnSettlement.abnormal(
                TurnExecutionStage.GENERATION_COORDINATION,
                new TurnResult(
                        new Result.Error("", 500),
                        null,
                        1,
                        Duration.ZERO,
                        TurnAudit.empty().withLocalTrace(raw)),
                failure);

        LocalTurnTrace projected = settlement.result().audit().localTrace();
        var marker = projected.events().stream().filter(event ->
                TurnEvidenceProjectionTraceEvent.TYPE.equals(event.type()))
                .findFirst().orElseThrow();
        assertEquals("UNKNOWN", marker.data().get("providerLifecycle"));
        assertTrue(TurnEvidenceProjectionTraceEvent.decode(projected).valid());
        assertEquals("UNKNOWN",
                TurnEvidenceProjectionTraceEvent.decode(projected).providerLifecycle());
        assertFalse(projected.toString().contains("T1190_FUTURE_CANARY"));
    }

    private static TurnSettlement completed() {
        return TurnSettlement.completed(
                TurnExecutionStage.TURN_FINALIZATION,
                turnResult(new Result.Ok("answer")));
    }

    private static TurnSettlement noResult() {
        return TurnSettlement.noResult(
                TurnExecutionStage.MODE_ROUTING,
                turnResult(new Result.Info("(no result)")));
    }

    private static TurnSettlement failed() {
        TurnFailureClassifier.Classification failure =
                TurnFailureClassifier.classify(new IllegalStateException("failure"));
        return TurnSettlement.abnormal(
                TurnExecutionStage.MODE_ROUTING,
                turnResult(new Result.Error("", failure.code())),
                failure);
    }

    private static TurnResult turnResult(Result result) {
        return new TurnResult(result, null, 1, Duration.ZERO, TurnAudit.empty());
    }
}
