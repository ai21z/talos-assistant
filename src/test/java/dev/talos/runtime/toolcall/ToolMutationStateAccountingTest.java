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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolMutationStateAccountingTest {
    @Test
    void successfulMutationRecordsStateClearsReadCachesAndReturnsSummary() {
        LoopState state = loopState();
        state.staticWebFullRewriteRequiredTargets.add("src/App.java");
        addReadEvidence(state, "src/App.java", "1 | old");
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
        assertNull(state.readEvidence.latestReadFileForPath("src/App.java", true));
        assertEquals("1 | old", state.readEvidence.latestReadFileForPath("src/App.java", false).body());
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
        addReadEvidence(failedState, "README.md", "1 | old");
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
        assertNotNull(failedState.readEvidence.latestReadFileForPath("README.md", true));

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
        addReadEvidence(state, "README.md", "1 | old");
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
        assertNotNull(state.readEvidence.latestReadFileForPath("README.md", true));
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

    private static void addReadEvidence(LoopState state, String path, String body) {
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state,
                new ToolCall("talos.read_file", Map.of("path", path)),
                path,
                ToolResult.ok(body));
    }
}
