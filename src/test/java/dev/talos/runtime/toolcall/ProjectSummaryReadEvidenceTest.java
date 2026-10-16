package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.ToolCallLoop.ToolOutcome;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.policy.EvidenceObligationVerifier.Status;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContentMetadata;
import dev.talos.tools.ToolContentMetadata.ReadCoverage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic execution metadata checks scope, source selection and chronology. */
class ProjectSummaryReadEvidenceTest {
    @TempDir Path workspace;
    private final TaskContract contract = new TaskContract(TaskType.WORKSPACE_EXPLAIN, false, false, false,
            Set.of(), Set.of(), "Explain this project and its configured port.");

    @BeforeEach void begin() {
        TurnTaskContractCapture.set(contract);
        LocalTurnTraceCapture.begin("summary", "tests", 1, "2026-10-08T00:00:00Z",
                "workspace", "ask", "fixture", "fixture", contract.originalUserRequest());
    }

    @AfterEach void clear() {
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    private LoopState state(boolean anchor) throws Exception {
        if (anchor && !Files.exists(workspace.resolve("README.md")))
            Files.writeString(workspace.resolve("README.md"), "Project facts.\nPort: 8200.\n");
        return new LoopState("", List.of(), new ArrayList<>(), workspace,
                Context.builder(new Config(null)).build(), null, 8, 0);
    }

    private void read(LoopState state, String path, ReadCoverage coverage, boolean success, boolean denied,
                      boolean withheld, boolean nativeCall) {
        String id = "read-" + state.messages.size();
        var outcome = new ToolOutcome("talos.read_file", path, success, false, denied, "", success ? "" : "failed");
        if (nativeCall) state.messages.add(ChatMessage.assistantWithToolCalls("", List.of(
                new ChatMessage.NativeToolCall(id, "talos.read_file", Map.of("path", path)))));
        String content = coverage == ReadCoverage.COMPLETE ? "Project facts.\nPort: 8200.\n" : "Project facts.\n";
        state.messages.add(nativeCall ? ChatMessage.toolResult(id, content) : ChatMessage.user(content));
        state.toolOutcomes.add(outcome);
        state.toolOutcomeResults.bind(outcome, state.messages.size() - 1, state.messages.getLast());
        if (success) {
            var evidence = state.readEvidence.record(new ToolCall("talos.read_file", Map.of("path", path)), path, content);
            state.toolOutcomeResults.bindRead(outcome, evidence, withheld,
                    ToolContentMetadata.normal().withReadCoverage(coverage), withheld ? "Content withheld." : "");
        }
    }

    private void full(LoopState state, String path) { read(state, path, ReadCoverage.COMPLETE, true, false, false, true); }
    private Status status(LoopState state) {
        return ReadEvidenceCoverage.capture(state).assessProjectSummary(state.toolOutcomes, workspace, contract).result().status();
    }
    private void search(LoopState state, String tool, String path, boolean bound) {
        var outcome = new ToolOutcome(tool, "", true, false, false, "source", "", null, "", null, null, null,
                path.isEmpty() ? List.of() : List.of(path));
        String id = "search-" + state.messages.size();
        state.messages.add(ChatMessage.assistantWithToolCalls("", List.of(
                new ChatMessage.NativeToolCall(id, tool, Map.of("query", "Project facts")))));
        state.messages.add(ChatMessage.toolResult(id, "Project facts."));
        state.toolOutcomes.add(outcome);
        if (bound) state.toolOutcomeResults.bind(outcome, state.messages.size() - 1, state.messages.getLast());
        state.toolOutcomeResults.bindSourceAdmission(outcome, true);
    }

    @Test void partialSummaryIsIncompleteButAnExplicitRangeRemainsUsable() throws Exception {
        var state = state(true);
        read(state, "README.md", ReadCoverage.REQUESTED_RANGE, true, false, false, true);
        var evidence = ReadEvidenceCoverage.capture(state);
        assertEquals(Status.UNSATISFIED, status(state));
        assertEquals(Status.SATISFIED, evidence.assess(Set.of("README.md"), Set.of("README.md"),
                state.toolOutcomes, workspace, contract).status());
        assertEquals(List.of("README.md"), evidence.assessProjectSummary(state.toolOutcomes, workspace, contract)
                .recoverableReadTargets());
        String text = evidence.projectSummaryObservations(state.toolOutcomes, workspace, contract);
        assertTrue(text.contains("Partial observed excerpt") && text.contains("Project facts."), text);
        assertFalse(text.contains("8200"), text);
    }

    @Test void fullRefreshResolvesPartialWithoutErasingHistory() throws Exception {
        var state = state(true);
        read(state, "README.md", ReadCoverage.LIMITED, true, false, false, true);
        full(state, "README.md");
        assertEquals(Status.SATISFIED, status(state));
        assertEquals(2, state.toolOutcomes.size());
    }

    @Test void removedAnchorCannotBecomeAnAbsentAnchorFallback() throws Exception {
        var state = state(true);
        full(state, "README.md");
        Files.move(workspace.resolve("README.md"), workspace.resolve("retained.txt"));
        read(state, "README.md", ReadCoverage.UNSPECIFIED, false, false, false, true);
        full(state, "other.txt");
        assertEquals(Status.UNSATISFIED, status(state));
        var evidence = ReadEvidenceCoverage.capture(state);
        assertTrue(evidence.assessProjectSummary(state.toolOutcomes, workspace, contract).recoverableReadTargets().isEmpty());
        String text = evidence.projectSummaryObservations(state.toolOutcomes, workspace, contract);
        assertTrue(text.contains("Earlier observed excerpt") && text.contains("8200"), text);
    }

    @Test void firstSelectionSurvivesScopedContinuation() throws Exception {
        var first = state(true);
        full(first, "README.md");
        var captured = ReadEvidenceCoverage.capture(first);
        Files.move(workspace.resolve("README.md"), workspace.resolve("retained.txt"));
        var second = state(false);
        read(second, "README.md", ReadCoverage.UNSPECIFIED, false, false, false, true);
        full(second, "other.txt");
        var outcomes = new ArrayList<>(first.toolOutcomes);
        outcomes.addAll(second.toolOutcomes);
        var combined = captured.followedBy(ReadEvidenceCoverage.capture(second));
        assertEquals(Status.UNSATISFIED, combined.assessProjectSummary(outcomes, workspace, contract).result().status());
    }

    @Test void initiallyAbsentAnchorAllowsCompleteFallbackButNotKnownPartialRead() throws Exception {
        var state = state(false);
        read(state, "app.txt", ReadCoverage.REQUESTED_RANGE, true, false, false, true);
        assertEquals(Status.UNSATISFIED, status(state));
        full(state, "app.txt");
        assertEquals(Status.SATISFIED, status(state));
    }

    @ParameterizedTest @ValueSource(strings = {"talos.grep", "talos.retrieve"})
    void structuredSearchIsInspectionButCannotEraseKnownReadGap(String tool) throws Exception {
        var state = state(true);
        search(state, tool, "README.md", true);
        assertEquals(Status.SATISFIED, status(state));
        read(state, "README.md", ReadCoverage.UNSPECIFIED, false, false, false, true);
        search(state, tool, "README.md", true);
        assertEquals(Status.UNSATISFIED, status(state));
    }

    @Test void searchRequiresSourceAndCarrierBinding() throws Exception {
        for (String path : List.of("", "unrelated.txt", "README.md")) {
            var state = state(true);
            search(state, "talos.grep", path, !path.equals("README.md"));
            assertEquals(Status.UNSATISFIED, status(state));
        }
    }

    @Test void changedSearchCarrierAndWrongScopeRemainUnavailable() throws Exception {
        var state = state(true);
        search(state, "talos.grep", "README.md", true);
        var captured = ReadEvidenceCoverage.capture(state);
        assertEquals(Status.UNSATISFIED, captured.assessProjectSummary(state.toolOutcomes,
                workspace.resolve("other"), contract).result().status());
        assertEquals(Status.UNSATISFIED, captured.assessProjectSummary(state.toolOutcomes,
                workspace, TaskContract.unknown("other")).result().status());
        var duplicate = new ArrayList<>(state.toolOutcomes);
        duplicate.add(duplicate.getFirst());
        assertEquals(Status.UNSATISFIED, captured.assessProjectSummary(duplicate, workspace, contract).result().status());
        state.messages.set(1, ChatMessage.toolResult("search-0", "altered"));
        assertEquals(Status.UNSATISFIED, status(state));
    }

    @Test void deniedRefreshCannotBeRetriedOrExposeEarlierContent() throws Exception {
        var state = state(true);
        full(state, "README.md");
        read(state, "README.md", ReadCoverage.UNSPECIFIED, false, true, false, true);
        var captured = ReadEvidenceCoverage.capture(state);
        var assessment = captured.assessProjectSummary(state.toolOutcomes, workspace, contract);
        assertEquals(Status.BLOCKED, assessment.result().status());
        assertTrue(assessment.recoverableReadTargets().isEmpty());
        assertTrue(captured.projectSummaryObservations(state.toolOutcomes, workspace, contract).isEmpty());
    }

    @Test void withheldReadCannotBeRetriedOrQuoted() throws Exception {
        var state = state(true);
        full(state, "README.md");
        read(state, "README.md", ReadCoverage.COMPLETE, true, false, true, true);
        var captured = ReadEvidenceCoverage.capture(state);
        assertEquals(Status.UNSATISFIED, status(state));
        assertTrue(captured.assessProjectSummary(state.toolOutcomes, workspace, contract).recoverableReadTargets().isEmpty());
        assertTrue(captured.projectSummaryObservations(state.toolOutcomes, workspace, contract).isEmpty());
    }

    @Test void unboundSourceCannotBeRetriedOrQuoted() throws Exception {
        var state = state(true);
        full(state, "README.md");
        state.messages.set(0, ChatMessage.assistant("proposal missing"));
        var captured = ReadEvidenceCoverage.capture(state);
        assertEquals(Status.UNSATISFIED, status(state));
        assertTrue(captured.assessProjectSummary(state.toolOutcomes, workspace, contract).recoverableReadTargets().isEmpty());
        assertTrue(captured.projectSummaryObservations(state.toolOutcomes, workspace, contract).isEmpty());
    }

    @Test void textReadPreservesExistingFreshnessRestriction() throws Exception {
        var state = state(true);
        read(state, "README.md", ReadCoverage.COMPLETE, true, false, false, false);
        assertEquals(Status.SATISFIED, status(state));
        read(state, "README.md", ReadCoverage.UNSPECIFIED, false, false, false, false);
        read(state, "README.md", ReadCoverage.COMPLETE, true, false, false, false);
        assertEquals(Status.UNSATISFIED, status(state));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void optionalFailedReadDoesNotBecomeASourceRequirement(boolean anchor) throws Exception {
        var state = state(anchor);
        full(state, anchor ? "README.md" : "app.txt");
        read(state, "missing-notes.txt", ReadCoverage.UNSPECIFIED, false, false, false, true);
        assertEquals(Status.SATISFIED, status(state));
        read(state, ".env", ReadCoverage.UNSPECIFIED, false, true, false, true);
        assertEquals(Status.SATISFIED, status(state), "Existing denial outcome handling is separate from summary coverage");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void admittedSecondarySourceRetainsItsKnownGap(boolean anchor) throws Exception {
        var state = state(anchor);
        full(state, anchor ? "README.md" : "app.txt");
        read(state, "settings.txt", ReadCoverage.REQUESTED_RANGE, true, false, false, true);
        assertEquals(Status.UNSATISFIED, status(state));
        full(state, "settings.txt");
        assertEquals(Status.SATISFIED, status(state));
        read(state, "settings.txt", ReadCoverage.UNSPECIFIED, false, false, false, true);
        assertEquals(Status.UNSATISFIED, status(state));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void broadSummaryRetainsBoundPrivacyReason(boolean anchor) throws Exception {
        var state = state(anchor);
        read(state, anchor ? "README.md" : "app.txt", ReadCoverage.COMPLETE, true, false, true, true);
        var captured = ReadEvidenceCoverage.capture(state);
        assertEquals(Status.UNSATISFIED, status(state));
        assertEquals(List.of("Content withheld."), captured.missingPrivacyNotices(state.toolOutcomes, workspace, contract));
        assertTrue(captured.projectSummaryObservations(state.toolOutcomes, workspace, contract).isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unrelatedWithholdingCannotEraseAdmittedSearchOrItsFailedRefresh(boolean anchor) throws Exception {
        var state = state(anchor);
        full(state, anchor ? "README.md" : "app.txt");
        search(state, "talos.grep", "settings.txt", true);
        read(state, "settings.txt", ReadCoverage.UNSPECIFIED, false, false, false, true);
        read(state, "private.txt", ReadCoverage.COMPLETE, true, false, true, true);
        state.contentWithheldFromModelContext = true;
        assertEquals(Status.UNSATISFIED, status(state));
    }

    @Test void withheldSearchCannotSatisfyAnchorInspection() throws Exception {
        var state = state(true);
        var outcome = new ToolOutcome("talos.grep", "", true, false, false, "source", "", null, "", null, null, null,
                List.of("README.md"));
        state.messages.add(ChatMessage.assistantWithToolCalls("", List.of(
                new ChatMessage.NativeToolCall("withheld", "talos.grep", Map.of("query", "facts")))));
        state.messages.add(ChatMessage.toolResult("withheld", "Content withheld."));
        state.toolOutcomes.add(outcome);
        state.toolOutcomeResults.bind(outcome, 1, state.messages.getLast());
        state.toolOutcomeResults.bindSourceAdmission(outcome, false);
        assertEquals(Status.UNSATISFIED, status(state));
    }

    @ParameterizedTest @ValueSource(strings = {"changed", "removed", "duplicated"})
    void invalidSearchBindingCannotEraseSecondarySourceRequirement(String alteration) throws Exception {
        var state = state(true);
        full(state, "README.md");
        search(state, "talos.grep", "settings.txt", true);
        read(state, "settings.txt", ReadCoverage.UNSPECIFIED, false, false, false, true);
        switch (alteration) {
            case "changed" -> state.messages.set(3, ChatMessage.toolResult("search-2", "different"));
            case "removed" -> state.messages.set(2, ChatMessage.assistant("proposal removed"));
            case "duplicated" -> state.messages.add(state.messages.get(3));
            default -> fail("Unknown fixture alteration");
        }
        var evidence = ReadEvidenceCoverage.capture(state);
        var assessment = evidence.assessProjectSummary(state.toolOutcomes, workspace, contract);
        assertEquals(Status.UNSATISFIED, assessment.result().status());
        assertEquals(dev.talos.runtime.policy.ProjectSummaryEvidencePolicy.Reason.ASSOCIATION_UNAVAILABLE,
                assessment.reason());
        assertTrue(assessment.recoverableReadTargets().isEmpty());
        assertTrue(evidence.projectSummaryObservations(state.toolOutcomes, workspace, contract).isEmpty());
    }
}
