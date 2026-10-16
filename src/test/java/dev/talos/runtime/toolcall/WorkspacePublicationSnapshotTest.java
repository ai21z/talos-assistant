package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.intent.RequestedResultInventory;
import dev.talos.runtime.observation.TurnRequestFrame;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.EvidenceObligationVerifier;
import dev.talos.runtime.policy.WorkspaceResultEvidencePolicy;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.DirectoryObservation;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContentMetadata;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic observation carriers isolate the real settlement and publication boundary. */
class WorkspacePublicationSnapshotTest {
    private static final String REQUEST = "Show workspace entries without publishing their names.";
    private static final String PRIVATE_NAME = "private-entry-482.txt";
    @TempDir Path workspace;

    @AfterEach void cleanup() {
        if (TurnRequestFrame.current() != null) TurnRequestFrame.current().close();
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    @Test void withheldNamesCannotReturnAfterCloseOnAnotherThreadOrInALaterPermissiveTurn() throws Exception {
        var task = TaskContractResolver.resolveFromUserRequest(REQUEST);
        var contract = task.contract();
        TurnTaskContractCapture.set(contract);
        LocalTurnTraceCapture.begin("publication-snapshot", "tests", 1, "2026-10-12T00:00:00Z",
                "workspace", "ask", "fixture", "fixture", REQUEST);
        WorkspaceResultEvidencePolicy.Assessment frozen;
        String publicAnswer;
        try (var frame = frame(TurnRequestFrame.Publication.NO_NAMES)) {
            frame.adopt(task);
            assertEquals(TurnRequestFrame.State.ADOPTED, frame.state());
            var state = new LoopState("", List.of(), new ArrayList<>(), workspace,
                    Context.builder(new Config()).build(), null, 10, 0);
            var call = new ToolCall("talos.list_dir", Map.of("path", "."));
            var result = ToolResult.ok(PRIVATE_NAME, ToolContentMetadata.normal().withDirectoryObservation(
                    new DirectoryObservation(workspace.toAbsolutePath().normalize().toString(), 1, 1, true, true)));
            var outcome = ToolOutcomeFactory.executed(call, ".", result, null, null, null);
            state.messages.add(ChatMessage.assistantWithToolCalls("", List.of(
                    new ChatMessage.NativeToolCall("listing", call.toolName(), new java.util.HashMap<String, Object>(call.parameters())))));
            state.messages.add(ChatMessage.toolResult("listing", result.output()));
            state.toolOutcomes.add(outcome);
            state.toolOutcomeResults.bind(outcome, 1, state.messages.getLast());
            state.toolOutcomeResults.bindDirectory(outcome, call);
            state.toolOutcomeResults.bindSourceAdmission(outcome, true);
            var loop = new ToolCallLoop.LoopResult("Provider answer", 1, 1, List.of("talos.list_dir"),
                    state.messages, 0, 0, false, 0, List.of(), 0, 0, 0, 0, null,
                    state.toolOutcomes, Map.of(), false, null, null, null, ReadEvidenceCoverage.capture(state));
            var plan = CurrentTurnPlan.create(contract, ExecutionPhase.INSPECT,
                    List.of("talos.list_dir"), List.of(), List.of());
            frozen = WorkspaceResultEvidencePolicy.assess(plan, loop, workspace);
            assertEquals(EvidenceObligationVerifier.Status.SATISFIED, frozen.aggregate().status());
            assertNotNull(frozen.results().getFirst().observation(), "The forbidden fixture name reaches the policy boundary.");
            assertTrue(frozen.results().getFirst().observation().summary().contains(PRIVATE_NAME));
            publicAnswer = frozen.compose(loop.finalAnswer());
            assertEquals("Entry names were withheld by the current request.", publicAnswer);
        }
        assertEquals(publicAnswer, frozen.compose(""));
        assertFalse(frozen.compose("").contains(PRIVATE_NAME));
        assertEquals(publicAnswer, CompletableFuture.supplyAsync(() -> frozen.compose("")).get(2, TimeUnit.SECONDS));
        try (var later = frame(TurnRequestFrame.Publication.ALL_NAMES)) {
            later.adopt(task);
            assertEquals(TurnRequestFrame.State.ADOPTED, later.state());
            assertEquals(publicAnswer, frozen.compose(""));
            assertFalse(frozen.compose("").contains(PRIVATE_NAME));
        }
    }

    private TurnRequestFrame frame(TurnRequestFrame.Publication publication) {
        return TurnRequestFrame.open(REQUEST, workspace, 1, (owned, task) -> {
            var contents = new RequestedResultInventory.RequestedResult("contents-result",
                    new RequestedResultInventory.SourceRegion(0, REQUEST.length()),
                    RequestedResultInventory.Kind.DIRECTORY_CONTENTS, "active workspace", "top-level, observation time",
                    RequestedResultInventory.EvidenceBasis.SCOPED_DIRECTORY_OBSERVATION);
            return new TurnRequestFrame.Meaning(new RequestedResultInventory(REQUEST, List.of(contents), List.of()),
                    Set.of(TurnRequestFrame.Action.METADATA, TurnRequestFrame.Action.ENUMERATE), publication, false, false);
        });
    }
}
