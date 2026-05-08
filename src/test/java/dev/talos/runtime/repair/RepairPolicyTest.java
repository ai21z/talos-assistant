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
    void structuralWebRepairInstructionRequiresCrossFileCoherenceBeforeWrites() {
        List<ChatMessage> messages = repairMessages("Fix the remaining static verification problems now.");
        TaskContract contract = TaskContractResolver.fromMessages(messages);

        RepairPlan plan = RepairPolicy.planForStaticVerification(messages, contract)
                .plan()
                .orElseThrow();

        assertTrue(plan.instruction().contains("Cross-file coherence checklist"), plan.instruction());
        assertTrue(plan.instruction().contains("HTML must link every CSS and JavaScript file being written"),
                plan.instruction());
        assertTrue(plan.instruction().contains("Every JavaScript ID or selector must exist in HTML"),
                plan.instruction());
        assertTrue(plan.instruction().contains("CSS selectors should correspond to classes or IDs in HTML"),
                plan.instruction());
        assertTrue(plan.instruction().contains("cross-check all HTML/CSS/JS files before emitting tool calls"),
                plan.instruction());
    }

    @Test
    void cssSelectorOnlyRepairUsesStylesheetTargetInsteadOfWholeWebSurface() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js."));
        messages.add(ChatMessage.assistant("""
                [Task incomplete: Static verification failed - CSS references missing class selectors: `.button`]

                The requested task is not verified complete.
                Unresolved static verification problems:
                - CSS references missing class selectors: `.button`

                Applied mutating tool calls:
                - index.html: Updated index.html
                - styles.css: Updated styles.css
                - scripts.js: Updated scripts.js
                """));
        messages.add(ChatMessage.user("Fix the remaining static verification problems now."));
        TaskContract contract = TaskContractResolver.fromMessages(messages);

        RepairPlan plan = RepairPolicy.planForStaticVerification(messages, contract)
                .plan()
                .orElseThrow();

        assertEquals(List.of("index.html", "scripts.js", "styles.css"), plan.expectedTargets());
        assertTrue(plan.instruction().contains("Full-file replacement targets: styles.css"),
                plan.instruction());
        assertFalse(plan.instruction().contains("Full-file replacement targets: index.html"),
                plan.instruction());
        assertFalse(plan.instruction().contains("scripts.js: You must use talos.write_file"),
                plan.instruction());
        assertEquals(List.of("styles.css"), plan.steps().stream()
                .filter(step -> step.type() == RepairStepType.WRITE_COMPLETE_FILE)
                .map(RepairPlanStep::targetPath)
                .toList());
    }

    @Test
    void cssOnlySelectorRepairExplainsStylesheetOnlyStrategy() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js."));
        messages.add(ChatMessage.assistant("""
                [Task incomplete: Static verification failed - CSS references missing class selectors: `.button`]

                Unresolved static verification problems:
                - CSS references missing class selectors: `.button`

                Applied mutating tool calls:
                - index.html: Updated index.html
                - styles.css: Updated styles.css
                - scripts.js: Updated scripts.js
                """));
        messages.add(ChatMessage.user("Fix the remaining static verification problems now."));
        TaskContract contract = TaskContractResolver.fromMessages(messages);

        RepairPlan plan = RepairPolicy.planForStaticVerification(messages, contract)
                .plan()
                .orElseThrow();

        assertTrue(plan.instruction().contains("CSS selector repair constraint"), plan.instruction());
        assertTrue(plan.instruction().contains("Only CSS targets are in this repair plan"),
                plan.instruction());
        assertTrue(plan.instruction().contains("do not depend on HTML edits"),
                plan.instruction());
        assertTrue(plan.instruction().contains("remove or rename orphan selectors"),
                plan.instruction());
        assertTrue(plan.instruction().contains("Do not leave a reported missing selector"),
                plan.instruction());
        assertFalse(plan.instruction().contains("add a matching class in HTML"),
                plan.instruction());
    }

    @Test
    void staticVerificationRepairInstructionNamesMissingExpectedTargetAndSimilarWrongTarget() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js."));
        messages.add(ChatMessage.assistant("""
                [Task incomplete: Static verification failed - scripts.js: expected target was not successfully mutated.]

                The requested task is not verified complete.
                Unresolved static verification problems:
                - scripts.js: expected target was not successfully mutated.
                - Calculator/form task is missing a result output element.

                Applied mutating tool calls:
                - index.html: wrote index.html
                - styles.css: wrote styles.css
                - script.js: wrote script.js
                """));
        messages.add(ChatMessage.user("Fix the remaining static verification problems now."));
        TaskContract contract = TaskContractResolver.fromMessages(messages);

        RepairPlan plan = RepairPolicy.planForStaticVerification(messages, contract)
                .plan()
                .orElseThrow();

        assertTrue(plan.instruction().contains("Missing expected targets: scripts.js"),
                plan.instruction());
        assertTrue(plan.instruction().contains("script.js does not satisfy scripts.js"),
                plan.instruction());
        assertTrue(plan.instruction().contains("Full-file replacement targets: index.html, scripts.js, styles.css"),
                plan.instruction());
        assertFalse(plan.instruction().contains("Full-file replacement targets: index.html, script.js, scripts.js"),
                plan.instruction());
    }

    @Test
    void staticVerificationRepairDoesNotPromoteWrongSimilarTargetWhenOnlyExpectedTargetIsMissing() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js."));
        messages.add(ChatMessage.assistant("""
                [Task incomplete: Static verification failed - scripts.js: expected target was not successfully mutated. Changed similar target(s) `script.js` does not satisfy `scripts.js`.]

                The requested task is not verified complete.
                Unresolved static verification problems:
                - scripts.js: expected target was not successfully mutated. Changed similar target(s) `script.js` does not satisfy `scripts.js`.

                Applied mutating tool calls:
                - index.html: Updated index.html (20 lines, 553 bytes)
                - styles.css: Updated styles.css (49 lines, 696 bytes)
                - script.js: Updated script.js (11 lines, 531 bytes)
                """));
        messages.add(ChatMessage.user("Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js. It should calculate BMI from height and weight."));
        TaskContract contract = TaskContractResolver.fromMessages(messages);

        RepairPlan plan = RepairPolicy.planForStaticVerification(messages, contract)
                .plan()
                .orElseThrow();

        assertTrue(plan.instruction().contains("Missing expected targets: scripts.js"),
                plan.instruction());
        assertTrue(plan.instruction().contains("script.js does not satisfy scripts.js"),
                plan.instruction());
        assertTrue(plan.instruction().contains("Full-file replacement targets: scripts.js"),
                plan.instruction());
        assertFalse(plan.instruction().contains("Full-file replacement targets: script.js, scripts.js"),
                plan.instruction());
        assertFalse(plan.steps().stream()
                        .anyMatch(step -> "script.js".equals(step.targetPath())),
                plan.instruction());
    }

    @Test
    void freshExactWriteDoesNotPlanStaticRepairFromPreviouslyAppliedTargets() {
        var messages = staleScriptsRepairMessages(
                "Overwrite index.html with exactly AFTER. Use talos.write_file.");
        TaskContract contract = TaskContractResolver.fromMessages(messages);

        RepairDecision decision = RepairPolicy.planForStaticVerification(messages, contract);

        assertEquals(RepairDecisionStatus.NOT_APPLICABLE, decision.status());
        assertTrue(decision.plan().isEmpty());
        assertTrue(decision.reason().contains("targets did not overlap"), decision.reason());
    }

    @Test
    void sameMissingTargetStillPlansStaticRepairFromPreviousFailure() {
        var messages = staleScriptsRepairMessages(
                "Fix scripts.js with complete corrected BMI JavaScript. Use talos.write_file.");
        TaskContract contract = TaskContractResolver.fromMessages(messages);

        RepairDecision decision = RepairPolicy.planForStaticVerification(messages, contract);

        assertEquals(RepairDecisionStatus.PLAN_CREATED, decision.status());
        RepairPlan plan = decision.plan().orElseThrow();
        assertEquals(List.of("scripts.js"), plan.expectedTargets());
        assertTrue(plan.instruction().contains("Full-file replacement targets: scripts.js"),
                plan.instruction());
        assertFalse(plan.instruction().contains("Full-file replacement targets: index.html"),
                plan.instruction());
    }

    @Test
    void explicitStructuralWebTaskDoesNotCarryStaleSiblingRepairTarget() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Make script.js fix the selector bug by changing .missing-button to .cta-button."));
        messages.add(ChatMessage.assistant("""
                [Task incomplete: Static verification failed - HTML does not link CSS file: `styles.css`; HTML does not link JavaScript file: `script.js`]

                The requested task is not verified complete.
                Unresolved static verification problems:
                - HTML does not link CSS file: `styles.css`
                - HTML does not link JavaScript file: `script.js`
                - JavaScript references missing class selectors: `.cta-button`
                - JavaScript references missing IDs: `#result`

                Applied mutating tool calls:
                - script.js: Edited script.js
                """));
        messages.add(ChatMessage.user(
                "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js."));
        TaskContract contract = TaskContractResolver.fromMessages(messages);

        RepairPlan plan = RepairPolicy.planForStaticVerification(messages, contract)
                .plan()
                .orElseThrow();

        assertEquals(List.of("index.html", "scripts.js", "styles.css"), plan.expectedTargets());
        assertTrue(plan.instruction().contains("Full-file replacement targets: index.html, scripts.js, styles.css"),
                plan.instruction());
        assertFalse(plan.instruction().contains("Full-file replacement targets: index.html, script.js, scripts.js"),
                plan.instruction());
        assertFalse(plan.steps().stream()
                        .anyMatch(step -> "script.js".equals(step.targetPath())),
                plan.instruction());
    }

    @Test
    void staleReadmeStaticFailureDoesNotPlanRepairForFreshWebTargets() {
        List<ChatMessage> messages = readmeFailureMessages(
                "Create index.html, styles.css, and scripts.js for a BMI calculator. Use talos.write_file.");
        TaskContract contract = TaskContractResolver.fromMessages(messages);

        RepairDecision decision = RepairPolicy.planForStaticVerification(messages, contract);

        assertEquals(RepairDecisionStatus.NOT_APPLICABLE, decision.status());
        assertTrue(decision.plan().isEmpty());
        assertTrue(decision.reason().contains("targets did not overlap"), decision.reason());
    }

    @Test
    void staleReadmeStaticFailureStillPlansRepairForCurrentReadmeTarget() {
        List<ChatMessage> messages = readmeFailureMessages("Fix README.md now using talos.write_file.");
        TaskContract contract = TaskContractResolver.fromMessages(messages);

        RepairDecision decision = RepairPolicy.planForStaticVerification(messages, contract);

        assertEquals(RepairDecisionStatus.PLAN_CREATED, decision.status());
        RepairPlan plan = decision.plan().orElseThrow();
        assertEquals(List.of("README.md"), plan.expectedTargets());
        assertTrue(plan.instruction().contains("README.md"), plan.instruction());
        assertFalse(plan.instruction().contains("Cross-file coherence checklist"), plan.instruction());
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

    private static List<ChatMessage> staleScriptsRepairMessages(String latestUser) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js."));
        messages.add(ChatMessage.assistant("""
                [Task incomplete: Static verification failed - scripts.js: expected target was not successfully mutated.; Expected web-app build to successfully mutate a JavaScript file.; JavaScript references missing IDs: `#bmi-form`]

                The requested task is not verified complete. Applied changes below are workspace changes only; unresolved static problems remain.

                Unresolved static verification problems:
                - scripts.js: expected target was not successfully mutated.
                - Expected web-app build to successfully mutate a JavaScript file.
                - JavaScript references missing IDs: `#bmi-form`

                Applied mutating tool calls:
                - index.html: Updated index.html (20 lines, 553 bytes)
                - styles.css: Updated styles.css (49 lines, 696 bytes)
                - script.js: Updated script.js (11 lines, 531 bytes)
                """));
        messages.add(ChatMessage.user(latestUser));
        return messages;
    }

    private static List<ChatMessage> readmeFailureMessages(String latestUser) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Edit README.md now using talos.write_file. The complete file must contain exactly two lines."));
        messages.add(ChatMessage.assistant("""
                [Task incomplete: Static verification failed - README.md literal content mismatch]

                The requested task is not verified complete.
                Remaining static verification problems:
                - README.md: literal content did not match the exact requested content.
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
