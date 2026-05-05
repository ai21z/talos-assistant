package dev.talos.runtime.workspace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceOperationResultTest {

    @Test
    void partialResultCarriesAppliedFailedSkippedAndCheckpointId() {
        WorkspaceOperationResult result = WorkspaceOperationResult.partial(
                List.of("a.txt"),
                List.of("b.txt"),
                List.of("c.txt"),
                "chk-123",
                "verification pending",
                List.of("a.txt applied", "b.txt failed"));

        assertEquals(WorkspaceOperationResult.Status.PARTIAL, result.status());
        assertEquals(List.of("a.txt"), result.changedPaths());
        assertEquals(List.of("b.txt"), result.failedPaths());
        assertEquals(List.of("c.txt"), result.skippedPaths());
        assertEquals("chk-123", result.checkpointId());
        assertEquals("verification pending", result.verificationSummary());
        assertEquals(List.of("a.txt applied", "b.txt failed"), result.summaryLines());
    }

    @Test
    void blockedAndFailedResultsNormalizeNullCollections() {
        WorkspaceOperationResult blocked = WorkspaceOperationResult.blocked("approval required");
        assertEquals(WorkspaceOperationResult.Status.BLOCKED, blocked.status());
        assertEquals(List.of(), blocked.changedPaths());
        assertEquals(List.of("approval required"), blocked.summaryLines());

        WorkspaceOperationResult failed = WorkspaceOperationResult.failed("copy failed");
        assertEquals(WorkspaceOperationResult.Status.FAILED, failed.status());
        assertEquals(List.of("copy failed"), failed.summaryLines());
    }
}
