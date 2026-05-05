package dev.talos.runtime.workspace;

import java.util.List;

/** Structured result for a planned workspace operation. */
public record WorkspaceOperationResult(
        Status status,
        List<String> changedPaths,
        List<String> failedPaths,
        List<String> skippedPaths,
        String checkpointId,
        String verificationSummary,
        List<String> summaryLines
) {
    public WorkspaceOperationResult {
        status = status == null ? Status.FAILED : status;
        changedPaths = List.copyOf(changedPaths == null ? List.of() : changedPaths);
        failedPaths = List.copyOf(failedPaths == null ? List.of() : failedPaths);
        skippedPaths = List.copyOf(skippedPaths == null ? List.of() : skippedPaths);
        checkpointId = checkpointId == null ? "" : checkpointId;
        verificationSummary = verificationSummary == null ? "" : verificationSummary;
        summaryLines = List.copyOf(summaryLines == null ? List.of() : summaryLines);
    }

    public static WorkspaceOperationResult applied(
            List<String> changedPaths,
            String checkpointId,
            String verificationSummary,
            List<String> summaryLines
    ) {
        return new WorkspaceOperationResult(
                Status.APPLIED,
                changedPaths,
                List.of(),
                List.of(),
                checkpointId,
                verificationSummary,
                summaryLines);
    }

    public static WorkspaceOperationResult partial(
            List<String> changedPaths,
            List<String> failedPaths,
            List<String> skippedPaths,
            String checkpointId,
            String verificationSummary,
            List<String> summaryLines
    ) {
        return new WorkspaceOperationResult(
                Status.PARTIAL,
                changedPaths,
                failedPaths,
                skippedPaths,
                checkpointId,
                verificationSummary,
                summaryLines);
    }

    public static WorkspaceOperationResult blocked(String reason) {
        return new WorkspaceOperationResult(
                Status.BLOCKED,
                List.of(),
                List.of(),
                List.of(),
                "",
                "",
                List.of(reason == null || reason.isBlank() ? "Operation blocked." : reason));
    }

    public static WorkspaceOperationResult failed(String reason) {
        return new WorkspaceOperationResult(
                Status.FAILED,
                List.of(),
                List.of(),
                List.of(),
                "",
                "",
                List.of(reason == null || reason.isBlank() ? "Operation failed." : reason));
    }

    public static WorkspaceOperationResult skipped(List<String> skippedPaths, String reason) {
        return new WorkspaceOperationResult(
                Status.SKIPPED,
                List.of(),
                List.of(),
                skippedPaths,
                "",
                "",
                List.of(reason == null || reason.isBlank() ? "Operation skipped." : reason));
    }

    public enum Status {
        APPLIED,
        PARTIAL,
        BLOCKED,
        FAILED,
        SKIPPED
    }
}
