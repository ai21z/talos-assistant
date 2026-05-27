package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolOutcomeFactoryTest {
    @Test
    void editPreApprovalFailurePreservesSyntheticInvalidParamsOutcomeWithoutWorkspacePlan() {
        ToolCall edit = new ToolCall("talos.edit_file", Map.of(
                "path", "README.md",
                "old_string", "old",
                "new_string", "new"));

        ToolCallLoop.ToolOutcome outcome =
                ToolOutcomeFactory.failedEditPreApproval(edit, "README.md", "old_string not found");

        assertEquals("talos.edit_file", outcome.toolName());
        assertEquals("README.md", outcome.pathHint());
        assertFalse(outcome.success());
        assertTrue(outcome.mutating());
        assertFalse(outcome.denied());
        assertEquals("", outcome.summary());
        assertEquals("old_string not found", outcome.errorMessage());
        assertEquals(ToolError.INVALID_PARAMS, outcome.errorCode());
        assertEquals(null, outcome.fileVerificationStatus());
        assertEquals(null, outcome.workspaceOperationPlan());
        assertEquals(ToolMutationEvidence.none(), outcome.mutationEvidence());
    }

    @Test
    void preExecutionMutationFailureCarriesWorkspaceOperationPlan() {
        ToolCall write = new ToolCall("talos.write_file", Map.of(
                "path", "README.md",
                "content", "new"));
        WorkspaceOperationPlan plan = writePlan();

        ToolCallLoop.ToolOutcome outcome =
                ToolOutcomeFactory.failedPreExecutionMutation(write, "README.md", "blocked", plan);

        assertEquals("talos.write_file", outcome.toolName());
        assertEquals("README.md", outcome.pathHint());
        assertFalse(outcome.success());
        assertTrue(outcome.mutating());
        assertFalse(outcome.denied());
        assertEquals("", outcome.summary());
        assertEquals("blocked", outcome.errorMessage());
        assertEquals(ToolError.INVALID_PARAMS, outcome.errorCode());
        assertSame(plan, outcome.workspaceOperationPlan());
    }

    @Test
    void executedSuccessPreservesVerificationWorkspacePlanSummaryAndMutationEvidence() {
        ToolCall write = new ToolCall("talos.write_file", Map.of(
                "path", "README.md",
                "content", "new"));
        ToolResult result = ToolResult.ok("Wrote README.md successfully.", VerificationStatus.PASS);
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(write, result, "README.md");
        WorkspaceOperationPlan plan = writePlan();
        ToolMutationEvidence evidence =
                ToolMutationEvidence.fullWriteReplacement("old", "new");

        ToolCallLoop.ToolOutcome outcome =
                ToolOutcomeFactory.executed(write, "README.md", result, classification, plan, evidence);

        assertEquals("talos.write_file", outcome.toolName());
        assertEquals("README.md", outcome.pathHint());
        assertTrue(outcome.success());
        assertTrue(outcome.mutating());
        assertFalse(outcome.denied());
        assertEquals("Wrote README.md successfully", outcome.summary());
        assertEquals("", outcome.errorMessage());
        assertEquals("", outcome.errorCode());
        assertEquals(VerificationStatus.PASS, outcome.fileVerificationStatus());
        assertSame(plan, outcome.workspaceOperationPlan());
        assertSame(evidence, outcome.mutationEvidence());
    }

    @Test
    void executedFailurePreservesDeniedAndErrorDetails() {
        ToolCall write = new ToolCall("talos.write_file", Map.of(
                "path", "README.md",
                "content", "new"));
        ToolResult result = ToolResult.fail(ToolError.denied("Permission denied"));
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(write, result, "README.md");

        ToolCallLoop.ToolOutcome outcome =
                ToolOutcomeFactory.executed(write, "README.md", result, classification, null, null);

        assertFalse(outcome.success());
        assertTrue(outcome.mutating());
        assertTrue(outcome.denied());
        assertEquals("", outcome.summary());
        assertEquals("Permission denied", outcome.errorMessage());
        assertEquals(ToolError.DENIED, outcome.errorCode());
        assertEquals(ToolMutationEvidence.none(), outcome.mutationEvidence());
    }

    @Test
    void listDirSuccessSummaryPreservesExistingLargeOutputTruncation() {
        ToolCall listDir = new ToolCall("talos.list_dir", Map.of("path", "."));
        String output = "x".repeat(4_001);
        ToolResult result = ToolResult.ok(output);
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(listDir, result, ".");

        ToolCallLoop.ToolOutcome outcome =
                ToolOutcomeFactory.executed(listDir, ".", result, classification, null, null);

        assertEquals(4_000 + "\n... (tool outcome summary truncated)".length(), outcome.summary().length());
        assertTrue(outcome.summary().endsWith("\n... (tool outcome summary truncated)"));
    }

    @Test
    void executionStageDelegatesToolOutcomeConstructionToFactory() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java"));

        assertTrue(source.contains("ToolOutcomeFactory."), source);
        assertFalse(source.contains("new dev.talos.runtime.ToolCallLoop.ToolOutcome"), source);
        assertFalse(source.contains("private static String toolOutcomeSummary"), source);
    }

    private static WorkspaceOperationPlan writePlan() {
        return WorkspaceOperationPlan.batch(
                WorkspaceOperationPlan.OperationKind.WRITE_FILE,
                List.of(WorkspaceOperationPlan.PathEffect.destination("README.md", true)),
                ToolRiskLevel.WRITE,
                true,
                WorkspaceOperationPlan.OverwritePolicy.OVERWRITE,
                false,
                "Write README.md.",
                "Write README.md");
    }
}
