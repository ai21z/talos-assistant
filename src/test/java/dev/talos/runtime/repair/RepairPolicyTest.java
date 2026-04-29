package dev.talos.runtime.repair;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.toolcall.LoopState;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairPolicyTest {

    @Test
    void staticVerificationFailureProducesBoundedRepairPlan() {
        List<ChatMessage> messages = repairMessages("Fix the remaining static verification problems now.");
        TaskContract contract = TaskContractResolver.fromMessages(messages);

        RepairDecision decision = RepairPolicy.planForStaticVerification(messages, contract);

        assertEquals(RepairDecisionStatus.PLAN_CREATED, decision.status());
        RepairPlan plan = decision.plan().orElseThrow();
        assertEquals(RepairPlanKind.STATIC_VERIFICATION_REPAIR, plan.kind());
        assertEquals(1, plan.budget().maxRepairPlansPerTurn());
        assertEquals(List.of("index.html", "scripts.js", "styles.css"), plan.expectedTargets());
        assertTrue(plan.verifierProblemsUsed().stream()
                .anyMatch(problem -> problem.contains("HTML does not link JavaScript file")));
        assertTrue(plan.steps().stream()
                .anyMatch(step -> step.type() == RepairStepType.WRITE_COMPLETE_FILE
                        && "scripts.js".equals(step.targetPath())));
        assertTrue(plan.steps().stream()
                .anyMatch(step -> step.type() == RepairStepType.VERIFY_STATIC));
        assertTrue(plan.instruction().contains("[Static verification repair context]"));
        assertTrue(plan.instruction().contains("Repair plan:"));
        assertTrue(plan.instruction().contains("must use talos.write_file"));
    }

    @Test
    void structuralWebFailuresRequireCompleteWritesForExpectedSmallWebTargets() {
        List<ChatMessage> messages = repairMessages("Fix the remaining static verification problems now.");
        TaskContract contract = TaskContractResolver.fromMessages(messages);

        RepairDecision decision = RepairPolicy.planForStaticVerification(messages, contract);

        RepairPlan plan = decision.plan().orElseThrow();
        assertTrue(plan.steps().stream()
                .anyMatch(step -> step.type() == RepairStepType.WRITE_COMPLETE_FILE
                        && "index.html".equals(step.targetPath())));
        assertTrue(plan.steps().stream()
                .anyMatch(step -> step.type() == RepairStepType.WRITE_COMPLETE_FILE
                        && "styles.css".equals(step.targetPath())));
        assertTrue(plan.steps().stream()
                .anyMatch(step -> step.type() == RepairStepType.WRITE_COMPLETE_FILE
                        && "scripts.js".equals(step.targetPath())));
        assertTrue(plan.instruction().contains("Full-file replacement targets: index.html, scripts.js, styles.css"),
                plan.instruction());
        assertTrue(plan.instruction().contains("must use talos.write_file with complete corrected file content"),
                plan.instruction());
        assertTrue(plan.instruction().contains("Do not use talos.edit_file for these structural web repair targets"),
                plan.instruction());
    }

    @Test
    void fullRewriteTargetsAreExtractedFromRepairContextInstruction() {
        List<ChatMessage> messages = List.of(ChatMessage.system("""
                [Static verification repair context]
                Full-file replacement targets: index.html, scripts.js, styles.css
                """));

        assertEquals(
                java.util.Set.of("index.html", "scripts.js", "styles.css"),
                RepairPolicy.fullRewriteTargetsFromRepairContext(messages));
    }

    @Test
    void structuralWebRepairInfersConventionalThreeFileTargetsWhenCurrentPromptOmitsNames() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("""
                This BMI page is broken. Fix it so it works as a 3-file webpage.
                Use the local files and apply the changes.
                """));
        messages.add(ChatMessage.assistant("""
                [Task incomplete: Static verification failed - HTML does not link JavaScript file: `scripts.js`;
                scripts.js: JavaScript file appears to be placeholder content.;
                Calculator/form task is missing a submit/calculate button.]

                Remaining static verification problems:
                - HTML does not link JavaScript file: `scripts.js`
                - scripts.js: JavaScript file appears to be placeholder content.
                - Calculator/form task is missing a submit/calculate button.
                """));
        messages.add(ChatMessage.user("Fix the remaining static verification problems now."));
        TaskContract contract = TaskContractResolver.fromMessages(messages);

        RepairPlan plan = RepairPolicy.planForStaticVerification(messages, contract)
                .plan()
                .orElseThrow();

        assertEquals(List.of("index.html", "scripts.js", "styles.css"), plan.expectedTargets());
        assertTrue(plan.instruction().contains("Full-file replacement targets: index.html, scripts.js, styles.css"),
                plan.instruction());
    }

    @Test
    void readOnlyContractsDoNotProduceRepairPlans() {
        List<ChatMessage> messages = repairMessages("did you make the changes?");
        TaskContract contract = TaskContractResolver.fromMessages(messages);

        RepairDecision decision = RepairPolicy.planForStaticVerification(messages, contract);

        assertEquals(RepairDecisionStatus.NOT_APPLICABLE, decision.status());
        assertTrue(decision.plan().isEmpty());
    }

    @Test
    void emptyEditRepairInstructionIsBoundedAndOneShotPerPath() {
        LoopState state = loopState();
        state.emptyEditArgumentFailuresByPath.put("index.html", 1);
        state.pathsReadThisTurn.add("index.html");

        var instruction = RepairPolicy.nextEmptyEditRepair(state);

        assertTrue(instruction.isPresent());
        assertEquals(RepairPlanKind.INVALID_EDIT_ARGUMENT_REPAIR, instruction.get().kind());
        assertEquals("index.html", instruction.get().path());
        assertTrue(instruction.get().instruction().contains("[Edit repair required]"));

        state.emptyEditRepairPromptedPaths.add("index.html");

        assertTrue(RepairPolicy.nextEmptyEditRepair(state).isEmpty());
    }

    @Test
    void staleEditRepairRequiresRereadBeforeRetry() {
        LoopState state = loopState();
        state.staleEditFailuresByPath.put("index.html", 1);
        state.pathsMutatedSinceRead.add("index.html");

        var instruction = RepairPolicy.nextStaleEditRepair(state);

        assertTrue(instruction.isPresent());
        assertEquals(RepairPlanKind.STALE_EDIT_REREAD_REPAIR, instruction.get().kind());
        assertEquals("index.html", instruction.get().path());
        assertTrue(instruction.get().instruction().contains("must be talos.read_file"));

        state.staleEditRepairPromptedPaths.add("index.html");

        assertTrue(RepairPolicy.nextStaleEditRepair(state).isEmpty());
    }

    @Test
    void nonRepairFollowUpDoesNotUseVerifierHistory() {
        List<ChatMessage> messages = repairMessages("what did you change?");
        TaskContract contract = TaskContractResolver.fromMessages(messages);

        RepairDecision decision = RepairPolicy.planForStaticVerification(messages, contract);

        assertEquals(RepairDecisionStatus.NOT_APPLICABLE, decision.status());
        assertFalse(contract.mutationAllowed());
    }

    private static List<ChatMessage> repairMessages(String latestUser) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Create index.html, styles.css, and scripts.js for a BMI calculator."));
        messages.add(ChatMessage.assistant("""
                [Task incomplete: Static verification failed - HTML does not link JavaScript file: `scripts.js`]

                The requested task is not verified complete.
                Remaining static verification problems:
                - styles.css: expected target was not successfully mutated.
                - HTML does not link JavaScript file: `scripts.js`
                - Calculator/form task is missing a submit/calculate button.
                """));
        messages.add(ChatMessage.user(latestUser));
        return messages;
    }

    private static LoopState loopState() {
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(ChatMessage.system("sys"))),
                Path.of("."),
                null,
                null,
                10,
                0);
    }
}
