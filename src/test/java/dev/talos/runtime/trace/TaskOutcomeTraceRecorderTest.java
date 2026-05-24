package dev.talos.runtime.trace;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.outcome.MutationOutcome;
import dev.talos.runtime.outcome.MutationOutcomeStatus;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.outcome.TaskOutcome;
import dev.talos.runtime.outcome.TruthWarning;
import dev.talos.runtime.outcome.TruthWarningType;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.verification.TaskVerificationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(trace.events().stream().anyMatch(event ->
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
