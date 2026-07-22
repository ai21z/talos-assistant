package dev.talos.runtime;

import dev.talos.runtime.trace.LocalTurnTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSessionStoreTurnEnvelopeTest {

    @Test
    void observedPairPersistsResolvableMatchingRowAndTrace(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        LocalTurnTrace trace = trace("trace-pair");
        TurnRecord record = record(trace.traceId());

        TurnEnvelopePersistenceReceipt receipt =
                store.persistTurnEnvelope("pair", record, trace);

        assertEquals(TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED,
                receipt.state());
        TurnRecord persisted = store.loadTurns("pair").getFirst();
        assertEquals(trace.traceId(), persisted.traceId());
        assertEquals(trace, store.loadTrace("pair", persisted.traceId()).orElseThrow());
    }

    @Test
    void traceFailureWritesRowWithoutClaimingMissingTrace(@TempDir Path dir) throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        Files.createDirectories(dir.resolve("traces"));
        Files.writeString(dir.resolve("traces").resolve("trace-blocked"), "not a directory");
        LocalTurnTrace trace = trace("trace-write-fails");

        TurnEnvelopePersistenceReceipt receipt = store.persistTurnEnvelope(
                "trace-blocked", record(trace.traceId()), trace);

        assertEquals(TurnEnvelopePersistenceReceipt.State.TURN_WRITE_ONLY, receipt.state());
        TurnRecord persisted = store.loadTurns("trace-blocked").getFirst();
        assertEquals("", persisted.traceId());
        assertTrue(store.loadTrace("trace-blocked", trace.traceId()).isEmpty());
    }

    @Test
    void rowFailureLeavesObservedTraceWithoutInventingPair(@TempDir Path dir) throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        Files.createDirectory(dir.resolve("row-blocked.turns.jsonl"));
        LocalTurnTrace trace = trace("row-write-fails");

        TurnEnvelopePersistenceReceipt receipt = store.persistTurnEnvelope(
                "row-blocked", record(trace.traceId()), trace);

        assertEquals(TurnEnvelopePersistenceReceipt.State.TRACE_WRITE_ONLY, receipt.state());
        assertTrue(store.loadTurns("row-blocked").isEmpty());
        assertEquals(trace, store.loadTrace("row-blocked", trace.traceId()).orElseThrow());
    }

    @Test
    void bothWriteFailuresAreReportedWithoutThrowing(@TempDir Path dir) throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        Files.createDirectories(dir.resolve("traces"));
        Files.writeString(dir.resolve("traces").resolve("both-blocked"), "not a directory");
        Files.createDirectory(dir.resolve("both-blocked.turns.jsonl"));
        LocalTurnTrace trace = trace("both-write-fails");

        TurnEnvelopePersistenceReceipt receipt = store.persistTurnEnvelope(
                "both-blocked", record(trace.traceId()), trace);

        assertEquals(TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED, receipt.state());
        assertTrue(store.loadTurns("both-blocked").isEmpty());
        assertTrue(store.loadTrace("both-blocked", trace.traceId()).isEmpty());
    }

    @Test
    void mismatchedTraceIdentityRejectsWholeEnvelope(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);

        TurnEnvelopePersistenceReceipt receipt = store.persistTurnEnvelope(
                "mismatch", record("different-trace"), trace("actual-trace"));

        assertEquals(TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED, receipt.state());
        assertTrue(store.loadTurns("mismatch").isEmpty());
        assertTrue(store.loadLatestTrace("mismatch").isEmpty());
    }

    @Test
    void mismatchedTurnNumberRejectsWholeEnvelope(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);

        TurnEnvelopePersistenceReceipt receipt = store.persistTurnEnvelope(
                "turn-mismatch", record("shared-trace"), trace("shared-trace", 2));

        assertEquals(TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED, receipt.state());
        assertTrue(store.loadTurns("turn-mismatch").isEmpty());
        assertTrue(store.loadLatestTrace("turn-mismatch").isEmpty());
    }

    @Test
    void collisionProneTraceIdentityRejectsWholeEnvelope(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);

        TurnEnvelopePersistenceReceipt receipt = store.persistTurnEnvelope(
                "unsafe-trace", record("a/b"), trace("a/b"));

        assertEquals(TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED, receipt.state());
        assertTrue(store.loadTurns("unsafe-trace").isEmpty());
        assertTrue(store.loadLatestTrace("unsafe-trace").isEmpty());
    }

    @Test
    void nonPositiveTurnIdentityRejectsWholeEnvelope(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);

        assertEquals(TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED,
                store.persistTurnEnvelope(
                        "bad-row", record("trace", 0), trace("trace", 1)).state());
        assertEquals(TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED,
                store.persistTurnEnvelope(
                        "bad-trace", record("trace", 1), trace("trace", 0)).state());
        assertTrue(store.loadTurns("bad-row").isEmpty());
        assertTrue(store.loadTurns("bad-trace").isEmpty());
        assertTrue(store.loadLatestTrace("bad-row").isEmpty());
        assertTrue(store.loadLatestTrace("bad-trace").isEmpty());
    }

    @Test
    void overlongOrUnsafeRowOnlyTraceIdentityRejectsWithoutWrites(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String overlong = "t".repeat(129);

        assertEquals(TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED,
                store.persistTurnEnvelope(
                        "overlong", record(overlong), trace(overlong)).state());
        assertEquals(TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED,
                store.persistTurnEnvelope(
                        "unsafe-row", record("a?b"), null).state());
        assertTrue(store.loadTurns("overlong").isEmpty());
        assertTrue(store.loadTurns("unsafe-row").isEmpty());
        assertTrue(store.loadLatestTrace("overlong").isEmpty());
        assertTrue(store.loadLatestTrace("unsafe-row").isEmpty());
    }

    @Test
    void loadTraceRejectsFilenameContentIdentityMismatch(@TempDir Path dir) throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        store.saveTrace("corrupt", trace("actual-trace"));
        Path traceDir = dir.resolve("traces/corrupt");
        Files.move(
                traceDir.resolve("000001-actual-trace.json"),
                traceDir.resolve("000001-requested-trace.json"));

        assertTrue(store.loadTrace("corrupt", "requested-trace").isEmpty());
        assertTrue(store.loadLatestTrace("corrupt").isEmpty());
    }

    @Test
    void loadTraceRejectsFilenameTurnMismatch(@TempDir Path dir) throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        store.saveTrace("corrupt-turn", trace("same-trace", 2));
        Path traceDir = dir.resolve("traces/corrupt-turn");
        Files.move(
                traceDir.resolve("000002-same-trace.json"),
                traceDir.resolve("000001-same-trace.json"));

        assertTrue(store.loadTrace("corrupt-turn", "same-trace").isEmpty());
        assertTrue(store.loadLatestTrace("corrupt-turn").isEmpty());
    }

    @Test
    void legacyAndEnvelopeWritersRemainByteIdentical(@TempDir Path dir) throws Exception {
        Path legacyDir = dir.resolve("legacy");
        Path envelopeDir = dir.resolve("envelope");
        JsonSessionStore legacy = new JsonSessionStore(legacyDir);
        JsonSessionStore envelope = new JsonSessionStore(envelopeDir);
        LocalTurnTrace trace = trace("trace-identical");
        TurnRecord record = record(trace.traceId());

        legacy.saveTrace("sid", trace);
        legacy.appendTurn("sid", record);
        assertEquals(TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED,
                envelope.persistTurnEnvelope("sid", record, trace).state());

        assertArrayEquals(
                Files.readAllBytes(legacyDir.resolve("sid.turns.jsonl")),
                Files.readAllBytes(envelopeDir.resolve("sid.turns.jsonl")));
        assertArrayEquals(
                Files.readAllBytes(legacyDir.resolve("traces/sid/000001-trace-identical.json")),
                Files.readAllBytes(envelopeDir.resolve("traces/sid/000001-trace-identical.json")));
    }

    @Test
    void legacyStoreDefaultReportsUnobservedInsteadOfSuccess() {
        int[] calls = {0, 0};
        SessionStore legacy = new SessionStore() {
            @Override public void save(SessionData data) {}
            @Override public Optional<SessionData> load(String sessionId) { return Optional.empty(); }
            @Override public boolean delete(String sessionId) { return false; }
            @Override public void saveTrace(String sessionId, LocalTurnTrace trace) { calls[0]++; }
            @Override public void appendTurn(String sessionId, TurnRecord record) { calls[1]++; }
        };
        LocalTurnTrace trace = trace("legacy-unobserved");

        TurnEnvelopePersistenceReceipt receipt = legacy.persistTurnEnvelope(
                "sid", record(trace.traceId()), trace);

        assertEquals(TurnEnvelopePersistenceReceipt.State.UNOBSERVED, receipt.state());
        assertEquals(1, calls[0]);
        assertEquals(1, calls[1]);
    }

    @Test
    void legacyStoreDefaultAttemptsRowWhenTraceWriteThrows() {
        List<String> calls = new ArrayList<>();
        SessionStore legacy = new SessionStore() {
            @Override public void save(SessionData data) {}
            @Override public Optional<SessionData> load(String sessionId) { return Optional.empty(); }
            @Override public boolean delete(String sessionId) { return false; }
            @Override public void saveTrace(String sessionId, LocalTurnTrace trace) {
                calls.add("trace");
                throw new IllegalStateException("trace write failed");
            }
            @Override public void appendTurn(String sessionId, TurnRecord record) {
                calls.add("row");
            }
        };
        LocalTurnTrace trace = trace("legacy-trace-fails");

        TurnEnvelopePersistenceReceipt receipt = legacy.persistTurnEnvelope(
                "sid", record(trace.traceId()), trace);

        assertEquals(TurnEnvelopePersistenceReceipt.State.UNOBSERVED, receipt.state());
        assertEquals(List.of("trace", "row"), calls);
    }

    @Test
    void legacyStoreDefaultReturnsUnobservedWhenRowWriteThrows() {
        List<String> calls = new ArrayList<>();
        SessionStore legacy = new SessionStore() {
            @Override public void save(SessionData data) {}
            @Override public Optional<SessionData> load(String sessionId) { return Optional.empty(); }
            @Override public boolean delete(String sessionId) { return false; }
            @Override public void saveTrace(String sessionId, LocalTurnTrace trace) {
                calls.add("trace");
            }
            @Override public void appendTurn(String sessionId, TurnRecord record) {
                calls.add("row");
                throw new IllegalStateException("row write failed");
            }
        };
        LocalTurnTrace trace = trace("legacy-row-fails");

        TurnEnvelopePersistenceReceipt receipt = legacy.persistTurnEnvelope(
                "sid", record(trace.traceId()), trace);

        assertEquals(TurnEnvelopePersistenceReceipt.State.UNOBSERVED, receipt.state());
        assertEquals(List.of("trace", "row"), calls);
    }

    private static LocalTurnTrace trace(String traceId) {
        return trace(traceId, 1);
    }

    private static LocalTurnTrace trace(String traceId, int turnNumber) {
        return LocalTurnTrace.builder(traceId, "sid", turnNumber, "2026-07-21T00:00:00Z")
                .workspaceHash("workspace")
                .mode("ask")
                .model("llama.cpp", "model")
                .build();
    }

    private static TurnRecord record(String traceId) {
        return record(traceId, 1);
    }

    private static TurnRecord record(String traceId, int turnNumber) {
        return new TurnRecord(
                turnNumber,
                Instant.parse("2026-07-21T00:00:01Z"),
                42,
                "inspect",
                "answer",
                List.of(new TurnRecord.ToolCallSummary("talos.read_file", "README.md", true)),
                0,
                0,
                0,
                "1 stages, 1.0ms, final=1",
                "ok",
                TurnPolicyTrace.empty(),
                traceId);
    }
}
