package dev.talos.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.core.util.Hash;
import dev.talos.runtime.intent.WorkspaceOperationObligation.Role;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.safety.ContentRestrictions;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolMutationEffects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static dev.talos.runtime.TurnRecord.ToolCallDisposition.BLOCKED;
import static dev.talos.runtime.TurnRecord.ToolCallDisposition.EXECUTED;
import static org.junit.jupiter.api.Assertions.*;

/** The operands of a workspace operation are persisted exactly, older rows stay exact, and private paths are hashed. */
class OperationOperandRecordTest {
    private static final String SESSION_ID = "operand-record";
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path sessions;

    @Test
    void operandsSurvivePersistenceAndTheStoredTailStaysExact() throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        List<TurnRecord.ToolCallSummary> calls = List.of(deniedMove(), appliedDelete(),
                new TurnRecord.ToolCallSummary("talos.write_file", "c.md", List.of("c.md"), true, "",
                        ToolMutationEffects.applied("c.md"), EXECUTED));
        append(store, record(1, calls));

        assertEquals(calls, store.loadTurns(SESSION_ID).getFirst().toolCalls());
        TurnLogTailObservation tail = store.observeTurnLogTail(SESSION_ID);
        assertTrue(tail.exact());
        assertEquals(calls, tail.turn().toolCalls());
        ArrayNode stored = (ArrayNode) JSON.readTree(Files.readString(log())).path("toolCalls");
        assertEquals("[{\"path\":\"a.md\",\"role\":\"SOURCE\"},{\"path\":\"archive/a.md\",\"role\":\"DESTINATION\"}]",
                JSON.writeValueAsString(stored.get(0).path("operands")));
        assertEquals("[]", JSON.writeValueAsString(stored.get(2).path("operands")));
    }

    @Test
    void aStoredRowWithoutOperandsStaysReadableAndExact() throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        TurnRecord.ToolCallSummary legacyMove = new TurnRecord.ToolCallSummary("talos.move_path", "a.md",
                List.of("a.md"), false, "approval denied", ToolMutationEffects.none(), BLOCKED,
                ToolFailureReason.USER_APPROVAL_DENIED);
        append(store, record(1, List.of(legacyMove)));
        ObjectNode row = (ObjectNode) JSON.readTree(Files.readString(log()));
        ((ObjectNode) row.path("toolCalls").get(0)).remove("operands");
        String legacyPrefix = JSON.writeValueAsString(row) + "\n";
        Files.writeString(log(), legacyPrefix);

        TurnLogTailObservation tail = store.observeTurnLogTail(SESSION_ID);
        assertTrue(tail.exact());
        assertEquals(List.of(legacyMove), tail.turn().toolCalls());
        assertEquals(Hash.sha256Hex(legacyPrefix.stripTrailing().getBytes(StandardCharsets.UTF_8)), tail.rowSha256());
        append(store, record(2, List.of(deniedMove())));
        assertTrue(Files.readString(log()).startsWith(legacyPrefix));
        assertEquals(List.of(legacyMove), store.loadTurns(SESSION_ID).getFirst().toolCalls());
        assertTrue(store.observeTurnLogTail(SESSION_ID).exact());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-list", "element-string", "missing-role", "missing-path", "unknown-role",
            "lower-role", "blank-path", "extra-key", "duplicate-role", "wrong-kind-role", "operands-on-write",
            "emptied-operands", "legacy-with-operands", "legacy-with-malformed-operands"})
    void malformedStoredOperandsCannotBecomeRestoredEvidence(String defect) throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        append(store, record(1, List.of(deniedMove(), new TurnRecord.ToolCallSummary("talos.write_file", "c.md",
                List.of("c.md"), true, "", ToolMutationEffects.applied("c.md"), EXECUTED))));
        assertTrue(store.observeTurnLogTail(SESSION_ID).exact());
        ObjectNode row = (ObjectNode) JSON.readTree(Files.readString(log()));
        ObjectNode move = (ObjectNode) row.path("toolCalls").get(0);
        ObjectNode write = (ObjectNode) row.path("toolCalls").get(1);
        ArrayNode operands = (ArrayNode) move.get("operands");
        ObjectNode first = (ObjectNode) operands.get(0);
        switch (defect) {
            case "not-a-list" -> move.put("operands", "a.md");
            case "element-string" -> operands.set(0, JSON.getNodeFactory().textNode("a.md"));
            case "missing-role" -> first.remove("role");
            case "missing-path" -> first.remove("path");
            case "unknown-role" -> first.put("role", "RELOCATED");
            case "lower-role" -> first.put("role", "source");
            case "blank-path" -> first.put("path", " ");
            case "extra-key" -> first.put("extra", true);
            case "duplicate-role" -> ((ObjectNode) operands.get(1)).put("role", "SOURCE");
            case "wrong-kind-role" -> first.put("role", "TARGET");
            case "operands-on-write" -> write.set("operands", operands.deepCopy());
            case "emptied-operands" -> move.putArray("operands");
            // The four legacy keys beside operands are no known shape, whatever the operands hold.
            case "legacy-with-operands" ->
                    move.remove(List.of("pathHints", "mutationEffects", "executionDisposition", "failureReason"));
            case "legacy-with-malformed-operands" -> {
                move.remove(List.of("pathHints", "mutationEffects", "executionDisposition", "failureReason"));
                move.put("operands", "a.md");
            }
            default -> throw new AssertionError(defect);
        }
        Files.writeString(log(), JSON.writeValueAsString(row) + "\n");
        if ("emptied-operands".equals(defect)) {
            // An empty list is a valid current row, so emptied operands read as a call that named none.
            assertTrue(store.observeTurnLogTail(SESSION_ID).exact());
            assertEquals(List.of(), store.loadTurns(SESSION_ID).getFirst().toolCalls().getFirst().operands());
            return;
        }
        assertTrue(store.loadTurns(SESSION_ID).isEmpty(), defect);
        assertEquals(TurnLogTailObservation.State.INVALID, store.observeTurnLogTail(SESSION_ID).state(), defect);
    }

    @Test
    void aWithheldOperationKeepsItsOperandRolesWithHashedPaths() {
        TurnAudit audit = new TurnAudit(List.of(deniedMove()), 1, 0, 1, TurnPolicyTrace.empty(), null,
                new ContentRestrictions(true, false, false, true), List.of());
        TurnRecord.ToolCallSummary withheld = audit.toolCalls().getFirst();
        assertFalse(audit.toString().contains("archive/a.md"));
        assertEquals(List.of(Role.SOURCE, Role.DESTINATION),
                withheld.operands().stream().map(TurnRecord.OperationOperand::role).toList());
        assertEquals(List.of(hashed("a.md"), hashed("archive/a.md")),
                withheld.operands().stream().map(TurnRecord.OperationOperand::path).toList());
    }

    private static String hashed(String path) {
        return ProtectedContentPolicy.REDACTED_PATH + "/" + Hash.sha256Hex(path.getBytes(StandardCharsets.UTF_8));
    }

    private static TurnRecord.ToolCallSummary deniedMove() {
        return new TurnRecord.ToolCallSummary("talos.move_path", "a.md", List.of(), false, "approval denied",
                ToolMutationEffects.none(), BLOCKED, ToolFailureReason.USER_APPROVAL_DENIED, null,
                List.of(new TurnRecord.OperationOperand("a.md", Role.SOURCE),
                        new TurnRecord.OperationOperand("archive/a.md", Role.DESTINATION)));
    }

    private static TurnRecord.ToolCallSummary appliedDelete() {
        return new TurnRecord.ToolCallSummary("talos.delete_path", "b.md", List.of("b.md"), true, "",
                ToolMutationEffects.none(), EXECUTED, ToolFailureReason.NONE, null,
                List.of(new TurnRecord.OperationOperand("b.md", Role.TARGET)));
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
        return new TurnRecord(number, Instant.parse("2026-11-01T00:00:00Z"), 1,
                "retained work", "recorded", calls, 0, 0, 0, "", "",
                TurnPolicyTrace.empty(), "trace-operand-" + number);
    }
}
