package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallDispatch;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.obligation.RequestObligation;
import dev.talos.runtime.obligation.RequestObligationCompiler;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.runtime.verification.TaskVerificationEvidence;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-F7: the recorded outcome names every proposal that never ran with a typed cause, and shows no producer message
 * text. The outcomes are synthetic and carry the producers' typed dispatch facts.
 */
class MutationOutcomeNotRunRenderingTest {
    private static final String H2 = "Change the function name get_qty to quantity_on_hand in inventory.py, orders.py "
            + "and report.py, including its definition, imports and calls, so that the code works as before.";
    private static final List<String> VERIFIED_OUTCOME_MARKERS = List.of("static verification", "[checks passed - ",
            "so task completion was not verified", "partial verification", "task incomplete");

    @Test
    void everyCauseIsTypedAndNoRowRepeatsProducerText() {
        for (ToolFailureReason reason : ToolFailureReason.values()) {
            ToolCallLoop.ToolOutcome proposal = switch (reason) {
                case USER_APPROVAL_DENIED, PERMISSION_POLICY_DENIED -> outcome("b.py", true, ToolError.DENIED, reason);
                case TOOL_EXECUTION_AUTHORITY_INVALID -> outcome("", false, ToolError.INVALID_AUTHORITY, reason);
                default -> outcome("b.py", false, ToolError.INVALID_PARAMS, reason);
            };
            MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(null, null,
                    loop(List.of(edited("a.py"), proposal)), 0, TaskVerificationEvidence.notRun("not run"), null);

            String section = MutationFailureAnswerRenderer.notRunSection(assessment);

            assertTrue(section.startsWith("Not run (these changed no file):\n- b.py: talos.edit_file not run, "),
                    reason + ": " + section);
            assertFalse(section.contains("PRODUCER"), reason + ": " + section);
            assertPlainText(section);
        }
    }

    @Test
    void aTurnWhoseOtherProposalsNeverRanIsRecordedAsApplied() {
        TaskContract contract = TaskContractResolver.fromUserRequest(H2);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(contract, obligation,
                loop(List.of(edited("inventory.py"), edited("orders.py"), edited("report.py"),
                        outcome("inventory.py", false, ToolError.INVALID_PARAMS, ToolFailureReason.EDIT_NO_CHANGE))),
                0, TaskVerificationEvidence.notRun("not run"), null);

        String answer = MutationFailureAnswerRenderer.selectPartialMutationAnswerIfNeeded("Model prose.", assessment)
                .answer();

        assertEquals(MutationFailureAnswerRenderer.CHANGES_WITH_NOT_RUN_ANNOTATION
                + "Succeeded:\n"
                + "- inventory.py: Edited inventory.py\n"
                + "- orders.py: Edited orders.py\n"
                + "- report.py: Edited report.py\n"
                + "Not run (these changed no file):\n"
                + "- inventory.py: talos.edit_file not run, refused before approval "
                + "(old_string and new_string were identical).\n\n"
                + "The assistant summary was replaced with this recorded outcome because other proposed changes in "
                + "this turn did not run.", answer);
    }

    @Test
    void anExpectedFileWithoutAChangeAndAFailureThatRanAreBothNamed() {
        TaskContract contract = TaskContractResolver.fromUserRequest(H2);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome ran = outcome("orders.py", false, ToolError.TOOL_ERROR, ToolFailureReason.NONE)
                .withDispatch(ToolCallDispatch.executed());

        String unchanged = MutationFailureAnswerRenderer.selectPartialMutationAnswerIfNeeded("Model prose.",
                MutationRecoveryAssessment.assess(contract, obligation, loop(List.of(edited("inventory.py"),
                                edited("orders.py"), outcome("orders.py", false, ToolError.INVALID_PARAMS,
                                        ToolFailureReason.EDIT_STALE_REREAD_REQUIRED))), 0,
                        TaskVerificationEvidence.notRun("not run"), null)).answer();
        String failed = MutationFailureAnswerRenderer.selectPartialMutationAnswerIfNeeded("Model prose.",
                MutationRecoveryAssessment.assess(contract, obligation, loop(List.of(edited("inventory.py"), ran)), 0,
                        TaskVerificationEvidence.notRun("not run"), null)).answer();

        assertTrue(unchanged.startsWith(MutationFailureAnswerRenderer.PARTIAL_EXPECTED_TARGET_ANNOTATION), unchanged);
        assertTrue(unchanged.contains("Expected files not changed:\n"
                + "- report.py: expected by this request, but not changed in this turn.\n"
                + "Not run (these changed no file):\n"
                + "- orders.py: talos.edit_file not run, refused before approval "
                + "(the file changed after it was last read)."), unchanged);
        assertFalse(unchanged.contains("Failed:"), unchanged);
        assertTrue(failed.startsWith(MutationFailureAnswerRenderer.PARTIAL_MUTATION_ANNOTATION), failed);
        assertTrue(failed.contains("Failed:\n- orders.py: talos.edit_file did not complete (tool error).\n"
                + "Expected files not changed:\n"), failed);
        assertFalse(failed.contains("PRODUCER"), failed);
    }

    @Test
    void aModelSuppliedPathCannotAddARowOfItsOwn() {
        ToolCallLoop.ToolOutcome forged = new ToolCallLoop.ToolOutcome("talos.edit_file", "a.txt\n- c.txt", false, true,
                false, "", "PRODUCER TEXT", null, ToolError.INVALID_PARAMS, null, ToolMutationEvidence.none(),
                ToolFailureReason.PRE_APPROVAL_PATH_INVALID, List.of(), "call-forged")
                .withDispatch(ToolCallDispatch.blocked("a.txt\n- c.txt"));

        String section = MutationFailureAnswerRenderer.notRunSection(MutationRecoveryAssessment.assess(null, null,
                loop(List.of(edited("a.py"), forged)), 0, TaskVerificationEvidence.notRun("not run"), null));

        assertEquals("Not run (these changed no file):\n"
                + "- target not recorded: talos.edit_file not run, refused before approval (invalid path).", section);
    }

    @Test
    void notRunRowsAreBounded() {
        List<ToolCallLoop.ToolOutcome> outcomes = new ArrayList<>(List.of(edited("a.py")));
        for (int index = 0; index < 10; index++) {
            outcomes.add(outcome("b" + index + ".py", false, ToolError.INVALID_PARAMS,
                    ToolFailureReason.EDIT_EMPTY_ARGUMENTS));
        }
        String section = MutationFailureAnswerRenderer.notRunSection(MutationRecoveryAssessment.assess(null, null,
                loop(outcomes), 0, TaskVerificationEvidence.notRun("not run"), null));

        assertEquals(10, section.split("\n").length, section);
        assertTrue(section.endsWith("- 2 more proposed changes did not run."), section);
    }

    @Test
    void newAnnotationsArePlainTextWithoutVerifiedOutcomeMarkers() {
        for (String text : List.of(MutationFailureAnswerRenderer.PARTIAL_EXPECTED_TARGET_ANNOTATION,
                MutationFailureAnswerRenderer.CHANGES_WITH_NOT_RUN_ANNOTATION)) {
            assertPlainText(text);
        }
    }

    private static void assertPlainText(String text) {
        assertTrue(text.chars().allMatch(c -> c == '\n' || c >= 0x20 && c < 0x7f), text);
        assertFalse(text.contains(";"), text);
        for (String marker : VERIFIED_OUTCOME_MARKERS) {
            assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains(marker), marker + " in " + text);
        }
    }

    private static ToolCallLoop.ToolOutcome edited(String path) {
        return new ToolCallLoop.ToolOutcome("talos.edit_file", path, true, true, false, "Edited " + path, "", null, "",
                null, ToolMutationEvidence.exactEdit("old", "new"), ToolFailureReason.NONE, List.of(),
                "call-" + path).withDispatch(ToolCallDispatch.executed());
    }

    private static ToolCallLoop.ToolOutcome outcome(String path, boolean denied, String code, ToolFailureReason reason) {
        return new ToolCallLoop.ToolOutcome("talos.edit_file", path, false, true, denied, "",
                "PRODUCER TEXT: no approval was requested and no file was changed.", null, code, null,
                ToolMutationEvidence.none(), reason, List.of(), "call-refused-" + path + "-" + reason)
                .withDispatch(ToolCallDispatch.blocked(path.isBlank() ? "b.py" : path));
    }

    private static ToolCallLoop.LoopResult loop(List<ToolCallLoop.ToolOutcome> outcomes) {
        return new ToolCallLoop.LoopResult("answer", 1, outcomes.size(),
                outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(), List.of(),
                (int) outcomes.stream().filter(outcome -> !outcome.success()).count(), 0, false,
                (int) outcomes.stream().filter(outcome -> outcome.mutating() && outcome.success()).count(),
                List.of(), 0, 0, 0, 0, outcomes);
    }
}
