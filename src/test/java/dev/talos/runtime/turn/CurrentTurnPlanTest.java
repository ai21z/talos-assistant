package dev.talos.runtime.turn;

import dev.talos.runtime.expectation.LiteralContentExpectation;
import dev.talos.runtime.expectation.TaskExpectation;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentTurnPlanTest {

    @Test
    void capturesContractObligationToolsAndLiteralExpectationOnce() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Overwrite index.html with exactly AFTER. Use talos.write_file.");

        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.write_file", "talos.read_file"),
                List.of("talos.write_file", "talos.read_file"),
                List.of());

        assertEquals(TaskType.FILE_EDIT, plan.taskContract().type());
        assertEquals("Overwrite index.html with exactly AFTER. Use talos.write_file.",
                plan.originalUserRequest());
        assertEquals(ExecutionPhase.APPLY, plan.phaseInitial());
        assertEquals(ExecutionPhase.APPLY, plan.phaseFinal());
        assertEquals(ActionObligation.MUTATING_TOOL_REQUIRED, plan.actionObligation());
        assertEquals(List.of("talos.write_file", "talos.read_file"), plan.nativeTools());
        assertEquals(CurrentTurnPlan.NONE_OR_NOT_DERIVED, plan.evidenceObligation());
        assertEquals(CurrentTurnPlan.NOT_DERIVED, plan.outputObligation());

        assertEquals(1, plan.taskExpectations().size());
        TaskExpectation expectation = plan.taskExpectations().getFirst();
        LiteralContentExpectation literal = assertInstanceOf(
                LiteralContentExpectation.class, expectation);
        assertEquals("index.html", literal.targetPath());
        assertEquals("AFTER", literal.expectedContent());
    }

    @Test
    void retryMessagesCannotChangeCapturedLiteralExpectation() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Overwrite index.html with exactly AFTER. Use talos.write_file."));

        TaskContract original = TaskContractResolver.fromMessages(messages);
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                original,
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of());

        messages.add(ChatMessage.assistant("I can help with that."));
        messages.add(ChatMessage.user(
                "The current-turn obligation was not satisfied. Call the write tool now."));

        TaskContract drifted = TaskContractResolver.fromMessages(messages);
        assertTrue(drifted.expectedTargets().isEmpty(),
                "This test proves mutable messages can lose the original exact target.");

        LiteralContentExpectation literal = assertInstanceOf(
                LiteralContentExpectation.class,
                plan.taskExpectations().getFirst());
        assertEquals("index.html", literal.targetPath());
        assertEquals("AFTER", literal.expectedContent());
        assertEquals(List.of("index.html"), plan.taskContract().expectedTargets().stream().toList());
    }

    @Test
    void listFieldsAreImmutableCopies() {
        TaskContract contract = TaskContractResolver.fromUserRequest("Create README.md.");
        List<String> nativeTools = new ArrayList<>(List.of("talos.write_file"));

        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                nativeTools,
                nativeTools,
                List.of());

        nativeTools.add("talos.edit_file");

        assertEquals(List.of("talos.write_file"), plan.nativeTools());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.nativeTools().add("talos.grep"));
    }
}
