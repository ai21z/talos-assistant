package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.context.ContentSaveContinuation;
import dev.talos.runtime.intent.ContentSaveRequest;
import dev.talos.runtime.intent.RequestedResultInventory;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Actual projection functions are exercised without a provider or tool execution. */
class PlanProjectionLineageTest {
    private static final String ORIGIN = "I want to write and save a txt file with a short medieval story.";
    private static final String REPLY = "`story.txt`";
    private static final List<String> TOOLS = List.of("talos.write_file", "talos.read_file", "talos.list_dir");
    @TempDir Path workspace;

    @Test
    void everyRetryPurposeCarriesTheExactClarificationInterpretation() {
        CurrentTurnPlan captured = clarificationPlan();
        for (RetryDispatchPurpose purpose : RetryDispatchPurpose.values()) {
            CurrentTurnPlan projected = purpose.project(captured);
            assertLineage(captured, projected);
            assertClarificationSource(projected);
        }
    }

    @Test
    void everyRetryPurposeCarriesTheExactRequestedResultInventory() {
        CurrentTurnPlan captured = workspacePlan();
        assertTrue(captured.requestedResults().boundedWorkspaceQuery());
        for (RetryDispatchPurpose purpose : RetryDispatchPurpose.values()) {
            assertLineage(captured, purpose.project(captured));
        }
    }

    @Test
    void selectedToolDispatchCarriesBothInterpretations() throws ReflectiveOperationException {
        for (CurrentTurnPlan captured : List.of(clarificationPlan(), workspacePlan())) {
            Method method = TurnModelDispatcher.class.getDeclaredMethod("dispatchPlan", CurrentTurnPlan.class, List.class);
            method.setAccessible(true);
            CurrentTurnPlan projected = (CurrentTurnPlan) method.invoke(null, captured,
                    List.of(new ToolSpec("talos.read_file", "Read", "{}")));
            assertLineage(captured, projected);
            assertEquals(List.of("talos.read_file"), projected.nativeTools());
            if (captured.contentSaveRequest().present()) assertClarificationSource(projected);
        }
    }

    @Test
    void compactPlanCarriesBothInterpretationsWithoutExpandingItsToolSurface() throws ReflectiveOperationException {
        for (CurrentTurnPlan captured : List.of(clarificationPlan(), workspacePlan())) {
            Method method = ExactWriteContextFallback.class.getDeclaredMethod("compactPlan", CurrentTurnPlan.class);
            method.setAccessible(true);
            CurrentTurnPlan projected = (CurrentTurnPlan) method.invoke(null, captured);
            assertLineage(captured, projected);
            assertEquals(List.of("talos.write_file"), projected.nativeTools());
            assertEquals(List.of("talos.write_file"), projected.promptTools());
            if (captured.contentSaveRequest().present()) assertClarificationSource(projected);
        }
    }

    @Test
    void filenameClarificationDoesNotBecomeEligibleForHistoryDroppingExactWriteFallback() {
        CurrentTurnPlan captured = clarificationPlan();
        assertTrue(captured.taskExpectations().isEmpty(), "A filename reply is not an exact literal write request");
        Context ctx = Context.builder(new Config())
                .nativeToolSpecs(List.of(new ToolSpec("talos.write_file", "Write", "{}"))).build();
        assertTrue(ExactWriteContextFallback.prepare(ctx, captured, (ignored, selected, tools) -> {
            fail("The origin content must not be dropped by broadening compact fallback eligibility");
            return ChatRequestControls.defaults();
        }).isEmpty());
        assertClarificationSource(captured);
    }

    @Test
    void retainedInventoryStillRejectsAnInterpretationFromAnotherRequest() {
        CurrentTurnPlan captured = workspacePlan();
        RequestedResultInventory unrelated = RequestedResultInventory.compile(
                TaskContractResolver.fromUserRequest("Where is this workspace?"));
        assertNotEquals(captured.requestedResults(), unrelated);
        assertThrows(IllegalArgumentException.class, () -> copyWith(captured, unrelated, captured.contentSaveRequest()));
        assertThrows(IllegalArgumentException.class, () -> copyWith(captured, captured.requestedResults(),
                ContentSaveRequest.empty("a different current request")));
    }

    private CurrentTurnPlan clarificationPlan() {
        var beginning = ContentSaveContinuation.evaluate(TaskContractResolver.resolveFromUserRequest(ORIGIN),
                null, workspace, 7, CapabilityPosture.AGENT, ContentRestrictions.UNRESTRICTED);
        assertNotNull(beginning.nextPending());
        var continuation = ContentSaveContinuation.evaluate(TaskContractResolver.resolveFromUserRequest(REPLY),
                beginning.nextPending(), workspace, 8, CapabilityPosture.AGENT, ContentRestrictions.UNRESTRICTED);
        assertTrue(continuation.consumed());
        assertTrue(continuation.originPromptData().contains(ORIGIN));
        return plan(continuation.resolution(), ExecutionPhase.APPLY, ActionObligation.MUTATING_TOOL_REQUIRED);
    }

    private CurrentTurnPlan workspacePlan() {
        return plan(TaskContractResolver.resolveFromUserRequest("Where is this workspace and is this directory empty?"),
                ExecutionPhase.INSPECT, ActionObligation.INSPECT_REQUIRED);
    }

    private static CurrentTurnPlan plan(TaskResolution resolution, ExecutionPhase phase, ActionObligation obligation) {
        return CurrentTurnPlan.createWithActionObligation(resolution, phase, TOOLS, TOOLS, List.of(),
                CurrentTurnPlan.NONE_OR_NOT_DERIVED, CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED, obligation, new Config());
    }

    private static void assertLineage(CurrentTurnPlan captured, CurrentTurnPlan projected) {
        assertSame(captured.taskContract(), projected.taskContract());
        assertEquals(captured.originalUserRequest(), projected.originalUserRequest());
        assertSame(captured.requestedResults(), projected.requestedResults(), "The captured inventory must be carried");
        assertSame(captured.contentSaveRequest(), projected.contentSaveRequest(), "The captured operation must be carried");
        assertEquals(captured.requestObligation(), projected.requestObligation());
        assertEquals(captured.workspaceOperationPlan(), projected.workspaceOperationPlan());
        assertEquals(captured.contentMutationPredecessorPaths(), projected.contentMutationPredecessorPaths());
    }

    private static void assertClarificationSource(CurrentTurnPlan plan) {
        ContentSaveRequest save = plan.contentSaveRequest();
        assertEquals(ORIGIN, plan.originalUserRequest());
        assertEquals(ORIGIN, save.originalRequest());
        assertNotNull(save.clarificationBinding());
        assertEquals(REPLY, save.clarificationBinding().replyRequest());
        assertEquals("story.txt", save.clarificationBinding().replySpan().text(REPLY));
        assertEquals(7, save.clarificationBinding().originTurn());
        assertEquals(8, save.clarificationBinding().replyTurn());
        assertEquals(ContentSaveRequest.Binding.MISSING, save.operations().getFirst().destination().binding());
    }

    private static CurrentTurnPlan copyWith(CurrentTurnPlan plan, RequestedResultInventory inventory, ContentSaveRequest save) {
        return new CurrentTurnPlan(plan.taskContract(), plan.originalUserRequest(), plan.phaseInitial(), plan.phaseFinal(),
                plan.actionObligation(), plan.taskExpectations(), plan.nativeTools(), plan.promptTools(), plan.blockedTools(),
                plan.evidenceObligation(), plan.outputObligation(), plan.activeTaskContext(), plan.artifactGoal(),
                plan.verifierProfile(), plan.requestObligation(), plan.workspaceOperationPlan(),
                plan.contentMutationPredecessorPaths(), inventory, save);
    }
}
