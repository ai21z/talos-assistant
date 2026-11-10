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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D25 option B: a refused or declined proposal on a requested file keeps the PARTIAL status unless it is a provable
 * no-op or a later change of that file followed it. A proposal on a file outside the request does not, and a proposal
 * whose file cannot be identified leaves the coverage not established. The outcomes are synthetic and carry the
 * producers' typed dispatch facts.
 */
class RequestedFileChangeNotRunTest {
    private static final String H2 = "Change the function name get_qty to quantity_on_hand in inventory.py, orders.py "
            + "and report.py, including its definition, imports and calls, so that the code works as before.";

    @Test
    void aRefusedLaterChangeOfARequestedFileKeepsThePartialStatus() {
        MutationRecoveryAssessment assessment = assess(List.of(edited("inventory.py"), edited("orders.py"),
                edited("report.py"), refused("inventory.py", ToolFailureReason.EDIT_EMPTY_ARGUMENTS)));

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertFalse(assessment.completedWithProposalsNotRun());
        assertTrue(assessment.requiredTargetCoverage().established());
        assertEquals(List.of(), assessment.requiredTargetCoverage().withoutChange());
        assertEquals(List.of("inventory.py"), assessment.requiredTargetCoverage().changeNotRun());
        assertFalse(assessment.requiredTargetCoverage().complete());
        assertEquals(List.of(), assessment.mutationOutcome().failed());
    }

    @Test
    void aDeclinedOrPolicyDeniedLaterChangeOfARequestedFileKeepsThePartialStatus() {
        for (ToolFailureReason reason : List.of(ToolFailureReason.USER_APPROVAL_DENIED,
                ToolFailureReason.PERMISSION_POLICY_DENIED)) {
            MutationRecoveryAssessment assessment = assess(List.of(edited("inventory.py"), edited("orders.py"),
                    edited("report.py"), denied("orders.py", reason)));

            assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status(), reason.name());
            assertEquals(List.of("orders.py"), assessment.requiredTargetCoverage().changeNotRun(), reason.name());
        }
    }

    @Test
    void aProvableNoOpALaterChangeOfThatFileOrAnUnrequestedFileDoesNotKeepThePartialStatus() {
        MutationRecoveryAssessment noOp = assess(List.of(edited("inventory.py"), edited("orders.py"),
                edited("report.py"), refused("report.py", ToolFailureReason.EDIT_NO_CHANGE)));
        MutationRecoveryAssessment laterChange = assess(List.of(edited("inventory.py"),
                denied("orders.py", ToolFailureReason.USER_APPROVAL_DENIED), edited("orders.py"),
                edited("report.py")));
        MutationRecoveryAssessment unrequested = assess(List.of(edited("inventory.py"), edited("orders.py"),
                edited("report.py"), denied("helper.py", ToolFailureReason.USER_APPROVAL_DENIED)));

        for (MutationRecoveryAssessment assessment : List.of(noOp, laterChange, unrequested)) {
            assertEquals(MutationOutcomeStatus.SUCCEEDED, assessment.mutationOutcome().status(),
                    assessment.mutationOutcome().notRun().toString());
            assertTrue(assessment.completedWithProposalsNotRun());
            assertTrue(assessment.requiredTargetCoverage().complete());
            assertEquals(List.of(), assessment.requiredTargetCoverage().changeNotRun());
        }
    }

    @Test
    void aProposalWhoseFileCannotBeIdentifiedLeavesTheCoverageNotEstablished() {
        ToolCallLoop.ToolOutcome pathless = new ToolCallLoop.ToolOutcome("talos.edit_file", "", false, true, false, "",
                "PRODUCER TEXT", null, ToolError.INVALID_AUTHORITY, null, ToolMutationEvidence.none(),
                ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID, List.of(), "call-pathless")
                .withDispatch(ToolCallDispatch.blocked(""));

        MutationRecoveryAssessment assessment = assess(List.of(edited("inventory.py"), edited("orders.py"),
                edited("report.py"), pathless));

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertFalse(assessment.requiredTargetCoverage().established());
        assertEquals(List.of(pathless), assessment.mutationOutcome().notRun());
    }

    @Test
    void theAnswerStatesThatAProposedChangeToARequestedFileDidNotRun() {
        String answer = MutationFailureAnswerRenderer.selectPartialMutationAnswerIfNeeded("Model prose.",
                assess(List.of(edited("inventory.py"), edited("orders.py"), edited("report.py"),
                        denied("orders.py", ToolFailureReason.USER_APPROVAL_DENIED)))).answer();

        assertEquals(MutationFailureAnswerRenderer.PARTIAL_REQUESTED_CHANGE_NOT_RUN_ANNOTATION
                + "Succeeded:\n"
                + "- inventory.py: Edited inventory.py\n"
                + "- orders.py: Edited orders.py\n"
                + "- report.py: Edited report.py\n"
                + "Requested files with a later change that did not run:\n"
                + "- orders.py: changed earlier in this turn, but a later proposed change to it did not run.\n"
                + "Not run (these changed no file):\n"
                + "- orders.py: talos.edit_file not run, approval was declined.\n\n"
                + "The assistant summary was replaced with this verified mutation outcome because the turn had "
                + "partial success.", answer);
        assertFalse(answer.contains("Model prose."), answer);
        assertTrue(MutationFailureAnswerRenderer.PARTIAL_REQUESTED_CHANGE_NOT_RUN_ANNOTATION.chars()
                .allMatch(c -> c == '\n' || c >= 0x20 && c < 0x7f));
    }

    /** Review finding 10: the last change of a target decides, not its first. */
    @Test
    void aLaterChangeAfterARefusalOfTheSameFileDoesNotKeepThePartialStatus() {
        MutationRecoveryAssessment assessment = assess(List.of(edited("inventory.py"), edited("orders.py"),
                edited("report.py"), refused("orders.py", ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND),
                editedAgain("orders.py")));

        assertEquals(MutationOutcomeStatus.SUCCEEDED, assessment.mutationOutcome().status());
        assertEquals(List.of(), assessment.requiredTargetCoverage().changeNotRun());
    }

    /**
     * Review finding 8 and critic item 3: a proposal without an identifiable file is resolved when every requested
     * file changed after it. Otherwise the coverage is not established for that typed reason, and the answer says so
     * without claiming that a requested file has no change.
     */
    @Test
    void aProposalWithoutAFileIsResolvedByLaterChangesOfEveryRequestedFile() {
        MutationRecoveryAssessment before = assess(List.of(pathless("call-pathless-1"), edited("inventory.py"),
                edited("orders.py"), edited("report.py")));
        MutationRecoveryAssessment after = assess(List.of(edited("inventory.py"), edited("orders.py"),
                pathless("call-pathless-2"), edited("report.py")));
        MutationRecoveryAssessment withoutChange = assess(List.of(edited("inventory.py"), edited("orders.py"),
                pathless("call-pathless-3")));

        assertEquals(MutationOutcomeStatus.SUCCEEDED, before.mutationOutcome().status());
        assertEquals(MutationOutcomeStatus.PARTIAL, after.mutationOutcome().status());
        assertFalse(after.requiredTargetCoverage().established());
        assertEquals(MutationRecoveryAssessment.CoverageGap.UNIDENTIFIED_PROPOSAL,
                after.requiredTargetCoverage().gap());
        String answer = MutationFailureAnswerRenderer.selectPartialMutationAnswerIfNeeded("Model prose.", after)
                .answer();
        assertTrue(answer.startsWith(MutationFailureAnswerRenderer.PARTIAL_UNIDENTIFIED_PROPOSAL_ANNOTATION), answer);
        assertTrue(answer.contains("Expected files:\n- A later proposed change does not record every file that it "
                + "would change, so a change of every requested file after it is not established.\n"), answer);
        assertFalse(answer.contains("not changed in this turn"), answer);
        // A requested file without any change keeps its specific, true row.
        assertEquals(MutationOutcomeStatus.PARTIAL, withoutChange.mutationOutcome().status());
        assertTrue(withoutChange.requiredTargetCoverage().established());
        assertEquals(List.of("report.py"), withoutChange.requiredTargetCoverage().withoutChange());
    }

    /** Critic item 11: a failure that ran and a later refused change of a requested file are both named. */
    @Test
    void aFailureThatRanAndARefusedLaterChangeAreBothNamed() {
        ToolCallLoop.ToolOutcome ran = new ToolCallLoop.ToolOutcome("talos.edit_file", "report.py", false, true, false,
                "", "PRODUCER TEXT", null, ToolError.TOOL_ERROR, null, ToolMutationEvidence.none(),
                ToolFailureReason.NONE, List.of(), "call-ran").withDispatch(ToolCallDispatch.executed());

        String answer = MutationFailureAnswerRenderer.selectPartialMutationAnswerIfNeeded("Model prose.",
                assess(List.of(edited("inventory.py"), edited("orders.py"), edited("report.py"), ran,
                        denied("inventory.py", ToolFailureReason.USER_APPROVAL_DENIED)))).answer();

        assertTrue(answer.startsWith(MutationFailureAnswerRenderer.PARTIAL_MUTATION_ANNOTATION), answer);
        assertTrue(answer.contains("Failed:\n- report.py: talos.edit_file did not complete (tool error).\n"), answer);
        assertTrue(answer.contains("Requested files with a later change that did not run:\n"
                + "- inventory.py: changed earlier in this turn, but a later proposed change to it did not run.\n"),
                answer);
    }

    /** Critic item 9: the summary of a failure with file effects keeps the requested-file section. */
    @Test
    void theFailedEffectsSummaryKeepsTheRequestedFileSection() {
        ToolCallLoop.ToolOutcome effects = new ToolCallLoop.ToolOutcome("talos.edit_file", "report.py", false, true,
                false, "", "PRODUCER TEXT", null, ToolError.TOOL_ERROR, null, ToolMutationEvidence.none(),
                ToolFailureReason.NONE, List.of(), "call-effects")
                .withMutationEffects(new dev.talos.tools.ToolMutationEffects(List.of("report.py"), List.of()))
                .withDispatch(ToolCallDispatch.executed());

        String answer = MutationFailureAnswerRenderer.selectPartialMutationAnswerIfNeeded("Model prose.",
                assess(List.of(edited("inventory.py"), edited("orders.py"), effects,
                        denied("orders.py", ToolFailureReason.USER_APPROVAL_DENIED)))).answer();

        assertTrue(answer.startsWith("The operation did not complete successfully."), answer);
        assertTrue(answer.contains("Requested files with a later change that did not run:\n"
                + "- orders.py: changed earlier in this turn, but a later proposed change to it did not run."),
                answer);
        // Round 3, critic item 5: the requested-file section comes before the Not run section, as in the summary.
        assertTrue(answer.indexOf("Requested files with a later change") < answer.indexOf("Not run ("), answer);
        // Round 3, item 12: a requested file whose failed call applied effects is not listed as unchanged.
        assertFalse(answer.contains("report.py: expected by this request, but not changed"), answer);
    }

    /**
     * Review finding 3 and critic item 11, with the session workspace as production passes it: an absolute spelling
     * of a requested file is that file, and a spelling that may name it is counted as naming it, because a proposal
     * that may have been meant for a requested file must not let the turn complete.
     */
    @Test
    void withTheWorkspaceAProposalThatMayNameARequestedFileKeepsThePartialStatus(@TempDir Path workspace)
            throws Exception {
        for (String file : List.of("inventory.py", "orders.py", "report.py")) {
            Files.writeString(workspace.resolve(file), "x\n");
        }
        String absolute = workspace.resolve("orders.py").toString();

        MutationRecoveryAssessment absoluteSpelling = assess(List.of(edited("inventory.py"), edited("orders.py"),
                edited("report.py"), denied(absolute, ToolFailureReason.USER_APPROVAL_DENIED)), workspace);
        MutationRecoveryAssessment outside = assess(List.of(edited("inventory.py"), edited("orders.py"),
                edited("report.py"), denied(workspace.resolve("helper.py").toString(),
                        ToolFailureReason.USER_APPROVAL_DENIED)), workspace);

        assertEquals(MutationOutcomeStatus.PARTIAL, absoluteSpelling.mutationOutcome().status());
        assertEquals(List.of("orders.py"), absoluteSpelling.requiredTargetCoverage().changeNotRun());
        assertEquals(List.of(), absoluteSpelling.requiredTargetCoverage().changeNotRunPossible());
        assertEquals(MutationOutcomeStatus.SUCCEEDED, outside.mutationOutcome().status());
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            String driveRelative = workspace.getRoot().toString().substring(0, 2) + "orders.py";
            MutationRecoveryAssessment rooted = assess(List.of(edited("inventory.py"), edited("orders.py"),
                    edited("report.py"), denied(driveRelative, ToolFailureReason.USER_APPROVAL_DENIED)), workspace);
            assertEquals(MutationOutcomeStatus.PARTIAL, rooted.mutationOutcome().status(), driveRelative);
            // Second review, defect D: a match that is only possible is stated as possible.
            assertEquals(List.of("orders.py"), rooted.requiredTargetCoverage().changeNotRunPossible());
            String answer = MutationFailureAnswerRenderer.selectPartialMutationAnswerIfNeeded("Model prose.", rooted)
                    .answer();
            // Round 3, defect 5: when every row is only possible, the annotation and heading say so as well.
            assertTrue(answer.startsWith(
                    MutationFailureAnswerRenderer.PARTIAL_POSSIBLE_REQUESTED_CHANGE_NOT_RUN_ANNOTATION), answer);
            assertTrue(answer.contains("Requested files that a later proposed change may affect:\n"
                    + "- orders.py: changed earlier in this turn, and a later proposed change that did not run may "
                    + "affect it.\n"), answer);
            assertFalse(answer.contains("Requested files with a later change that did not run:"), answer);
        }
    }

    /**
     * Round 3, defect 2: an incomplete move whose named source is a requested file is a proposed change to that file,
     * so the answer names it instead of saying that the proposal records no file.
     */
    @Test
    void anIncompleteMoveOfARequestedFileNamesThatFile() {
        ToolCallLoop.ToolOutcome move = new ToolCallLoop.ToolOutcome("talos.move_path", "inventory.py", false, true,
                false, "", "PRODUCER TEXT", null, ToolError.INVALID_PARAMS, null, ToolMutationEvidence.none(),
                ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED, List.of(), "call-move")
                .withDispatch(ToolCallDispatch.blocked("inventory.py", new ToolCallDispatch.ProposedChange(
                        ToolCallDispatch.OperationKind.MOVE, List.of("inventory.py"), List.of("inventory.py"),
                        false)));

        MutationRecoveryAssessment assessment = assess(List.of(edited("inventory.py"), edited("orders.py"),
                edited("report.py"), move));

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertEquals(List.of("inventory.py"), assessment.requiredTargetCoverage().changeNotRun());
        String answer = MutationFailureAnswerRenderer.selectPartialMutationAnswerIfNeeded("Model prose.", assessment)
                .answer();
        assertTrue(answer.contains("- inventory.py: changed earlier in this turn, but a later proposed change to it "
                + "did not run.\n"), answer);
        assertFalse(answer.contains("does not record every file"), answer);
    }

    /**
     * Round 3, defect 1: a folder delete under a rejected namespace is a delete by the producer's typed kind, so it
     * keeps the turn partial like the accepted name does.
     */
    @Test
    void aFolderDeleteUnderARejectedNamespaceKeepsThePartialStatus() {
        String request = "In docs/a.md and docs/b.md, replace alpha with beta.";
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome delete = folderOperation("functions.delete_path", ToolCallDispatch.OperationKind.DELETE,
                "docs", ToolError.NOT_FOUND, false, ToolFailureReason.NONE);

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(contract, obligation,
                loop(List.of(edited("docs/a.md"), edited("docs/b.md"), delete)), 0,
                TaskVerificationEvidence.notRun("not run"), null);

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertEquals(List.of("docs/a.md", "docs/b.md"),
                assessment.requiredTargetCoverage().changeNotRunPossible().stream().sorted().toList());
    }

    /** Round 3, test gap: folder containment with the session workspace, as production passes it. */
    @Test
    void withTheWorkspaceAFolderOperationHoldsTheRequestedFilesBelowItOnly(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.createDirectories(workspace.resolve("docs2"));
        Files.writeString(workspace.resolve("docs/a.md"), "alpha\n");
        Files.writeString(workspace.resolve("docs/b.md"), "alpha\n");
        String request = "In docs/a.md and docs/b.md, replace alpha with beta.";
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        List<String> holding = new java.util.ArrayList<>(List.of("docs", "./docs", "docs/",
                workspace.resolve("docs").toString()));
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            holding.add("DOCS");
        }

        for (String folder : holding) {
            for (ToolCallDispatch.OperationKind kind : List.of(ToolCallDispatch.OperationKind.DELETE,
                    ToolCallDispatch.OperationKind.MOVE, ToolCallDispatch.OperationKind.COPY,
                    ToolCallDispatch.OperationKind.RENAME)) {
                MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(contract, obligation,
                        loop(List.of(edited("docs/a.md"), edited("docs/b.md"), folderOperation(
                                "talos." + kind.name().toLowerCase(java.util.Locale.ROOT) + "_path", kind, folder,
                                ToolError.DENIED, true, ToolFailureReason.USER_APPROVAL_DENIED))), 0,
                        TaskVerificationEvidence.notRun("not run"), workspace);
                assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status(),
                        kind + " " + folder);
            }
        }
        for (ToolCallLoop.ToolOutcome outside : List.of(
                folderOperation("talos.delete_path", ToolCallDispatch.OperationKind.DELETE, "docs2",
                        ToolError.DENIED, true, ToolFailureReason.USER_APPROVAL_DENIED),
                folderOperation("talos.mkdir", ToolCallDispatch.OperationKind.MKDIR, "docs",
                        ToolError.DENIED, true, ToolFailureReason.USER_APPROVAL_DENIED))) {
            MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(contract, obligation,
                    loop(List.of(edited("docs/a.md"), edited("docs/b.md"), outside)), 0,
                    TaskVerificationEvidence.notRun("not run"), workspace);
            assertEquals(MutationOutcomeStatus.SUCCEEDED, assessment.mutationOutcome().status(), outside.toolName());
        }
    }

    /** Round 3, test gap: a success without a recorded path next to a requested file without a change. */
    @Test
    void anUnidentifiedSuccessNextToAnUnchangedRequestedFileLeavesTheCoverageNotEstablished() {
        ToolCallLoop.ToolOutcome pathlessSuccess = new ToolCallLoop.ToolOutcome("talos.apply_workspace_batch", "", true,
                true, false, "applied", "", null, "", null, ToolMutationEvidence.none(), ToolFailureReason.NONE,
                List.of(), "call-batch").withDispatch(ToolCallDispatch.executed());

        MutationRecoveryAssessment assessment = assess(List.of(edited("inventory.py"), pathlessSuccess,
                denied("orders.py", ToolFailureReason.USER_APPROVAL_DENIED)));

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertEquals(MutationRecoveryAssessment.CoverageGap.UNIDENTIFIED_SUCCESS,
                assessment.requiredTargetCoverage().gap());
    }

    /**
     * Critic item 1 of round 3: the row of a copy names both operands, and a forbidden-target cause does not attach
     * itself to the requested destination.
     */
    @Test
    void aCopyRowNamesBothOperandsAndAForbiddenCauseDoesNotNameTheDestination() {
        ToolCallLoop.ToolOutcome copy = new ToolCallLoop.ToolOutcome("talos.copy_path", "secret.py", false, true,
                false, "", "PRODUCER TEXT", null, ToolError.INVALID_PARAMS, null, ToolMutationEvidence.none(),
                ToolFailureReason.PRE_APPROVAL_TARGET_FORBIDDEN, List.of(), "call-copy")
                .withDispatch(ToolCallDispatch.blocked("secret.py", new ToolCallDispatch.ProposedChange(
                        ToolCallDispatch.OperationKind.COPY, List.of("inventory.py"),
                        List.of("secret.py", "inventory.py"), true)));

        String section = MutationFailureAnswerRenderer.notRunSection(assess(List.of(edited("inventory.py"),
                edited("orders.py"), edited("report.py"), copy)));

        assertEquals("Not run (these changed no file):\n- secret.py -> inventory.py: talos.copy_path not run, refused "
                + "before approval (a file this call names is forbidden by the request).", section);
    }

    /**
     * Round 4, defect a: a spelling that differs only in letter case may name the requested file on a file system
     * that ignores case, on every platform, so it is a possible match. With a proven match beside it, both sections
     * are shown and the annotation states the proven one.
     */
    @Test
    void aCaseVariantIsAPossibleMatchOnEveryPlatformAndMixedRowsUseTheirOwnSections() {
        MutationRecoveryAssessment caseOnly = assess(List.of(edited("inventory.py"), edited("orders.py"),
                edited("report.py"), denied("ORDERS.PY", ToolFailureReason.USER_APPROVAL_DENIED)));
        MutationRecoveryAssessment mixed = assess(List.of(edited("inventory.py"), edited("orders.py"),
                edited("report.py"), denied("INVENTORY.PY", ToolFailureReason.USER_APPROVAL_DENIED),
                denied("orders.py", ToolFailureReason.USER_APPROVAL_DENIED)));

        assertEquals(MutationOutcomeStatus.PARTIAL, caseOnly.mutationOutcome().status());
        assertEquals(List.of("orders.py"), caseOnly.requiredTargetCoverage().changeNotRunPossible());
        String answer = MutationFailureAnswerRenderer.selectPartialMutationAnswerIfNeeded("Model prose.", mixed)
                .answer();
        assertTrue(answer.startsWith(MutationFailureAnswerRenderer.PARTIAL_REQUESTED_CHANGE_NOT_RUN_ANNOTATION), answer);
        assertTrue(answer.contains("Requested files with a later change that did not run:\n"
                + "- orders.py: changed earlier in this turn, but a later proposed change to it did not run.\n"
                + "Requested files that a later proposed change may affect:\n"
                + "- inventory.py: changed earlier in this turn, and a later proposed change that did not run may "
                + "affect it.\n"), answer);
    }

    /**
     * Round 4, defect e and a test gap: without a workspace a folder in another letter case holds the requested file,
     * and with a workspace a folder that holds the workspace root holds every requested file.
     */
    @Test
    void aFolderInAnotherCaseOrAnAncestorOfTheWorkspaceHoldsTheRequestedFiles(@TempDir Path workspace)
            throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/a.md"), "alpha\n");
        Files.writeString(workspace.resolve("docs/b.md"), "alpha\n");
        String request = "In docs/a.md and docs/b.md, replace alpha with beta.";
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);

        MutationRecoveryAssessment upperCase = MutationRecoveryAssessment.assess(contract, obligation,
                loop(List.of(edited("docs/a.md"), edited("docs/b.md"), folderOperation("talos.delete_path",
                        ToolCallDispatch.OperationKind.DELETE, "DOCS", ToolError.DENIED, true,
                        ToolFailureReason.USER_APPROVAL_DENIED))), 0, TaskVerificationEvidence.notRun("not run"), null);
        MutationRecoveryAssessment ancestor = MutationRecoveryAssessment.assess(contract, obligation,
                loop(List.of(edited("docs/a.md"), edited("docs/b.md"), folderOperation("talos.delete_path",
                        ToolCallDispatch.OperationKind.DELETE, "..", ToolError.DENIED, true,
                        ToolFailureReason.USER_APPROVAL_DENIED))), 0, TaskVerificationEvidence.notRun("not run"),
                workspace);

        assertEquals(MutationOutcomeStatus.PARTIAL, upperCase.mutationOutcome().status());
        assertEquals(MutationOutcomeStatus.PARTIAL, ancestor.mutationOutcome().status());
        assertEquals(List.of("docs/a.md", "docs/b.md"),
                ancestor.requiredTargetCoverage().changeNotRunPossible().stream().sorted().toList());
    }

    /**
     * Round 5: a proposal path is placed in the workspace. A dot-segment spelling of a requested file is that file,
     * and a path outside the workspace is outside the request.
     */
    @Test
    void aProposalPathIsPlacedInTheWorkspaceBeforeItIsMatched(@TempDir Path workspace) throws Exception {
        for (String file : List.of("inventory.py", "orders.py", "report.py")) {
            Files.writeString(workspace.resolve(file), "x\n");
        }
        Files.createDirectories(workspace.resolve("docs"));

        MutationRecoveryAssessment dotSegments = assess(List.of(edited("inventory.py"), edited("orders.py"),
                edited("report.py"), denied("docs/../orders.py", ToolFailureReason.USER_APPROVAL_DENIED)), workspace);
        MutationRecoveryAssessment outside = assess(List.of(edited("inventory.py"), edited("orders.py"),
                edited("report.py"), denied(workspace.getParent().resolve("elsewhere.py").toString(),
                        ToolFailureReason.USER_APPROVAL_DENIED)), workspace);

        assertEquals(MutationOutcomeStatus.PARTIAL, dotSegments.mutationOutcome().status());
        assertEquals(List.of("orders.py"), dotSegments.requiredTargetCoverage().changeNotRun());
        assertEquals(MutationOutcomeStatus.SUCCEEDED, outside.mutationOutcome().status());
        assertEquals(MutationRecoveryAssessment.CoverageGap.NONE, outside.requiredTargetCoverage().gap());
    }

    /**
     * Round 5: on a file system that keeps letter case, a case variant that exists as another file cannot have named
     * the requested file, so it is not a match. Where the file system ignores case the case does not arise.
     */
    @Test
    void aCaseVariantThatExistsAsAnotherFileIsNotAMatch(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("orders.py"), "x\n");
        Path variant = workspace.resolve("ORDERS.PY");
        if (!Files.exists(variant)) Files.writeString(variant, "y\n");
        org.junit.jupiter.api.Assumptions.assumeFalse(Files.isSameFile(workspace.resolve("orders.py"), variant),
                "the file system ignores letter case");
        for (String file : List.of("inventory.py", "report.py")) {
            Files.writeString(workspace.resolve(file), "x\n");
        }

        MutationRecoveryAssessment assessment = assess(List.of(edited("inventory.py"), edited("orders.py"),
                edited("report.py"), denied("ORDERS.PY", ToolFailureReason.USER_APPROVAL_DENIED)), workspace);

        assertEquals(MutationOutcomeStatus.SUCCEEDED, assessment.mutationOutcome().status());
    }

    /**
     * Round 5: a workspace opened through a link is held by a declined delete of its real folder, so the turn stays
     * partial. Where links cannot be made the case does not arise.
     */
    @Test
    void aDeleteOfTheRealFolderOfALinkedWorkspaceHoldsTheRequestedFiles(@TempDir Path base) throws Exception {
        Path real = Files.createDirectories(base.resolve("real"));
        Files.createDirectories(real.resolve("docs"));
        Files.writeString(real.resolve("docs/a.md"), "alpha\n");
        Files.writeString(real.resolve("docs/b.md"), "alpha\n");
        Path link = base.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links are not available: " + unavailable);
        }
        String request = "In docs/a.md and docs/b.md, replace alpha with beta.";
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(contract, obligation,
                loop(List.of(edited("docs/a.md"), edited("docs/b.md"), folderOperation("talos.delete_path",
                        ToolCallDispatch.OperationKind.DELETE, real.toRealPath().toString(), ToolError.DENIED, true,
                        ToolFailureReason.USER_APPROVAL_DENIED))), 0, TaskVerificationEvidence.notRun("not run"), link);

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
    }

    /**
     * Round 6, security regression: a refused proposal path whose root is not the workspace root, such as a UNC share
     * or another drive, is placed outside the workspace from its text, without any file-system access, so a
     * model-chosen host is never contacted.
     */
    @Test
    void aProposalPathOnAnotherRootIsPlacedOutsideWithoutTouchingTheFileSystem(@TempDir Path workspace)
            throws Exception {
        for (String file : List.of("inventory.py", "orders.py", "report.py")) {
            Files.writeString(workspace.resolve(file), "x\n");
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
        assertTrue(MutationRecoveryAssessment.probesFileSystemFor(workspace, "orders.py"));
        assertTrue(MutationRecoveryAssessment.probesFileSystemFor(workspace, workspace.resolve("docs/x.md").toString()));
        if (windows) {
            assertFalse(MutationRecoveryAssessment.probesFileSystemFor(workspace, "//host.invalid/share/x.md"));
            assertFalse(MutationRecoveryAssessment.probesFileSystemFor(workspace, "\\\\host.invalid\\share\\x.md"));
            String otherDrive = workspace.getRoot().toString().toUpperCase(java.util.Locale.ROOT).startsWith("Z")
                    ? "Y:/x.md" : "Z:/x.md";
            assertFalse(MutationRecoveryAssessment.probesFileSystemFor(workspace, otherDrive));

            MutationRecoveryAssessment unc = assess(List.of(edited("inventory.py"), edited("orders.py"),
                    edited("report.py"), denied("//host.invalid/share/orders.py",
                            ToolFailureReason.USER_APPROVAL_DENIED)), workspace);
            assertEquals(MutationOutcomeStatus.SUCCEEDED, unc.mutationOutcome().status());
        }
    }

    /**
     * Round 6: a spelling of a requested file through a link inside the workspace is read as that file by its real
     * path, so a refused change through it keeps the turn partial. A folder operand that is itself a link is not
     * followed, because the operation acts on the link. Where links cannot be made the cases do not arise.
     */
    @Test
    void aLinkInsideTheWorkspaceIsResolvedAndALinkOperandIsNotFollowed(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/guide.md"), "alpha\n");
        try {
            Files.createSymbolicLink(workspace.resolve("manual"), workspace.resolve("docs"));
            Files.createSymbolicLink(workspace.resolve("home"), workspace);
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links are not available: " + unavailable);
        }
        String request = "In docs/guide.md, replace alpha with beta.";
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        assertEquals(List.of("docs/guide.md"), obligation.requiredMutationTargets());

        MutationRecoveryAssessment throughLink = MutationRecoveryAssessment.assess(contract, obligation,
                loop(List.of(edited("docs/guide.md"), denied("manual/guide.md",
                        ToolFailureReason.USER_APPROVAL_DENIED))), 0, TaskVerificationEvidence.notRun("not run"),
                workspace);
        MutationRecoveryAssessment linkOperand = MutationRecoveryAssessment.assess(contract, obligation,
                loop(List.of(edited("docs/guide.md"), folderOperation("talos.delete_path",
                        ToolCallDispatch.OperationKind.DELETE, "home", ToolError.DENIED, true,
                        ToolFailureReason.USER_APPROVAL_DENIED))), 0, TaskVerificationEvidence.notRun("not run"),
                workspace);

        assertEquals(MutationOutcomeStatus.PARTIAL, throughLink.mutationOutcome().status());
        assertEquals(List.of("docs/guide.md"), throughLink.requiredTargetCoverage().changeNotRunPossible());
        assertEquals(MutationOutcomeStatus.SUCCEEDED, linkOperand.mutationOutcome().status());
    }

    /**
     * Round 6: on a file system that keeps letter case, a declined delete of a different folder whose name differs
     * only in case does not hold the requested files. Where the file system ignores case the case does not arise.
     */
    @Test
    void aDistinctFolderInAnotherCaseDoesNotHoldTheRequestedFiles(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/a.md"), "alpha\n");
        Files.writeString(workspace.resolve("docs/b.md"), "alpha\n");
        Path variant = workspace.resolve("DOCS");
        if (!Files.exists(variant)) Files.createDirectories(variant);
        org.junit.jupiter.api.Assumptions.assumeFalse(Files.isSameFile(workspace.resolve("docs"), variant),
                "the file system ignores letter case");
        String request = "In docs/a.md and docs/b.md, replace alpha with beta.";
        TaskContract contract = TaskContractResolver.fromUserRequest(request);

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(contract,
                RequestObligationCompiler.compile(contract), loop(List.of(edited("docs/a.md"), edited("docs/b.md"),
                        folderOperation("talos.delete_path", ToolCallDispatch.OperationKind.DELETE, "DOCS",
                                ToolError.DENIED, true, ToolFailureReason.USER_APPROVAL_DENIED))), 0,
                TaskVerificationEvidence.notRun("not run"), workspace);

        assertEquals(MutationOutcomeStatus.SUCCEEDED, assessment.mutationOutcome().status());
    }

    /**
     * Round 6: a complete proposal whose path cannot be placed in the workspace keeps the turn partial with its own
     * typed gap and a sentence that does not say the proposal recorded no file.
     */
    @Test
    void aCompleteProposalWhosePathCannotBePlacedHasItsOwnGap() {
        MutationRecoveryAssessment assessment = assess(List.of(edited("inventory.py"), edited("orders.py"),
                edited("report.py"), denied("/srv/elsewhere/orders.py", ToolFailureReason.USER_APPROVAL_DENIED)));

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertEquals(MutationRecoveryAssessment.CoverageGap.UNPLACED_PROPOSAL,
                assessment.requiredTargetCoverage().gap());
        String answer = MutationFailureAnswerRenderer.selectPartialMutationAnswerIfNeeded("Model prose.", assessment)
                .answer();
        assertTrue(answer.startsWith(MutationFailureAnswerRenderer.PARTIAL_UNPLACED_PROPOSAL_ANNOTATION), answer);
        assertTrue(answer.contains("Expected files:\n- A later proposed change names a path that could not be placed "
                + "in the workspace, so a change of every requested file after it is not established.\n"), answer);
        assertFalse(answer.contains("does not record every file"), answer);
    }

    private static ToolCallLoop.ToolOutcome folderOperation(String toolName, ToolCallDispatch.OperationKind kind,
                                                            String folder, String code, boolean denied,
                                                            ToolFailureReason reason) {
        return new ToolCallLoop.ToolOutcome(toolName, folder, false, true, denied, "", "PRODUCER TEXT", null, code,
                null, ToolMutationEvidence.none(), reason, List.of(), "call-" + toolName + "-" + folder)
                .withDispatch(ToolCallDispatch.blocked(folder, new ToolCallDispatch.ProposedChange(kind,
                        List.of(folder), List.of(folder), true)));
    }

    /**
     * Second review, defect E: a success whose path is unknown does not turn a known requested file whose later
     * change did not run into an unestablished coverage, because that row stays true either way.
     */
    @Test
    void anUnidentifiedSuccessKeepsTheRequestedFileRow() {
        ToolCallLoop.ToolOutcome pathlessSuccess = new ToolCallLoop.ToolOutcome("talos.apply_workspace_batch", "", true,
                true, false, "applied", "", null, "", null, ToolMutationEvidence.none(), ToolFailureReason.NONE,
                List.of(), "call-batch").withDispatch(ToolCallDispatch.executed());

        MutationRecoveryAssessment assessment = assess(List.of(edited("inventory.py"), edited("orders.py"),
                edited("report.py"), pathlessSuccess, denied("orders.py", ToolFailureReason.USER_APPROVAL_DENIED)));

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertTrue(assessment.requiredTargetCoverage().established());
        assertEquals(List.of("orders.py"), assessment.requiredTargetCoverage().changeNotRun());
        String answer = MutationFailureAnswerRenderer.selectPartialMutationAnswerIfNeeded("Model prose.", assessment)
                .answer();
        assertTrue(answer.startsWith(MutationFailureAnswerRenderer.PARTIAL_REQUESTED_CHANGE_NOT_RUN_ANNOTATION), answer);
    }

    /**
     * Second review, defect I: a declined delete, move, copy or rename of a folder that holds a requested file may
     * affect that file, so it keeps the turn partial and says so as a possibility.
     */
    @Test
    void aDeclinedOperationOnAFolderThatHoldsARequestedFileKeepsThePartialStatus() {
        String request = "In docs/a.md and docs/b.md, replace alpha with beta.";
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        assertEquals(java.util.Set.of("docs/a.md", "docs/b.md"), java.util.Set.copyOf(obligation.requiredMutationTargets()));
        ToolCallLoop.ToolOutcome folderDelete = folderOperation("talos.delete_path",
                ToolCallDispatch.OperationKind.DELETE, "docs", ToolError.DENIED, true,
                ToolFailureReason.USER_APPROVAL_DENIED);
        ToolCallLoop.ToolOutcome folderMkdir = folderOperation("talos.mkdir",
                ToolCallDispatch.OperationKind.MKDIR, "docs", ToolError.DENIED, true,
                ToolFailureReason.USER_APPROVAL_DENIED);

        MutationRecoveryAssessment deleted = MutationRecoveryAssessment.assess(contract, obligation,
                loop(List.of(edited("docs/a.md"), edited("docs/b.md"), folderDelete)), 0,
                TaskVerificationEvidence.notRun("not run"), null);
        MutationRecoveryAssessment made = MutationRecoveryAssessment.assess(contract, obligation,
                loop(List.of(edited("docs/a.md"), edited("docs/b.md"), folderMkdir)), 0,
                TaskVerificationEvidence.notRun("not run"), null);

        assertEquals(MutationOutcomeStatus.PARTIAL, deleted.mutationOutcome().status());
        assertEquals(List.of("docs/a.md", "docs/b.md"),
                deleted.requiredTargetCoverage().changeNotRun().stream().sorted().toList());
        assertEquals(deleted.requiredTargetCoverage().changeNotRun(),
                deleted.requiredTargetCoverage().changeNotRunPossible());
        assertTrue(MutationFailureAnswerRenderer.selectPartialMutationAnswerIfNeeded("Model prose.", deleted).answer()
                .startsWith(MutationFailureAnswerRenderer.PARTIAL_POSSIBLE_REQUESTED_CHANGE_NOT_RUN_ANNOTATION));
        // A mkdir of the folder changes no file inside it.
        assertEquals(MutationOutcomeStatus.SUCCEEDED, made.mutationOutcome().status());
    }

    private static MutationRecoveryAssessment assess(List<ToolCallLoop.ToolOutcome> outcomes) {
        return assess(outcomes, null);
    }

    private static MutationRecoveryAssessment assess(List<ToolCallLoop.ToolOutcome> outcomes, Path workspace) {
        TaskContract contract = TaskContractResolver.fromUserRequest(H2);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        return MutationRecoveryAssessment.assess(contract, obligation, loop(outcomes), 0,
                TaskVerificationEvidence.notRun("not run"), workspace);
    }

    private static ToolCallLoop.ToolOutcome editedAgain(String path) {
        return new ToolCallLoop.ToolOutcome("talos.edit_file", path, true, true, false, "Edited " + path, "", null, "",
                null, ToolMutationEvidence.exactEdit("old2", "new2"), ToolFailureReason.NONE, List.of(),
                "call-edit-again-" + path).withDispatch(ToolCallDispatch.executed());
    }

    private static ToolCallLoop.ToolOutcome pathless(String toolCallId) {
        return new ToolCallLoop.ToolOutcome("talos.edit_file", "", false, true, false, "", "PRODUCER TEXT", null,
                ToolError.INVALID_AUTHORITY, null, ToolMutationEvidence.none(),
                ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID, List.of(), toolCallId)
                .withDispatch(ToolCallDispatch.blocked(""));
    }

    private static ToolCallLoop.ToolOutcome edited(String path) {
        return new ToolCallLoop.ToolOutcome("talos.edit_file", path, true, true, false, "Edited " + path, "", null, "",
                null, ToolMutationEvidence.exactEdit("old", "new"), ToolFailureReason.NONE, List.of(),
                "call-edit-" + path).withDispatch(ToolCallDispatch.executed());
    }

    private static ToolCallLoop.ToolOutcome refused(String path, ToolFailureReason reason) {
        return new ToolCallLoop.ToolOutcome("talos.edit_file", path, false, true, false, "", "PRODUCER TEXT", null,
                ToolError.INVALID_PARAMS, null, ToolMutationEvidence.none(), reason, List.of(),
                "call-refused-" + path + "-" + reason).withDispatch(ToolCallDispatch.blocked(path));
    }

    private static ToolCallLoop.ToolOutcome denied(String path, ToolFailureReason reason) {
        return new ToolCallLoop.ToolOutcome("talos.edit_file", path, false, true, true, "", "PRODUCER TEXT", null,
                ToolError.DENIED, null, ToolMutationEvidence.none(), reason, List.of(),
                "call-denied-" + path + "-" + reason).withDispatch(ToolCallDispatch.blocked(path));
    }

    private static ToolCallLoop.LoopResult loop(List<ToolCallLoop.ToolOutcome> outcomes) {
        return new ToolCallLoop.LoopResult("answer", 1, outcomes.size(),
                outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(), List.of(),
                (int) outcomes.stream().filter(outcome -> !outcome.success()).count(), 0, false,
                (int) outcomes.stream().filter(outcome -> outcome.mutating() && outcome.success()).count(),
                List.of(), 0, 0, 0, 0, outcomes);
    }
}
