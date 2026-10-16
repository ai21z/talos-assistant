package dev.talos.runtime;

import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.safety.ContentRestrictions;
import dev.talos.tools.ToolMutationEffects;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.impl.FileWriteTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static dev.talos.runtime.TurnRecord.ToolCallDisposition.*;
import static org.junit.jupiter.api.Assertions.*;

/** Constructed audits and real file-tool fixtures exercise memory recording. No subprocesses are launched. */
class HistoricalAttributionEvidenceTest {
    private static final String WORKSPACE = JsonSessionStore.sessionIdFor(Path.of("constructed-workspace"));
    private static final String OTHER_WORKSPACE = JsonSessionStore.sessionIdFor(Path.of("other-constructed-workspace"));
    @TempDir Path workspace;

    @Test
    void failedAppliedEditRequiresTheDeclaredFileTargetAndMatchingWorkspace() {
        var memory = new SessionMemory();
        record(memory, 1, WORKSPACE, List.of(call("edit_file", false, EXECUTED,
                new ToolMutationEffects(List.of("./README.md"), List.of("uncertain.txt")))),
                ContentRestrictions.UNRESTRICTED);

        var turn = memory.historicalAttributionEvidence().turns().getFirst();
        var call = turn.calls().getFirst();
        assertEquals("talos.edit_file", call.canonicalToolName());
        assertFalse(call.reportedSuccess());
        assertEquals(List.of("README.md"), call.appliedTargets());
        assertEquals(List.of("uncertain.txt"), call.uncertainTargets());
        assertEquals("README.md", call.declaredTarget());
        assertTrue(turn.hasAppliedEdit(WORKSPACE, ".\\README.md"));
        assertFalse(turn.hasAppliedEdit(WORKSPACE, "other.txt"));
        assertFalse(turn.hasAppliedEdit(OTHER_WORKSPACE, "README.md"));
        assertFalse(turn.hasAppliedEdit("", "README.md"));
        assertFalse(turn.hasAppliedEdit(WORKSPACE, "uncertain.txt"));
        assertFalse(turn.hasAppliedEdit(WORKSPACE, "unrelated.txt"));
        assertFalse(memory.toolEvidence().getFirst().success());
    }

    @Test
    void realFileWriteParentCreationIsNotAnEditOfTheParent() throws IOException {
        var request = new ToolCall("talos.write_file", Map.of("path", "release.v1/README.md", "content", "new file"));
        var result = new FileWriteTool().execute(request,
                new ToolContext(workspace, new Sandbox(workspace, Map.of()), new Config()));
        assertTrue(result.success(), result.errorMessage());
        assertEquals("new file", Files.readString(workspace.resolve(request.param("path"))));
        assertEquals(List.of("release.v1/README.md", "release.v1"), result.mutationEffects().appliedPaths());
        var memory = new SessionMemory();
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        record(memory, 1, workspaceId, List.of(call(request.toolName(), request.param("path"), result.success(),
                EXECUTED, result.mutationEffects())), ContentRestrictions.UNRESTRICTED);
        var turn = memory.historicalAttributionEvidence().turns().getFirst();
        assertTrue(turn.hasAppliedEdit(workspaceId, "release.v1/README.md"));
        assertFalse(turn.hasAppliedEdit(workspaceId, "release.v1"));
        assertTrue(turn.calls().getFirst().appliedTargets().contains("release.v1"));
    }

    @Test
    void realFileWriteFailureAfterParentCreationDoesNotEstablishAnyFileEdit() {
        var request = new ToolCall("talos.write_file", Map.of("path", "failed.v1/README.md", "content", "\ud800"));
        var result = new FileWriteTool().execute(request,
                new ToolContext(workspace, new Sandbox(workspace, Map.of()), new Config()));
        assertFalse(result.success());
        assertTrue(Files.isDirectory(workspace.resolve("failed.v1")));
        assertFalse(Files.exists(workspace.resolve(request.param("path"))));
        assertEquals(List.of("failed.v1"), result.mutationEffects().appliedPaths());
        assertTrue(result.mutationEffects().uncertainPaths().isEmpty());
        var memory = new SessionMemory();
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        // The audit producer must preserve the request target even when its only effect was the parent.
        record(memory, 1, workspaceId, List.of(call(request.toolName(), request.param("path"), result.success(),
                EXECUTED, result.mutationEffects())), ContentRestrictions.UNRESTRICTED);
        var turn = memory.historicalAttributionEvidence().turns().getFirst();
        assertEquals("failed.v1/README.md", turn.calls().getFirst().declaredTarget());
        assertNotEquals(result.mutationEffects().affectedPaths().getFirst(), turn.calls().getFirst().declaredTarget());
        assertFalse(turn.hasAppliedEdit(workspaceId, "failed.v1/README.md"));
        assertFalse(turn.hasAppliedEdit(workspaceId, "failed.v1"));
    }

    @Test
    void missingOrOmittedDeclaredTargetCannotSupportAnAppliedEdit() {
        for (String target : List.of("", "x".repeat(HistoricalAttributionEvidence.MAX_TARGET_CHARS + 1))) {
            var summary = new TurnRecord.ToolCallSummary("talos.write_file", target, List.of(), true,
                    "", ToolMutationEffects.applied("README.md"), EXECUTED);
            var memory = new SessionMemory();
            record(memory, 1, WORKSPACE, List.of(summary), ContentRestrictions.UNRESTRICTED);
            var turn = memory.historicalAttributionEvidence().turns().getFirst();
            assertEquals("", turn.calls().getFirst().declaredTarget());
            assertEquals(!target.isEmpty(), turn.calls().getFirst().declaredTargetOmitted());
            assertFalse(turn.hasAppliedEdit(WORKSPACE, "README.md"));
        }
    }

    @Test
    void successfulNoEffectLegacyBlockedAndNonEditEffectsCannotEstablishAnEdit() {
        var memory = new SessionMemory();
        record(memory, 1, WORKSPACE, List.of(
                call("talos.edit_file", true, EXECUTED, ToolMutationEffects.none()),
                call("talos.write_file", true, UNKNOWN, ToolMutationEffects.applied("README.md")),
                call("talos.edit_file", false, BLOCKED, ToolMutationEffects.none()),
                call("talos.run_command", true, EXECUTED, ToolMutationEffects.applied("README.md")),
                call("talos.mkdir", true, EXECUTED, ToolMutationEffects.applied("README.md")),
                call("private-custom-operation", true, EXECUTED, ToolMutationEffects.applied("README.md"))),
                ContentRestrictions.UNRESTRICTED);
        var turn = memory.historicalAttributionEvidence().turns().getFirst();
        assertFalse(turn.hasAppliedEdit(WORKSPACE, "README.md"));
        assertEquals("", turn.calls().getLast().canonicalToolName());
        assertFalse(turn.toString().contains("private-custom-operation"));
        assertTrue(turn.calls().get(3).commandReachedExecution());
        assertFalse(turn.calls().getFirst().commandReachedExecution());
    }

    @Test
    void onlyExecutedCommandsEstablishReachingTheToolBoundary() {
        var memory = new SessionMemory();
        record(memory, 1, WORKSPACE, List.of(
                call("talos.run_command", false, EXECUTED, ToolMutationEffects.none()),
                call("talos.run_command", true, UNKNOWN, ToolMutationEffects.none()),
                call("talos.run_command", false, BLOCKED, ToolMutationEffects.none())),
                ContentRestrictions.UNRESTRICTED);
        assertEquals(List.of(true, false, false), memory.historicalAttributionEvidence().turns().getFirst()
                .calls().stream().map(HistoricalAttributionEvidence.Call::commandReachedExecution).toList());
        // Neither prose nor a static PASSED trace adds a subprocess or test execution receipt.
        assertFalse(memory.historicalAttributionEvidence().toString().contains("PASSED"));
        assertFalse(memory.historicalAttributionEvidence().toString().contains("tests passed"));
    }

    @Test
    void absentMalformedAndUnavailableWorkspaceIdentityCannotSupportNamedEdits() {
        for (String workspace : List.of("", "sha256:unavailable", "arbitrary-workspace", "a".repeat(64),
                "sha256:" + "a".repeat(64))) {
            var memory = new SessionMemory();
            record(memory, 1, workspace, List.of(call("talos.write_file", true, EXECUTED,
                    ToolMutationEffects.applied("README.md"))), ContentRestrictions.UNRESTRICTED);
            var turn = memory.historicalAttributionEvidence().turns().getFirst();
            assertEquals("", turn.workspaceHash());
            assertFalse(turn.hasAppliedEdit(workspace, "README.md"));
        }
        var withoutTrace = new SessionMemory();
        withoutTrace.recordWorkStatus(new TurnResult(new Result.Ok("Constructed answer"), null, 1,
                Duration.ZERO, new TurnAudit(List.of(call("talos.edit_file", true, EXECUTED,
                ToolMutationEffects.applied("README.md"))), 0, 0, 0)));
        assertFalse(withoutTrace.historicalAttributionEvidence().turns().getFirst()
                .hasAppliedEdit(WORKSPACE, "README.md"));
    }

    @Test
    void privacyFilteredEffectsRemainWithheldAndCannotBeUsedAsNamedEditProof() {
        var memory = new SessionMemory();
        record(memory, 1, WORKSPACE, List.of(call("talos.edit_file", "private-secret.txt", true, EXECUTED,
                ToolMutationEffects.applied("private-secret.txt"))),
                new ContentRestrictions(false, false, false, false));
        var turn = memory.historicalAttributionEvidence().turns().getFirst();
        assertFalse(turn.toString().contains("private-secret.txt"));
        assertFalse(turn.hasAppliedEdit(WORKSPACE, "private-secret.txt"));
        String withheld = turn.calls().getFirst().appliedTargets().getFirst();
        assertFalse(turn.hasAppliedEdit(WORKSPACE, withheld));
        assertEquals(EXECUTED, turn.calls().getFirst().disposition());
    }

    @Test
    void projectionBoundsCallsPathsAndCharactersAndRecordsEachOmission() {
        var memory = new SessionMemory();
        var paths = IntStream.range(0, HistoricalAttributionEvidence.MAX_TARGETS_PER_LIST + 1)
                .mapToObj(index -> "file-" + index + ".txt").toList();
        var manyPaths = call("talos.edit_file", true, EXECUTED,
                new ToolMutationEffects(paths, List.of("x".repeat(HistoricalAttributionEvidence.MAX_TARGET_CHARS + 1))));
        var calls = new ArrayList<TurnRecord.ToolCallSummary>();
        calls.add(manyPaths);
        calls.addAll(Collections.nCopies(HistoricalAttributionEvidence.MAX_CALLS_PER_TURN,
                call("talos.read_file", true, EXECUTED, ToolMutationEffects.none())));
        record(memory, 1, WORKSPACE, calls, ContentRestrictions.UNRESTRICTED);
        var turn = memory.historicalAttributionEvidence().turns().getFirst();
        assertEquals(HistoricalAttributionEvidence.MAX_CALLS_PER_TURN, turn.calls().size());
        assertEquals(1, turn.omittedCalls());
        assertEquals(HistoricalAttributionEvidence.MAX_TARGETS_PER_LIST, turn.calls().getFirst().appliedTargets().size());
        assertEquals(1, turn.calls().getFirst().omittedAppliedTargets());
        assertEquals(1, turn.calls().getFirst().omittedUncertainTargets());

        var longPaths = IntStream.range(0, 20).mapToObj(index -> index + "x".repeat(999)).toList();
        record(memory, 2, WORKSPACE, List.of(call("talos.edit_file", true, EXECUTED,
                new ToolMutationEffects(longPaths, List.of("uncertain.txt")))), ContentRestrictions.UNRESTRICTED);
        var bounded = memory.historicalAttributionEvidence().turns().getLast();
        long chars = bounded.calls().stream().mapToLong(entry -> entry.declaredTarget().length() + entry.appliedTargets().stream()
                .mapToInt(String::length).sum() + entry.uncertainTargets().stream().mapToInt(String::length).sum()).sum();
        assertTrue(chars <= HistoricalAttributionEvidence.MAX_TARGET_CHARS_PER_TURN);
        var boundedCall = bounded.calls().getFirst();
        assertEquals(longPaths.size(), boundedCall.appliedTargets().size() + boundedCall.omittedAppliedTargets());
        assertTrue(boundedCall.omittedAppliedTargets() > 0);
    }

    @Test
    void snapshotsAreImmutableEvictionIsExplicitAndClearResetsEvidence() {
        var memory = new SessionMemory();
        record(memory, 1, WORKSPACE, List.of(call("talos.write_file", true, EXECUTED,
                ToolMutationEffects.applied("README.md"))), ContentRestrictions.UNRESTRICTED);
        var snapshot = memory.historicalAttributionEvidence();
        for (int turn = 2; turn <= HistoricalAttributionEvidence.MAX_TURNS + 1; turn++) {
            memory.recordWorkStatus(new TurnResult(new Result.Ok("Constructed answer"), turn));
        }
        var current = memory.historicalAttributionEvidence();
        assertEquals(HistoricalAttributionEvidence.MAX_TURNS, current.turns().size());
        assertEquals(1, current.evictedTurns());
        assertEquals(2, current.turns().getFirst().turnNumber());
        assertEquals(1, snapshot.turns().size());
        assertEquals(0, snapshot.evictedTurns());
        assertTrue(snapshot.turns().getFirst().hasAppliedEdit(WORKSPACE, "README.md"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.turns().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.turns().getFirst().calls().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.turns().getFirst().calls().getFirst().appliedTargets().clear());
        memory.clear();
        assertTrue(memory.historicalAttributionEvidence().turns().isEmpty());
        assertEquals(0, memory.historicalAttributionEvidence().evictedTurns());
        assertTrue(memory.historicalAttributionEvidence().memoryPresent());
    }

    @Test
    void auditFactsRemainAvailableWithoutAProseBuffer() {
        var memory = new SessionMemory();
        var trace = LocalTurnTrace.builder("constructed-attribution", "offline-fixture", 1,
                        "2026-10-01T00:00:00Z")
                .workspaceHash(WORKSPACE).build();
        memory.recordWorkStatus(new TurnResult(new Result.Ok("Constructed answer"), null, 1,
                Duration.ZERO, new TurnAudit(List.of(call("talos.edit_file", true, EXECUTED,
                ToolMutationEffects.applied("README.md"))), 0, 0, 0, TurnPolicyTrace.empty(), trace)));
        assertFalse(memory.hasContent());
        var evidence = memory.historicalAttributionEvidence();
        assertTrue(evidence.memoryPresent());
        assertTrue(evidence.turns().getFirst().hasAppliedEdit(WORKSPACE, "README.md"));
        assertTrue(evidence.turns().getFirst().hasAppliedEdit(
                JsonSessionStore.sessionIdFor(Path.of("constructed-workspace", ".")), "README.md"));
    }

    @Test
    void restoredProseAndLegacyToolEvidenceDoNotReconstructRuntimeAttribution() {
        var memory = new SessionMemory();
        memory.update("Earlier work", "README.md was edited and all tests passed.");
        memory.recordToolEvidence(1, List.of(call("talos.edit_file", true, EXECUTED,
                ToolMutationEffects.applied("README.md"))));
        assertTrue(memory.historicalAttributionEvidence().memoryPresent());
        assertTrue(memory.historicalAttributionEvidence().turns().isEmpty());
    }

    private static TurnRecord.ToolCallSummary call(String name, boolean success,
                                                   TurnRecord.ToolCallDisposition disposition,
                                                   ToolMutationEffects effects) {
        return call(name, "README.md", success, disposition, effects);
    }

    private static TurnRecord.ToolCallSummary call(String name, String target, boolean success,
                                                   TurnRecord.ToolCallDisposition disposition,
                                                   ToolMutationEffects effects) {
        return new TurnRecord.ToolCallSummary(name, target, effects.affectedPaths(), success,
                "unretained diagnostic", effects, disposition);
    }

    private static void record(SessionMemory memory, int turn, String workspace,
                               List<TurnRecord.ToolCallSummary> calls, ContentRestrictions restrictions) {
        var trace = LocalTurnTrace.builder("constructed-attribution", "offline-fixture", turn,
                        "2026-10-01T00:00:00Z")
                .workspaceHash(workspace).verification("PASSED", "Constructed static pass", List.of()).build();
        var audit = new TurnAudit(calls, 0, 0, 0, TurnPolicyTrace.empty(), trace, restrictions, List.of());
        var result = new TurnResult(new Result.Ok("Constructed prose claims all tests passed."), null,
                turn, Duration.ZERO, audit);
        new MemoryUpdateListener(new ConversationManager(memory, new TokenBudget()), null, memory)
                .onTurnComplete(result, "Constructed attribution evidence input");
    }
}
