package dev.talos.runtime.policy;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceObligationAssessmentTest {

    @Test
    void nullPlanReturnsNoObligationWithSatisfiedResult() {
        EvidenceObligationAssessment assessment = EvidenceObligationAssessment.assess(null, null, null);

        assertEquals(EvidenceObligation.NONE, assessment.obligation());
        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, assessment.result().status());
        assertFalse(assessment.missingEvidence());
        assertFalse(assessment.protectedReadApprovalMissing());
    }

    @Test
    void sourceEvidenceTargetsArePreferredOverExpectedTargets() {
        CurrentTurnPlan plan = plan(
                EvidenceObligation.READ_TARGET_REQUIRED,
                contract(Set.of("output.md"), Set.of("source.md")));
        ToolCallLoop.LoopResult loopResult = loopResult(
                List.of("talos.read_file"),
                List.of("source.md"),
                List.of(readOutcome("source.md")));

        EvidenceObligationAssessment assessment = EvidenceObligationAssessment.assess(plan, loopResult, null);

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, assessment.result().status());
        assertFalse(assessment.missingEvidence());
    }

    @Test
    void legacyLoopToolNamesAndReadPathsAreSynthesizedWhenToolOutcomesAreAbsent() {
        CurrentTurnPlan plan = plan(
                EvidenceObligation.READ_TARGET_REQUIRED,
                contract(Set.of("README.md"), Set.of()));
        ToolCallLoop.LoopResult loopResult = loopResult(
                List.of("talos.read_file"),
                List.of("README.md"),
                List.of());

        EvidenceObligationAssessment assessment = EvidenceObligationAssessment.assess(plan, loopResult, null);

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, assessment.result().status());
    }

    @Test
    void existingToolOutcomesAreUsedInsteadOfLegacyFallbackEvidence() {
        CurrentTurnPlan plan = plan(
                EvidenceObligation.READ_TARGET_REQUIRED,
                contract(Set.of("README.md"), Set.of()));
        ToolCallLoop.LoopResult loopResult = loopResult(
                List.of("talos.read_file"),
                List.of("README.md"),
                List.of(readOutcome("notes.md")));

        EvidenceObligationAssessment assessment = EvidenceObligationAssessment.assess(plan, loopResult, null);

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, assessment.result().status());
        assertTrue(assessment.missingEvidence());
    }

    @Test
    void protectedReadApprovalMissingOnlyForUnsatisfiedProtectedReadObligation() {
        ToolCallLoop.LoopResult emptyLoop = loopResult(List.of(), List.of(), List.of());

        EvidenceObligationAssessment protectedAssessment = EvidenceObligationAssessment.assess(
                plan(EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED, contract(Set.of(".env"), Set.of())),
                emptyLoop,
                null);
        EvidenceObligationAssessment readAssessment = EvidenceObligationAssessment.assess(
                plan(EvidenceObligation.READ_TARGET_REQUIRED, contract(Set.of(".env"), Set.of())),
                emptyLoop,
                null);

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, protectedAssessment.result().status());
        assertTrue(protectedAssessment.missingEvidence());
        assertTrue(protectedAssessment.protectedReadApprovalMissing());
        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, readAssessment.result().status());
        assertTrue(readAssessment.missingEvidence());
        assertFalse(readAssessment.protectedReadApprovalMissing());
    }

    @Test
    void planModeStaticWebRedesignDoesNotRequireReadingAbsentSatellites(@TempDir Path workspace) throws Exception {
        // T900: plan-mode "redesign this page" on a single-file index.html projected the
        // conventional triplet; the read-evidence obligation demanded reading nonexistent
        // style.css/script.js -> false block. The model read index.html (the only file
        // that exists), so the obligation must be SATISFIED.
        Files.writeString(workspace.resolve("index.html"), "<h1>Acme</h1>");
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                false, // read-only (plan posture)
                true,
                Set.of("index.html", "style.css", "script.js"),
                Set.of(),
                Set.of(),
                "lets plan a full page change to make it visually better",
                "contextual-static-web-follow-up; capability-posture-read-only");
        CurrentTurnPlan plan = plan(EvidenceObligation.READ_TARGET_REQUIRED, contract);
        ToolCallLoop.LoopResult loopResult = loopResult(
                List.of("talos.read_file"),
                List.of("index.html"),
                List.of(readOutcome("index.html")));

        EvidenceObligationAssessment assessment =
                EvidenceObligationAssessment.assess(plan, loopResult, workspace);

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, assessment.result().status());
        assertFalse(assessment.missingEvidence());
    }

    @Test
    void planModeStaticWebStillRequiresReadingThePageThatExists(@TempDir Path workspace) throws Exception {
        // Guard: dropping absent satellites must not drop index.html. If the model read
        // nothing, the turn is still (correctly) unsatisfied.
        Files.writeString(workspace.resolve("index.html"), "<h1>Acme</h1>");
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                false,
                true,
                Set.of("index.html", "style.css", "script.js"),
                Set.of(),
                Set.of(),
                "redesign this page",
                "contextual-static-web-follow-up; capability-posture-read-only");
        CurrentTurnPlan plan = plan(EvidenceObligation.READ_TARGET_REQUIRED, contract);
        ToolCallLoop.LoopResult loopResult = loopResult(List.of(), List.of(), List.of());

        EvidenceObligationAssessment assessment =
                EvidenceObligationAssessment.assess(plan, loopResult, workspace);

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, assessment.result().status());
        assertTrue(assessment.missingEvidence());
    }

    private static CurrentTurnPlan plan(EvidenceObligation obligation, TaskContract contract) {
        return new CurrentTurnPlan(
                contract,
                contract.originalUserRequest(),
                ExecutionPhase.INSPECT,
                ExecutionPhase.INSPECT,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                obligation.name(),
                CurrentTurnPlan.NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED);
    }

    private static TaskContract contract(Set<String> expectedTargets, Set<String> sourceEvidenceTargets) {
        return new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                expectedTargets,
                sourceEvidenceTargets,
                Set.of(),
                "inspect files",
                "test");
    }

    private static ToolCallLoop.LoopResult loopResult(
            List<String> toolNames,
            List<String> readPaths,
            List<ToolCallLoop.ToolOutcome> outcomes
    ) {
        return new ToolCallLoop.LoopResult(
                "answer",
                1,
                toolNames.size(),
                toolNames,
                List.of(),
                0,
                0,
                false,
                0,
                readPaths,
                0,
                0,
                0,
                0,
                outcomes);
    }

    private static ToolCallLoop.ToolOutcome readOutcome(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.read_file",
                path,
                true,
                false,
                false,
                "read " + path,
                "");
    }
}
