package dev.talos.runtime.trace;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.outcome.MutationOutcome;
import dev.talos.runtime.outcome.MutationOutcomeStatus;
import dev.talos.runtime.outcome.MutationRecoveryAssessment;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.outcome.TaskOutcome;
import dev.talos.runtime.outcome.TruthWarning;
import dev.talos.runtime.outcome.TruthWarningType;
import dev.talos.runtime.obligation.RequestObligation;
import dev.talos.runtime.obligation.RequestObligationCompiler;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.verification.TaskVerificationEvidenceSource;
import dev.talos.runtime.verification.TaskVerificationResult;
import dev.talos.runtime.verification.TaskVerificationStatus;
import dev.talos.tools.ToolFailureReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskOutcomeTraceRecorderTest {
    @AfterEach
    void cleanup() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsVerificationWarningsAndOutcomeSummary() {
        TaskVerificationResult verification = TaskVerificationResult.failed(
                "Static verification failed.",
                List.of(),
                List.of("Missing script.js"));
        ToolCallLoop.ToolOutcome denied = new ToolCallLoop.ToolOutcome(
                "talos.edit_file", "index.html", false, true, true,
                "", "approval denied");
        TaskOutcome outcome = taskOutcome(
                TaskCompletionStatus.BLOCKED_BY_POLICY,
                new MutationOutcome(
                        MutationOutcomeStatus.DENIED,
                        List.of(),
                        List.of(),
                        List.of(denied),
                        0),
                verification,
                List.of(
                        TruthWarning.of(TruthWarningType.MISSING_EVIDENCE, "Missing evidence."),
                        TruthWarning.of(TruthWarningType.COMMAND_FAILED, "Command failed.")),
                List.of(denied));

        beginTrace();
        TaskOutcomeTraceRecorder.record("BLOCKED", "FAILED", outcome, verification);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertNotNull(trace);
        assertEquals("FAILED", trace.verification().status());
        assertEquals("Static verification failed.", trace.verification().summary());
        assertEquals(List.of("Missing script.js"), trace.verification().problems());
        assertEquals("BLOCKED", trace.outcome().status());
        assertEquals("FAILED", trace.outcome().verificationStatus());
        assertEquals("DENIED", trace.outcome().approvalStatus());
        assertEquals("DENIED", trace.outcome().mutationStatus());
        assertEquals("BLOCKED_BY_POLICY", trace.outcome().classification());
        assertTrue(trace.warnings().stream().anyMatch(warning ->
                "MISSING_EVIDENCE".equals(warning.code())
                        && "Missing evidence.".equals(warning.message())));
        assertTrue(trace.warnings().stream().anyMatch(warning ->
                "COMMAND_FAILED".equals(warning.code())
                        && "Command failed.".equals(warning.message())));
        assertTrue(trace.events().stream().anyMatch(event ->
                "VERIFICATION_COMPLETED".equals(event.type())));
        assertFalse(trace.events().stream().anyMatch(event ->
                "OUTCOME_RENDERED".equals(event.type())));
    }

    @Test
    void approvalStatusIsGrantedOrNotRequiredWhenMutationSucceeded() {
        ToolCallLoop.ToolOutcome success = new ToolCallLoop.ToolOutcome(
                "talos.write_file", "index.html", true, true, false,
                "wrote index.html", "");
        TaskOutcome outcome = taskOutcome(
                TaskCompletionStatus.COMPLETED_UNVERIFIED,
                new MutationOutcome(
                        MutationOutcomeStatus.SUCCEEDED,
                        List.of(success),
                        List.of(),
                        List.of(),
                        0),
                TaskVerificationResult.notRun("Not run."),
                List.of(),
                List.of(success));

        beginTrace();
        TaskOutcomeTraceRecorder.record("COMPLETE", "NOT_RUN", outcome, outcome.verificationResult());
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals("GRANTED_OR_NOT_REQUIRED", trace.outcome().approvalStatus());
        assertEquals("SUCCEEDED", trace.outcome().mutationStatus());
    }

    @Test
    void approvalStatusIsNoneWithoutMutationSuccessOrDenial() {
        TaskOutcome outcome = taskOutcome(
                TaskCompletionStatus.READ_ONLY_ANSWERED,
                new MutationOutcome(
                        MutationOutcomeStatus.NOT_REQUESTED,
                        List.of(),
                        List.of(),
                        List.of(),
                        0),
                TaskVerificationResult.notRun("Not applicable."),
                List.of(),
                List.of());

        beginTrace();
        TaskOutcomeTraceRecorder.record("COMPLETE", "NOT_RUN", outcome, outcome.verificationResult());
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals("NONE", trace.outcome().approvalStatus());
        assertEquals("NOT_REQUESTED", trace.outcome().mutationStatus());
        assertEquals("READ_ONLY_ANSWERED", trace.outcome().classification());
    }

    @Test
    void recordsExactMatchedRecoveryAfterVerificationAndBeforeOutcomeProjection() {
        RequestObligation obligation = RequestObligationCompiler.compile(
                TaskContractResolver.fromUserRequest(
                        "Append Decision 004 to docs/decisions.md saying "
                                + "health checks remain read-only. "
                                + "Preserve Decisions 001 through 003 and change no code."));
        ToolCallLoop.ToolOutcome success = new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "docs/decisions.md",
                true,
                true,
                false,
                "wrote",
                "");
        MutationOutcome mutationOutcome = new MutationOutcome(
                MutationOutcomeStatus.SUCCEEDED,
                List.of(success),
                List.of(),
                List.of(),
                0);
        MutationRecoveryAssessment assessment = new MutationRecoveryAssessment(
                mutationOutcome,
                List.of(new MutationRecoveryAssessment.FailureAssessment(
                        1,
                        2,
                        "call-1-1",
                        "call-1-2",
                        MutationRecoveryAssessment.Decision
                                .RECOVERED_MATCHED_OBLIGATION,
                        ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND,
                        "docs/decisions.md",
                        obligation.canonicalSha256(),
                        obligation.canonicalSha256(),
                        TaskVerificationEvidenceSource.POST_APPLY_STATIC,
                        TaskVerificationStatus.PASSED,
                        List.of("call-1-2"))));
        TaskVerificationResult verification =
                TaskVerificationResult.passed("Verified.", List.of());
        TaskOutcome outcome = taskOutcome(
                TaskCompletionStatus.COMPLETED_VERIFIED,
                mutationOutcome,
                verification,
                List.of(),
                List.of(success));

        beginTrace();
        TaskOutcomeTraceRecorder.record(
                "COMPLETE",
                "PASSED",
                outcome,
                verification,
                dev.talos.runtime.verification.VerificationReport.empty(),
                assessment);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> MutationRecoveryTraceEventFactory.EVENT_TYPE.equals(
                        candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(
                Set.of(
                        "schemaVersion",
                        "failureOrdinal",
                        "repairOrdinal",
                        "failureToolCallId",
                        "repairToolCallId",
                        "decision",
                        "resolved",
                        "recovered",
                        "failureReason",
                        "targetHint",
                        "targetSha256",
                        "requestObligationSha256",
                        "verifiedRequestObligationSha256",
                        "coveredSuccessfulMutationToolCallIds",
                        "verificationSource",
                        "verificationStatus"),
                event.data().keySet());
        assertEquals(2, event.data().get("schemaVersion"));
        assertEquals("call-1-1", event.data().get("failureToolCallId"));
        assertEquals("call-1-2", event.data().get("repairToolCallId"));
        assertEquals(obligation.canonicalSha256(),
                event.data().get("requestObligationSha256"));
        assertEquals(obligation.canonicalSha256(),
                event.data().get("verifiedRequestObligationSha256"));
        assertEquals(
                List.of("call-1-2"),
                event.data().get("coveredSuccessfulMutationToolCallIds"));
        assertEquals("docs/decisions.md", event.data().get("targetHint"));
        assertTrue(String.valueOf(event.data().get("targetSha256"))
                .matches("sha256:[0-9a-f]{64}"));
        int verificationIndex = eventIndex(trace, "VERIFICATION_COMPLETED");
        int recoveryIndex = eventIndex(
                trace, MutationRecoveryTraceEventFactory.EVENT_TYPE);
        assertTrue(verificationIndex < recoveryIndex);
    }

    @Test
    void recordsTypedRepairSettlementAsResolvedButNeverRecovered() {
        RequestObligation obligation =
                RequestObligationCompiler.notMigrated(new TaskContract(
                        dev.talos.runtime.task.TaskType.FILE_EDIT,
                        true,
                        true,
                        true,
                        Set.of("index.html"),
                        Set.of(),
                        "Apply the verified index.html repair."));
        ToolCallLoop.ToolOutcome success = new ToolCallLoop.ToolOutcome(
                "talos.edit_file",
                "index.html",
                true,
                true,
                false,
                "edited",
                "");
        MutationOutcome mutationOutcome = new MutationOutcome(
                MutationOutcomeStatus.SUCCEEDED,
                List.of(success),
                List.of(),
                List.of(),
                0);
        MutationRecoveryAssessment assessment = new MutationRecoveryAssessment(
                mutationOutcome,
                List.of(new MutationRecoveryAssessment.FailureAssessment(
                        1,
                        2,
                        "call-1-1",
                        "call-1-2",
                        MutationRecoveryAssessment.Decision
                                .RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN,
                        ToolFailureReason.EDIT_EMPTY_ARGUMENTS,
                        "index.html",
                        obligation.canonicalSha256(),
                        "",
                        TaskVerificationEvidenceSource.POST_APPLY_STATIC,
                        TaskVerificationStatus.PASSED,
                        List.of())));
        TaskVerificationResult verification =
                TaskVerificationResult.passed("Verified.", List.of());
        TaskOutcome outcome = taskOutcome(
                TaskCompletionStatus.COMPLETED_VERIFIED,
                mutationOutcome,
                verification,
                List.of(),
                List.of(success));

        beginTrace();
        TaskOutcomeTraceRecorder.record(
                "COMPLETE",
                "PASSED",
                outcome,
                verification,
                dev.talos.runtime.verification.VerificationReport.empty(),
                assessment);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        TurnTraceEvent event = trace.events().stream()
                .filter(candidate ->
                        MutationRecoveryTraceEventFactory.EVENT_TYPE.equals(
                                candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(
                MutationRecoveryAssessment.Decision
                        .RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN.name(),
                event.data().get("decision"));
        assertEquals(true, event.data().get("resolved"));
        assertEquals(false, event.data().get("recovered"));
        assertEquals("", event.data().get("verifiedRequestObligationSha256"));
        assertEquals(
                List.of(),
                event.data().get("coveredSuccessfulMutationToolCallIds"));
        assertEquals("SUCCEEDED", trace.outcome().mutationStatus());
        int verificationIndex = eventIndex(trace, "VERIFICATION_COMPLETED");
        int recoveryIndex = eventIndex(
                trace, MutationRecoveryTraceEventFactory.EVENT_TYPE);
        int outcomeIndex = eventIndex(trace, "OUTCOME_EVALUATED");
        assertTrue(verificationIndex < recoveryIndex);
        assertTrue(recoveryIndex < outcomeIndex);
    }

    private static int eventIndex(LocalTurnTrace trace, String type) {
        for (int index = 0; index < trace.events().size(); index++) {
            if (type.equals(trace.events().get(index).type())) return index;
        }
        return -1;
    }

    private static TaskOutcome taskOutcome(
            TaskCompletionStatus completionStatus,
            MutationOutcome mutationOutcome,
            TaskVerificationResult verification,
            List<TruthWarning> warnings,
            List<ToolCallLoop.ToolOutcome> toolOutcomes
    ) {
        return new TaskOutcome(
                TaskContract.unknown("test"),
                completionStatus,
                mutationOutcome,
                verification,
                warnings,
                toolOutcomes);
    }

    private static void beginTrace() {
        LocalTurnTraceCapture.begin(
                "trc-task-outcome-recorder",
                "sid",
                1,
                "2026-05-24T12:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "test");
    }
}
