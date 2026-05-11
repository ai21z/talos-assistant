package dev.talos.tools.impl;

import dev.talos.core.Config;
import dev.talos.core.capability.CapabilityKind;
import dev.talos.core.security.Sandbox;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolOperationMetadata;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BatchWorkspaceApplyToolTest {

    @Test
    void appliesCoherentBatchAndReturnsRuntimeOwnedSummary(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("source.txt"), "source");
        Files.writeString(workspace.resolve("old.txt"), "old");
        var tool = new BatchWorkspaceApplyTool();

        ToolResult result = tool.execute(
                new ToolCall("talos.apply_workspace_batch", Map.of("operations_json", """
                        [
                          {"op":"mkdir","path":"docs"},
                          {"op":"copy_path","from":"source.txt","to":"docs/source.txt"},
                          {"op":"rename_path","path":"old.txt","new_name":"new.txt"}
                        ]
                        """)),
                context(workspace));

        assertTrue(result.success(), result.errorMessage());
        assertTrue(Files.isDirectory(workspace.resolve("docs")));
        assertEquals("source", Files.readString(workspace.resolve("docs/source.txt")));
        assertFalse(Files.exists(workspace.resolve("old.txt")));
        assertEquals("old", Files.readString(workspace.resolve("new.txt")));
        assertTrue(result.output().contains("Applied batch workspace operation"), result.output());
        assertTrue(result.output().contains("Created directory docs"), result.output());
        assertTrue(result.output().contains("Copied source.txt -> docs/source.txt"), result.output());
        assertTrue(result.output().contains("Renamed old.txt -> new.txt"), result.output());

        ToolOperationMetadata metadata = tool.descriptor().operationMetadata();
        assertEquals(CapabilityKind.ORGANIZE, metadata.capabilityKind());
        assertEquals(ToolRiskLevel.WRITE, metadata.riskLevel());
        assertTrue(metadata.mutatesWorkspace());
        assertTrue(metadata.canAffectMultiplePaths());
        assertTrue(metadata.requiresCheckpoint());
    }

    @Test
    void appliesExplicitDeletePathOperation(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/old-plan.md"), "delete me");
        var tool = new BatchWorkspaceApplyTool();

        ToolResult result = tool.execute(
                new ToolCall("talos.apply_workspace_batch", Map.of("operations_json", """
                        [
                          {"op":"delete_path","path":"docs/old-plan.md"}
                        ]
                        """)),
                context(workspace));

        assertTrue(result.success(), result.errorMessage());
        assertFalse(Files.exists(workspace.resolve("docs/old-plan.md")));
        assertTrue(result.output().contains("Deleted docs/old-plan.md"), result.output());
    }

    @Test
    void deletePathBatchPlanIsDestructiveForApprovalAndCheckpointing() {
        var call = new ToolCall("talos.apply_workspace_batch", Map.of("operations_json", """
                [{"op":"delete_path","path":"docs/old-plan.md"}]
                """));

        var plan = dev.talos.runtime.workspace.WorkspaceBatchPlanParser.parse(call).orElseThrow();

        assertEquals(ToolRiskLevel.DESTRUCTIVE, plan.checkpointPlan().riskLevel());
        assertEquals(List.of("docs/old-plan.md"), plan.checkpointPlan().checkpointPaths());
    }

    @Test
    void partialFailureReportsAppliedAndFailedPaths(@TempDir Path workspace) {
        var tool = new BatchWorkspaceApplyTool();

        ToolResult result = tool.execute(
                new ToolCall("talos.apply_workspace_batch", Map.of("operations_json", """
                        [
                          {"op":"mkdir","path":"docs"},
                          {"op":"move_path","from":"missing.txt","to":"docs/missing.txt"}
                        ]
                        """)),
                context(workspace));

        assertFalse(result.success());
        assertTrue(Files.isDirectory(workspace.resolve("docs")),
                "the already-applied operation should remain applied after a partial failure");
        assertTrue(result.errorMessage().contains("Batch partially applied"), result.errorMessage());
        assertTrue(result.errorMessage().contains("Applied: docs"), result.errorMessage());
        assertTrue(result.errorMessage().contains("Failed: missing.txt -> docs/missing.txt"),
                result.errorMessage());
    }

    @Test
    void rejectsInvalidJsonAndWorkspaceEscapeBeforeMutation(@TempDir Path workspace) {
        var tool = new BatchWorkspaceApplyTool();

        ToolResult invalidJson = tool.execute(
                new ToolCall("talos.apply_workspace_batch", Map.of("operations_json", "not json")),
                context(workspace));
        assertFalse(invalidJson.success());
        assertTrue(invalidJson.errorMessage().contains("Invalid operations_json"), invalidJson.errorMessage());

        ToolResult escape = tool.execute(
                new ToolCall("talos.apply_workspace_batch", Map.of("operations_json", """
                        [{"op":"mkdir","path":"../outside"}]
                        """)),
                context(workspace));
        assertFalse(escape.success());
        assertTrue(escape.errorMessage().contains("Path not allowed"), escape.errorMessage());
        assertFalse(Files.exists(workspace.resolve("docs")));
    }

    private static ToolContext context(Path workspace) {
        return new ToolContext(
                workspace,
                new Sandbox(workspace, Map.of()),
                new Config());
    }
}
