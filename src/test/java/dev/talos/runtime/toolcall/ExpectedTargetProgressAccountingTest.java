package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpectedTargetProgressAccountingTest {

    @Test
    void returnsExpectedTargetsFromCurrentTaskWhenNoMutationSatisfiedThem() {
        LoopState state = state("Create README.md and notes.md.");

        List<String> remaining = ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state);

        assertEquals(Set.of("README.md", "notes.md"), Set.copyOf(remaining));
        assertEquals(2, remaining.size());
    }

    @Test
    void successfulMutatingOutcomeSatisfiesTargetByNormalizedPath() {
        LoopState state = state("Create README.md and notes.md.");
        state.toolOutcomes.add(outcome("talos.write_file", "./README.md"));

        List<String> remaining = ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state);

        assertEquals(List.of("notes.md"), remaining);
    }

    @Test
    void workspaceOperationPathEffectsSatisfyExpectedTargets() {
        LoopState state = state(
                "Organize these files using workspace operation tools only: copy README.md to "
                        + "docs/notes/README-copy.md, move scratch/todo.md to docs/todo.md, "
                        + "then rename docs/todo.md to tasks.md. Do not use command execution.");
        state.toolOutcomes.add(workspaceOutcome(
                "talos.copy_path",
                "docs/notes/README-copy.md",
                WorkspaceOperationPlan.copyPath(
                        "README.md",
                        "docs/notes/README-copy.md",
                        WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                        false)));
        state.toolOutcomes.add(workspaceOutcome(
                "talos.move_path",
                "docs/todo.md",
                WorkspaceOperationPlan.movePath(
                        "scratch/todo.md",
                        "docs/todo.md",
                        WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS)));
        state.toolOutcomes.add(workspaceOutcome(
                "talos.rename_path",
                "docs/tasks.md",
                WorkspaceOperationPlan.batch(
                        WorkspaceOperationPlan.OperationKind.RENAME_PATH,
                        List.of(
                                WorkspaceOperationPlan.PathEffect.source(
                                        "docs/todo.md",
                                        true,
                                        WorkspaceOperationPlan.OperationKind.RENAME_PATH),
                                WorkspaceOperationPlan.PathEffect.destination(
                                        "docs/tasks.md",
                                        true,
                                        WorkspaceOperationPlan.OperationKind.RENAME_PATH)),
                        dev.talos.tools.ToolRiskLevel.WRITE,
                        true,
                        WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                        false,
                        "Rename docs/todo.md to docs/tasks.md.",
                        "Rename: docs/todo.md -> docs/tasks.md")));

        assertTrue(ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state).isEmpty());
    }

    @Test
    void successfulNestedPathKeepsExistingBasenameSatisfactionCompatibility() {
        LoopState state = state("Create summary.md.");
        state.toolOutcomes.add(outcome("talos.write_file", "docs/summary.md"));

        assertTrue(ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state).isEmpty());
    }

    @Test
    void staticWebFullRewriteRepairContextSuppressesExpectedTargetProgress() {
        LoopState state = state("Create index.html.");
        state.staticWebFullRewriteRequiredTargets.add("index.html");

        assertTrue(ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state).isEmpty());
    }

    @Test
    void adoptersDoNotKeepPrivateExpectedTargetAccountingCopies() throws Exception {
        String stage = java.nio.file.Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));
        String sourcePlanner = java.nio.file.Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/SourceEvidenceExactRepairPlanner.java"));
        String targetPlanner = java.nio.file.Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/TargetReadbackCompactRepairPlanner.java"));

        assertTrue(stage.contains("ExpectedTargetProgressAccounting.remainingExpectedMutationTargets"), stage);
        assertTrue(sourcePlanner.contains("ExpectedTargetProgressAccounting.remainingExpectedMutationTargets"),
                sourcePlanner);
        assertTrue(targetPlanner.contains("ExpectedTargetProgressAccounting.remainingExpectedMutationTargets"),
                targetPlanner);
        for (String source : List.of(stage, sourcePlanner, targetPlanner)) {
            assertFalse(source.contains("private static List<String> remainingExpectedMutationTargets"), source);
            assertFalse(source.contains("private static void addSatisfiedExpectedTargetKeys"), source);
            assertFalse(source.contains("private static void addExpectedTargetPathKeys"), source);
        }
    }

    private static LoopState state(String userRequest) {
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(userRequest))),
                Path.of("."),
                null,
                null,
                5,
                0);
    }

    private static ToolCallLoop.ToolOutcome outcome(String toolName, String pathHint) {
        return new ToolCallLoop.ToolOutcome(
                toolName,
                pathHint,
                true,
                true,
                false,
                "mutated " + pathHint,
                "");
    }

    private static ToolCallLoop.ToolOutcome workspaceOutcome(
            String toolName,
            String pathHint,
            WorkspaceOperationPlan plan
    ) {
        return new ToolCallLoop.ToolOutcome(
                toolName,
                pathHint,
                true,
                true,
                false,
                "workspace operation applied",
                "",
                null,
                "",
                plan);
    }
}
