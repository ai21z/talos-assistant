package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolFailureStateAccountingTest {
    @Test
    void failedMutatingResultRecordsCountsClearsReadCachesAndReportsFailure() {
        LoopState state = loopState();
        state.successfulReadCalls.put("talos.read_file:path=README.md;", "1 | old");
        state.successfulReadCallBodies.put("talos.read_file:path=README.md;", "1 | old");
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
        assertTrue(state.successfulReadCalls.isEmpty());
        assertTrue(state.successfulReadCallBodies.isEmpty());
    }

    @Test
    void expectedTargetScopeFailureRecordsCountsButPreservesReadCaches() {
        LoopState state = loopState();
        state.successfulReadCalls.put("talos.read_file:path=index.html;", "1 | <main></main>");
        state.successfulReadCallBodies.put("talos.read_file:path=index.html;", "1 | <main></main>");
        ToolCall write = new ToolCall("talos.write_file", Map.of("path", "docs\\other.md", "content", "new"));
        ToolResult result = ToolResult.fail(ToolError.invalidParams(
                "Target outside expected targets before approval: docs/other.md"));
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(write, result, "docs\\other.md");

        ToolFailureStateAccounting.Result accounting =
                ToolFailureStateAccounting.recordFailure(state, write, classification, "docs\\other.md", false);

        assertTrue(accounting.failureRecorded());
        assertEquals(1, state.failedCalls);
        assertEquals(1, state.failureCountsByTool.get("talos.write_file"));
        assertEquals(1, state.failureCountsByPath.get("docs/other.md"));
        assertFalse(state.successfulReadCalls.isEmpty());
        assertFalse(state.successfulReadCallBodies.isEmpty());
    }

    @Test
    void oldStringMissAfterSameTurnReadWithoutMutationPreservesReadCaches() {
        LoopState state = loopState();
        state.pathsReadThisTurn.add("docs/notes.md");
        state.successfulReadCalls.put("talos.read_file:path=docs/notes.md;", "1 | old");
        state.successfulReadCallBodies.put("talos.read_file:path=docs/notes.md;", "1 | old");
        ToolCall edit = new ToolCall("talos.edit_file", Map.of(
                "path", "docs\\notes.md",
                "old_string", "missing",
                "new_string", "new"));
        ToolResult result = ToolResult.fail(ToolError.invalidParams("old_string not found"));
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(edit, result, "docs\\notes.md");

        ToolFailureStateAccounting.Result accounting =
                ToolFailureStateAccounting.recordFailure(state, edit, classification, "docs\\notes.md", true);

        assertTrue(accounting.failureRecorded());
        assertEquals(1, state.failedCalls);
        assertEquals(1, state.failureCountsByTool.get("talos.edit_file"));
        assertEquals(1, state.failureCountsByPath.get("docs/notes.md"));
        assertFalse(state.successfulReadCalls.isEmpty());
        assertFalse(state.successfulReadCallBodies.isEmpty());
    }

    @Test
    void failedReadOnlyResultRecordsCountsAndPreservesReadCaches() {
        LoopState state = loopState();
        state.successfulReadCalls.put("talos.read_file:path=README.md;", "1 | old");
        state.successfulReadCallBodies.put("talos.read_file:path=README.md;", "1 | old");
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
        assertFalse(state.successfulReadCalls.isEmpty());
        assertFalse(state.successfulReadCallBodies.isEmpty());
    }

    @Test
    void syntheticPreResultFailureRecordsCountsWithoutCachePolicy() {
        LoopState state = loopState();
        state.successfulReadCalls.put("talos.read_file:path=README.md;", "1 | old");
        state.successfulReadCallBodies.put("talos.read_file:path=README.md;", "1 | old");
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
        assertFalse(state.successfulReadCalls.isEmpty());
        assertFalse(state.successfulReadCallBodies.isEmpty());
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
}
