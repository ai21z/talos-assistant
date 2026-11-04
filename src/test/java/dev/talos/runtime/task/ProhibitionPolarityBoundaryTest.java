package dev.talos.runtime.task;

import dev.talos.runtime.intent.TargetSource;
import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.intent.TaskIntentProjector;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.ActionObligationPolicy;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.policy.CapabilityPosturePolicy;
import dev.talos.runtime.toolcall.CanonicalToolDescriptors;
import dev.talos.runtime.toolcall.ToolCapabilityAvailability;
import dev.talos.runtime.toolcall.ToolSurfacePlanner;
import dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A prohibition only narrows authority. Through the real resolver, workspace reconciliation, posture, tool surface
 * planner and obligation policy, a read-only request with an added prohibition keeps its own surface and targets, a
 * prohibition never adds a tool, and a prohibition never changes the authority of another operation.
 */
class ProhibitionPolarityBoundaryTest {
    private static final Set<String> WORKSPACE_OPERATION_TOOLS = Set.of(
            "talos.copy_path", "talos.move_path", "talos.rename_path", "talos.mkdir", "talos.delete_path");
    private static final Set<String> MUTATING_TOOLS = Set.of(
            "talos.write_file", "talos.edit_file", "talos.apply_workspace_batch",
            "talos.copy_path", "talos.move_path", "talos.rename_path", "talos.mkdir", "talos.delete_path");
    private static final Set<String> MUTATING_OR_EXECUTING_TOOLS = Set.of(
            "talos.write_file", "talos.edit_file", "talos.apply_workspace_batch", "talos.run_command",
            "talos.copy_path", "talos.move_path", "talos.rename_path", "talos.mkdir", "talos.delete_path");

    @TempDir
    Path workspace;

    @BeforeEach
    void seed() throws Exception {
        Files.writeString(workspace.resolve("index.html"),
                "<!DOCTYPE html>\n<html>\n<body>\n<h1>Dead Synth</h1>\n</body>\n</html>\n",
                StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("notes.md"), "# Notes\n\n- Tune the bass line.\n",
                StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("style.css"), "body { font-family: sans-serif; }\n",
                StandardCharsets.UTF_8);
    }

    @Test
    void prohibitedDeleteKeepsTheListingSurface() {
        Turn turn = turn("List the files. Don't edit or delete anything.");

        assertEquals(TaskType.DIRECTORY_LISTING, turn.resolution().contract().type(), turn.describe());
        assertFalse(turn.resolution().blocksUnscopedMutation(), turn.describe());
        assertEquals(List.of("talos.list_dir"), turn.tools(), turn.describe());
        assertEquals(ActionObligation.LIST_DIR_ONLY, turn.obligation(), turn.describe());
        assertSame(turn("List the files. Don't edit anything."), turn);
    }

    @Test
    void sameSentenceProhibitionKeepsTheListingSurface() {
        Turn turn = turn("List the files and do not delete anything.");

        assertTrue(turn.tools().contains("talos.list_dir"), turn.describe());
        assertNoneOf(MUTATING_OR_EXECUTING_TOOLS, turn);
    }

    @Test
    void prohibitionKeepsTheRequestedReadTarget() {
        Turn base = turn("Summarize notes.md.");
        for (String request : List.of(
                "Summarize notes.md. Don't delete it.",
                "Summarize notes.md. Do not delete anything.",
                "Summarize notes.md. Don't delete anything.")) {
            Turn turn = turn(request);
            assertTrue(turn.resolution().contract().expectedTargets().contains("notes.md"), turn.describe());
            assertTrue(turn.tools().contains("talos.read_file"), turn.describe());
            assertNoneOf(MUTATING_OR_EXECUTING_TOOLS, turn);
            assertSame(base, turn);
        }
    }

    @Test
    void prohibitionDoesNotSuppressARequestedEdit() {
        String edit = "add a footer that says Thanks for visiting to index.html";
        Turn base = turn(edit);
        assertTrue(base.resolution().contract().mutationAllowed(), base.describe());
        for (String request : List.of(
                edit + ". Don't delete anything.",
                edit + ". Do not delete notes.md.",
                "Edit index.html but don't delete it.")) {
            Turn turn = turn(request);
            assertTrue(turn.resolution().contract().mutationAllowed(), turn.describe());
            assertTrue(turn.resolution().contract().expectedTargets().contains("index.html"), turn.describe());
            assertTrue(turn.tools().contains("talos.edit_file") || turn.tools().contains("talos.write_file"),
                    turn.describe());
            assertNoneOf(WORKSPACE_OPERATION_TOOLS, turn);
            assertEquals(base.obligation(), turn.obligation(), turn.describe());
        }
    }

    /** Prohibition polarity comes from lexical recognition, so it may narrow but never authorize another operation. */
    @Test
    void prohibitionNeverChangesTheAuthorityOfAnotherOperation() {
        Turn base = turn("Copy notes.md to notes-copy.md.");
        assertEquals(WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE,
                base.resolution().workspaceOperationPlan().state(), base.describe());
        assertTrue(base.tools().contains("talos.copy_path"), base.describe());

        for (String request : List.of(
                "Copy notes.md to notes-copy.md. Don't delete anything.",
                "Copy notes.md to notes-copy.md. Don't hesitate to delete style.css.",
                "Copy notes.md to notes-copy.md. Don't edit index.html, then delete style.css.")) {
            Turn turn = turn(request);
            assertNotEquals(WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE,
                    turn.resolution().workspaceOperationPlan().state(), turn.describe());
            assertNoneOf(WORKSPACE_OPERATION_TOOLS, turn);
        }
    }

    /** By owner decision, a read-only request keeps the approval-gated command that its own surface offers. */
    @Test
    void prohibitionOnAReadOnlyRequestKeepsItsOwnCommandSurface() {
        Turn base = turn("Verify that the Gradle build passes.");
        assertTrue(base.tools().contains("talos.run_command"), base.describe());

        Turn turn = turn("Verify that the Gradle build passes. Don't delete anything.");

        assertSame(base, turn);
        assertNoneOf(MUTATING_TOOLS, turn);
        assertFalse(turn.resolution().contract().mutationAllowed(), turn.describe());
    }

    @Test
    void prohibitedOperandIsNeverOffered() {
        Turn named = turn("Summarize notes.md but don't delete notes.md.");

        assertNoneOf(MUTATING_OR_EXECUTING_TOOLS, named);
        assertTrue(named.resolution().contract().forbiddenTargets().contains("notes.md"), named.describe());
    }

    @Test
    void budgetFailClosedOperationStillBlocksTheOtherOperation() {
        Turn turn = turn("Copy notes.md to notes-copy.md. " + " ".repeat(5_000) + "delete style.css.");

        assertNotEquals(WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE,
                turn.resolution().workspaceOperationPlan().state(), turn.describe());
        assertNoneOf(WORKSPACE_OPERATION_TOOLS, turn);
    }

    private static void assertNoneOf(Set<String> forbidden, Turn turn) {
        Set<String> present = new LinkedHashSet<>(turn.tools());
        present.retainAll(forbidden);
        assertTrue(present.isEmpty(), turn.describe());
    }

    /**
     * The prohibition leaves the request's own type, targets, tools, obligation and phase unchanged. Its workspace
     * operation clause may still be recorded as blocked, because a prohibition keeps blocking workspace operations.
     */
    private static void assertSame(Turn base, Turn turn) {
        assertEquals(base.resolution().contract().type(), turn.resolution().contract().type(), turn.describe());
        assertEquals(base.resolution().contract().mutationAllowed(),
                turn.resolution().contract().mutationAllowed(), turn.describe());
        assertEquals(base.resolution().contract().expectedTargets(),
                turn.resolution().contract().expectedTargets(), turn.describe());
        assertEquals(base.tools(), turn.tools(), turn.describe());
        assertEquals(base.obligation(), turn.obligation(), turn.describe());
        assertEquals(base.phase(), turn.phase(), turn.describe());
    }

    /** Mirrors AssistantTurnPreparation.prepare without active-task memory, under the AGENT posture of auto mode. */
    private Turn turn(String request) {
        TaskResolution initial = TaskContractResolver.resolveFromMessages(List.of(ChatMessage.user(request)));
        TaskContract raw = WorkspaceTargetReconciler.reconcile(initial.contract(), workspace);
        TaskIntent rawIntent = TaskIntentProjector.project(initial.intent(), raw,
                TargetSource.WORKSPACE_EVIDENCE, "workspace-target-reconciliation");
        CapabilityPosturePolicy.EffectiveTurn effective = CapabilityPosturePolicy.apply(CapabilityPosture.AGENT, raw);
        TaskContract contract = WorkspaceTargetReconciler.reconcile(effective.taskContract(), workspace);
        TaskIntent intent = TaskIntentProjector.project(rawIntent, contract,
                TargetSource.WORKSPACE_EVIDENCE, "workspace-target-reconciliation");
        TaskResolution resolution = initial.projectOnto(contract, intent);
        ExecutionPhase phase = resolution.blocksUnscopedMutation() ? ExecutionPhase.INSPECT : effective.phase();
        ToolSurfacePlanner.Plan plan = ToolSurfacePlanner.plan(resolution, phase,
                CanonicalToolDescriptors.registry(), ToolCapabilityAvailability.allEnabled());
        return new Turn(request, resolution, phase, plan.nativeToolNames(), plan.reason(),
                ActionObligationPolicy.derive(resolution, phase));
    }

    private record Turn(String request, TaskResolution resolution, ExecutionPhase phase, List<String> tools,
                        String reason, ActionObligation obligation) {
        String describe() {
            return request + " -> type=" + resolution.contract().type()
                    + " expected=" + resolution.contract().expectedTargets()
                    + " forbidden=" + resolution.contract().forbiddenTargets()
                    + " plan=" + resolution.workspaceOperationPlan().state()
                    + "/" + resolution.workspaceOperationPlan().reason()
                    + " phase=" + phase + " tools=" + tools + " reason=" + reason + " obligation=" + obligation;
        }
    }
}
