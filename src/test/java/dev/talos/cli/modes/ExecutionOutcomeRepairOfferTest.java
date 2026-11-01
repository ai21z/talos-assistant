package dev.talos.cli.modes;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.context.RepairOffer;
import dev.talos.tools.ToolFailureReason;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A failed-verification answer offers repair only when the turn had no denied change (P2, review M3). */
class ExecutionOutcomeRepairOfferTest {
    private static final ToolCallLoop.ToolOutcome WRITE = new ToolCallLoop.ToolOutcome(
            "talos.write_file", "README.md", true, true, false,
            "Wrote README.md", "", dev.talos.tools.VerificationStatus.WARN);
    private static final ToolCallLoop.ToolOutcome DENIED_EDIT = new ToolCallLoop.ToolOutcome(
            "talos.edit_file", "README.md", false, true, true,
            "", "User did not approve the talos.edit_file call.")
            .withFailureReason(ToolFailureReason.USER_APPROVAL_DENIED);

    @Test
    void aFailedVerificationAnswerCarriesTheOffer(@TempDir Path workspace) throws Exception {
        ExecutionOutcome outcome = outcome(workspace, new ToolCallLoop.LoopResult(
                "[ok] Wrote README.md", 1, 1,
                List.of("talos.write_file"), List.of(),
                1, 0, false, 1, List.of(),
                0, 0, 0, 0,
                List.of(WRITE)));

        assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
        assertTrue(RepairOffer.renderedIn(outcome.finalAnswer()), outcome.finalAnswer());
    }

    @Test
    void aTurnWithADeniedChangeGetsNoOffer(@TempDir Path workspace) throws Exception {
        ExecutionOutcome outcome = outcome(workspace, new ToolCallLoop.LoopResult(
                "[ok] Wrote README.md", 2, 2,
                List.of("talos.write_file", "talos.edit_file"), List.of(),
                2, 0, false, 1, List.of(),
                0, 0, 0, 0,
                List.of(WRITE, DENIED_EDIT)));

        assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
        assertFalse(outcome.finalAnswer().contains(RepairOffer.LINE), outcome.finalAnswer());
    }

    @Test
    void aFailedCheckOfAWorkspaceOperationGetsNoOffer(@TempDir Path workspace) throws Exception {
        // A workspace operation leaves no carried context (P8), so no context could bind an offer.
        Files.writeString(workspace.resolve("style.css"), "body {}\n");
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Move style.css to css/style.css."));
        ToolCallLoop.ToolOutcome move = new ToolCallLoop.ToolOutcome(
                "talos.move_path", "css/style.css", true, true, false,
                "Moved style.css to css/style.css", "", null, "",
                dev.talos.runtime.workspace.WorkspaceOperationPlan.movePath("style.css", "css/style.css",
                        dev.talos.runtime.workspace.WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "Moved style.css.", messages, new ToolCallLoop.LoopResult(
                        "[ok] Moved style.css", 1, 1,
                        List.of("talos.move_path"), List.of(),
                        1, 0, false, 1, List.of(),
                        0, 0, 0, 0,
                        List.of(move)), workspace, 0);

        assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus(), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains(RepairOffer.LINE), outcome.finalAnswer());
    }

    private static ExecutionOutcome outcome(Path workspace, ToolCallLoop.LoopResult loopResult) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Talos\n");
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Create README.md with a short project headline."));
        var plan = new dev.talos.runtime.turn.CurrentTurnPlan(
                dev.talos.runtime.task.TaskContractResolver.fromMessages(messages),
                null,
                dev.talos.runtime.phase.ExecutionPhase.APPLY,
                null,
                null,
                null,
                List.of("talos.write_file", "talos.edit_file"),
                List.of("talos.write_file", "talos.edit_file"),
                List.of(),
                "NONE",
                null,
                null,
                null,
                null);
        return ExecutionOutcome.fromToolLoop(
                "Done. I updated README.md and verified it.", plan, messages, loopResult, workspace, 0);
    }
}
