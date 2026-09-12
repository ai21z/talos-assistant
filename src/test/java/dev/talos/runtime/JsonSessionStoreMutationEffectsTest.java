package dev.talos.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.tools.ToolMutationEffects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import dev.talos.core.util.Hash;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonSessionStoreMutationEffectsTest {
    @TempDir Path sessions;
    private final ObjectMapper json = new ObjectMapper();

    @Test void effectsAndMultiplePathsRoundTripWithoutConvertingFailureToSuccess() {
        JsonSessionStore store = new JsonSessionStore(sessions);
        TurnRecord original = record();
        try (var writer = store.acquireSessionWriter("effect-shape")) {
            writer.appendTurn("effect-shape", original);
        }
        TurnRecord restored = store.loadTurns("effect-shape").getFirst();
        assertEquals(original.toolCalls(), restored.toolCalls());
        assertEquals(original.turnNumber(), restored.turnNumber());
        assertEquals(original.timestamp(), restored.timestamp());
        assertEquals(original.durationMs(), restored.durationMs());
        assertEquals(original.userInput(), restored.userInput());
        assertEquals(original.assistantText(), restored.assistantText());
        assertEquals(original.status(), restored.status());
        assertEquals(original.traceId(), restored.traceId());
        assertEquals(original.approvalsRequired(), restored.approvalsRequired());
        assertEquals(original.approvalsGranted(), restored.approvalsGranted());
        assertEquals(original.approvalsDenied(), restored.approvalsDenied());
        assertEquals(original.retrievalTraceSummary(), restored.retrievalTraceSummary());
    }

    @Test void rejectsMalformedOrIncompleteEffectFieldsInsteadOfDroppingThem() throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        try (var writer = store.acquireSessionWriter("effect-shape")) {
            writer.appendTurn("effect-shape", record());
        }
        Path file = sessions.resolve("effect-shape.turns.jsonl");
        String valid = Files.readString(file);
        for (int defect = 0; defect < 10; defect++) {
            ObjectNode row = (ObjectNode) json.readTree(valid);
            ObjectNode call = (ObjectNode) row.path("toolCalls").get(0);
            ObjectNode effects = (ObjectNode) call.get("mutationEffects");
            switch (defect) {
                case 0 -> effects.remove("appliedPaths");
                case 1 -> effects.put("uncertainPaths", "not-an-array");
                case 2 -> call.remove("pathHints");
                case 3 -> call.remove("mutationEffects");
                case 4 -> effects.put("unknown-extra-field", true);
                case 5 -> call.putArray("name").add("talos.apply_workspace_batch");
                case 6 -> call.put("pathHint", false);
                case 7 -> call.put("success", "false");
                case 8 -> call.put("reason", 7);
                case 9 -> call.putNull("name");
                default -> throw new AssertionError();
            }
            Files.writeString(file, json.writeValueAsString(row) + "\n");
            assertTrue(store.loadTurns("effect-shape").isEmpty(), "Malformed effect case " + defect);
            assertEquals(TurnLogTailObservation.State.INVALID,
                    store.observeTurnLogTail("effect-shape").state(), "Malformed effect case " + defect);
        }
    }

    @Test void legacyRowsRemainReadableWithoutInventingHistoricalEffectEvidence() throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        try (var writer = store.acquireSessionWriter("effect-shape")) {
            writer.appendTurn("effect-shape", record());
        }
        Path file = sessions.resolve("effect-shape.turns.jsonl");
        ObjectNode row = (ObjectNode) json.readTree(Files.readString(file));
        ObjectNode call = (ObjectNode) row.path("toolCalls").get(0);
        call.remove("mutationEffects");
        call.remove("pathHints");
        Files.writeString(file, json.writeValueAsString(row) + "\n");
        TurnRecord restored = store.loadTurns("effect-shape").getFirst();
        assertFalse(restored.toolCalls().getFirst().success());
        assertFalse(restored.toolCalls().getFirst().mutationEffects().hasEffects());
    }

    @Test void exactLegacyRowsRemainObservableWithoutWeakeningCurrentRows() throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        try (var writer = store.acquireSessionWriter("effect-shape")) {
            writer.appendTurn("effect-shape", record());
        }
        assertTrue(store.observeTurnLogTail("effect-shape").exact());
        Path file = sessions.resolve("effect-shape.turns.jsonl");
        ObjectNode row = (ObjectNode) json.readTree(Files.readString(file));
        ObjectNode legacyCall = (ObjectNode) row.path("toolCalls").get(0);
        legacyCall.remove(List.of("pathHints", "mutationEffects"));
        String legacy = json.writeValueAsString(row);
        Files.writeString(file, legacy + "\n");
        TurnLogTailObservation observation = store.observeTurnLogTail("effect-shape");
        assertTrue(observation.exact());
        assertEquals(Hash.sha256Hex(legacy.getBytes(StandardCharsets.UTF_8)), observation.rowSha256());
        assertFalse(observation.turn().toolCalls().getFirst().mutationEffects().hasEffects());
        legacyCall.put("unexpected", true);
        Files.writeString(file, json.writeValueAsString(row) + "\n");
        assertEquals(TurnLogTailObservation.State.INVALID, store.observeTurnLogTail("effect-shape").state());
    }

    @Test void mixedLegacyAndCurrentCallsRetainExactObservableShape() throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        try (var writer = store.acquireSessionWriter("effect-shape")) {
            writer.appendTurn("effect-shape", record());
        }
        Path file = sessions.resolve("effect-shape.turns.jsonl");
        ObjectNode row = (ObjectNode) json.readTree(Files.readString(file));
        var calls = (com.fasterxml.jackson.databind.node.ArrayNode) row.get("toolCalls");
        ObjectNode legacy = ((ObjectNode) calls.get(0)).deepCopy();
        legacy.remove(List.of("pathHints", "mutationEffects"));
        calls.add(legacy);
        Files.writeString(file, json.writeValueAsString(row) + "\n");
        TurnLogTailObservation observation = store.observeTurnLogTail("effect-shape");
        assertTrue(observation.exact());
        assertTrue(observation.turn().toolCalls().getFirst().mutationEffects().hasEffects());
        assertFalse(observation.turn().toolCalls().getLast().mutationEffects().hasEffects());
    }

    private static TurnRecord record() {
        return new TurnRecord(1, Instant.parse("2026-09-05T00:00:00Z"), 1,
                "mutation", "failed with effects", List.of(new TurnRecord.ToolCallSummary(
                        "talos.apply_workspace_batch", "docs", List.of("docs", "note.txt", "maybe.txt"),
                        false, "partial", new ToolMutationEffects(List.of("docs", "note.txt"), List.of("maybe.txt")))),
                1, 1, 0, "", "error", TurnPolicyTrace.empty(), "trace-effects-1");
    }
}
