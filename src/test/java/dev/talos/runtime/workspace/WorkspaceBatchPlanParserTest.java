package dev.talos.runtime.workspace;

import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceBatchPlanParserTest {

    @Test
    void parsesPreviewAndCheckpointPlanForBatchOperations() {
        WorkspaceBatchPlan plan = WorkspaceBatchPlanParser.parse(
                new ToolCall("talos.apply_workspace_batch", Map.of("operations_json", """
                        [
                          {"op":"mkdir","path":"docs"},
                          {"op":"move_path","from":"source.txt","to":"dest.txt","overwrite":true},
                          {"op":"copy_path","from":"README.md","to":"docs/README.md"},
                          {"op":"rename_path","path":"old.txt","new_name":"new.txt"}
                        ]
                        """))).orElseThrow();

        assertEquals(4, plan.operations().size());
        assertTrue(plan.previewSummary().contains("mkdir docs"), plan.previewSummary());
        assertTrue(plan.previewSummary().contains("move source.txt -> dest.txt"), plan.previewSummary());
        assertTrue(plan.previewSummary().contains("copy README.md -> docs/README.md"), plan.previewSummary());
        assertTrue(plan.previewSummary().contains("rename old.txt -> new.txt"), plan.previewSummary());

        WorkspaceOperationPlan checkpointPlan = plan.checkpointPlan();
        assertEquals(WorkspaceOperationPlan.OperationKind.BATCH_APPLY, checkpointPlan.operationKind());
        assertTrue(checkpointPlan.pathEffects().stream()
                        .anyMatch(effect -> effect.role() == WorkspaceOperationPlan.PathRole.SOURCE
                                && effect.path().equals("README.md")),
                "copy source should be exposed to verification metadata");
        assertTrue(checkpointPlan.pathEffects().stream()
                        .anyMatch(effect -> effect.role() == WorkspaceOperationPlan.PathRole.DESTINATION
                                && effect.path().equals("docs/README.md")),
                "copy destination should be exposed to verification metadata");
        assertTrue(checkpointPlan.checkpointPaths().contains("docs"));
        assertTrue(checkpointPlan.checkpointPaths().contains("source.txt"));
        assertTrue(checkpointPlan.checkpointPaths().contains("dest.txt"));
        assertTrue(checkpointPlan.checkpointPaths().contains("docs/README.md"));
        assertFalse(checkpointPlan.checkpointPaths().contains("README.md"),
                "copy sources are read-only inputs and do not need restore capture");
        assertTrue(checkpointPlan.checkpointPaths().contains("old.txt"));
        assertTrue(checkpointPlan.checkpointPaths().contains("new.txt"));
    }

    @Test
    void exposesNestedPathsForPermissionPolicy() {
        ToolCall call = new ToolCall("talos.apply_workspace_batch", Map.of("operations_json", """
                [{"op":"move_path","from":"public.txt","to":".env"}]
                """));

        assertEquals(
                java.util.List.of("public.txt", ".env"),
                WorkspaceBatchPlanParser.pathValues(call));
    }

    @Test
    void rejectsUnknownOperations() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> WorkspaceBatchPlanParser.parse(
                        new ToolCall("talos.apply_workspace_batch", Map.of("operations_json", """
                                [{"op":"delete_path","path":"README.md"}]
                                """))));

        assertTrue(error.getMessage().contains("Unsupported batch operation"), error.getMessage());
    }
}
