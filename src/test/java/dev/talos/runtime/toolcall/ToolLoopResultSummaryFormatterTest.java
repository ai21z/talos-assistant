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
    void retainsRawFailureCountUntilPostVerificationRecoveryIsKnown() {
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
                "[Used 2 tool(s): talos.edit_file, talos.write_file | 2 iteration(s)] "
                        + "[1 failed]",
                ToolLoopResultSummaryFormatter.format(result));
    }

    @Test
    void readOnlyAnswerSummaryDisclosesStableSuccessfulReadSet() {
        var result = new ToolCallLoop.LoopResult(
                "answer",
                1,
                3,
                List.of("talos.list_dir", "talos.read_file", "talos.read_file"),
                List.of(),
                0,
                0,
                false,
                0,
                List.of("notes/project-notes.md", "notes/meeting-notes.md"),
                0,
                0,
                0,
                0,
                FailureDecision.continueLoop(),
                List.of(
                        new ToolCallLoop.ToolOutcome(
                                "talos.list_dir", "notes", true, false, false,
                                "project-notes.md\nmeeting-notes.md\n", "", null),
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "notes/project-notes.md", true, false, false,
                                "read", "", null),
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "notes/meeting-notes.md", true, false, false,
                                "read", "", null)));

        assertEquals(
                "[Used 3 tool(s): talos.list_dir, talos.read_file | 1 iteration(s) "
                        + "| read: notes/meeting-notes.md, notes/project-notes.md]",
                ToolLoopResultSummaryFormatter.format(result));
    }

    @Test
    void showsCallsRefusedBeforeExecutionAsNotRunInsteadOfFailed() {
        // P21 (D18 option A): a call refused for its executable authority never ran, so it is shown as not run.
        var refused = ToolOutcomeFactory.failedAuthority(null, "blocked");
        var failedEdit = new ToolCallLoop.ToolOutcome(
                "talos.edit_file",
                "notes.md",
                false,
                true,
                false,
                "",
                "old_string not found",
                null,
                ToolError.INVALID_PARAMS)
                .withFailureReason(ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND);
        var result = new ToolCallLoop.LoopResult(
                "answer",
                2,
                2,
                List.of("talos.edit_file"),
                List.of(),
                2,
                0,
                false,
                0,
                List.of(),
                0,
                0,
                0,
                0,
                FailureDecision.continueLoop(),
                List.of(refused, failedEdit));

        assertEquals(
                "[Used 2 tool(s): talos.edit_file | 2 iteration(s)] [1 failed] [1 not run]",
                ToolLoopResultSummaryFormatter.format(result));
    }

    @Test
    void aCallWitnessedAsNeverRunIsShownAsNotRunAndACallThatRanAsFailed() {
        var refused = new ToolCallLoop.ToolOutcome("talos.edit_file", "a.md", false, true, false, "",
                "refused", null, ToolError.INVALID_PARAMS)
                .withDispatch(dev.talos.runtime.ToolCallDispatch.blocked("a.md"));
        var ran = new ToolCallLoop.ToolOutcome("talos.edit_file", "a.md", false, true, false, "",
                "failed", null, ToolError.TOOL_ERROR)
                .withDispatch(dev.talos.runtime.ToolCallDispatch.executed());
        var unwitnessed = new ToolCallLoop.ToolOutcome("talos.edit_file", "a.md", false, true, false, "",
                "failed", null, ToolError.INVALID_PARAMS);
        var result = new ToolCallLoop.LoopResult("answer", 2, 3, List.of("talos.edit_file"), List.of(), 3, 0,
                false, 0, List.of(), 0, 0, 0, 0, FailureDecision.continueLoop(), List.of(refused, ran, unwitnessed));

        assertEquals(
                "[Used 3 tool(s): talos.edit_file | 2 iteration(s)] [2 failed] [1 not run]",
                ToolLoopResultSummaryFormatter.format(result));
    }

    @Test
    void aRefusedCallAloneIsNotShownAsFailed() {
        var result = new ToolCallLoop.LoopResult(
                "answer",
                2,
                1,
                List.of("talos.edit_file"),
                List.of(),
                1,
                0,
                false,
                1,
                List.of(),
                0,
                0,
                0,
                0,
                FailureDecision.continueLoop(),
                List.of(ToolOutcomeFactory.failedAuthority(null, "blocked")));

        assertEquals(
                "[Used 1 tool(s): talos.edit_file | 2 iteration(s)] [1 not run]",
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
        assertEquals(0, count(formatterSource, "private static int displayFailedCalls"), formatterSource);
        assertEquals(0, count(formatterSource, "private static String normalizeSummaryPath"), formatterSource);
        assertTrue(
                formatterSource.contains(
                        "int displayFailedCalls = Math.max(0, result.failedCalls() - notRunCalls)"),
                formatterSource);
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
