package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.tools.ToolError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MutationOutcomeTest {

    @Test
    void noMutationRequestedIsNotRequested() {
        var contract = TaskContractResolver.fromUserRequest("Check the workspace. Do not change anything.");

        MutationOutcome outcome = MutationOutcome.from(contract, loopResult(List.of()), 0);

        assertEquals(MutationOutcomeStatus.NOT_REQUESTED, outcome.status());
        assertEquals(0, outcome.successCount());
        assertEquals(0, outcome.failureCount());
    }

    @Test
    void mutationRequestedButNoMutatingOutcomeIsNotAttempted() {
        var contract = TaskContractResolver.fromUserRequest("Edit index.html.");

        MutationOutcome outcome = MutationOutcome.from(contract, loopResult(List.of()), 0);

        assertEquals(MutationOutcomeStatus.NOT_ATTEMPTED, outcome.status());
    }

    @Test
    void deniedOnlyMutationIsDenied() {
        var contract = TaskContractResolver.fromUserRequest("Edit index.html.");

        MutationOutcome outcome = MutationOutcome.from(contract, loopResult(List.of(
                new ToolCallLoop.ToolOutcome(
                        "talos.edit_file", "index.html", false, true, true, "", "approval denied")
        )), 0);

        assertEquals(MutationOutcomeStatus.DENIED, outcome.status());
        assertEquals(1, outcome.denied().size());
    }

    @Test
    void deniedMutationDominatesNoSuccessTurnEvenWithEarlierFailures() {
        var contract = TaskContractResolver.fromUserRequest("Edit index.html.");

        MutationOutcome outcome = MutationOutcome.from(contract, loopResult(List.of(
                new ToolCallLoop.ToolOutcome(
                        "talos.edit_file", "index.html", false, true, false, "", "invalid args"),
                new ToolCallLoop.ToolOutcome(
                        "talos.edit_file", "index.html", false, true, true, "", "approval denied")
        )), 0);

        assertEquals(MutationOutcomeStatus.DENIED, outcome.status());
        assertEquals(1, outcome.failed().size());
        assertEquals(1, outcome.denied().size());
        assertEquals(2, outcome.failureCount());
    }

    @Test
    void mixedMutationSuccessAndFailureIsPartial() {
        var contract = TaskContractResolver.fromUserRequest("Edit index.html and style.css.");

        MutationOutcome outcome = MutationOutcome.from(contract, loopResult(List.of(
                new ToolCallLoop.ToolOutcome(
                        "talos.edit_file", "index.html", true, true, false, "edited", ""),
                new ToolCallLoop.ToolOutcome(
                        "talos.edit_file", "style.css", false, true, false, "", "old_string not found")
        )), 0);

        assertEquals(MutationOutcomeStatus.PARTIAL, outcome.status());
        assertEquals(1, outcome.successCount());
        assertEquals(1, outcome.failureCount());
    }

    @Test
    void successfulMutationIsSucceeded() {
        var contract = TaskContractResolver.fromUserRequest("Edit index.html.");

        MutationOutcome outcome = MutationOutcome.from(contract, loopResult(List.of(
                new ToolCallLoop.ToolOutcome(
                        "talos.edit_file", "index.html", true, true, false, "edited", "")
        )), 0);

        assertEquals(MutationOutcomeStatus.SUCCEEDED, outcome.status());
        assertEquals(1, outcome.successCount());
    }

    @Test
    void duplicateWorkspaceOperationFailureAfterSameSuccessIsRecovered() {
        var contract = TaskContractResolver.fromUserRequest(
                "Copy README.md to docs/README-copy.md.");
        WorkspaceOperationPlan plan = WorkspaceOperationPlan.copyPath(
                "README.md",
                "docs/README-copy.md",
                WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                false);

        MutationOutcome outcome = MutationOutcome.from(contract, loopResult(List.of(
                workspaceOutcome("talos.copy_path", "docs/README-copy.md", true,
                        "Copied README.md -> docs/README-copy.md", "", "", plan),
                workspaceOutcome("talos.copy_path", "docs/README-copy.md", false,
                        "", "Destination already exists: docs/README-copy.md.",
                        ToolError.INVALID_PARAMS, plan)
        )), 0);

        assertEquals(MutationOutcomeStatus.SUCCEEDED, outcome.status());
        assertEquals(1, outcome.successCount());
        assertEquals(0, outcome.failureCount());
        assertEquals(0, outcome.failed().size());
    }

    @Test
    void earlierWorkspaceOperationFailureBeforeSameSuccessIsNotRecovered() {
        var contract = TaskContractResolver.fromUserRequest(
                "Copy README.md to docs/README-copy.md.");
        WorkspaceOperationPlan plan = WorkspaceOperationPlan.copyPath(
                "README.md",
                "docs/README-copy.md",
                WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                false);

        MutationOutcome outcome = MutationOutcome.from(contract, loopResult(List.of(
                workspaceOutcome("talos.copy_path", "docs/README-copy.md", false,
                        "", "Destination already exists: docs/README-copy.md.",
                        ToolError.INVALID_PARAMS, plan),
                workspaceOutcome("talos.copy_path", "docs/README-copy.md", true,
                        "Copied README.md -> docs/README-copy.md", "", "", plan)
        )), 0);

        assertEquals(MutationOutcomeStatus.PARTIAL, outcome.status());
        assertEquals(1, outcome.successCount());
        assertEquals(1, outcome.failureCount());
    }

    @Test
    void duplicateBatchWorkspaceApplyFailureAfterSameSuccessIsRecovered() {
        var contract = TaskContractResolver.fromUserRequest(
                "Use talos.apply_workspace_batch only to copy README.md to archive/README-copy.md.");
        WorkspaceOperationPlan plan = WorkspaceOperationPlan.batch(
                WorkspaceOperationPlan.OperationKind.BATCH_APPLY,
                List.of(
                        WorkspaceOperationPlan.PathEffect.absentBefore(
                                "archive", true, WorkspaceOperationPlan.OperationKind.CREATE_DIRECTORY),
                        WorkspaceOperationPlan.PathEffect.source(
                                "README.md", false, WorkspaceOperationPlan.OperationKind.COPY_PATH),
                        WorkspaceOperationPlan.PathEffect.destination(
                                "archive/README-copy.md", true, WorkspaceOperationPlan.OperationKind.COPY_PATH)),
                dev.talos.tools.ToolRiskLevel.WRITE,
                true,
                WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                false,
                "Apply workspace batch.",
                "Batch: mkdir archive, copy README.md -> archive/README-copy.md");

        MutationOutcome outcome = MutationOutcome.from(contract, loopResult(List.of(
                workspaceOutcome("talos.apply_workspace_batch", "archive/README-copy.md", true,
                        "Applied batch workspace operation", "", "", plan),
                workspaceOutcome("talos.apply_workspace_batch", "archive/README-copy.md", false,
                        "", "Batch workspace operation failed. Applied: (none). Failed: copy README.md "
                        + "-> archive/README-copy.md. Reason: Destination already exists: archive/README-copy.md.",
                        ToolError.INTERNAL_ERROR, plan)
        )), 0);

        assertEquals(MutationOutcomeStatus.SUCCEEDED, outcome.status());
        assertEquals(1, outcome.successCount());
        assertEquals(0, outcome.failureCount());
    }

    private static ToolCallLoop.LoopResult loopResult(List<ToolCallLoop.ToolOutcome> outcomes) {
        return new ToolCallLoop.LoopResult(
                "answer",
                1,
                outcomes.size(),
                outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(),
                List.of(),
                0,
                0,
                false,
                (int) outcomes.stream().filter(outcome -> outcome.mutating() && outcome.success()).count(),
                List.of(),
                0,
                0,
                0,
                0,
                outcomes
        );
    }

    private static ToolCallLoop.ToolOutcome workspaceOutcome(
            String toolName,
            String pathHint,
            boolean success,
            String summary,
            String errorMessage,
            String errorCode,
            WorkspaceOperationPlan plan
    ) {
        return new ToolCallLoop.ToolOutcome(
                toolName,
                pathHint,
                success,
                true,
                false,
                summary,
                errorMessage,
                null,
                errorCode,
                plan);
    }
}
