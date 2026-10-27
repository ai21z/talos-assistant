package dev.talos.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.core.util.Hash;
import dev.talos.safety.ContentRestrictionScope;
import dev.talos.safety.ContentRestrictions;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolMutationEffects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static dev.talos.runtime.TurnRecord.ToolCallDisposition.BLOCKED;
import static dev.talos.runtime.TurnRecord.ToolCallDisposition.EXECUTED;
import static dev.talos.runtime.TurnRecord.ToolCallDisposition.UNKNOWN;
import static org.junit.jupiter.api.Assertions.*;

class ToolCallDispositionTest {
    private static final String SESSION_ID = "disposition-contract";
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path sessions;

    @AfterEach
    void clearAudit() {
        TurnAuditCapture.end();
    }

    @Test
    void executedFailureAndBlockedCallRetainDifferentRuntimeFacts() {
        TurnAuditCapture.begin();
        ToolCall call = new ToolCall("talos.run_command", Map.of());
        TurnAuditCapture.recordBlockedToolCall(call, "", "EXECUTE", "failed");
        TurnAuditCapture.recordExecutedToolCall(call, List.of(), false, "failed");

        List<TurnRecord.ToolCallSummary> calls = TurnAuditCapture.end().toolCalls();

        assertNotEquals(calls.get(0), calls.get(1),
                "A blocked call and an executed failure must retain distinct execution facts.");
        assertEquals(BLOCKED, calls.get(0).executionDisposition());
        assertEquals(EXECUTED, calls.get(1).executionDisposition());
        assertFalse(calls.get(0).success());
        assertFalse(calls.get(1).success());
        assertFalse(calls.get(1).mutationEffects().hasEffects());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void compatibilityConstructorsAndAmbiguousCaptureNeverInferDisposition(boolean success) {
        ToolMutationEffects effects = ToolMutationEffects.applied("note.txt");
        List<TurnRecord.ToolCallSummary> summaries = List.of(
                new TurnRecord.ToolCallSummary("talos.write_file", "note.txt", success),
                new TurnRecord.ToolCallSummary("talos.write_file", "note.txt", success, "blocked"),
                new TurnRecord.ToolCallSummary("talos.write_file", "note.txt", List.of("note.txt"), success, "executed"),
                new TurnRecord.ToolCallSummary("talos.write_file", "note.txt", List.of("note.txt"), success, "failed", effects));
        assertTrue(summaries.stream().allMatch(call -> call.executionDisposition() == UNKNOWN));
        assertEquals(effects, summaries.getLast().mutationEffects());

        TurnAuditCapture.begin();
        ToolCall call = new ToolCall("talos.write_file", Map.of("path", "note.txt"));
        TurnAuditCapture.recordToolCall(call.toolName(), "note.txt", success);
        TurnAuditCapture.recordToolCall(call.toolName(), "note.txt", success, "blocked");
        TurnAuditCapture.recordToolCall(call.toolName(), List.of("note.txt"), success, "executed");
        TurnAuditCapture.recordToolCall(call, "note.txt", success, "blocked");
        TurnAuditCapture.recordToolCall(call, List.of("note.txt"), success, "executed");
        List<TurnRecord.ToolCallSummary> captured = TurnAuditCapture.end().toolCalls();
        assertEquals(5, captured.size());
        assertTrue(captured.stream().allMatch(value -> value.executionDisposition() == UNKNOWN));
    }

    @Test
    void currentProducerFactsSurvivePersistenceWithoutUpgradingSuccessOrEffects() throws Exception {
        TurnAuditCapture.begin();
        ToolCall command = new ToolCall("talos.run_command", Map.of());
        TurnAuditCapture.recordBlockedToolCall(command, "", "EXECUTE", "denied", "policy denied");
        TurnAuditCapture.recordExecutedToolCall(command, List.of(), false, "exit 1");
        TurnAuditCapture.recordExecutedToolCall(command, List.of(), true, "");
        ToolMutationEffects effects = new ToolMutationEffects(List.of("applied.txt"), List.of("uncertain.txt"));
        TurnAuditCapture.recordExecutedToolCall(new ToolCall("talos.write_file", Map.of()),
                List.of("applied.txt", "uncertain.txt"), false, "partial", effects);
        TurnAuditCapture.recordToolCall("talos.run_command", "", true, "");
        List<TurnRecord.ToolCallSummary> calls = TurnAuditCapture.end().toolCalls();
        JsonSessionStore store = new JsonSessionStore(sessions);
        append(store, record(1, calls));

        List<TurnRecord.ToolCallSummary> restored = store.loadTurns(SESSION_ID).getFirst().toolCalls();
        assertEquals(calls, restored);
        assertEquals(List.of(BLOCKED, EXECUTED, EXECUTED, EXECUTED, UNKNOWN),
                restored.stream().map(TurnRecord.ToolCallSummary::executionDisposition).toList());
        assertFalse(restored.get(3).success());
        assertEquals(effects, restored.get(3).mutationEffects());
        TurnLogTailObservation tail = store.observeTurnLogTail(SESSION_ID);
        assertTrue(tail.exact());
        assertEquals(restored, tail.turn().toolCalls());
        assertEquals("BLOCKED", JSON.readTree(Files.readString(log())).path("toolCalls").get(0)
                .path("executionDisposition").asText());
    }

    @Test
    void exactLegacyFourAndSixFieldCallsStayUnknownAcrossMixedHistoryAndAppend() throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        TurnRecord.ToolCallSummary legacyFailure = new TurnRecord.ToolCallSummary(
                "talos.run_command", "", false, "blocked");
        TurnRecord.ToolCallSummary legacyEffect = new TurnRecord.ToolCallSummary(
                "talos.write_file", "note.txt", List.of("note.txt"), true, "executed",
                ToolMutationEffects.applied("note.txt"));
        TurnRecord original = record(1, List.of(legacyFailure, legacyEffect, blocked()));
        append(store, original);
        ObjectNode row = (ObjectNode) JSON.readTree(Files.readString(log()));
        ArrayNode calls = (ArrayNode) row.get("toolCalls");
        ((ObjectNode) calls.get(0)).remove(List.of("pathHints", "mutationEffects", "executionDisposition", "failureReason"));
        ((ObjectNode) calls.get(1)).remove(List.of("executionDisposition", "failureReason"));
        String legacyPrefix = JSON.writeValueAsString(row) + "\n";
        Files.writeString(log(), legacyPrefix);

        TurnLogTailObservation tail = store.observeTurnLogTail(SESSION_ID);
        assertTrue(tail.exact());
        assertEquals(original.toolCalls(), tail.turn().toolCalls());
        assertEquals(Hash.sha256Hex(legacyPrefix.stripTrailing().getBytes(StandardCharsets.UTF_8)), tail.rowSha256());
        assertEquals(List.of(UNKNOWN, UNKNOWN, BLOCKED), tail.turn().toolCalls().stream()
                .map(TurnRecord.ToolCallSummary::executionDisposition).toList());
        assertFalse(tail.turn().toolCalls().getFirst().mutationEffects().hasEffects());
        assertEquals(ToolMutationEffects.applied("note.txt"), tail.turn().toolCalls().get(1).mutationEffects());

        append(store, record(2, List.of(executedFailure())));
        assertTrue(Files.readString(log()).startsWith(legacyPrefix), "An append must preserve the physical legacy prefix.");
        assertEquals(original.toolCalls(), store.loadTurns(SESSION_ID).getFirst().toolCalls());
        TurnLogTailObservation appended = store.observeTurnLogTail(SESSION_ID);
        assertTrue(appended.exact());
        assertEquals(2, appended.appendOrdinal());
        assertEquals(EXECUTED, appended.turn().toolCalls().getFirst().executionDisposition());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "1", "1.0", "[]", "{}", "\"\"",
            "\"executed\"", "\" EXECUTED \"", "\"TESTS_PASSED\""})
    void malformedDispositionCannotBecomeRestoredEvidence(String encodedValue) throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        ObjectNode row = storedRow(store);
        firstCall(row).set("executionDisposition", JSON.readTree(encodedValue));
        assertRejected(store, row);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "pathHint", "pathHints", "success", "reason", "mutationEffects",
            "extra", "five-field", "success-string", "reason-number", "paths-string"})
    void incompleteOrMalformedCurrentShapeCannotMasqueradeAsLegacy(String defect) throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        ObjectNode row = storedRow(store);
        ObjectNode call = firstCall(row);
        switch (defect) {
            case "extra" -> call.put("extra", true);
            case "five-field" -> call.remove(List.of("pathHints", "mutationEffects"));
            case "success-string" -> call.put("success", "false");
            case "reason-number" -> call.put("reason", 1);
            case "paths-string" -> call.put("pathHints", "note.txt");
            default -> call.remove(defect);
        }
        assertRejected(store, row);
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "applied", "uncertain", "blank-effect"})
    void contradictoryBlockedRowsAreRejectedBeforeNormalization(String defect) throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        ObjectNode row = storedRow(store);
        ObjectNode call = firstCall(row);
        ObjectNode effects = (ObjectNode) call.get("mutationEffects");
        switch (defect) {
            case "success" -> call.put("success", true);
            case "applied" -> effects.putArray("appliedPaths").add("note.txt");
            case "uncertain" -> effects.putArray("uncertainPaths").add("note.txt");
            case "blank-effect" -> effects.putArray("appliedPaths").add("");
            default -> throw new AssertionError(defect);
        }
        assertRejected(store, row);
    }

    @Test
    void runtimeCannotConstructBlockedSuccessOrEffects() {
        assertThrows(IllegalArgumentException.class, () -> new TurnRecord.ToolCallSummary(
                "talos.run_command", "", List.of(), true, "", ToolMutationEffects.none(), BLOCKED));
        for (ToolMutationEffects effects : List.of(ToolMutationEffects.applied("note.txt"),
                ToolMutationEffects.uncertain("note.txt"))) {
            assertThrows(IllegalArgumentException.class, () -> new TurnRecord.ToolCallSummary(
                    "talos.write_file", "note.txt", List.of("note.txt"), false, "denied", effects, BLOCKED));
        }
    }

    @Test
    void malformedPrefixCannotGainExactObservationWhenAValidTurnIsAppended() throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        ObjectNode row = storedRow(store);
        firstCall(row).put("executionDisposition", "TESTS_PASSED");
        String invalidPrefix = JSON.writeValueAsString(row) + "\n";
        Files.writeString(log(), invalidPrefix);

        append(store, record(2, List.of(executedFailure())));

        assertTrue(Files.readString(log()).startsWith(invalidPrefix));
        List<TurnRecord> restored = store.loadTurns(SESSION_ID);
        assertEquals(1, restored.size());
        assertEquals(2, restored.getFirst().turnNumber());
        assertEquals(EXECUTED, restored.getFirst().toolCalls().getFirst().executionDisposition());
        assertEquals(TurnLogTailObservation.State.INVALID, store.observeTurnLogTail(SESSION_ID).state());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restrictedAndAbnormalProjectionPreservesRetainedDispositionWithoutRehydratingDetails(
            boolean artifactAllowed) throws Exception {
        String privatePath = "private-document-operation.txt";
        TurnAuditCapture.begin();
        ContentRestrictionScope.restrict(new ContentRestrictions(true, artifactAllowed, !artifactAllowed, true));
        ToolCall call = new ToolCall("talos.write_file", Map.of("path", privatePath));
        TurnAuditCapture.recordExecutedToolCall(call, List.of(privatePath), false, privatePath,
                new ToolMutationEffects(List.of(privatePath), List.of("private-other-path.txt")));
        TurnAuditCapture.recordBlockedToolCall(call, privatePath, "EXECUTE", privatePath);
        TurnAuditCapture.recordToolCall(call, privatePath, true, "");
        TurnAudit audit = TurnAuditCapture.end();
        assertEquals(List.of(EXECUTED, BLOCKED, UNKNOWN), audit.toolCalls().stream()
                .map(TurnRecord.ToolCallSummary::executionDisposition).toList());
        assertFalse(audit.toString().contains(privatePath));
        assertFalse(audit.toString().contains("private-other-path.txt"));
        assertEquals(audit.toolCalls(), audit.withLocalTrace(null).toolCalls());

        JsonSessionStore store = new JsonSessionStore(sessions);
        int turn = 0;
        for (TurnSettlement.Kind kind : TurnSettlement.Kind.values()) {
            TurnAudit projected = audit.safeTerminalProjection(kind, false);
            List<TurnRecord.ToolCallSummary> expected = kind == TurnSettlement.Kind.COMPLETED
                    ? audit.toolCalls() : List.of(audit.toolCalls().get(0), audit.toolCalls().get(2));
            assertEquals(expected, projected.toolCalls(), kind.name());
            append(store, new TurnRecord(++turn, Instant.parse("2026-09-27T00:00:00Z"), 1,
                    "", "", projected.toolCalls(), 0, 0, 0, "", kind.name().toLowerCase(java.util.Locale.ROOT),
                    TurnPolicyTrace.empty(), "trace-disposition-" + turn));
            assertEquals(expected, store.loadTurns(SESSION_ID).getLast().toolCalls());
        }
        assertFalse(Files.readString(log()).contains(privatePath));
        assertFalse(Files.readString(log()).contains("private-other-path.txt"));
        assertTrue(store.observeTurnLogTail(SESSION_ID).exact());
    }

    @Test
    void unrestrictedAbnormalProjectionStillDropsOrdinaryCallEvidence() {
        TurnAudit audit = new TurnAudit(List.of(blocked(), executedFailure()), 1, 0, 1);
        assertEquals(audit, audit.safeTerminalProjection(TurnSettlement.Kind.COMPLETED, false));
        for (TurnSettlement.Kind kind : List.of(TurnSettlement.Kind.NO_RESULT,
                TurnSettlement.Kind.FAILED, TurnSettlement.Kind.CANCELLED)) {
            TurnAudit projected = audit.safeTerminalProjection(kind, false);
            assertTrue(projected.toolCalls().isEmpty(), kind.name());
            assertEquals(1, projected.approvalsDenied());
        }
    }

    private ObjectNode storedRow(JsonSessionStore store) throws Exception {
        append(store, record(1, List.of(blocked())));
        assertTrue(store.observeTurnLogTail(SESSION_ID).exact(), "The unmodified stored fixture must be exact.");
        return (ObjectNode) JSON.readTree(Files.readString(log()));
    }

    private void assertRejected(JsonSessionStore store, ObjectNode row) throws Exception {
        Files.writeString(log(), JSON.writeValueAsString(row) + "\n");
        assertTrue(store.loadTurns(SESSION_ID).isEmpty());
        TurnLogTailObservation tail = store.observeTurnLogTail(SESSION_ID);
        assertEquals(TurnLogTailObservation.State.INVALID, tail.state());
        assertNull(tail.turn());
        assertTrue(tail.rowSha256().isEmpty());
    }

    private static ObjectNode firstCall(ObjectNode row) {
        return (ObjectNode) row.path("toolCalls").get(0);
    }

    private Path log() {
        return sessions.resolve(SESSION_ID + ".turns.jsonl");
    }

    private static void append(JsonSessionStore store, TurnRecord row) {
        try (SessionWriterLease writer = store.acquireSessionWriter(SESSION_ID)) {
            writer.appendTurn(SESSION_ID, row);
        }
    }

    private static TurnRecord record(int number, List<TurnRecord.ToolCallSummary> calls) {
        return new TurnRecord(number, Instant.parse("2026-09-27T00:00:00Z"), 1,
                "retained work", "recorded", calls, 0, 0, 0, "", "",
                TurnPolicyTrace.empty(), "trace-disposition-" + number);
    }

    private static TurnRecord.ToolCallSummary blocked() {
        return new TurnRecord.ToolCallSummary("talos.run_command", "", List.of(), false,
                "denied", ToolMutationEffects.none(), BLOCKED);
    }

    private static TurnRecord.ToolCallSummary executedFailure() {
        return new TurnRecord.ToolCallSummary("talos.run_command", "", List.of(), false,
                "exit 1", ToolMutationEffects.none(), EXECUTED);
    }
}
