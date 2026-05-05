package dev.talos.runtime.workspace;

import dev.talos.tools.ToolRiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceOperationPlanTest {

    @Test
    void movePlanRepresentsSourceDestinationAndCheckpointPaths() {
        WorkspaceOperationPlan plan = WorkspaceOperationPlan.movePath(
                "src/report.md",
                "archive/report.md",
                WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS);

        assertFalse(plan.operationId().isBlank());
        assertEquals(WorkspaceOperationPlan.OperationKind.MOVE_PATH, plan.operationKind());
        assertEquals(ToolRiskLevel.WRITE, plan.riskLevel());
        assertTrue(plan.requiresCheckpoint());
        assertFalse(plan.recursive());
        assertEquals(WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS, plan.overwritePolicy());
        assertEquals(List.of("src/report.md"), plan.pathsByRole(WorkspaceOperationPlan.PathRole.SOURCE));
        assertEquals(List.of("archive/report.md"), plan.pathsByRole(WorkspaceOperationPlan.PathRole.DESTINATION));
        assertEquals(List.of("src/report.md", "archive/report.md"), plan.checkpointPaths());
        assertTrue(plan.approvalSummary().contains("Move src/report.md to archive/report.md"));
        assertTrue(plan.previewSummary().contains("src/report.md -> archive/report.md"));
    }

    @Test
    void deletePlanRepresentsDeletedPathRecursiveFlagAndDestructiveRisk() {
        WorkspaceOperationPlan plan = WorkspaceOperationPlan.deletePath("old-output", true);

        assertEquals(WorkspaceOperationPlan.OperationKind.DELETE_PATH, plan.operationKind());
        assertEquals(ToolRiskLevel.DESTRUCTIVE, plan.riskLevel());
        assertTrue(plan.requiresCheckpoint());
        assertTrue(plan.recursive());
        assertEquals(List.of("old-output"), plan.pathsByRole(WorkspaceOperationPlan.PathRole.DELETED));
        assertEquals(List.of("old-output"), plan.checkpointPaths());
        assertTrue(plan.approvalSummary().contains("Delete old-output recursively"));
    }

    @Test
    void batchPlanDefensivelyCopiesPathEffects() {
        var effects = new java.util.ArrayList<>(List.of(
                WorkspaceOperationPlan.PathEffect.source("a.txt", true),
                WorkspaceOperationPlan.PathEffect.destination("b.txt", true),
                WorkspaceOperationPlan.PathEffect.absentBefore("new.txt", true)));

        WorkspaceOperationPlan plan = WorkspaceOperationPlan.batch(
                WorkspaceOperationPlan.OperationKind.BATCH_APPLY,
                effects,
                ToolRiskLevel.WRITE,
                true,
                WorkspaceOperationPlan.OverwritePolicy.OVERWRITE,
                false,
                "Apply 3 workspace changes.",
                "Batch preview");

        effects.add(WorkspaceOperationPlan.PathEffect.deleted("late.txt", true));

        assertEquals(3, plan.pathEffects().size());
        assertEquals(List.of("a.txt", "b.txt", "new.txt"), plan.checkpointPaths());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.pathEffects().add(WorkspaceOperationPlan.PathEffect.deleted("x", true)));
    }
}
