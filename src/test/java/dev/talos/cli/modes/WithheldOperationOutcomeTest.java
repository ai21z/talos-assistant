package dev.talos.cli.modes;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolMutationEffects;
import dev.talos.tools.WorkspacePathIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Not-performed statements and the outcome of withheld workspace operations (P14, RC2-I). The plans come from the real
 * resolver. The tool outcomes are constructed, because a write that fails after creating its folder cannot be scripted
 * through a provider.
 */
class WithheldOperationOutcomeTest {
    private static final String MKDIR_REQUEST = "create a folder named archive and then write archive/notes.md";
    private static final String MKDIR_STATEMENT = "[Not performed: create folder archive. An operation before a "
            + "content change in the same request is not supported.]";

    @TempDir
    Path temp;

    @Test
    void aFolderThatAFailedOrUncertainWriteMayHaveCreatedIsNotStatedAsNotPerformed() throws Exception {
        CurrentTurnPlan plan = plan(MKDIR_REQUEST);
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        var failedAfterCreatingFolder = write(false).withMutationEffects(
                new ToolMutationEffects(List.of("archive"), List.of()));
        var uncertainWrite = write(false).withMutationEffects(
                new ToolMutationEffects(List.of(), List.of("archive/notes.md")));
        var deniedWrite = new ToolCallLoop.ToolOutcome("talos.write_file", "archive/notes.md", false, true, true,
                "", "denied");

        assertEquals("",
                ExecutionOutcome.withheldOperationStatements(plan, loop(failedAfterCreatingFolder), workspace));
        assertEquals("", ExecutionOutcome.withheldOperationStatements(plan, loop(uncertainWrite), workspace));
        assertEquals(MKDIR_STATEMENT, ExecutionOutcome.withheldOperationStatements(plan, loop(deniedWrite), workspace));
        assertEquals(MKDIR_STATEMENT, ExecutionOutcome.withheldOperationStatements(plan, null, null));
    }

    @Test
    void aCaseVariantSettlesTheFolderOnlyWhereWorkspaceIdentityMayMatchIt() throws Exception {
        CurrentTurnPlan plan = plan(MKDIR_REQUEST);
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Files.createDirectories(workspace.resolve("Archive"));
        var write = write(true).withMutationEffects(ToolMutationEffects.applied("Archive/notes.md"));

        String expected = WorkspacePathIdentity.mayMatch(workspace, "archive", "Archive") ? "" : MKDIR_STATEMENT;
        assertEquals(expected, ExecutionOutcome.withheldOperationStatements(plan, loop(write), workspace));
    }

    @Test
    void aWithheldOperationTurnsACompleteTurnPartialOnlyWhenFilesChanged() {
        var verified = new OutcomeDominancePolicy.Decision(ExecutionOutcome.CompletionStatus.COMPLETE,
                TaskCompletionStatus.COMPLETED_VERIFIED, false);
        var readOnly = new OutcomeDominancePolicy.Decision(ExecutionOutcome.CompletionStatus.COMPLETE,
                TaskCompletionStatus.READ_ONLY_ANSWERED, false);
        var blocked = new OutcomeDominancePolicy.Decision(ExecutionOutcome.CompletionStatus.BLOCKED,
                TaskCompletionStatus.BLOCKED_BY_APPROVAL, false);

        assertEquals(new OutcomeDominancePolicy.Decision(ExecutionOutcome.CompletionStatus.PARTIAL,
                TaskCompletionStatus.PARTIAL, false),
                OutcomeDominancePolicy.withWithheldOperations(verified, true, true));
        assertEquals(new OutcomeDominancePolicy.Decision(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY,
                TaskCompletionStatus.ADVISORY_ONLY, false),
                OutcomeDominancePolicy.withWithheldOperations(readOnly, true, false));
        assertEquals(new OutcomeDominancePolicy.Decision(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY,
                TaskCompletionStatus.ADVISORY_ONLY, false),
                OutcomeDominancePolicy.withWithheldOperations(verified, true, false));
        assertSame(blocked, OutcomeDominancePolicy.withWithheldOperations(blocked, true, true));
        assertSame(verified, OutcomeDominancePolicy.withWithheldOperations(verified, false, true));
    }

    private static CurrentTurnPlan plan(String request) {
        return CurrentTurnPlan.createWithActionObligation(TaskContractResolver.resolveFromUserRequest(request),
                ExecutionPhase.APPLY, List.of(), List.of(), List.of(), null, null, null, null, null);
    }

    private static ToolCallLoop.ToolOutcome write(boolean success) {
        return new ToolCallLoop.ToolOutcome("talos.write_file", "archive/notes.md", success, true, false,
                success ? "Wrote archive/notes.md." : "", success ? "" : "write failed");
    }

    private static ToolCallLoop.LoopResult loop(ToolCallLoop.ToolOutcome outcome) {
        return new ToolCallLoop.LoopResult("", 1, 1, List.of(outcome.toolName()),
                List.of(ChatMessage.assistant("")), outcome.success() ? 0 : 1, 0, false,
                outcome.success() ? 1 : 0, List.of(), 0, 0, 0, 0, List.of(outcome));
    }
}
