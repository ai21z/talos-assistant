package dev.talos.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.safety.ContentRestrictionScope;
import dev.talos.safety.ContentRestrictions;
import dev.talos.tools.*;
import dev.talos.tools.impl.FileEditTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real edit results are checked at audit capture, without provider or permission simulation. */
class ExecutedEditAuditCaptureTest {
    @TempDir Path workspace;
    private static final String BEFORE = "Ordinary local before text.";
    private static final String AFTER = "Ordinary local after text.";

    @AfterEach void clear() { TurnAuditCapture.end(); }

    @Test void exactExecutedAssociationRetainsOnlyEphemeralDetail() throws Exception {
        var result = edit("note.txt", BEFORE, AFTER);
        assertTrue(result.executedEditDetail().available());
        TurnAuditCapture.begin();
        capture("note.txt", result);
        var audit = TurnAuditCapture.end();
        var call = audit.toolCalls().getFirst();
        assertTrue(call.executedEditDetail().available());
        assertEquals(result.executedEditDetail(), call.executedEditDetail());
        assertEquals(TurnRecord.ToolCallDisposition.EXECUTED, call.executionDisposition());
        assertEquals(List.of("note.txt"), call.mutationEffects().appliedPaths());
        String serialized = new ObjectMapper().writeValueAsString(call);
        assertFalse(serialized.contains("executedEditDetail"));
        for (String surface : List.of(serialized, call.toString(), audit.toString())) {
            assertFalse(surface.contains(BEFORE));
            assertFalse(surface.contains(AFTER));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"model", "artifact", "history"})
    void sourceDestinationDenialPreventsRetentionWithoutChangingEffects(String destination) throws Exception {
        var result = edit("note.txt", BEFORE, AFTER);
        TurnAuditCapture.begin();
        ContentRestrictionScope.restrict(new ContentRestrictions(!destination.equals("model"),
                !destination.equals("artifact"), !destination.equals("history"), true));
        capture("note.txt", result);
        var call = TurnAuditCapture.end().toolCalls().getFirst();
        assertFalse(call.executedEditDetail().available());
        assertTrue(call.success());
        assertEquals(1, call.mutationEffects().appliedPaths().size());
        assertEquals(TurnRecord.ToolCallDisposition.EXECUTED, call.executionDisposition());
    }

    @Test void indexOnlyDenialDoesNotInventDisplayOrHistoryDenial() throws Exception {
        var result = edit("note.txt", BEFORE, AFTER);
        TurnAuditCapture.begin();
        ContentRestrictionScope.restrict(new ContentRestrictions(true, true, true, false));
        capture("note.txt", result);
        assertTrue(TurnAuditCapture.end().toolCalls().getFirst().executedEditDetail().available());
    }

    @Test void associationCannotBeMovedToAnotherTargetToolOrDisposition() throws Exception {
        var result = edit("note.txt", BEFORE, AFTER);
        var detail = result.executedEditDetail();
        for (var call : List.of(
                new TurnRecord.ToolCallSummary("talos.write_file", "note.txt", List.of("note.txt"), true, "",
                        result.mutationEffects(), TurnRecord.ToolCallDisposition.EXECUTED, ToolFailureReason.NONE, detail),
                new TurnRecord.ToolCallSummary("talos.edit_file", "different.txt", List.of("note.txt"), true, "",
                        result.mutationEffects(), TurnRecord.ToolCallDisposition.EXECUTED, ToolFailureReason.NONE, detail),
                new TurnRecord.ToolCallSummary("talos.edit_file", "note.txt", List.of("different.txt"), true, "",
                        ToolMutationEffects.applied("different.txt"), TurnRecord.ToolCallDisposition.EXECUTED,
                        ToolFailureReason.NONE, detail),
                new TurnRecord.ToolCallSummary("talos.edit_file", "note.txt", List.of("note.txt"), true, "",
                        result.mutationEffects(), TurnRecord.ToolCallDisposition.UNKNOWN, ToolFailureReason.NONE, detail),
                new TurnRecord.ToolCallSummary("talos.edit_file", "note.txt", List.of("note.txt"), false, "denied",
                        ToolMutationEffects.none(), TurnRecord.ToolCallDisposition.BLOCKED,
                        ToolFailureReason.USER_APPROVAL_DENIED, detail),
                new TurnRecord.ToolCallSummary("talos.edit_file", "note.txt", List.of("note.txt"), false, "uncertain",
                        new ToolMutationEffects(List.of("note.txt"), List.of("other.txt")),
                        TurnRecord.ToolCallDisposition.EXECUTED, ToolFailureReason.NONE, detail))) {
            assertFalse(call.executedEditDetail().available());
        }
        assertFalse(new TurnRecord.ToolCallSummary("talos.edit_file", "note.txt", List.of("note.txt"),
                true, "", result.mutationEffects(), TurnRecord.ToolCallDisposition.EXECUTED,
                ToolFailureReason.NONE).executedEditDetail().available());
    }

    @Test void detailBudgetDropsWholeDetailsButPreservesEveryCallAndEffect() throws Exception {
        String before = "Ordinary old text. ".repeat(16);
        String after = "Ordinary new text. ".repeat(16);
        var result = edit("note.txt", before, after);
        assertTrue(result.executedEditDetail().available());
        int cost = before.length() + after.length();
        int count = 4096 / cost + 2;
        TurnAuditCapture.begin();
        for (int i = 0; i < count; i++) capture("note.txt", result);
        var calls = TurnAuditCapture.end().toolCalls();
        assertEquals(count, calls.size());
        assertEquals(4096 / cost, calls.stream().filter(call -> call.executedEditDetail().available()).count());
        assertTrue(calls.stream().allMatch(call -> call.success()
                && call.mutationEffects().equals(result.mutationEffects())));
        assertTrue(calls.stream().filter(call -> call.executedEditDetail().available()).allMatch(call ->
                call.executedEditDetail().before().equals(before) && call.executedEditDetail().after().equals(after)));
    }

    @Test void inactiveAuditDoesNotCreateRetention() throws Exception {
        var result = edit("note.txt", BEFORE, AFTER);
        capture("note.txt", result);
        assertTrue(TurnAuditCapture.end().toolCalls().isEmpty());
    }

    private ToolResult edit(String path, String before, String after) throws Exception {
        Files.writeString(workspace.resolve(path), before);
        var context = new ToolContext(workspace, new Sandbox(workspace, Map.of()), new Config());
        var result = new FileEditTool().execute(new ToolCall("talos.edit_file",
                Map.of("path", path, "old_string", before, "new_string", after)), context);
        assertTrue(result.success());
        assertEquals(after, Files.readString(workspace.resolve(path)));
        return result;
    }

    private static void capture(String path, ToolResult result) {
        TurnAuditCapture.recordExecutedToolCall(new ToolCall("talos.edit_file", Map.of("path", path)), path,
                List.of(path), result.success(), "", result.mutationEffects(), ToolFailureReason.NONE,
                result.executedEditDetail());
    }
}
