package dev.talos.runtime.trace;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.outcome.MutationRecoveryAssessment;
import dev.talos.runtime.outcome.TaskOutcome;
import dev.talos.runtime.verification.TaskVerificationResult;
import dev.talos.runtime.verification.VerificationReport;

/** Records task outcome evidence into the active local turn trace. */
public final class TaskOutcomeTraceRecorder {
    private TaskOutcomeTraceRecorder() {}

    public static void record(
            String completionStatus,
            String verificationStatus,
            TaskOutcome taskOutcome,
            TaskVerificationResult verification
    ) {
        record(completionStatus, verificationStatus, taskOutcome, verification, VerificationReport.empty());
    }

    public static void record(
            String completionStatus,
            String verificationStatus,
            TaskOutcome taskOutcome,
            TaskVerificationResult verification,
            VerificationReport verificationReport
    ) {
        record(
                completionStatus,
                verificationStatus,
                taskOutcome,
                verification,
                verificationReport,
                null);
    }

    public static void record(
            String completionStatus,
            String verificationStatus,
            TaskOutcome taskOutcome,
            TaskVerificationResult verification,
            VerificationReport verificationReport,
            MutationRecoveryAssessment mutationRecoveryAssessment
    ) {
        recordPresented(completionStatus, verificationStatus, taskOutcome, verification, verificationReport,
                mutationRecoveryAssessment, completionStatus,
                taskOutcome == null ? "" : taskOutcome.completionStatus().name());
    }

    public static void recordPresented(String completionStatus, String verificationStatus, TaskOutcome taskOutcome,
                                       TaskVerificationResult verification, VerificationReport verificationReport,
                                       MutationRecoveryAssessment mutationRecoveryAssessment,
                                       String operationalStatus, String operationalClassification) {
        if (verification != null) {
            LocalTurnTraceCapture.recordVerification(
                    verification.status().name(),
                    verification.summary(),
                    verification.problems(),
                    verificationReport);
        }
        if (taskOutcome != null) {
            LocalTurnTraceCapture.recordMutationRecoveryAssessment(
                    mutationRecoveryAssessment);
            taskOutcome.warnings().forEach(warning ->
                    LocalTurnTraceCapture.warning(warning.type().name(), warning.message()));
            LocalTurnTraceCapture.recordPresentedOutcome(
                    safe(completionStatus),
                    safe(verificationStatus),
                    approvalStatus(taskOutcome),
                    taskOutcome.mutationOutcome().status().name(),
                    taskOutcome.completionStatus().name(), safe(operationalStatus), safe(operationalClassification));
        }
    }

    private static String approvalStatus(TaskOutcome outcome) {
        if (outcome == null || outcome.mutationOutcome() == null) return "UNKNOWN";
        if (outcome.toolOutcomes().stream().anyMatch(ToolCallLoop.ToolOutcome::denied)) return "DENIED";
        if (!outcome.mutationOutcome().denied().isEmpty()) return "DENIED";
        if (outcome.mutationOutcome().successCount() > 0) return "GRANTED_OR_NOT_REQUIRED";
        return "NONE";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
