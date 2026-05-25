package dev.talos.runtime.toolcall;

import dev.talos.runtime.TurnSourceEvidenceCapture;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadEvidenceStateAccountingTest {
    @Test
    void successfulReadFileRecordsPathAndClearsStaleReadState() {
        LoopState state = loopState();
        state.pathsMutatedSinceRead.add("docs/notes.md");
        state.staleEditFailuresByPath.put("docs/notes.md", 2);
        state.staleEditRepairPromptedPaths.add("docs/notes.md");
        state.staleEditRereadIgnoredPath = "docs/notes.md";
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "docs\\notes.md"));

        TurnSourceEvidenceCapture.begin();
        try {
            ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                    state,
                    read,
                    "docs\\notes.md",
                    ToolResult.ok("1 | # Notes"));

            assertTrue(state.pathsReadThisTurn.contains("docs/notes.md"));
            assertFalse(state.pathsMutatedSinceRead.contains("docs/notes.md"));
            assertFalse(state.staleEditFailuresByPath.containsKey("docs/notes.md"));
            assertFalse(state.staleEditRepairPromptedPaths.contains("docs/notes.md"));
            assertEquals(null, state.staleEditRereadIgnoredPath);
            assertEquals(Set.of("docs/notes.md"), TurnSourceEvidenceCapture.readPaths());
        } finally {
            TurnSourceEvidenceCapture.clear();
        }
    }

    @Test
    void readOnlyNonFileToolPopulatesSuccessfulReadCachesOnly() {
        LoopState state = loopState();
        ToolCall grep = new ToolCall("talos.grep", Map.of("pattern", "TODO", "path", "src"));

        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state,
                grep,
                "src",
                ToolResult.ok("src/Main.java:7: TODO"));

        String signature = ToolCallSupport.buildReadCallSignature(grep);
        assertFalse(state.pathsReadThisTurn.contains("src"));
        assertEquals("src/Main.java:7: TODO", state.successfulReadCalls.get(signature));
        assertEquals("src/Main.java:7: TODO", state.successfulReadCallBodies.get(signature));
    }

    @Test
    void failedReadResultDoesNotRecordReadPathOrCaches() {
        LoopState state = loopState();
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "missing.md"));

        TurnSourceEvidenceCapture.begin();
        try {
            ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                    state,
                    read,
                    "missing.md",
                    ToolResult.fail(ToolError.notFound("missing")));

            assertTrue(state.pathsReadThisTurn.isEmpty());
            assertTrue(state.successfulReadCalls.isEmpty());
            assertTrue(state.successfulReadCallBodies.isEmpty());
            assertTrue(TurnSourceEvidenceCapture.readPaths().isEmpty());
        } finally {
            TurnSourceEvidenceCapture.clear();
        }
    }

    @Test
    void clearSuccessfulReadCachesRemainsExplicit() {
        LoopState state = loopState();
        state.successfulReadCalls.put("read_file:path=README.md;", "1 | # Demo");
        state.successfulReadCallBodies.put("read_file:path=README.md;", "1 | # Demo");

        ReadEvidenceStateAccounting.clearSuccessfulReadCaches(state);

        assertTrue(state.successfulReadCalls.isEmpty());
        assertTrue(state.successfulReadCallBodies.isEmpty());
    }

    @Test
    void executionStageDelegatesReadEvidenceStateAccounting() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java"));

        assertTrue(source.contains("ReadEvidenceStateAccounting.recordSuccessfulToolResult"), source);
        assertTrue(source.contains("ReadEvidenceStateAccounting.clearSuccessfulReadCaches"), source);
        assertFalse(source.contains("private static void recordSuccessfulRead"), source);
        assertFalse(source.contains("state.successfulReadCalls.put"), source);
        assertFalse(source.contains("state.successfulReadCallBodies.put"), source);
        assertFalse(source.contains("TurnSourceEvidenceCapture.recordRead"), source);
    }

    private static LoopState loopState() {
        return new LoopState("", java.util.List.of(), java.util.List.of(), null, null, null, 5, 0);
    }
}
