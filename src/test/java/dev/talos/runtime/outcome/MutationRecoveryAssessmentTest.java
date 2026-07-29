package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.obligation.RequestObligation;
import dev.talos.runtime.obligation.RequestObligationCompiler;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.runtime.verification.TaskVerificationEvidence;
import dev.talos.runtime.verification.TaskVerificationResult;
import dev.talos.runtime.verification.VerificationReport;
import dev.talos.runtime.verification.StaticTaskVerifier;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationRecoveryAssessmentTest {
    private static final String F16_REQUEST =
            "Append a Contributing section to README.md with one sentence saying "
                    + "Run tests before review. Preserve all current text.";
    private static final String F17_REQUEST =
            "In notes.md, change draft to final. If an exact edit fails, "
                    + "recover without deleting any other line.";
    private static final String F21_REQUEST =
            "Append Decision 004 to docs/decisions.md saying health checks remain read-only. "
                    + "Preserve Decision 001 and change no code.";

    @TempDir
    Path workspace;

    @Test
    void authoritativeVerifiedLaterRepairResolvesEligibleFailure() throws Exception {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome failure = failedEdit(
                "docs/decisions.md",
                ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND);
        ToolCallLoop.ToolOutcome repair = successfulWrite(
                "docs/decisions.md",
                "Decision 001: baseline\n",
                "Decision 001: baseline\nDecision 004: health checks remain read-only\n");

        ToolCallLoop.LoopResult loop = loopResult(List.of(failure, repair));
        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loop,
                0,
                verifiedPostApply(
                        workspace,
                        contract,
                        obligation,
                        loop,
                        "docs/decisions.md",
                        "Decision 001: baseline\n"
                                + "Decision 004: health checks remain read-only\n"));

        assertEquals(MutationOutcomeStatus.SUCCEEDED, assessment.mutationOutcome().status());
        assertTrue(assessment.mutationOutcome().failed().isEmpty());
        assertEquals(1, assessment.failureAssessments().size());
        MutationRecoveryAssessment.FailureAssessment decision =
                assessment.failureAssessments().getFirst();
        assertEquals(
                MutationRecoveryAssessment.Decision.RECOVERED_MATCHED_OBLIGATION,
                decision.decision());
        assertEquals(1, decision.failureOrdinal());
        assertEquals(2, decision.repairOrdinal());
        assertEquals(List.of("call-1-2"), decision.coveredToolCallIds());
        assertTrue(decision.recovered());
    }

    @Test
    void f16VerifiedFullRewriteRepairResolvesTheFailedAppendAttempt() throws Exception {
        TaskContract contract = TaskContractResolver.fromUserRequest(F16_REQUEST);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        String before = "# Talos\n\nExisting text.\n";
        String after = before + "\n## Contributing\nRun tests before review.\n";
        ToolCallLoop.ToolOutcome failure = typedRepairFailure(
                "README.md",
                ToolFailureReason.EDIT_FULL_REWRITE_REQUIRED,
                "call-1-1");
        ToolCallLoop.ToolOutcome repair = successfulWrite(
                "README.md",
                before,
                after,
                "call-1-2");
        ToolCallLoop.LoopResult loop = loopResult(List.of(failure, repair));

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loop,
                0,
                verifiedPostApply(
                        workspace,
                        contract,
                        obligation,
                        loop,
                        "README.md",
                        after));

        assertEquals(MutationOutcomeStatus.SUCCEEDED, assessment.mutationOutcome().status());
        assertEquals(
                MutationRecoveryAssessment.Decision.RECOVERED_MATCHED_OBLIGATION,
                assessment.failureAssessments().getFirst().decision());
    }

    @Test
    void f17VerifiedExactRepairResolvesAFormerlyNonWhitelistedAmbiguousEdit()
            throws Exception {
        TaskContract contract = TaskContractResolver.fromUserRequest(F17_REQUEST);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        String before = "Status: draft\nKeep this line unchanged.\n";
        String after = "Status: final\nKeep this line unchanged.\n";
        ToolCallLoop.ToolOutcome failure = typedRepairFailure(
                "notes.md",
                ToolFailureReason.EDIT_OLD_STRING_AMBIGUOUS,
                "call-1-1");
        ToolCallLoop.ToolOutcome repair = successfulEdit(
                "notes.md",
                "draft",
                "final",
                "call-1-2");
        ToolCallLoop.LoopResult loop = loopResult(List.of(failure, repair));

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loop,
                0,
                verifiedPostApply(
                        workspace,
                        contract,
                        obligation,
                        loop,
                        "notes.md",
                        after));

        assertEquals(MutationOutcomeStatus.SUCCEEDED, assessment.mutationOutcome().status());
        assertTrue(assessment.mutationOutcome().failed().isEmpty());
        MutationRecoveryAssessment.FailureAssessment recovery =
                assessment.failureAssessments().getFirst();
        assertEquals(
                MutationRecoveryAssessment.Decision.RECOVERED_MATCHED_OBLIGATION,
                recovery.decision());
        assertEquals("call-1-1", recovery.failureToolCallId());
        assertEquals("call-1-2", recovery.repairToolCallId());
        assertEquals(List.of("call-1-2"), recovery.coveredToolCallIds());
    }

    @Test
    void ambiguousEditRepairWithoutAuthoritativeFinalStateProofRemainsPartial() {
        TaskContract contract = TaskContractResolver.fromUserRequest(F17_REQUEST);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loopResult(List.of(
                        typedRepairFailure(
                                "notes.md",
                                ToolFailureReason.EDIT_OLD_STRING_AMBIGUOUS,
                                "call-1-1"),
                        successfulEdit(
                                "notes.md",
                                "draft",
                                "final",
                                "call-1-2"))),
                0,
                passedPostApply());

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertEquals(
                MutationRecoveryAssessment.Decision.FINAL_STATE_PROOF_MISSING,
                assessment.failureAssessments().getFirst().decision());
    }

    @Test
    void ambiguousEditRecoveryRejectsNonCanonicalFailureShapes() {
        TaskContract contract = TaskContractResolver.fromUserRequest(F17_REQUEST);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome repair = successfulEdit(
                "notes.md",
                "draft",
                "final",
                "call-1-2");
        List<ToolCallLoop.ToolOutcome> invalidFailures = List.of(
                ambiguousEditFailure(
                        "talos.write_file",
                        ToolError.INVALID_PARAMS,
                        ToolMutationEvidence.none()),
                ambiguousEditFailure(
                        "talos.edit_file",
                        ToolError.TOOL_ERROR,
                        ToolMutationEvidence.none()),
                ambiguousEditFailure(
                        "talos.edit_file",
                        ToolError.INVALID_PARAMS,
                        ToolMutationEvidence.exactEdit("draft", "final")));

        for (ToolCallLoop.ToolOutcome invalid : invalidFailures) {
            MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                    contract,
                    obligation,
                    loopResult(List.of(invalid, repair)),
                    0,
                    passedPostApply());

            assertEquals(
                    MutationOutcomeStatus.PARTIAL,
                    assessment.mutationOutcome().status());
            assertEquals(
                    MutationRecoveryAssessment.Decision.FAILURE_NOT_ELIGIBLE,
                    assessment.failureAssessments().getFirst().decision());
        }
    }

    @Test
    void notMigratedSamePathSuccessCannotRecoverScenarioElevenShape() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Update the page title and hero in index.html.");
        RequestObligation obligation = RequestObligationCompiler.compile(contract);

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loopResult(List.of(
                        failedEdit(
                                "index.html",
                                ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND),
                        successfulEdit("index.html", "<header>", "<header class=\"site\">"),
                        successfulEdit("index.html", "<main>", "<main class=\"hero\">"))),
                0,
                passedPostApply());

        assertEquals(
                RequestObligation.DerivationStatus.NOT_MIGRATED,
                obligation.derivationStatus());
        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertEquals(1, assessment.mutationOutcome().failed().size());
        assertEquals(
                MutationRecoveryAssessment.Decision.OBLIGATION_NOT_COMPLETE,
                assessment.failureAssessments().getFirst().decision());
    }

    @Test
    void typedEmptyEditRetrySettlesWithoutClaimingRecoveryProof() {
        TaskContract contract = notMigratedContract(Set.of("index.html"));
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome failure = typedRepairFailure(
                "index.html",
                ToolFailureReason.EDIT_EMPTY_ARGUMENTS,
                "call-1-1");
        ToolCallLoop.ToolOutcome repair = successfulEdit(
                "index.html",
                "<main>",
                "<main><a class=\"cta-button\">Listen</a>",
                "call-1-3");

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loopResult(List.of(
                        failure,
                        successfulRead("index.html", "call-1-2"),
                        repair)),
                0,
                passedPostApply());

        assertEquals(
                RequestObligation.DerivationStatus.NOT_MIGRATED,
                obligation.derivationStatus());
        assertEquals(
                MutationOutcomeStatus.SUCCEEDED,
                assessment.mutationOutcome().status());
        MutationRecoveryAssessment.FailureAssessment settlement =
                assessment.failureAssessments().getFirst();
        assertEquals(
                MutationRecoveryAssessment.Decision
                        .RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN,
                settlement.decision());
        assertEquals(1, settlement.failureOrdinal());
        assertEquals(3, settlement.repairOrdinal());
        assertEquals("call-1-1", settlement.failureToolCallId());
        assertEquals("call-1-3", settlement.repairToolCallId());
        assertTrue(settlement.resolved());
        assertFalse(settlement.recovered());
        assertTrue(settlement.verifiedObligationSha256().isBlank());
        assertTrue(settlement.coveredToolCallIds().isEmpty());

        MutationRecoveryAssessment fullWriteSettlement =
                MutationRecoveryAssessment.assess(
                        contract,
                        obligation,
                        loopResult(List.of(
                                failure,
                                successfulRead("index.html", "call-1-2"),
                                successfulWrite(
                                        "index.html",
                                        "<main>",
                                        "<main><a class=\"cta-button\">Listen</a>",
                                        "call-1-3"))),
                        0,
                        passedPostApply());
        assertEquals(
                MutationRecoveryAssessment.Decision
                        .RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN,
                fullWriteSettlement.failureAssessments()
                        .getFirst()
                        .decision());
    }

    @Test
    void typedFullRewriteRedirectSettlesInsideVerifiedMultiTargetRepair() {
        TaskContract contract = notMigratedContract(
                Set.of("index.html", "styles.css", "scripts.js"));
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome failure = typedRepairFailure(
                "index.html",
                ToolFailureReason.EDIT_FULL_REWRITE_REQUIRED,
                "call-1-1");
        ToolCallLoop.ToolOutcome indexRepair = successfulWriteWithoutPriorEvidence(
                "index.html",
                "call-1-2");
        ToolCallLoop.ToolOutcome stylesRepair = successfulWriteWithoutPriorEvidence(
                "styles.css",
                "call-1-3");
        ToolCallLoop.ToolOutcome scriptsRepair = successfulWriteWithoutPriorEvidence(
                "scripts.js",
                "call-1-4");

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loopResult(List.of(
                        failure,
                        indexRepair,
                        stylesRepair,
                        scriptsRepair)),
                0,
                passedPostApply());

        assertEquals(
                MutationOutcomeStatus.SUCCEEDED,
                assessment.mutationOutcome().status());
        MutationRecoveryAssessment.FailureAssessment settlement =
                assessment.failureAssessments().getFirst();
        assertEquals(
                MutationRecoveryAssessment.Decision
                        .RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN,
                settlement.decision());
        assertEquals("call-1-2", settlement.repairToolCallId());
        assertTrue(settlement.resolved());
        assertFalse(settlement.recovered());
    }

    @Test
    void typedRepairSettlementRejectsAmbiguousOrUnverifiedSequences() {
        TaskContract contract = notMigratedContract(Set.of("index.html"));
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome emptyFailure = typedRepairFailure(
                "index.html",
                ToolFailureReason.EDIT_EMPTY_ARGUMENTS,
                "call-1-1");
        ToolCallLoop.ToolOutcome repair = successfulEdit(
                "index.html",
                "before",
                "after",
                "call-1-3");

        List<MutationRecoveryAssessment> rejected = List.of(
                MutationRecoveryAssessment.assess(
                        contract,
                        obligation,
                        loopResult(List.of(
                                emptyFailure,
                                typedRepairFailure(
                                        "index.html",
                                        ToolFailureReason.EDIT_EMPTY_ARGUMENTS,
                                        "call-1-2"),
                                successfulEdit(
                                        "index.html",
                                        "before",
                                        "after",
                                        "call-1-3"))),
                        0,
                        passedPostApply()),
                MutationRecoveryAssessment.assess(
                        contract,
                        obligation,
                        loopResult(List.of(
                                emptyFailure,
                                successfulRead("index.html", "call-1-2"),
                                successfulWriteWithoutPriorEvidence(
                                        "index.html",
                                        "call-1-3"))),
                        0,
                        passedPostApply()),
                MutationRecoveryAssessment.assess(
                        contract,
                        obligation,
                        loopResult(List.of(
                                emptyFailure,
                                repair,
                                successfulWrite(
                                        "index.html",
                                        "after",
                                        "later",
                                        "call-1-3"))),
                        0,
                        passedPostApply()),
                MutationRecoveryAssessment.assess(
                        contract,
                        obligation,
                        loopResult(List.of(emptyFailure, repair)),
                        1,
                        passedPostApply()),
                MutationRecoveryAssessment.assess(
                        contract,
                        obligation,
                        loopResult(List.of(emptyFailure, repair)),
                        0,
                        TaskVerificationEvidence.notRun("not run")));

        rejected.forEach(
                MutationRecoveryAssessmentTest::assertNoTypedSettlement);
    }

    @Test
    void typedRepairSettlementRequiresPassedPostApplyStaticEvidence() {
        TaskContract contract = notMigratedContract(Set.of("index.html"));
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.LoopResult loop = loopResult(List.of(
                typedRepairFailure(
                        "index.html",
                        ToolFailureReason.EDIT_EMPTY_ARGUMENTS,
                        "call-1-1"),
                successfulRead("index.html", "call-1-2"),
                successfulEdit(
                        "index.html",
                        "before",
                        "after",
                        "call-1-3")));

        List<TaskVerificationEvidence> rejectedEvidence = List.of(
                TaskVerificationEvidence.postApply(
                        TaskVerificationResult.readbackOnly(
                                "readback only",
                                List.of()),
                        VerificationReport.empty()),
                TaskVerificationEvidence.postApply(
                        TaskVerificationResult.failed(
                                "failed",
                                List.of(),
                                List.of("mismatch")),
                        VerificationReport.empty()),
                TaskVerificationEvidence.postApply(
                        TaskVerificationResult.notRun("not run"),
                        VerificationReport.empty()),
                TaskVerificationEvidence.postApply(
                        TaskVerificationResult.unavailable(
                                "unavailable",
                                List.of(),
                                List.of("verifier unavailable")),
                        VerificationReport.empty()),
                new TaskVerificationEvidence(
                        TaskVerificationResult.passed(
                                "document extraction passed",
                                List.of()),
                        VerificationReport.empty(),
                        dev.talos.runtime.verification
                                .TaskVerificationEvidenceSource
                                .DOCUMENT_EXTRACTION_TOOL_RESULT),
                new TaskVerificationEvidence(
                        TaskVerificationResult.passed(
                                "inconsistent source passed",
                                List.of()),
                        VerificationReport.empty(),
                        dev.talos.runtime.verification
                                .TaskVerificationEvidenceSource.NOT_RUN),
                TaskVerificationEvidence.embeddedAssistant(
                        TaskVerificationResult.passed(
                                "assistant claimed success",
                                List.of())));

        rejectedEvidence.forEach(evidence -> assertNoTypedSettlement(
                MutationRecoveryAssessment.assess(
                        contract,
                        obligation,
                        loop,
                        0,
                        evidence)));
    }

    @Test
    void typedRepairSettlementRequiresExactFailureAndRepairAuthority() {
        TaskContract contract = notMigratedContract(Set.of("index.html"));
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome repair = successfulEdit(
                "index.html",
                "before",
                "after",
                "call-1-3");
        ToolCallLoop.ToolOutcome read =
                successfulRead("index.html", "call-1-2");
        ToolCallLoop.ToolOutcome wrongErrorCode = new ToolCallLoop.ToolOutcome(
                "talos.edit_file",
                "index.html",
                false,
                true,
                false,
                "",
                "internal",
                null,
                ToolError.INTERNAL_ERROR,
                null,
                ToolMutationEvidence.none(),
                ToolFailureReason.EDIT_EMPTY_ARGUMENTS,
                List.of(),
                "call-1-1");
        ToolCallLoop.ToolOutcome forgedFailureEvidence =
                new ToolCallLoop.ToolOutcome(
                        "talos.edit_file",
                        "index.html",
                        false,
                        true,
                        false,
                        "",
                        "invalid",
                        null,
                        ToolError.INVALID_PARAMS,
                        null,
                        ToolMutationEvidence.exactEdit("old", "new"),
                        ToolFailureReason.EDIT_EMPTY_ARGUMENTS,
                        List.of(),
                        "call-1-1");
        ToolCallLoop.ToolOutcome failure = typedRepairFailure(
                "index.html",
                ToolFailureReason.EDIT_EMPTY_ARGUMENTS,
                "call-1-1");
        ToolCallLoop.ToolOutcome successWithFailureReason =
                new ToolCallLoop.ToolOutcome(
                        "talos.edit_file",
                        "index.html",
                        true,
                        true,
                        false,
                        "edited",
                        "",
                        null,
                        "",
                        null,
                        ToolMutationEvidence.exactEdit("before", "after"),
                        ToolFailureReason.EDIT_OLD_STRING_AMBIGUOUS,
                        List.of(),
                        "call-1-3");

        List<MutationRecoveryAssessment> rejected = List.of(
                MutationRecoveryAssessment.assess(
                        contract,
                        obligation,
                        loopResult(List.of(wrongErrorCode, read, repair)),
                        0,
                        passedPostApply()),
                MutationRecoveryAssessment.assess(
                        contract,
                        obligation,
                        loopResult(List.of(
                                forgedFailureEvidence,
                                read,
                                repair)),
                        0,
                        passedPostApply()),
                MutationRecoveryAssessment.assess(
                        contract,
                        obligation,
                        loopResult(List.of(
                                successfulEdit(
                                        "index.html",
                                        "before",
                                        "after",
                                        "call-1-1"),
                                typedRepairFailure(
                                        "index.html",
                                        ToolFailureReason.EDIT_EMPTY_ARGUMENTS,
                                        "call-1-2"))),
                        0,
                        passedPostApply()),
                MutationRecoveryAssessment.assess(
                        contract,
                        obligation,
                        loopResult(List.of(
                                failure,
                                read,
                                successfulEdit(
                                        "index.html",
                                        "before",
                                        "after",
                                        "call-1-1"))),
                        0,
                        passedPostApply()),
                MutationRecoveryAssessment.assess(
                        contract,
                        obligation,
                        loopResult(List.of(
                                failure,
                                read,
                                successWithFailureReason)),
                        0,
                        passedPostApply()),
                MutationRecoveryAssessment.assess(
                        contract,
                        obligation,
                        loopResult(List.of(
                                failure,
                                read,
                                repair,
                                successfulWrite(
                                        "README.md",
                                        "",
                                        "outside target",
                                        "call-1-4"))),
                        0,
                        passedPostApply()));

        rejected.forEach(
                MutationRecoveryAssessmentTest::assertNoTypedSettlement);
    }

    @Test
    void typedRepairSettlementRequiresTheExactCompiledObligation() {
        TaskContract contract = notMigratedContract(Set.of("index.html"));
        RequestObligation mismatched = RequestObligationCompiler.compile(
                notMigratedContract(Set.of("README.md")));
        ToolCallLoop.LoopResult loop = loopResult(List.of(
                typedRepairFailure(
                        "index.html",
                        ToolFailureReason.EDIT_EMPTY_ARGUMENTS,
                        "call-1-1"),
                successfulRead("index.html", "call-1-2"),
                successfulEdit(
                        "index.html",
                        "before",
                        "after",
                        "call-1-3")));

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                mismatched,
                loop,
                0,
                passedPostApply());

        assertNoTypedSettlement(assessment);
    }

    @Test
    void typedRepairSettlementRejectsForeignVerifiedObligationReceipt()
            throws Exception {
        TaskContract proofContract =
                TaskContractResolver.fromUserRequest(F21_REQUEST);
        RequestObligation proofObligation =
                RequestObligationCompiler.compile(proofContract);
        ToolCallLoop.LoopResult proofLoop = loopResult(List.of(
                successfulWrite(
                        "docs/decisions.md",
                        "Decision 001: baseline\n",
                        "Decision 001: baseline\n"
                                + "Decision 004: health checks remain read-only\n",
                        "call-1-9")));
        TaskVerificationEvidence foreignReceipt = verifiedPostApply(
                workspace,
                proofContract,
                proofObligation,
                proofLoop,
                "docs/decisions.md",
                "Decision 001: baseline\n"
                        + "Decision 004: health checks remain read-only\n");

        TaskContract contract = notMigratedContract(Set.of("index.html"));
        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                RequestObligationCompiler.compile(contract),
                loopResult(List.of(
                        typedRepairFailure(
                                "index.html",
                                ToolFailureReason.EDIT_EMPTY_ARGUMENTS,
                                "call-1-1"),
                        successfulRead("index.html", "call-1-2"),
                        successfulEdit(
                                "index.html",
                                "before",
                                "after",
                                "call-1-3"))),
                0,
                foreignReceipt);

        assertNoTypedSettlement(assessment);
    }

    @Test
    void fullRewriteRedirectRequiresFullWriteAndExpectedTargetAuthority() {
        ToolCallLoop.ToolOutcome failure = typedRepairFailure(
                "index.html",
                ToolFailureReason.EDIT_FULL_REWRITE_REQUIRED,
                "call-1-1");
        ToolCallLoop.ToolOutcome editRepair = successfulEdit(
                "index.html",
                "before",
                "after",
                "call-1-2");

        TaskContract expectedTarget = notMigratedContract(Set.of("index.html"));
        MutationRecoveryAssessment wrongRepair =
                MutationRecoveryAssessment.assess(
                        expectedTarget,
                        RequestObligationCompiler.compile(expectedTarget),
                        loopResult(List.of(failure, editRepair)),
                        0,
                        passedPostApply());

        TaskContract wrongTarget = notMigratedContract(Set.of("README.md"));
        MutationRecoveryAssessment missingAuthority =
                MutationRecoveryAssessment.assess(
                        wrongTarget,
                        RequestObligationCompiler.compile(wrongTarget),
                        loopResult(List.of(
                                failure,
                                successfulWrite(
                                        "index.html",
                                        "before",
                                        "after",
                                        "call-1-2"))),
                        0,
                        passedPostApply());

        assertEquals(
                MutationOutcomeStatus.PARTIAL,
                wrongRepair.mutationOutcome().status());
        assertEquals(
                MutationOutcomeStatus.PARTIAL,
                missingAuthority.mutationOutcome().status());
    }

    @Test
    void typedRepairSettlementLeavesUnrelatedFailureVisibleAndOrdered() {
        TaskContract contract =
                notMigratedContract(Set.of("index.html", "README.md"));
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome eligible = typedRepairFailure(
                "index.html",
                ToolFailureReason.EDIT_EMPTY_ARGUMENTS,
                "call-1-1");
        ToolCallLoop.ToolOutcome repair = successfulEdit(
                "index.html",
                "before",
                "after",
                "call-1-3");
        ToolCallLoop.ToolOutcome unrelated = new ToolCallLoop.ToolOutcome(
                "talos.edit_file",
                "README.md",
                false,
                true,
                false,
                "",
                "old_string matched more than once",
                null,
                ToolError.INVALID_PARAMS,
                null,
                ToolMutationEvidence.exactEdit("old", "new"),
                ToolFailureReason.EDIT_OLD_STRING_AMBIGUOUS,
                List.of(),
                "call-1-4");

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loopResult(List.of(
                        eligible,
                        successfulRead("index.html", "call-1-2"),
                        repair,
                        unrelated)),
                0,
                passedPostApply());

        assertEquals(
                MutationOutcomeStatus.PARTIAL,
                assessment.mutationOutcome().status());
        assertEquals(List.of(unrelated), assessment.mutationOutcome().failed());
        assertEquals(List.of(unrelated), assessment.unresolvedOutcomesInOrder());
        assertEquals(
                MutationRecoveryAssessment.Decision
                        .RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN,
                assessment.failureAssessments().getFirst().decision());
        assertEquals(
                MutationRecoveryAssessment.Decision.FAILURE_NOT_ELIGIBLE,
                assessment.failureAssessments().get(1).decision());
    }

    @Test
    void oldStringMissRemainsCompleteObligationOnly() {
        TaskContract contract = notMigratedContract(Set.of("index.html"));
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome oldStringFailure = new ToolCallLoop.ToolOutcome(
                "talos.edit_file",
                "index.html",
                false,
                true,
                false,
                "",
                "old_string not found",
                null,
                ToolError.INVALID_PARAMS,
                null,
                ToolMutationEvidence.none(),
                ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND,
                List.of(),
                "call-1-1");

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loopResult(List.of(
                        oldStringFailure,
                        successfulEdit(
                                "index.html",
                                "<header>",
                                "<header class=\"site\">",
                                "call-1-2"))),
                0,
                passedPostApply());

        assertEquals(
                MutationOutcomeStatus.PARTIAL,
                assessment.mutationOutcome().status());
        assertEquals(
                MutationRecoveryAssessment.Decision.OBLIGATION_NOT_COMPLETE,
                assessment.failureAssessments().getFirst().decision());
    }

    @Test
    void unprovenTypedRepairSettlementRejectsForgedProofFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MutationRecoveryAssessment.FailureAssessment(
                        1,
                        2,
                        "call-1-1",
                        "call-1-2",
                        MutationRecoveryAssessment.Decision
                                .RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN,
                        ToolFailureReason.EDIT_EMPTY_ARGUMENTS,
                        "index.html",
                        "a".repeat(64),
                        "a".repeat(64),
                        dev.talos.runtime.verification
                                .TaskVerificationEvidenceSource.POST_APPLY_STATIC,
                        dev.talos.runtime.verification
                                .TaskVerificationStatus.PASSED,
                        List.of("call-1-2")));
    }

    @Test
    void absentReadbackOrFailedVerificationCannotRecover() {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.LoopResult loop = loopResult(List.of(
                failedEdit(
                        "docs/decisions.md",
                        ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND),
                successfulEdit(
                        "docs/decisions.md",
                        "Decision 001: baseline\n",
                        "Decision 001: baseline\nDecision 004: health checks remain read-only\n")));

        List<TaskVerificationEvidence> evidence = List.of(
                TaskVerificationEvidence.notRun("not run"),
                TaskVerificationEvidence.postApply(
                        TaskVerificationResult.readbackOnly("readback only", List.of()),
                        VerificationReport.empty()),
                TaskVerificationEvidence.postApply(
                        TaskVerificationResult.failed("failed", List.of(), List.of("mismatch")),
                        VerificationReport.empty()));

        for (TaskVerificationEvidence value : evidence) {
            MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                    contract,
                    obligation,
                    loop,
                    0,
                    value);
            assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
            assertFalse(assessment.failureAssessments().getFirst().recovered());
        }
    }

    @Test
    void differentTargetDenialAndUnrelatedFailureRemainVisible() throws Exception {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome targetFailure = failedEdit(
                "docs/decisions.md",
                ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND);
        ToolCallLoop.ToolOutcome unrelated = failedEdit(
                "README.md",
                ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND);
        ToolCallLoop.ToolOutcome denial = deniedEdit("docs/decisions.md");
        ToolCallLoop.ToolOutcome repair = successfulWrite(
                "docs/decisions.md",
                "Decision 001: baseline\n",
                "Decision 001: baseline\nDecision 004: health checks remain read-only\n");

        ToolCallLoop.LoopResult loop =
                loopResult(List.of(targetFailure, unrelated, denial, repair));
        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loop,
                0,
                verifiedPostApply(
                        workspace,
                        contract,
                        obligation,
                        loop,
                        "docs/decisions.md",
                        "Decision 001: baseline\n"
                                + "Decision 004: health checks remain read-only\n"));

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertEquals(List.of(unrelated), assessment.mutationOutcome().failed());
        assertEquals(List.of(denial), assessment.mutationOutcome().denied());
        assertTrue(assessment.failureAssessments().get(0).recovered());
        assertEquals(
                MutationRecoveryAssessment.Decision.TARGET_MISMATCH,
                assessment.failureAssessments().get(1).decision());
        assertEquals(
                MutationRecoveryAssessment.Decision.DENIED_OR_POLICY,
                assessment.failureAssessments().get(2).decision());
    }

    @Test
    void successBeforeFailureDoesNotRecoverLaterFailure() throws Exception {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);

        ToolCallLoop.LoopResult loop = loopResult(List.of(
                successfulWrite(
                        "docs/decisions.md",
                        "Decision 001: baseline\n",
                        "Decision 001: baseline\nDecision 004: health checks remain read-only\n"),
                failedEdit(
                        "docs/decisions.md",
                        ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND)));
        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loop,
                0,
                verifiedPostApply(
                        workspace,
                        contract,
                        obligation,
                        loop,
                        "docs/decisions.md",
                        "Decision 001: baseline\n"
                                + "Decision 004: health checks remain read-only\n"));

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertEquals(
                MutationRecoveryAssessment.Decision.NO_LATER_STRUCTURED_REPAIR,
                assessment.failureAssessments().getFirst().decision());
    }

    @Test
    void untypedFailureCannotBeRecoveredByVerifiedSameTargetSuccess() {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loopResult(List.of(
                        failedEdit("docs/decisions.md", ToolFailureReason.NONE),
                        successfulWrite(
                                "docs/decisions.md",
                                "Decision 001: baseline\n",
                                "Decision 001: baseline\nDecision 004: health checks remain read-only\n"))),
                0,
                passedPostApply());

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertEquals(
                MutationRecoveryAssessment.Decision.FAILURE_NOT_ELIGIBLE,
                assessment.failureAssessments().getFirst().decision());
    }

    @Test
    void genericPostApplyPassCannotForgeFinalStateProof() {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.LoopResult loop = loopResult(List.of(
                failedEdit(
                        "docs/decisions.md",
                        ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND),
                successfulWrite(
                        "docs/decisions.md",
                        "Decision 001: baseline\n",
                        "Decision 001: baseline\n"
                                + "Decision 004: health checks remain read-only\n")));

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loop,
                0,
                passedPostApply());

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertEquals(
                MutationRecoveryAssessment.Decision.FINAL_STATE_PROOF_MISSING,
                assessment.failureAssessments().getFirst().decision());
    }

    @Test
    void laterSameTargetUntypedMutationPreventsRecoveryProof() throws Exception {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome failure = failedEdit(
                "docs/decisions.md",
                ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND);
        ToolCallLoop.ToolOutcome repair = successfulWrite(
                "docs/decisions.md",
                "Decision 001: baseline\n",
                "Decision 001: baseline\nDecision 004: health checks remain read-only\n");
        ToolCallLoop.ToolOutcome laterUntypedMutation = new ToolCallLoop.ToolOutcome(
                "talos.run_command",
                "docs/decisions.md",
                true,
                true,
                false,
                "command completed",
                "",
                null,
                "",
                null,
                ToolMutationEvidence.none(),
                ToolFailureReason.NONE,
                List.of(),
                "call-1-3");
        ToolCallLoop.LoopResult loop = loopResult(
                List.of(failure, repair, laterUntypedMutation));

        Path destination = workspace.resolve("docs/decisions.md");
        Files.createDirectories(destination.getParent());
        Files.writeString(
                destination,
                "Decision 001: baseline\nDecision 004: health checks remain read-only\n");
        TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                workspace,
                contract,
                obligation,
                loop,
                0);
        assertFalse(evidence.requestObligationReceipt().verified());

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loop,
                0,
                evidence);

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertFalse(assessment.failureAssessments().getFirst().recovered());
    }

    @Test
    void missingExactToolCallIdentityCannotRecoverVerifiedFailure() throws Exception {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome failureWithoutIdentity = new ToolCallLoop.ToolOutcome(
                "talos.edit_file",
                "docs/decisions.md",
                false,
                true,
                false,
                "",
                "old_string not found",
                null,
                ToolError.INVALID_PARAMS,
                null,
                ToolMutationEvidence.exactEdit("missing", "replacement"),
                ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND,
                List.of(),
                "");
        ToolCallLoop.ToolOutcome repair = successfulWrite(
                "docs/decisions.md",
                "Decision 001: baseline\n",
                "Decision 001: baseline\nDecision 004: health checks remain read-only\n");
        ToolCallLoop.LoopResult loop = loopResult(
                List.of(failureWithoutIdentity, repair));

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loop,
                0,
                verifiedPostApply(
                        workspace,
                        contract,
                        obligation,
                        loop,
                        "docs/decisions.md",
                        "Decision 001: baseline\n"
                                + "Decision 004: health checks remain read-only\n"));

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertEquals(
                MutationRecoveryAssessment.Decision.CALL_IDENTITY_UNAVAILABLE,
                assessment.failureAssessments().getFirst().decision());
    }

    @Test
    void blankPathSuccessfulMutationPreventsReceiptAndRecovery() throws Exception {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome failure = failedEdit(
                "docs/decisions.md",
                ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND);
        ToolCallLoop.ToolOutcome repair = successfulWrite(
                "docs/decisions.md",
                "Decision 001: baseline\n",
                "Decision 001: baseline\nDecision 004: health checks remain read-only\n");
        ToolCallLoop.ToolOutcome blankPathSuccess = successfulWrite(
                "",
                "",
                "unbound",
                "call-1-3");
        ToolCallLoop.LoopResult loop = loopResult(
                List.of(failure, repair, blankPathSuccess));

        Path destination = workspace.resolve("docs/decisions.md");
        Files.createDirectories(destination.getParent());
        Files.writeString(
                destination,
                "Decision 001: baseline\nDecision 004: health checks remain read-only\n");
        TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                workspace,
                contract,
                obligation,
                loop,
                0);

        assertFalse(evidence.requestObligationReceipt().verified());
        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loop,
                0,
                evidence);
        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertFalse(assessment.failureAssessments().getFirst().recovered());
    }

    @Test
    void repairIdentityMustBeCoveredByExactReceipt() throws Exception {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome failure = failedEdit(
                "docs/decisions.md",
                ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND);
        ToolCallLoop.ToolOutcome verifiedRepair = successfulWrite(
                "docs/decisions.md",
                "Decision 001: baseline\n",
                "Decision 001: baseline\nDecision 004: health checks remain read-only\n",
                "call-1-2");
        ToolCallLoop.LoopResult verifiedLoop = loopResult(
                List.of(failure, verifiedRepair));
        TaskVerificationEvidence evidence = verifiedPostApply(
                workspace,
                contract,
                obligation,
                verifiedLoop,
                "docs/decisions.md",
                "Decision 001: baseline\n"
                        + "Decision 004: health checks remain read-only\n");

        ToolCallLoop.ToolOutcome unboundRepair = successfulWrite(
                "docs/decisions.md",
                "Decision 001: baseline\n",
                "Decision 001: baseline\nDecision 004: health checks remain read-only\n",
                "call-1-9");
        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loopResult(List.of(failure, unboundRepair)),
                0,
                evidence);

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertEquals(
                MutationRecoveryAssessment.Decision.FINAL_STATE_PROOF_MISMATCH,
                assessment.failureAssessments().getFirst().decision());
    }

    @Test
    void laterSameTargetStructuredSuccessInvalidatesUniqueRepairAttribution()
            throws Exception {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome failure = failedEdit(
                "docs/decisions.md",
                ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND);
        ToolCallLoop.ToolOutcome verifiedRepair = successfulWrite(
                "docs/decisions.md",
                "Decision 001: baseline\n",
                "Decision 001: baseline\nDecision 004: health checks remain read-only\n",
                "call-1-2");
        ToolCallLoop.LoopResult verifiedLoop = loopResult(
                List.of(failure, verifiedRepair));
        TaskVerificationEvidence evidence = verifiedPostApply(
                workspace,
                contract,
                obligation,
                verifiedLoop,
                "docs/decisions.md",
                "Decision 001: baseline\n"
                        + "Decision 004: health checks remain read-only\n");
        ToolCallLoop.ToolOutcome laterUnrelated = successfulWrite(
                "docs/decisions.md",
                "Decision 001: baseline\nDecision 004: health checks remain read-only\n",
                "Decision 001: baseline\nDecision 004: health checks remain read-only\n"
                        + "unrelated\n",
                "call-1-3");

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loopResult(List.of(failure, verifiedRepair, laterUnrelated)),
                0,
                evidence);

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertEquals(
                MutationRecoveryAssessment.Decision.LATER_MUTATION_INVALIDATED,
                assessment.failureAssessments().getFirst().decision());
    }

    @Test
    void unresolvedOutcomesRetainDenialBeforeFailureOrder() {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome denial = deniedEdit("docs/decisions.md");
        ToolCallLoop.ToolOutcome failure = failedEdit(
                "docs/decisions.md",
                ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND);

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loopResult(List.of(denial, failure)),
                0,
                TaskVerificationEvidence.notRun("not run"));

        assertEquals(
                List.of(denial, failure),
                assessment.unresolvedOutcomesInOrder());
    }

    @Test
    void permissionPolicyDenialCannotBeRecoveredByVerifiedLaterSuccess()
            throws Exception {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        ToolCallLoop.ToolOutcome denial = deniedEdit(
                "docs/decisions.md",
                ToolFailureReason.PERMISSION_POLICY_DENIED,
                "call-1-1");
        ToolCallLoop.ToolOutcome repair = successfulWrite(
                "docs/decisions.md",
                "Decision 001: baseline\n",
                "Decision 001: baseline\nDecision 004: health checks remain read-only\n",
                "call-1-2");
        ToolCallLoop.LoopResult loop = loopResult(List.of(denial, repair));

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loop,
                0,
                verifiedPostApply(
                        workspace,
                        contract,
                        obligation,
                        loop,
                        "docs/decisions.md",
                        "Decision 001: baseline\n"
                                + "Decision 004: health checks remain read-only\n"));

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertEquals(List.of(denial), assessment.mutationOutcome().denied());
        assertEquals(
                MutationRecoveryAssessment.Decision.DENIED_OR_POLICY,
                assessment.failureAssessments().getFirst().decision());
        assertFalse(assessment.failureAssessments().getFirst().recovered());
    }

    @Test
    void duplicateWorkspaceCompatibilityIsResolvedButNeverRecoveryProof() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Copy README.md to docs/README-copy.md.");
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        WorkspaceOperationPlan plan = WorkspaceOperationPlan.copyPath(
                "README.md",
                "docs/README-copy.md",
                WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                false);
        ToolCallLoop.ToolOutcome success = workspaceOutcome(
                "talos.copy_path",
                "docs/README-copy.md",
                true,
                "",
                plan,
                "call-1-1");
        ToolCallLoop.ToolOutcome duplicate = workspaceOutcome(
                "talos.copy_path",
                "docs/README-copy.md",
                false,
                "Destination already exists: docs/README-copy.md.",
                plan,
                "call-1-2");

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loopResult(List.of(success, duplicate)),
                0,
                TaskVerificationEvidence.notRun("not run"));

        assertEquals(MutationOutcomeStatus.SUCCEEDED, assessment.mutationOutcome().status());
        MutationRecoveryAssessment.FailureAssessment duplicateDecision =
                assessment.failureAssessments().getFirst();
        assertEquals(
                MutationRecoveryAssessment.Decision.REDUNDANT_ALREADY_APPLIED_OPERATION,
                duplicateDecision.decision());
        assertTrue(duplicateDecision.resolved());
        assertFalse(duplicateDecision.recovered());
        assertTrue(duplicateDecision.coveredToolCallIds().isEmpty());
    }

    @Test
    void laterMutationInvalidatesLegacyDuplicateWorkspaceResolution() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Copy README.md to docs/README-copy.md.");
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        WorkspaceOperationPlan copyPlan = WorkspaceOperationPlan.copyPath(
                "README.md",
                "docs/README-copy.md",
                WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                false);
        ToolCallLoop.ToolOutcome success = workspaceOutcome(
                "talos.copy_path",
                "docs/README-copy.md",
                true,
                "",
                copyPlan,
                "call-1-1");
        ToolCallLoop.ToolOutcome duplicate = workspaceOutcome(
                "talos.copy_path",
                "docs/README-copy.md",
                false,
                "Destination already exists: docs/README-copy.md.",
                copyPlan,
                "call-1-2");
        ToolCallLoop.ToolOutcome laterDelete = workspaceOutcome(
                "talos.delete_path",
                "docs/README-copy.md",
                true,
                "",
                WorkspaceOperationPlan.deletePath(
                        "docs/README-copy.md",
                        false),
                "call-1-3");

        MutationRecoveryAssessment assessment = MutationRecoveryAssessment.assess(
                contract,
                obligation,
                loopResult(List.of(success, duplicate, laterDelete)),
                0,
                TaskVerificationEvidence.notRun("not run"));

        assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status());
        assertEquals(List.of(duplicate), assessment.mutationOutcome().failed());
        assertFalse(assessment.failureAssessments().getFirst().resolved());
    }

    private static TaskVerificationEvidence passedPostApply() {
        return TaskVerificationEvidence.postApply(
                TaskVerificationResult.passed("Append statement verification passed.", List.of()),
                VerificationReport.empty());
    }

    private static TaskContract notMigratedContract(Set<String> targets) {
        return new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                targets,
                Set.of(),
                "Apply the requested verified repair to the named target files.");
    }

    private static TaskVerificationEvidence verifiedPostApply(
            Path workspace,
            TaskContract contract,
            RequestObligation obligation,
            ToolCallLoop.LoopResult loop,
            String target,
            String content
    ) throws Exception {
        Path destination = workspace.resolve(target);
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, content);
        TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                workspace,
                contract,
                obligation,
                loop,
                0);
        assertEquals(
                dev.talos.runtime.verification.TaskVerificationStatus.PASSED,
                evidence.compatibilityResult().status());
        assertTrue(evidence.requestObligationReceipt().proves(obligation));
        return evidence;
    }

    private static ToolCallLoop.ToolOutcome failedEdit(
            String path,
            ToolFailureReason reason
    ) {
        return new ToolCallLoop.ToolOutcome(
                "talos.edit_file",
                path,
                false,
                true,
                false,
                "",
                "old_string not found",
                null,
                ToolError.INVALID_PARAMS,
                null,
                ToolMutationEvidence.exactEdit("missing", "replacement"),
                reason,
                List.of(),
                "call-1-1");
    }

    private static ToolCallLoop.ToolOutcome typedRepairFailure(
            String path,
            ToolFailureReason reason,
            String toolCallId
    ) {
        return new ToolCallLoop.ToolOutcome(
                "talos.edit_file",
                path,
                false,
                true,
                false,
                "",
                "invalid edit parameters",
                null,
                ToolError.INVALID_PARAMS,
                null,
                ToolMutationEvidence.none(),
                reason,
                List.of(),
                toolCallId);
    }

    private static ToolCallLoop.ToolOutcome ambiguousEditFailure(
            String toolName,
            String errorCode,
            ToolMutationEvidence mutationEvidence
    ) {
        return new ToolCallLoop.ToolOutcome(
                toolName,
                "notes.md",
                false,
                true,
                false,
                "",
                "old_string matched more than once",
                null,
                errorCode,
                null,
                mutationEvidence,
                ToolFailureReason.EDIT_OLD_STRING_AMBIGUOUS,
                List.of(),
                "call-1-1");
    }

    private static ToolCallLoop.ToolOutcome deniedEdit(String path) {
        return deniedEdit(
                path,
                ToolFailureReason.USER_APPROVAL_DENIED,
                "call-1-3");
    }

    private static ToolCallLoop.ToolOutcome deniedEdit(
            String path,
            ToolFailureReason reason,
            String toolCallId
    ) {
        return new ToolCallLoop.ToolOutcome(
                "talos.edit_file",
                path,
                false,
                true,
                true,
                "",
                "approval denied",
                null,
                ToolError.DENIED,
                null,
                ToolMutationEvidence.none(),
                reason,
                List.of(),
                toolCallId);
    }

    private static ToolCallLoop.ToolOutcome successfulEdit(
            String path,
            String oldString,
            String newString
    ) {
        return successfulEdit(path, oldString, newString, "call-1-2");
    }

    private static ToolCallLoop.ToolOutcome successfulRead(
            String path,
            String toolCallId
    ) {
        return new ToolCallLoop.ToolOutcome(
                "talos.read_file",
                path,
                true,
                false,
                false,
                "read",
                "",
                null,
                "",
                null,
                ToolMutationEvidence.none(),
                ToolFailureReason.NONE,
                List.of(path),
                toolCallId);
    }

    private static ToolCallLoop.ToolOutcome successfulEdit(
            String path,
            String oldString,
            String newString,
            String toolCallId
    ) {
        return new ToolCallLoop.ToolOutcome(
                "talos.edit_file",
                path,
                true,
                true,
                false,
                "edited",
                "",
                null,
                "",
                null,
                ToolMutationEvidence.exactEdit(oldString, newString),
                ToolFailureReason.NONE,
                List.of(),
                toolCallId);
    }

    private static ToolCallLoop.ToolOutcome successfulWrite(
            String path,
            String oldString,
            String newString
    ) {
        return successfulWrite(path, oldString, newString, "call-1-2");
    }

    private static ToolCallLoop.ToolOutcome successfulWrite(
            String path,
            String oldString,
            String newString,
            String toolCallId
    ) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                path,
                true,
                true,
                false,
                "wrote",
                "",
                null,
                "",
                null,
                ToolMutationEvidence.fullWriteReplacement(oldString, newString),
                ToolFailureReason.NONE,
                List.of(),
                toolCallId);
    }

    private static ToolCallLoop.ToolOutcome successfulWriteWithoutPriorEvidence(
            String path,
            String toolCallId
    ) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                path,
                true,
                true,
                false,
                "wrote",
                "",
                null,
                "",
                null,
                ToolMutationEvidence.none(),
                ToolFailureReason.NONE,
                List.of(),
                toolCallId);
    }

    private static ToolCallLoop.ToolOutcome workspaceOutcome(
            String toolName,
            String path,
            boolean success,
            String errorMessage,
            WorkspaceOperationPlan plan,
            String toolCallId
    ) {
        return new ToolCallLoop.ToolOutcome(
                toolName,
                path,
                success,
                true,
                false,
                success ? "workspace operation applied" : "",
                errorMessage,
                null,
                success ? "" : ToolError.INVALID_PARAMS,
                plan,
                ToolMutationEvidence.none(),
                ToolFailureReason.NONE,
                List.of(),
                toolCallId);
    }

    private static ToolCallLoop.LoopResult loopResult(
            List<ToolCallLoop.ToolOutcome> outcomes
    ) {
        return new ToolCallLoop.LoopResult(
                "answer",
                1,
                outcomes.size(),
                outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(),
                List.of(),
                (int) outcomes.stream().filter(outcome -> !outcome.success()).count(),
                0,
                false,
                (int) outcomes.stream()
                        .filter(outcome -> outcome.mutating() && outcome.success())
                        .count(),
                List.of(),
                0,
                0,
                0,
                0,
                outcomes);
    }

    private static void assertNoTypedSettlement(
            MutationRecoveryAssessment assessment
    ) {
        assertEquals(
                MutationOutcomeStatus.PARTIAL,
                assessment.mutationOutcome().status());
        assertTrue(assessment.failureAssessments().stream()
                .noneMatch(value -> value.decision()
                        == MutationRecoveryAssessment.Decision
                                .RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN));
    }
}
