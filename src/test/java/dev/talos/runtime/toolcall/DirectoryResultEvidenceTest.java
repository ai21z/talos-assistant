package dev.talos.runtime.toolcall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ToolCallLoop.ToolOutcome;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.policy.EvidenceObligationVerifier.Status;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.intent.RequestedResultInventory;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.WorkspaceResultEvidencePolicy;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolContentMetadata;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolMutationEffects;
import dev.talos.tools.DirectoryObservation;
import dev.talos.tools.impl.ListDirTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Real directory tool observations with synthetic protocol carriers isolate association and freshness. */
class DirectoryResultEvidenceTest {
    @TempDir Path workspace;
    private final TaskContract contract = TaskContractResolver.fromUserRequest(
            "Where is this workspace and is it empty?");

    @BeforeEach void begin() {
        TurnTaskContractCapture.set(contract);
        LocalTurnTraceCapture.begin("directory-evidence", "tests", 1, "2026-10-09T00:00:00Z",
                "workspace", "ask", "fixture", "fixture", contract.originalUserRequest());
    }

    @AfterEach void clear() {
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    private LoopState state() {
        return new LoopState("", List.of(), new ArrayList<>(), workspace,
                Context.builder(new Config()).build(), null, 10, 0);
    }

    private ToolOutcome list(LoopState state, String target, int maxEntries, boolean admitted, boolean metadata) {
        var call = new ToolCall("talos.list_dir", Map.of("path", target, "max_entries", "" + maxEntries));
        var result = new ListDirTool().execute(call,
                new ToolContext(workspace, new Sandbox(workspace, Map.of()), new Config()));
        if (!metadata && result.success()) result = ToolResult.ok(result.output());
        var outcome = ToolOutcomeFactory.executed(call, target, result, null, null, null);
        String id = "listing-" + state.messages.size();
        state.messages.add(ChatMessage.assistantWithToolCalls("", List.of(
                new ChatMessage.NativeToolCall(id, call.toolName(), Map.of("path", target, "max_entries", "" + maxEntries)))));
        state.messages.add(ChatMessage.toolResult(id, result.success() ? result.output() : result.errorMessage()));
        state.toolOutcomes.add(outcome);
        state.toolOutcomeResults.bind(outcome, state.messages.size() - 1, state.messages.getLast());
        state.toolOutcomeResults.bindDirectory(outcome, call);
        state.toolOutcomeResults.bindSourceAdmission(outcome, admitted);
        return outcome;
    }

    private Status status(LoopState state) {
        return ReadEvidenceCoverage.capture(state).assessDirectory(
                state.toolOutcomes, workspace, contract, ".").result().status();
    }

    @Test void emptyAndHiddenEntriesAreObservedWithoutSourceReads() throws Exception {
        var empty = state();
        var observed = list(empty, ".", 200, true, true);
        assertEquals(Status.SATISFIED, status(empty));
        assertEquals(0, observed.directoryObservation().returnedEntries());
        assertTrue(observed.directoryObservation().includesHiddenEntries());
        Files.writeString(workspace.resolve(".hidden"), "secret");
        var populated = state();
        var hidden = list(populated, ".", 200, true, true);
        assertEquals(Status.SATISFIED, status(populated));
        assertEquals(1, hidden.directoryObservation().returnedEntries());
        assertTrue(hidden.summary().contains(".hidden"));
    }

    @Test void truncatedObservationCannotEstablishCompleteContents() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "a");
        Files.writeString(workspace.resolve("b.txt"), "b");
        var state = state();
        var outcome = list(state, ".", 1, true, true);
        assertFalse(outcome.directoryObservation().complete());
        assertEquals(Status.UNSATISFIED, status(state));
        var coverage = ReadEvidenceCoverage.capture(state);
        var loop = new ToolCallLoop.LoopResult("The directory is empty.", 1, 1, List.of("talos.list_dir"),
                state.messages, 0, 0, false, 0, List.of(), 0, 0, 0, 0, null,
                state.toolOutcomes, Map.of(), false, null, null, null, coverage);
        var plan = CurrentTurnPlan.create(contract, ExecutionPhase.INSPECT, List.of("talos.list_dir"), List.of(), List.of());
        String composed = WorkspaceResultEvidencePolicy.assess(plan, loop, workspace).compose(loop.finalAnswer());
        assertTrue(composed.contains("not empty"), composed);
        assertTrue(composed.contains("complete contents remain unresolved"), composed);
        assertFalse(composed.contains("directory is empty"), composed);
    }

    @Test void failedLaterObservationCannotBorrowEarlierEmptyResult() {
        var state = state();
        list(state, ".", 200, true, true);
        // A real failure at the same effective root is captured without directory metadata.
        var call = new ToolCall("talos.list_dir", Map.of("path", "."));
        var failed = ToolOutcomeFactory.executed(call, ".", ToolResult.fail("filesystem observation failed"), null, null, null);
        state.toolOutcomes.add(failed);
        state.messages.add(ChatMessage.user("failed listing result"));
        state.toolOutcomeResults.bind(failed, state.messages.size() - 1, state.messages.getLast());
        state.toolOutcomeResults.bindDirectory(failed, call);
        assertEquals(Status.UNSATISFIED, status(state));
    }

    @Test void authenticatedCompleteRefreshResolvesEarlierTruncation() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "a");
        Files.writeString(workspace.resolve("b.txt"), "b");
        var state = state();
        var limited = list(state, ".", 1, true, true);
        assertEquals(Status.UNSATISFIED, status(state));
        var complete = list(state, ".", 200, true, true);
        var assessment = ReadEvidenceCoverage.capture(state).assessDirectory(
                state.toolOutcomes, workspace, contract, ".");
        assertEquals(Status.SATISFIED, assessment.result().status());
        assertSame(complete, assessment.observation());
        assertFalse(limited.directoryObservation().complete(), "Historical truncation is preserved.");
        assertEquals(2, state.toolOutcomes.size());
    }

    @Test void laterCompleteObservationOfAnotherDirectoryDoesNotResolveRootTruncation() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "a");
        Files.createDirectory(workspace.resolve("sub"));
        var state = state();
        var root = list(state, ".", 1, true, true);
        var other = list(state, "sub", 200, true, true);
        assertTrue(other.directoryObservation().complete());
        var assessment = ReadEvidenceCoverage.capture(state).assessDirectory(
                state.toolOutcomes, workspace, contract, ".");
        assertEquals(Status.UNSATISFIED, assessment.result().status());
        assertSame(root, assessment.observation());
    }

    @Test void filteredObservationRequiresAuthenticatedRefreshIncludingHiddenEntries() {
        var state = state();
        syntheticDirectory(state, false, true);
        assertEquals(Status.UNSATISFIED, status(state));
        syntheticDirectory(state, true, false);
        assertEquals(Status.UNSATISFIED, status(state), "A text carrier does not authenticate refresh after filtering.");
        list(state, ".", 200, true, true);
        assertEquals(Status.SATISFIED, status(state));
    }

    /** Synthetic filtering metadata isolates a capability not offered by ListDirTool. */
    private void syntheticDirectory(LoopState state, boolean includesHidden, boolean nativeCarrier) {
        var call = new ToolCall("talos.list_dir", Map.of("path", "."));
        var result = ToolResult.ok("(empty directory)", ToolContentMetadata.normal().withDirectoryObservation(
                new DirectoryObservation(workspace.toAbsolutePath().normalize().toString(), 1, 0, true, includesHidden)));
        var outcome = ToolOutcomeFactory.executed(call, ".", result, null, null, null);
        String id = "filtered-" + state.messages.size();
        if (nativeCarrier) state.messages.add(ChatMessage.assistantWithToolCalls("", List.of(
                new ChatMessage.NativeToolCall(id, call.toolName(), Map.of("path", ".")))));
        state.messages.add(nativeCarrier ? ChatMessage.toolResult(id, result.output()) : ChatMessage.user(result.output()));
        state.toolOutcomes.add(outcome);
        state.toolOutcomeResults.bind(outcome, state.messages.size() - 1, state.messages.getLast());
        state.toolOutcomeResults.bindDirectory(outcome, call);
        state.toolOutcomeResults.bindSourceAdmission(outcome, true);
    }

    @Test void completeAcquisitionWithClippedPresentationKeepsExplicitPartialSummaryMarker() throws Exception {
        for (int i = 0; i < 90; i++) Files.writeString(workspace.resolve(
                "entry-%03d-%s.txt".formatted(i, "x".repeat(55))), "fixture");
        var state = state();
        var observation = list(state, ".", 200, true, true);
        assertTrue(observation.directoryObservation().complete());
        assertEquals(90, observation.directoryObservation().returnedEntries());
        assertTrue(observation.summary().contains("tool outcome summary truncated"));
        var loop = new ToolCallLoop.LoopResult("All entries are shown in this complete answer.", 1, 1,
                List.of("talos.list_dir"), state.messages, 0, 0, false, 0, List.of(), 0, 0, 0, 0, null,
                state.toolOutcomes, Map.of(), false, null, null, null, ReadEvidenceCoverage.capture(state));
        var plan = CurrentTurnPlan.create(contract, ExecutionPhase.INSPECT, List.of("talos.list_dir"), List.of(), List.of());
        var assessment = WorkspaceResultEvidencePolicy.assess(plan, loop, workspace);
        assertEquals(Status.SATISFIED, assessment.aggregate().status(), "Acquisition itself was complete.");
        String composed = assessment.compose(loop.finalAnswer());
        assertTrue(composed.contains("not empty"), composed);
        assertTrue(composed.contains("tool outcome summary truncated"), composed);
        assertFalse(composed.contains("All entries are shown"), composed);
    }

    @Test void wrongTargetAndMissingMetadataRemainUnknown() throws Exception {
        Files.createDirectory(workspace.resolve("sub"));
        var wrong = state();
        list(wrong, "sub", 200, true, true);
        assertEquals(Status.UNSATISFIED, status(wrong));
        var legacy = state();
        list(legacy, ".", 200, true, false);
        assertEquals(Status.UNSATISFIED, status(legacy));
    }

    @Test void withheldAndChangedProtocolCallDoNotEstablishEvidence() {
        var withheld = state();
        list(withheld, ".", 200, false, true);
        assertEquals(Status.UNSATISFIED, status(withheld));
        var changed = state();
        list(changed, ".", 200, true, true);
        changed.messages.set(0, ChatMessage.assistantWithToolCalls("", List.of(
                new ChatMessage.NativeToolCall("listing-0", "talos.list_dir", Map.of("path", "elsewhere")))));
        assertEquals(Status.UNSATISFIED, status(changed));
    }

    @Test void evidenceCannotBeReusedInAnotherTurnOrWorkspace() {
        var state = state();
        list(state, ".", 200, true, true);
        var captured = ReadEvidenceCoverage.capture(state);
        assertEquals(Status.UNSATISFIED, captured.assessDirectory(state.toolOutcomes,
                workspace.resolve("other"), contract, ".").result().status());
        LocalTurnTraceCapture.clear();
        assertEquals(Status.UNSATISFIED, captured.assessDirectory(state.toolOutcomes,
                workspace, contract, ".").result().status());
    }

    @Test void markedPreExecutionRejectionPreservesAdmittedDirectoryObservation() {
        for (String code : List.of(ToolError.DENIED, ToolError.INVALID_AUTHORITY)) {
            for (boolean nativeCarrier : List.of(true, false)) {
                var state = state();
                var observed = list(state, ".", 200, true, true);
                var rejected = mutation(state, false, code, ToolMutationEffects.none(), true, nativeCarrier);
                var assessment = ReadEvidenceCoverage.capture(state).assessDirectory(
                        state.toolOutcomes, workspace, contract, ".");
                assertEquals(Status.SATISFIED, assessment.result().status());
                assertSame(observed, assessment.observation());
                assertFalse(rejected.success());
                assertTrue(rejected.mutating());
                assertEquals(code, rejected.errorCode(), "Operational outcome flags are retained.");
            }
        }
    }

    @Test void unmarkedDenialAuthorityFailureAndFailedExecutionCannotProveNonExecution() {
        for (String code : List.of(ToolError.DENIED, ToolError.INVALID_AUTHORITY, ToolError.TOOL_ERROR)) {
            var state = state();
            list(state, ".", 200, true, true);
            mutation(state, false, code, ToolMutationEffects.none(), false, true);
            assertEquals(Status.UNSATISFIED, status(state), code);
        }
    }

    @Test void successfulAppliedAndUncertainExecutionCannotBeMarkedAsPreExecutionRejection() {
        var successful = state();
        list(successful, ".", 200, true, true);
        mutation(successful, true, "", ToolMutationEffects.none(), true, true);
        assertEquals(Status.UNSATISFIED, status(successful));
        for (var effects : List.of(ToolMutationEffects.applied("changed.txt"),
                ToolMutationEffects.uncertain("changed.txt"))) {
            var state = state();
            list(state, ".", 200, true, true);
            mutation(state, false, ToolError.DENIED, effects, true, true);
            assertEquals(Status.UNSATISFIED, status(state), effects.toString());
        }
    }

    @Test void rejectionWithMissingChangedOrDuplicatedCarrierDoesNotPreserveEvidence() {
        for (int defect = 0; defect < 4; defect++) {
            var state = state();
            list(state, ".", 200, true, true);
            var rejected = mutation(state, false, ToolError.DENIED, ToolMutationEffects.none(), true, true);
            int resultIndex = state.messages.size() - 1;
            switch (defect) {
                case 0 -> state.messages.removeLast();
                case 1 -> state.messages.set(resultIndex, ChatMessage.toolResult("other-id", "rejected"));
                case 2 -> state.messages.set(resultIndex,
                        ChatMessage.toolResult(state.messages.getLast().toolCallId(), "changed result"));
                case 3 -> state.messages.add(state.messages.getLast());
            }
            assertFalse(state.toolOutcomeResults.preExecutionRejected(rejected, state.toolOutcomes, state.messages));
            assertEquals(Status.UNSATISFIED, status(state), "Carrier defect " + defect);
        }
    }

    @Test void structurallyEqualCopiedRejectionCannotInheritRuntimeIdentityProof() {
        var state = state();
        list(state, ".", 200, true, true);
        var rejected = mutation(state, false, ToolError.DENIED, ToolMutationEffects.none(), true, true);
        var captured = ReadEvidenceCoverage.capture(state);
        var copy = rejected.withMutationEffects(rejected.mutationEffects());
        assertEquals(rejected, copy);
        assertNotSame(rejected, copy);
        state.toolOutcomes.set(state.toolOutcomes.size() - 1, copy);
        state.toolOutcomeResults.bind(copy, state.messages.size() - 1, state.messages.getLast());
        assertEquals(Status.UNSATISFIED, status(state));
        assertEquals(Status.UNSATISFIED, captured.assessDirectory(
                state.toolOutcomes, workspace, contract, ".").result().status());
    }

    @Test void capturedRejectionProofIsFrozenAndDuplicateOccurrencesRemainInvalid() {
        var state = state();
        list(state, ".", 200, true, true);
        var rejected = mutation(state, false, ToolError.DENIED, ToolMutationEffects.none(), false, true);
        var beforeMark = ReadEvidenceCoverage.capture(state);
        state.toolOutcomeResults.bindPreExecutionRejection(rejected);
        assertEquals(Status.UNSATISFIED, beforeMark.assessDirectory(
                state.toolOutcomes, workspace, contract, ".").result().status());
        assertEquals(Status.SATISFIED, status(state));
        state.toolOutcomes.add(rejected);
        assertEquals(Status.UNSATISFIED, status(state));
    }

    @Test void preExecutionRejectionDoesNotOverrideRecordedMutationSinceStart() {
        var state = state();
        list(state, ".", 200, true, true);
        mutation(state, false, ToolError.DENIED, ToolMutationEffects.none(), true, true);
        state.mutationSinceStart = true;
        assertEquals(Status.UNSATISFIED, status(state));
    }

    /** Synthetic mutation outcomes test provenance, not physical writes or executor behavior. */
    private ToolOutcome mutation(LoopState state, boolean success, String code, ToolMutationEffects effects,
                                 boolean marked, boolean nativeCarrier) {
        String id = "mutation-" + state.messages.size();
        var outcome = new ToolOutcome("talos.write_file", "changed.txt", success, true,
                ToolError.DENIED.equals(code), "", success ? "" : "rejected", null, code,
                null, null, null, List.of(), id, effects);
        if (nativeCarrier) state.messages.add(ChatMessage.assistantWithToolCalls("", List.of(
                new ChatMessage.NativeToolCall(id, "talos.write_file", Map.of("path", "changed.txt")))));
        state.messages.add(nativeCarrier ? ChatMessage.toolResult(id, "rejected") : ChatMessage.user("rejected"));
        state.toolOutcomes.add(outcome);
        state.toolOutcomeResults.bind(outcome, state.messages.size() - 1, state.messages.getLast());
        if (marked) state.toolOutcomeResults.bindPreExecutionRejection(outcome);
        return outcome;
    }

    @Test void capturedPurposeSelectsItsAnchorIndependentlyOfLegacyTaskType() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "Project source anchor");
        var qa = new TaskContract(TaskType.READ_ONLY_QA, false, false, false, Set.of(), Set.of(),
                "Where is this workspace and what does this project do?");
        TurnTaskContractCapture.set(qa);
        var inventory = RequestedResultInventory.compile(qa);
        TurnTaskContractCapture.setRequestedResults(inventory);
        assertSame(inventory, TurnTaskContractCapture.requestedResults());
        var state = state();
        var assessment = ReadEvidenceCoverage.capture(state).assessProjectSummary(state.toolOutcomes, workspace, qa);
        assertEquals(Status.UNSATISFIED, assessment.result().status());
        assertEquals(List.of("README.md"), assessment.recoverableReadTargets());
        assertThrows(IllegalArgumentException.class, () -> TurnTaskContractCapture.setRequestedResults(
                RequestedResultInventory.compile(TaskContract.unknown("another request"))));
    }

    @Test void typedDirectoryMetadataSurvivesStrictSerializationAndOldMissingMetadataRemainsUnknown() throws Exception {
        var json = new ObjectMapper();
        var result = new ListDirTool().execute(new ToolCall("talos.list_dir", Map.of("path", ".")),
                new ToolContext(workspace, new Sandbox(workspace, Map.of()), new Config()));
        ToolResult restored = json.readValue(json.writeValueAsString(result), ToolResult.class);
        assertEquals(result.contentMetadata().directoryObservation(), restored.contentMetadata().directoryObservation());
        assertNotNull(result.contentMetadata().withModelHandoffAllowed(false, "withheld").directoryObservation());
        var oldResult = (ObjectNode) json.valueToTree(result);
        ((ObjectNode) oldResult.get("contentMetadata")).remove("directoryObservation");
        assertNull(json.treeToValue(oldResult, ToolResult.class).contentMetadata().directoryObservation());
        ((ObjectNode) oldResult.get("contentMetadata")).put("unrecognizedCompleteness", true);
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> json.treeToValue(oldResult, ToolResult.class));

        var state = state();
        var outcome = list(state, ".", 200, true, true);
        var restoredOutcome = json.readValue(json.writeValueAsString(outcome), ToolOutcome.class);
        assertEquals(outcome.directoryObservation(), restoredOutcome.directoryObservation());
        var captured = ReadEvidenceCoverage.capture(state);
        assertEquals(Status.UNSATISFIED, captured.assessDirectory(List.of(restoredOutcome),
                workspace, contract, ".").result().status(), "Deserialization cannot mint a current executed occurrence.");
        var oldOutcome = (ObjectNode) json.valueToTree(outcome);
        oldOutcome.remove("directoryObservation");
        assertNull(json.treeToValue(oldOutcome, ToolOutcome.class).directoryObservation());
    }
}
