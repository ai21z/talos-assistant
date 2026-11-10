package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallDispatch;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.obligation.RequestObligation;
import dev.talos.runtime.obligation.RequestObligationCompiler;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.runtime.verification.TaskVerificationEvidence;
import dev.talos.runtime.verification.TaskVerificationResult;
import dev.talos.runtime.verification.VerificationReport;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolMutationEffects;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static dev.talos.runtime.outcome.MutationRecoveryAssessment.ProposalDisposition.DENIED_BY_POLICY;
import static dev.talos.runtime.outcome.MutationRecoveryAssessment.ProposalDisposition.DENIED_BY_USER;
import static dev.talos.runtime.outcome.MutationRecoveryAssessment.ProposalDisposition.FAILED;
import static dev.talos.runtime.outcome.MutationRecoveryAssessment.ProposalDisposition.REFUSED_BEFORE_APPROVAL;
import static dev.talos.runtime.outcome.MutationRecoveryAssessment.ProposalDisposition.REFUSED_BY_AUTHORITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-F7 (D1): a proposal that never reached the tool registry is not a failed change. The status is PARTIAL only for
 * an unresolved failure that ran or cannot be proven not to have run, or for a required target without a change. The
 * outcomes are synthetic and carry the producers' typed dispatch facts.
 */
class MutationRecoveryNotRunAssessmentTest {
    private static final String H2 = "Change the function name get_qty to quantity_on_hand in inventory.py, orders.py "
            + "and report.py, including its definition, imports and calls, so that the code works as before.";

    @Test
    void refusedNoOpEditsWithEveryRequiredTargetChangedAreSucceeded() {
        TaskContract contract = TaskContractResolver.fromUserRequest(H2);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        assertEquals(Set.of("inventory.py", "orders.py", "report.py"), Set.copyOf(obligation.requiredMutationTargets()));
        List<ToolCallLoop.ToolOutcome> outcomes = List.of(
                edited("inventory.py", "call-1-4"),
                edited("orders.py", "call-1-5"),
                edited("orders.py", "call-1-6"),
                edited("report.py", "call-2-1"),
                edited("report.py", "call-3-1"),
                refused("inventory.py", ToolFailureReason.EDIT_NO_CHANGE, "call-4-1"),
                refused("inventory.py", ToolFailureReason.EDIT_EMPTY_ARGUMENTS, "call-4-2"));

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract, obligation, loop(outcomes), 0, passed(), null);
        MutationRecoveryAssessment unwitnessed = MutationRecoveryAssessment.assess(
                contract, obligation, loop(outcomes.stream()
                        .map(outcome -> outcome.success() ? outcome : outcome.withDispatch(ToolCallDispatch.unknown()))
                        .toList()), 0, passed(), null);

        assertEquals(MutationOutcomeStatus.SUCCEEDED, assessment.mutationOutcome().status());
        assertTrue(assessment.completedWithProposalsNotRun());
        assertTrue(assessment.changesWithUnresolvedProposals());
        assertEquals(List.of(), assessment.mutationOutcome().failed());
        assertEquals(outcomes.subList(5, 7), assessment.mutationOutcome().notRun());
        assertEquals(2, assessment.mutationOutcome().failureCount());
        assertTrue(assessment.requiredTargetCoverage().complete());
        assertEquals(List.of(REFUSED_BEFORE_APPROVAL, REFUSED_BEFORE_APPROVAL),
                assessment.failureAssessments().stream().map(MutationRecoveryAssessment.FailureAssessment::disposition)
                        .toList());
        // A failure without a dispatch witness keeps the classification from before LA-F7.
        assertEquals(MutationOutcomeStatus.PARTIAL, unwitnessed.mutationOutcome().status());
        assertTrue(unwitnessed.changesWithUnresolvedProposals());
        // The recovery decisions, which the trace records, are the same for both.
        assertEquals(decisions(unwitnessed), decisions(assessment));
    }

    @Test
    void aRefusalNextToAnExpectedFileWithoutAChangeStaysPartial() {
        TaskContract contract = TaskContractResolver.fromUserRequest(H2);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome refusal = refused("orders.py", ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND, "call-2-1");

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(contract, obligation,
                loop(List.of(edited("inventory.py", "call-1-1"), edited("orders.py", "call-1-2"), refusal)),
                0, passed(), null);

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertTrue(assessment.requiredTargetCoverage().established());
        assertEquals(List.of("report.py"), assessment.requiredTargetCoverage().withoutChange());
        assertEquals(List.of(), assessment.mutationOutcome().failed());
        assertEquals(List.of(refusal), assessment.mutationOutcome().notRun());
    }

    @Test
    void aFailureThatRanOrMayHaveRunStaysAFailure() {
        ToolCallLoop.ToolOutcome unwitnessed = invalidEdit("inventory.py", ToolFailureReason.EDIT_NO_CHANGE, "call-2-1");
        ToolCallLoop.ToolOutcome checkpoint = new ToolCallLoop.ToolOutcome("talos.edit_file", "report.py", false, true,
                false, "", "Required checkpoint failed before mutation", null, ToolError.INTERNAL_ERROR, null,
                ToolMutationEvidence.none(), ToolFailureReason.NONE, List.of(), "call-2-2")
                .withDispatch(ToolCallDispatch.blocked("report.py"));
        ToolCallLoop.ToolOutcome ranAfterDenial = denied("orders.py", ToolFailureReason.USER_APPROVAL_DENIED, "call-2-3")
                .withDispatch(ToolCallDispatch.executed());
        ToolCallLoop.ToolOutcome ranWithAuthorityReason = new ToolCallLoop.ToolOutcome("talos.edit_file", "orders.py",
                false, true, false, "", "authority", null, ToolError.INVALID_AUTHORITY, null,
                ToolMutationEvidence.none(), ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID, List.of(), "call-2-4")
                .withDispatch(ToolCallDispatch.executed());
        ToolCallLoop.ToolOutcome effects = invalidEdit("orders.py", ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND,
                "call-2-5").withMutationEffects(new ToolMutationEffects(List.of(), List.of("orders.py")));

        for (ToolCallLoop.ToolOutcome outcome : List.of(unwitnessed, checkpoint, ranAfterDenial,
                ranWithAuthorityReason, effects)) {
            assertEquals(FAILED, MutationRecoveryAssessment.dispositionOf(outcome), outcome.toString());
        }
        TaskContract contract = TaskContractResolver.fromUserRequest(H2);
        for (ToolCallLoop.ToolOutcome failure : List.of(unwitnessed, checkpoint)) {
            MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(contract, null,
                    loop(List.of(edited("inventory.py", "call-1-1"), edited("orders.py", "call-1-2"),
                            edited("report.py", "call-1-3"), failure)), 0, passed(), null);
            assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status(), failure.toString());
            assertEquals(List.of(failure), assessment.mutationOutcome().failed());
        }
        assertThrows(IllegalArgumentException.class,
                () -> MutationRecoveryAssessment.dispositionOf(edited("a.py", "call-9-9")));
    }

    @Test
    void dispositionsOfProposalsThatNeverRanComeFromTypedFacts() {
        assertEquals(DENIED_BY_USER, MutationRecoveryAssessment.dispositionOf(
                denied("a.py", ToolFailureReason.USER_APPROVAL_DENIED, "call-1-1")));
        assertEquals(DENIED_BY_POLICY, MutationRecoveryAssessment.dispositionOf(
                denied("a.py", ToolFailureReason.PERMISSION_POLICY_DENIED, "call-1-2")));
        assertEquals(REFUSED_BY_AUTHORITY, MutationRecoveryAssessment.dispositionOf(new ToolCallLoop.ToolOutcome(
                "talos.edit_file", "", false, true, false, "", "authority", null, ToolError.INVALID_AUTHORITY, null,
                ToolMutationEvidence.none(), ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID, List.of(), "")));
        assertEquals(REFUSED_BEFORE_APPROVAL, MutationRecoveryAssessment.dispositionOf(
                refused("a.py", ToolFailureReason.NONE, "call-1-3")));
        assertEquals(FAILED, MutationRecoveryAssessment.dispositionOf(new ToolCallLoop.ToolOutcome(
                "talos.edit_file", "a.py", false, true, false, "", "tool", null, ToolError.TOOL_ERROR, null,
                ToolMutationEvidence.none(), ToolFailureReason.NONE, List.of(), "call-1-4")
                .withDispatch(ToolCallDispatch.blocked("a.py"))));
    }

    @Test
    void aDeclinedUnrequestedFileIsNotRunAndADeclinedExpectedFileKeepsThePartialStatus() {
        TaskContract contract = TaskContractResolver.fromUserRequest(H2);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome helper = denied("helper.py", ToolFailureReason.USER_APPROVAL_DENIED, "call-2-1");
        ToolCallLoop.ToolOutcome report = denied("report.py", ToolFailureReason.USER_APPROVAL_DENIED, "call-2-2");

        MutationRecoveryAssessment unrequested = MutationRecoveryAssessment.assess(contract, obligation,
                loop(List.of(edited("inventory.py", "call-1-1"), edited("orders.py", "call-1-2"),
                        edited("report.py", "call-1-3"), helper)), 0, passed(), null);
        MutationRecoveryAssessment expected = MutationRecoveryAssessment.assess(contract, obligation,
                loop(List.of(edited("inventory.py", "call-1-1"), edited("orders.py", "call-1-2"), report)),
                0, passed(), null);

        assertEquals(MutationOutcomeStatus.SUCCEEDED, unrequested.mutationOutcome().status());
        assertEquals(List.of(helper), unrequested.mutationOutcome().denied());
        assertEquals(List.of(helper), unrequested.mutationOutcome().notRun());
        assertEquals(DENIED_BY_USER, unrequested.failureAssessments().getFirst().disposition());
        assertEquals(MutationRecoveryAssessment.Decision.DENIED_OR_POLICY,
                unrequested.failureAssessments().getFirst().decision());
        assertEquals(MutationOutcomeStatus.PARTIAL, expected.mutationOutcome().status());
        assertEquals(List.of("report.py"), expected.requiredTargetCoverage().withoutChange());
    }

    @Test
    void coverageThatCannotBeEstablishedKeepsThePartialStatus() {
        TaskContract contract = TaskContractResolver.fromUserRequest(H2);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome refusal = refused("inventory.py", ToolFailureReason.EDIT_NO_CHANGE, "call-2-1");
        List<ToolCallLoop.ToolOutcome> complete = List.of(edited("inventory.py", "call-1-1"),
                edited("orders.py", "call-1-2"), edited("report.py", "call-1-3"), refusal);
        ToolCallLoop.ToolOutcome pathless = new ToolCallLoop.ToolOutcome("talos.batch_workspace_apply", "", true, true,
                false, "applied", "", null, "", null, ToolMutationEvidence.none(), ToolFailureReason.NONE, List.of(),
                "call-1-4");

        assertEquals(MutationOutcomeStatus.SUCCEEDED, MutationRecoveryAssessment.assess(contract, obligation,
                loop(complete), 0, passed(), null).mutationOutcome().status());
        assertEquals(MutationOutcomeStatus.PARTIAL, MutationRecoveryAssessment.assess(null, null,
                loop(complete), 0, passed(), null).mutationOutcome().status());
        assertEquals(MutationOutcomeStatus.PARTIAL, MutationRecoveryAssessment.assess(contract, obligation,
                loop(complete), 1, passed(), null).mutationOutcome().status());
        MutationRecoveryAssessment unknownPath = MutationRecoveryAssessment.assess(contract, obligation,
                loop(List.of(edited("inventory.py", "call-1-1"), pathless, refusal)), 0, passed(), null);
        assertEquals(MutationOutcomeStatus.PARTIAL, unknownPath.mutationOutcome().status());
        assertFalse(unknownPath.requiredTargetCoverage().established());
        assertEquals(List.of(), unknownPath.requiredTargetCoverage().withoutChange());
    }

    @Test
    void unresolvedOutcomesKeepTurnOrderAcrossFailuresAndProposalsThatNeverRan() {
        ToolCallLoop.ToolOutcome refusal = refused("report.py", ToolFailureReason.EDIT_NO_CHANGE, "call-1-2");
        ToolCallLoop.ToolOutcome failure = invalidEdit("orders.py", ToolFailureReason.NONE, "call-1-3");
        ToolCallLoop.ToolOutcome denial = denied("helper.py", ToolFailureReason.USER_APPROVAL_DENIED, "call-1-4");

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(null, null,
                loop(List.of(edited("inventory.py", "call-1-1"), refusal, failure, denial)), 0, passed(), null);

        assertEquals(List.of(refusal, failure, denial), assessment.unresolvedOutcomesInOrder());
        assertEquals(List.of(failure), assessment.mutationOutcome().failed());
        assertEquals(List.of(refusal, denial), assessment.mutationOutcome().notRun());
        assertEquals(3, assessment.mutationOutcome().failureCount());
    }

    private static List<MutationRecoveryAssessment.Decision> decisions(MutationRecoveryAssessment assessment) {
        return assessment.failureAssessments().stream().map(MutationRecoveryAssessment.FailureAssessment::decision)
                .toList();
    }

    private static TaskVerificationEvidence passed() {
        return TaskVerificationEvidence.postApply(
                TaskVerificationResult.passed("Exact edit replacement checks passed.", List.of()),
                VerificationReport.empty());
    }

    private static ToolCallLoop.ToolOutcome edited(String path, String toolCallId) {
        return new ToolCallLoop.ToolOutcome("talos.edit_file", path, true, true, false, "Edited " + path, "", null, "",
                null, ToolMutationEvidence.exactEdit("old", "new"), ToolFailureReason.NONE, List.of(), toolCallId)
                .withDispatch(ToolCallDispatch.executed());
    }

    private static ToolCallLoop.ToolOutcome invalidEdit(String path, ToolFailureReason reason, String toolCallId) {
        return new ToolCallLoop.ToolOutcome("talos.edit_file", path, false, true, false, "", "PRODUCER TEXT", null,
                ToolError.INVALID_PARAMS, null, ToolMutationEvidence.none(), reason, List.of(), toolCallId);
    }

    private static ToolCallLoop.ToolOutcome refused(String path, ToolFailureReason reason, String toolCallId) {
        return invalidEdit(path, reason, toolCallId).withDispatch(ToolCallDispatch.blocked(path));
    }

    private static ToolCallLoop.ToolOutcome denied(String path, ToolFailureReason reason, String toolCallId) {
        return new ToolCallLoop.ToolOutcome("talos.edit_file", path, false, true, true, "", "approval denied", null,
                ToolError.DENIED, null, ToolMutationEvidence.none(), reason, List.of(), toolCallId)
                .withDispatch(ToolCallDispatch.blocked(path));
    }

    private static ToolCallLoop.LoopResult loop(List<ToolCallLoop.ToolOutcome> outcomes) {
        return new ToolCallLoop.LoopResult("answer", 1, outcomes.size(),
                outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(), List.of(),
                (int) outcomes.stream().filter(outcome -> !outcome.success()).count(), 0, false,
                (int) outcomes.stream().filter(outcome -> outcome.mutating() && outcome.success()).count(),
                List.of(), 0, 0, 0, 0, outcomes);
    }
}
