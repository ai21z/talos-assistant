package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
    void verifyOnlyConstraintTargetDoesNotRemainAsMutationProgressTarget() {
        LoopState state = state("Rewrite styles.css so index.html still works.");
        state.toolOutcomes.add(outcome("talos.write_file", "styles.css"));

        List<String> remaining = ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state);

        assertTrue(remaining.isEmpty(), remaining.toString());
    }

    @Test
    void workspaceReconciledPluralStaticWebTargetsSatisfyExpectedProgress(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<script src=\"scripts.js\"></script>\n");
        Files.writeString(workspace.resolve("styles.css"), "body { margin: 0; }\n");
        Files.writeString(workspace.resolve("scripts.js"), "console.log('existing');\n");
        LoopState state = state(
                "Create a modern synthwave website here with CSS styling and JavaScript interaction.",
                workspace);
        state.toolOutcomes.add(outcome("talos.write_file", "index.html"));
        state.toolOutcomes.add(outcome("talos.write_file", "styles.css"));
        state.toolOutcomes.add(outcome("talos.write_file", "scripts.js"));

        List<String> remaining = ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state);

        assertTrue(remaining.isEmpty(), remaining.toString());
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
        String selector = java.nio.file.Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptObligationSelector.java"));
        String sourcePlanner = java.nio.file.Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/SourceEvidenceExactRepairPlanner.java"));
        String targetPlanner = java.nio.file.Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/TargetReadbackCompactRepairPlanner.java"));

        assertTrue(selector.contains("ExpectedTargetProgressAccounting.remainingExpectedMutationTargets"),
                selector);
        assertTrue(sourcePlanner.contains("ExpectedTargetProgressAccounting.remainingExpectedMutationTargets"),
                sourcePlanner);
        assertTrue(targetPlanner.contains("ExpectedTargetProgressAccounting.remainingExpectedMutationTargets"),
                targetPlanner);
        for (String source : List.of(selector, sourcePlanner, targetPlanner)) {
            assertFalse(source.contains("private static List<String> remainingExpectedMutationTargets"), source);
            assertFalse(source.contains("private static void addSatisfiedExpectedTargetKeys"), source);
            assertFalse(source.contains("private static void addExpectedTargetPathKeys"), source);
        }
    }

    private static LoopState state(String userRequest) {
        return state(userRequest, Path.of("."));
    }

    private static LoopState state(String userRequest, Path workspace) {
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(userRequest))),
                workspace,
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
