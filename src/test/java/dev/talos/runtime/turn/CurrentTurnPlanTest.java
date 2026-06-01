package dev.talos.runtime.turn;

import dev.talos.runtime.expectation.LiteralContentExpectation;
import dev.talos.runtime.expectation.TaskExpectation;
import dev.talos.runtime.capability.VerifierProfile;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
        assertEquals(List.of("talos.write_file", "talos.read_file"), plan.promptTools());
        assertEquals(List.of(), plan.blockedTools());
        assertEquals("NONE", plan.evidenceObligation());
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
        List<String> promptTools = new ArrayList<>(List.of("talos.write_file"));
        List<String> blockedTools = new ArrayList<>(List.of("talos.shell"));

        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                nativeTools,
                promptTools,
                blockedTools);

        nativeTools.add("talos.edit_file");
        promptTools.add("talos.edit_file");
        blockedTools.add("talos.exec");

        assertEquals(List.of("talos.write_file"), plan.nativeTools());
        assertEquals(List.of("talos.write_file"), plan.promptTools());
        assertEquals(List.of("talos.shell"), plan.blockedTools());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.nativeTools().add("talos.grep"));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.promptTools().add("talos.grep"));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.blockedTools().add("talos.grep"));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.taskExpectations().add(new LiteralContentExpectation(
                        "README.md",
                        "content",
                        LiteralContentExpectation.MatchMode.EXACT,
                        "test")));
    }

    @Test
    void readTargetPlanCapturesReadEvidenceObligation() {
        TaskContract contract = TaskContractResolver.fromUserRequest("Read README.md and summarize it.");

        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.INSPECT,
                List.of("talos.read_file"),
                List.of("talos.read_file"),
                List.of());

        assertEquals("READ_TARGET_REQUIRED", plan.evidenceObligation());
    }

    @Test
    void createCanCarryActiveContextArtifactGoalAndVerifierProfile() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("README.md"),
                Set.of(),
                "make those changes");

        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of(),
                "ACTIVE PROPOSED_CHANGES targets=[README.md] operation=APPLY_EDIT",
                "README APPLY_EDIT targets=[README.md] source=ACTIVE_CONTEXT",
                "NONE_OR_NOT_DERIVED");

        assertEquals("ACTIVE PROPOSED_CHANGES targets=[README.md] operation=APPLY_EDIT",
                plan.activeTaskContext());
        assertEquals("README APPLY_EDIT targets=[README.md] source=ACTIVE_CONTEXT",
                plan.artifactGoal());
        assertEquals("NONE_OR_NOT_DERIVED", plan.verifierProfile());
    }

    @Test
    void createDerivesSourceDerivedVerifierProfileWhenNoProfileIsExplicit() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_CREATE,
                true,
                true,
                true,
                Set.of("summary.md"),
                Set.of("alpha.txt", "beta.txt"),
                Set.of(),
                "Summarize alpha.txt and beta.txt into summary.md.",
                "test-source-derived-plan");

        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.read_file", "talos.write_file"),
                List.of("talos.read_file", "talos.write_file"),
                List.of());

        assertEquals(VerifierProfile.SOURCE_DERIVED.name(), plan.verifierProfile());
    }

    @Test
    void createDerivesStaticWebVerifierProfileWhenNoProfileIsExplicit() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Create index.html, styles.css, and scripts.js for a BMI calculator.");

        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of());

        assertEquals(VerifierProfile.STATIC_WEB.name(), plan.verifierProfile());
    }

    @Test
    void directConstructorDefensivelyCopiesTaskExpectations() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Overwrite index.html with exactly AFTER. Use talos.write_file.");
        List<TaskExpectation> expectations = new ArrayList<>();
        expectations.add(new LiteralContentExpectation(
                "index.html",
                "AFTER",
                LiteralContentExpectation.MatchMode.EXACT,
                "test"));

        CurrentTurnPlan plan = new CurrentTurnPlan(
                contract,
                contract.originalUserRequest(),
                ExecutionPhase.APPLY,
                ExecutionPhase.APPLY,
                ActionObligation.MUTATING_TOOL_REQUIRED,
                expectations,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of(),
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NOT_DERIVED,
                CurrentTurnPlan.NOT_DERIVED);

        expectations.clear();

        assertEquals(1, plan.taskExpectations().size());
        LiteralContentExpectation literal = assertInstanceOf(
                LiteralContentExpectation.class,
                plan.taskExpectations().getFirst());
        assertEquals("index.html", literal.targetPath());
        assertEquals("AFTER", literal.expectedContent());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.taskExpectations().add(new LiteralContentExpectation(
                        "index.html",
                        "CHANGED",
                        LiteralContentExpectation.MatchMode.EXACT,
                        "test")));
    }
}
