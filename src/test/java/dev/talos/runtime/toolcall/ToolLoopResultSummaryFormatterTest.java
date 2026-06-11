package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolLoopResultSummaryFormatterTest {

    @Test
    void returnsNullWhenNoToolsWereInvoked() {
        var result = new ToolCallLoop.LoopResult(
                "plain answer",
                0,
                0,
                List.of(),
                List.of(),
                0,
                0,
                false,
                0,
                List.of(),
                0,
                0,
                0,
                0);

        assertNull(ToolLoopResultSummaryFormatter.format(result));
    }

    @Test
    void formatsToolNamesFailuresIterationLimitAndFailurePolicyMarker() {
        var result = new ToolCallLoop.LoopResult(
                "answer",
                3,
                4,
                List.of("talos.read_file", "talos.write_file", "talos.read_file"),
                List.of(),
                2,
                1,
                true,
                1,
                List.of("README.md"),
                0,
                0,
                0,
                0,
                FailureDecision.stop(FailureAction.STOP_WITH_PARTIAL, "fixture"),
                List.of());

        assertEquals(
                "[Used 4 tool(s): talos.read_file, talos.write_file | 3 iteration(s)] "
                        + "[2 failed] [iteration limit reached] [failure policy stopped]",
                ToolLoopResultSummaryFormatter.format(result));
    }

    @Test
    void suppressesRecoveredEditFailuresByNormalizedPath() {
        var failedEdit = new ToolCallLoop.ToolOutcome(
                "talos.edit_file",
                "./src/App.java",
                false,
                true,
                false,
                "",
                "old_string not found",
                null,
                ToolError.INVALID_PARAMS)
                .withFailureReason(ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND);
        var laterWrite = new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "src/app.java",
                true,
                true,
                false,
                "Wrote src/app.java successfully",
                "",
                null);
        var result = new ToolCallLoop.LoopResult(
                "answer",
                2,
                2,
                List.of("talos.edit_file", "talos.write_file"),
                List.of(),
                1,
                1,
                false,
                1,
                List.of(),
                0,
                0,
                0,
                0,
                FailureDecision.continueLoop(),
                List.of(failedEdit, laterWrite));

        assertEquals(
                "[Used 2 tool(s): talos.edit_file, talos.write_file | 2 iteration(s)]",
                ToolLoopResultSummaryFormatter.format(result));
    }

    @Test
    void loopResultSummaryDelegatesToFormatterOwner() throws Exception {
        String loopSource = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/ToolCallLoop.java"));
        String formatterSource = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolLoopResultSummaryFormatter.java"));

        assertEquals(1, count(loopSource, "ToolLoopResultSummaryFormatter.format(this)"), loopSource);
        assertEquals(0, count(loopSource, "displayFailedCalls("), loopSource);
        assertTrue(formatterSource.contains("private static int displayFailedCalls"), formatterSource);
        assertTrue(formatterSource.contains("private static String normalizeSummaryPath"), formatterSource);
    }

    private static int count(String source, String needle) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
