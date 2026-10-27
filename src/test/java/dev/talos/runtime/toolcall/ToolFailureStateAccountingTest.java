package dev.talos.runtime.toolcall;

import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.failure.FailurePolicy;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolResult;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolFailureStateAccountingTest {
    @Test
    void failedMutatingResultRecordsCountsClearsReadCachesAndReportsFailure() {
        LoopState state = loopState();
        addReadEvidence(state, "README.md", "1 | old");
        ToolCall write = new ToolCall("talos.write_file", Map.of("path", "docs\\notes.md", "content", "new"));
        ToolResult result = ToolResult.fail(ToolError.invalidParams("Path not allowed before approval: docs/notes.md"));
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(write, result, "docs\\notes.md");

        ToolFailureStateAccounting.Result accounting =
                ToolFailureStateAccounting.recordFailure(state, write, classification, "docs\\notes.md", false);

        assertTrue(accounting.failureRecorded());
        assertEquals(1, state.failedCalls);
        assertEquals(1, state.failureCountsByTool.get("talos.write_file"));
        assertEquals(1, state.failureCountsByPath.get("docs/notes.md"));
        assertNull(state.readEvidence.latestReadFileForPath("README.md", true));
    }

    @Test
    void expectedTargetScopeFailureRecordsCountsButPreservesReadCaches() {
        LoopState state = loopState();
        addReadEvidence(state, "index.html", "1 | <main></main>");
        ToolCall write = new ToolCall("talos.write_file", Map.of("path", "docs\\other.md", "content", "new"));
        ToolResult result = ToolResult.fail(ToolError.invalidParams(
                ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                "Target outside expected targets before approval: docs/other.md"));
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(write, result, "docs\\other.md");

        ToolFailureStateAccounting.Result accounting =
                ToolFailureStateAccounting.recordFailure(state, write, classification, "docs\\other.md", false);

        assertTrue(accounting.failureRecorded());
        assertEquals(1, state.failedCalls);
        assertEquals(1, state.failureCountsByTool.get("talos.write_file"));
        assertEquals(1, state.failureCountsByPath.get("docs/other.md"));
        assertNotNull(state.readEvidence.latestReadFileForPath("index.html", true));
    }

    @Test
    void oldStringMissAfterSameTurnReadWithoutMutationPreservesReadCaches() {
        LoopState state = loopState();
        addReadEvidence(state, "docs/notes.md", "1 | old");
        ToolCall edit = new ToolCall("talos.edit_file", Map.of(
                "path", "docs\\notes.md",
                "old_string", "missing",
                "new_string", "new"));
        ToolResult result = ToolResult.fail(ToolError.invalidParams(
                ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND, "old_string not found"));
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(edit, result, "docs\\notes.md");

        ToolFailureStateAccounting.Result accounting =
                ToolFailureStateAccounting.recordFailure(state, edit, classification, "docs\\notes.md", true);

        assertTrue(accounting.failureRecorded());
        assertEquals(1, state.failedCalls);
        assertEquals(1, state.failureCountsByTool.get("talos.edit_file"));
        assertEquals(1, state.failureCountsByPath.get("docs/notes.md"));
        assertNotNull(state.readEvidence.latestReadFileForPath("docs/notes.md", true));
    }

    @Test
    void failedReadOnlyResultRecordsCountsAndPreservesReadCaches() {
        LoopState state = loopState();
        addReadEvidence(state, "README.md", "1 | old");
        ToolCall grep = new ToolCall("talos.grep", Map.of("pattern", "TODO", "path", "src"));
        ToolResult result = ToolResult.fail(ToolError.invalidParams("missing pattern"));
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(grep, result, "src");

        ToolFailureStateAccounting.Result accounting =
                ToolFailureStateAccounting.recordFailure(state, grep, classification, "src", false);

        assertTrue(accounting.failureRecorded());
        assertEquals(1, state.failedCalls);
        assertEquals(1, state.failureCountsByTool.get("talos.grep"));
        assertEquals(1, state.failureCountsByPath.get("src"));
        assertNotNull(state.readEvidence.latestReadFileForPath("README.md", true));
    }

    @Test
    void syntheticPreResultFailureRecordsCountsWithoutCachePolicy() {
        LoopState state = loopState();
        addReadEvidence(state, "README.md", "1 | old");
        ToolCall edit = new ToolCall("talos.edit_file", Map.of(
                "path", "README.md",
                "old_string", "old",
                "new_string", "new"));

        ToolFailureStateAccounting.Result accounting =
                ToolFailureStateAccounting.recordFailure(state, edit, "README.md");

        assertTrue(accounting.failureRecorded());
        assertEquals(1, state.failedCalls);
        assertEquals(1, state.failureCountsByTool.get("talos.edit_file"));
        assertEquals(1, state.failureCountsByPath.get("README.md"));
        assertNotNull(state.readEvidence.latestReadFileForPath("README.md", true));
    }

    @Test
    void integrityFailedWriteRecordsGenericFailureAndStopsUnderExistingFailurePolicy() {
        LoopState state = loopState();
        FailurePolicy policy = FailurePolicy.defaults(10);
        ToolCall write = new ToolCall("talos.write_file", Map.of(
                "path", "README.md",
                "content", "new"));
        ToolResult result = ToolResult.fail(
                ToolError.internal("File verification failed: read-back mismatch"),
                VerificationStatus.INTEGRITY_FAIL);
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(write, result, "README.md");

        FailureDecision decision = FailureDecision.continueLoop();
        for (int i = 1; i <= 3; i++) {
            ToolMutationStateAccounting.Result mutation =
                    ToolMutationStateAccounting.recordSuccessfulMutation(
                            state,
                            write,
                            "README.md",
                            result);
            ToolFailureStateAccounting.Result failure =
                    ToolFailureStateAccounting.recordFailure(
                            state,
                            write,
                            classification,
                            "README.md",
                            false);

            assertFalse(mutation.mutationRecorded());
            assertTrue(failure.failureRecorded());
            assertEquals(i, state.failedCalls);
            assertEquals(i, state.failureCountsByTool.get("talos.write_file"));
            assertEquals(i, state.failureCountsByPath.get("README.md"));
            assertFalse(state.mutationSinceStart);
            assertEquals(0, state.mutatingToolSuccesses);
            assertTrue(state.pathsMutatedSinceRead.isEmpty());

            decision = policy.afterIteration(state, failedIteration());
            if (i < 3) {
                assertFalse(decision.shouldStop(), decision.reason());
            }
        }

        assertTrue(decision.shouldStop());
        assertEquals(FailureAction.ASK_USER, decision.action());
        assertTrue(decision.reason().contains("path `README.md`"), decision.reason());
    }

    @Test
    void executionStageDelegatesGenericFailureStateAccounting() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java"));

        assertTrue(source.contains("ToolFailureStateAccounting.recordFailure"), source);
        assertFalse(source.contains("private static void recordFailure"), source);
        assertFalse(source.contains("private static boolean shouldClearSuccessfulReadCallsAfterFailure"), source);
        assertFalse(source.contains("state.failedCalls++"), source);
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

    private static ToolCallExecutionStage.IterationOutcome failedIteration() {
        return new ToolCallExecutionStage.IterationOutcome(0, List.of(), 1, false, false, false, 0);
    }
}
