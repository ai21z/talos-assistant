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

    @Test void typedFailureCausesRoundTripAndPreviousDispositionRowsRemainUnknown() throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        var causes = List.of(dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED,
                dev.talos.tools.ToolFailureReason.PERMISSION_POLICY_DENIED,
                dev.talos.tools.ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID);
        var calls = causes.stream().map(cause -> new TurnRecord.ToolCallSummary("talos.edit_file", "note.txt",
                List.of("note.txt"), false, "Wording is not evidence of cause", ToolMutationEffects.none(),
                TurnRecord.ToolCallDisposition.BLOCKED, cause)).toList();
        var original = new TurnRecord(1, Instant.parse("2026-10-06T00:00:00Z"), 1,
                "Edit note.txt", "Blocked", calls, 1, 0, 1, "", "error", TurnPolicyTrace.empty(), "trace-causes-1");
        try (var writer = store.acquireSessionWriter("failure-cause")) {
            writer.appendTurn("failure-cause", original);
        }
        assertEquals(causes, store.loadTurns("failure-cause").getFirst().toolCalls().stream()
                .map(TurnRecord.ToolCallSummary::failureReason).toList());
        assertTrue(store.observeTurnLogTail("failure-cause").exact());
        Path file = sessions.resolve("failure-cause.turns.jsonl");
        ObjectNode row = (ObjectNode) json.readTree(Files.readString(file));
        for (var call : row.path("toolCalls")) ((ObjectNode) call).remove("failureReason");
        Files.writeString(file, json.writeValueAsString(row) + "\n");
        var legacy = store.loadTurns("failure-cause").getFirst();
        assertTrue(legacy.toolCalls().stream().allMatch(call -> call.failureReason() == dev.talos.tools.ToolFailureReason.NONE));
        assertTrue(store.observeTurnLogTail("failure-cause").exact());
        ((ObjectNode) row.path("toolCalls").get(0)).put("failureReason", "UNKNOWN_FUTURE_CAUSE");
        Files.writeString(file, json.writeValueAsString(row) + "\n");
        assertTrue(store.loadTurns("failure-cause").isEmpty());
        assertEquals(TurnLogTailObservation.State.INVALID, store.observeTurnLogTail("failure-cause").state());
    }

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

    @Test void rejectsContradictoryPreExecutionCausesBeforeEffectNormalization() throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        try (var writer = store.acquireSessionWriter("cause-invariant")) {
            writer.appendTurn("cause-invariant", record());
        }
        Path file = sessions.resolve("cause-invariant.turns.jsonl");
        String valid = Files.readString(file);
        for (var cause : List.of(dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED,
                dev.talos.tools.ToolFailureReason.PERMISSION_POLICY_DENIED,
                dev.talos.tools.ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID)) {
            for (int defect = 0; defect < 7; defect++) {
                ObjectNode row = (ObjectNode) json.readTree(valid);
                ObjectNode call = (ObjectNode) row.path("toolCalls").get(0);
                call.put("failureReason", cause.name());
                call.put("executionDisposition", "BLOCKED");
                call.put("success", false);
                ObjectNode effects = (ObjectNode) call.get("mutationEffects");
                effects.putArray("appliedPaths");
                effects.putArray("uncertainPaths");
                switch (defect) {
                    case 0 -> call.put("executionDisposition", "EXECUTED");
                    case 1 -> call.put("executionDisposition", "UNKNOWN");
                    case 2 -> call.put("success", true);
                    case 3 -> effects.putArray("appliedPaths").add("note.txt");
                    case 4 -> effects.putArray("uncertainPaths").add("note.txt");
                    case 5 -> effects.putArray("appliedPaths").add(" ");
                    case 6 -> effects.putArray("uncertainPaths").add("");
                    default -> throw new AssertionError();
                }
                Files.writeString(file, json.writeValueAsString(row) + "\n");
                String context = cause + "/defect=" + defect;
                assertTrue(store.loadTurns("cause-invariant").isEmpty(), context);
                assertEquals(TurnLogTailObservation.State.INVALID,
                        store.observeTurnLogTail("cause-invariant").state(), context);
            }
        }
    }

    @Test void absentCauseKeepsSuccessfulUnknownDispositionAndEffectEvidence() throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        try (var writer = store.acquireSessionWriter("cause-legacy")) {
            writer.appendTurn("cause-legacy", record());
        }
        Path file = sessions.resolve("cause-legacy.turns.jsonl");
        ObjectNode row = (ObjectNode) json.readTree(Files.readString(file));
        ObjectNode call = (ObjectNode) row.path("toolCalls").get(0);
        call.put("success", true);
        for (boolean typedFieldPresent : List.of(true, false)) {
            if (!typedFieldPresent) call.remove("failureReason");
            Files.writeString(file, json.writeValueAsString(row) + "\n");
            var restored = store.loadTurns("cause-legacy").getFirst().toolCalls().getFirst();
            assertTrue(restored.success());
            assertEquals(TurnRecord.ToolCallDisposition.UNKNOWN, restored.executionDisposition());
            assertEquals(dev.talos.tools.ToolFailureReason.NONE, restored.failureReason());
            assertEquals(record().toolCalls().getFirst().mutationEffects(), restored.mutationEffects());
            assertTrue(store.observeTurnLogTail("cause-legacy").exact());
        }
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
        call.remove("executionDisposition");
        call.remove("failureReason");
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
        legacyCall.remove(List.of("pathHints", "mutationEffects", "executionDisposition", "failureReason"));
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
        legacy.remove(List.of("pathHints", "mutationEffects", "executionDisposition", "failureReason"));
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
