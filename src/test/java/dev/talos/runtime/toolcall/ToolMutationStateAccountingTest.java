package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolResult;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolMutationStateAccountingTest {
    @Test
    void successfulMutationRecordsStateClearsReadCachesAndReturnsSummary() {
        LoopState state = loopState();
        state.staticWebFullRewriteRequiredTargets.add("src/App.java");
        state.successfulReadCalls.put("talos.read_file:path=src/App.java;", "1 | old");
        state.successfulReadCallBodies.put("talos.read_file:path=src/App.java;", "1 | old");
        state.readFileBodiesThisTurn.put("src/App.java", "1 | old");
        ToolCall write = new ToolCall("talos.write_file", Map.of(
                "path", "src\\App.java",
                "content", "new"));

        ToolMutationStateAccounting.Result result =
                ToolMutationStateAccounting.recordSuccessfulMutation(
                        state,
                        write,
                        "src\\App.java",
                        ToolResult.ok("Wrote file successfully. Verified: valid Java."));

        assertTrue(result.mutationRecorded());
        assertEquals("✓ Wrote file successfully", result.mutationSummary());
        assertTrue(state.mutationSinceStart);
        assertEquals(1, state.mutatingToolSuccesses);
        assertTrue(state.pathsMutatedSinceRead.contains("src/App.java"));
        assertFalse(state.staticWebFullRewriteRequiredTargets.contains("src/App.java"));
        assertTrue(state.successfulReadCalls.isEmpty());
        assertTrue(state.successfulReadCallBodies.isEmpty());
        assertEquals("1 | old", state.readFileBodiesThisTurn.get("src/App.java"));
        assertEquals(java.util.List.of("✓ Wrote file successfully"), state.pendingMutationSummaries);
    }

    @Test
    void blankMutationOutputRecordsStateWithoutSummary() {
        LoopState state = loopState();
        ToolCall write = new ToolCall("talos.write_file", Map.of("path", "README.md", "content", ""));

        ToolMutationStateAccounting.Result result =
                ToolMutationStateAccounting.recordSuccessfulMutation(
                        state,
                        write,
                        "README.md",
                        ToolResult.ok("   \n"));

        assertTrue(result.mutationRecorded());
        assertEquals("", result.mutationSummary());
        assertTrue(state.mutationSinceStart);
        assertEquals(1, state.mutatingToolSuccesses);
        assertTrue(state.pathsMutatedSinceRead.contains("README.md"));
        assertTrue(state.pendingMutationSummaries.isEmpty());
    }

    @Test
    void failedMutationAndSuccessfulReadOnlyCallAreNoOps() {
        LoopState failedState = loopState();
        failedState.successfulReadCalls.put("talos.read_file:path=README.md;", "1 | old");
        ToolCall write = new ToolCall("talos.write_file", Map.of("path", "README.md", "content", "new"));

        ToolMutationStateAccounting.Result failed =
                ToolMutationStateAccounting.recordSuccessfulMutation(
                        failedState,
                        write,
                        "README.md",
                        ToolResult.fail("denied"));

        assertFalse(failed.mutationRecorded());
        assertFalse(failedState.mutationSinceStart);
        assertEquals(0, failedState.mutatingToolSuccesses);
        assertEquals(1, failedState.successfulReadCalls.size());

        LoopState readOnlyState = loopState();
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "README.md"));

        ToolMutationStateAccounting.Result readOnly =
                ToolMutationStateAccounting.recordSuccessfulMutation(
                        readOnlyState,
                        read,
                        "README.md",
                        ToolResult.ok("1 | # Demo"));

        assertFalse(readOnly.mutationRecorded());
        assertFalse(readOnlyState.mutationSinceStart);
        assertEquals(0, readOnlyState.mutatingToolSuccesses);
        assertTrue(readOnlyState.pathsMutatedSinceRead.isEmpty());
    }

    @Test
    void integrityFailedMutationWithVerificationMetadataDoesNotRecordSuccessfulMutation() {
        LoopState state = loopState();
        state.successfulReadCalls.put("talos.read_file:path=README.md;", "1 | old");
        ToolCall write = new ToolCall("talos.write_file", Map.of("path", "README.md", "content", "new"));
        ToolResult result = ToolResult.fail(
                ToolError.internal("File verification failed: read-back mismatch"),
                VerificationStatus.INTEGRITY_FAIL);

        ToolMutationStateAccounting.Result recorded =
                ToolMutationStateAccounting.recordSuccessfulMutation(
                        state,
                        write,
                        "README.md",
                        result);

        assertFalse(recorded.mutationRecorded());
        assertFalse(state.mutationSinceStart);
        assertEquals(0, state.mutatingToolSuccesses);
        assertTrue(state.pathsMutatedSinceRead.isEmpty());
        assertEquals(1, state.successfulReadCalls.size());
        assertTrue(state.pendingMutationSummaries.isEmpty());
    }

    @Test
    void executionStageDelegatesSuccessfulMutationStateAccounting() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java"));

        assertTrue(source.contains("ToolMutationStateAccounting.recordSuccessfulMutation"), source);
        assertFalse(source.contains("private static void recordMutationSuccess"), source);
        assertFalse(source.contains("state.mutationSinceStart = true"), source);
        assertFalse(source.contains("state.mutatingToolSuccesses++"), source);
        assertFalse(source.contains("state.pendingMutationSummaries.add"), source);
    }

    private static LoopState loopState() {
        return new LoopState("", java.util.List.of(), java.util.List.of(), null, null, null, 5, 0);
    }
}
