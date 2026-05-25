package dev.talos.runtime.toolcall;

import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionPathContextTest {
    @Test
    void readOnlyCallUsesPathHintWithoutWorkspaceOperationPlan() {
        ToolExecutionPathContext context = ToolExecutionPathContext.from(
                new ToolCall("talos.read_file", Map.of("path", "docs/notes.md")));

        assertNull(context.workspaceOperationPlan());
        assertEquals("docs/notes.md", context.pathHint());
    }

    @Test
    void workspaceOperationCallPrefersPrimaryChangedPath() {
        ToolExecutionPathContext context = ToolExecutionPathContext.from(
                new ToolCall("talos.move_path", Map.of(
                        "from", "drafts/notes.md",
                        "to", "archive/notes.md")));

        WorkspaceOperationPlan plan = context.workspaceOperationPlan();
        assertNotNull(plan);
        assertEquals(WorkspaceOperationPlan.OperationKind.MOVE_PATH, plan.operationKind());
        assertEquals("archive/notes.md", context.pathHint());
        assertEquals("archive/notes.md", plan.primaryChangedPath());
    }

    @Test
    void invalidWorkspaceOperationFallsBackToGenericPathHint() {
        ToolExecutionPathContext context = ToolExecutionPathContext.from(
                new ToolCall("talos.apply_workspace_batch", Map.of(
                        "operations_json", "[not-json")));

        assertNull(context.workspaceOperationPlan());
        assertNull(context.pathHint());
    }

    @Test
    void sourceEvidenceRepairCanRecomputeContextForUpdatedCall() {
        ToolExecutionPathContext before = ToolExecutionPathContext.from(
                new ToolCall("talos.write_file", Map.of("path", "wrong.md", "content", "old")));
        ToolExecutionPathContext after = ToolExecutionPathContext.from(
                new ToolCall("talos.write_file", Map.of("path", "right.md", "content", "new")));

        assertNull(before.workspaceOperationPlan());
        assertNull(after.workspaceOperationPlan());
        assertEquals("wrong.md", before.pathHint());
        assertEquals("right.md", after.pathHint());
    }

    @Test
    void toolCallExecutionStageDelegatesPathContextDerivation() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java"));

        assertTrue(source.contains("ToolExecutionPathContext.from("), source);
        assertFalse(source.contains("WorkspaceOperationPlanner.checkpointPlan("), source);
        assertFalse(source.contains("WorkspaceOperationPlanner.isWorkspaceOperationTool("), source);
        assertFalse(source.contains("private static WorkspaceOperationPlan workspaceOperationPlan("), source);
        assertFalse(source.contains("private static String pathHint(ToolCall call"), source);
    }
}
