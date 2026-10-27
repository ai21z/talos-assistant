package dev.talos.cli.modes;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.task.WorkspaceTargetReconciler;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidenceTestSupport;
import dev.talos.runtime.verification.ReadOnlyWebAnswerAssessment;
import dev.talos.runtime.verification.ReadOnlyWebDiagnosticAnswer;
import dev.talos.runtime.verification.ReadOnlyWebDiagnosticResult;
import dev.talos.runtime.verification.StaticTaskVerifier;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyWebDiagnosticSelectionTest {
    private static final String REQUEST = "Check whether this website has mismatches between HTML classes/IDs "
            + "and the selectors used in CSS or JavaScript. Do not change anything yet.";
    private static final String SUPPORTED = "`#calc` is missing from index.html. "
            + "The correct selector is `#calculate`.";
    private static final List<String> WEB_READS = List.of("index.html", "style.css", "script.js");

    @AfterEach
    void capturedContractIsCleared() {
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    @Test
    void displayedPrefixIsNotConvertedIntoLimitedSelection(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        String answer = ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX + "\n\nConfiguration data is stored locally.";
        Turn turn = turn(workspace, "Summarize README.md.", answer, List.of("README.md"), 0);

        ReadOnlyWebDiagnosticResult selected = select(workspace, turn, answer);

        assertEquals(answer, selected.answer());
        assertEquals(ReadOnlyWebAnswerAssessment.notApplicable(), selected.assessment());
        assertTrue(selected.diagnostics().isEmpty());
        assertFalse(selected.assessment().limited());
    }

    @Test
    void nonWebOutcomeIsNotDowngradedByDisplayedPrefix(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        String answer = ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX + "\n\nConfiguration data is stored locally.";
        Turn turn = turn(workspace, "Summarize README.md.", answer, List.of("README.md"), 0);

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                answer, turn.plan(), turn.messages(), turn.loop(), workspace, 0);

        assertEquals(answer, outcome.finalAnswer());
        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
        assertFalse(outcome.advisoryOnly());
        assertFalse(outcome.webDiagnosticGroundedOverride());
        assertFalse(outcome.selectorGroundedOverride());
        assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
    }

    @Test
    void limitedSelectionIsRetainedBySelectorOverride(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        String answer = "The correct selector is `#calc`.";
        Turn turn = turn(workspace, REQUEST, answer, WEB_READS, 0);
        ReadOnlyWebDiagnosticResult selected = select(workspace, turn, answer);

        assertTrue(selected.assessment().limited());
        assertNotEquals(answer, selected.answer());
        assertFalse(selected.diagnostics().isEmpty());
        assertEquals(selected.answer(), override(workspace, turn, selected.answer(), selected));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                answer, turn.plan(), turn.messages(), turn.loop(), workspace, 0);

        assertEquals(selected.answer(), outcome.finalAnswer());
        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertTrue(outcome.webDiagnosticGroundedOverride());
        assertFalse(outcome.selectorGroundedOverride());
    }

    @Test
    void copiedSelectionWithUnboundRenderedWordingIsNotRetained(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        String answer = "The correct selector is `#calc`.";
        Turn turn = turn(workspace, REQUEST, answer, WEB_READS, 0);
        ReadOnlyWebDiagnosticResult selected = select(workspace, turn, answer);
        assertTrue(selected.assessment().limited());
        String alternate = "A selector mismatch was found in the inspected files.";
        ReadOnlyWebDiagnosticResult rendered = new ReadOnlyWebDiagnosticResult(
                alternate, selected.assessment(), selected.diagnostics());

        assertFalse(alternate.startsWith(ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX));
        assertNotEquals(alternate, override(workspace, turn, alternate, rendered));
    }

    @Test
    void pathObligationCannotBypassExplicitSelectorDiagnosis(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        String request = "Read index.html and script.js. Diagnose the selector mismatch because "
                + "#calc does not exist in the HTML. Do not change anything.";
        Turn turn = turn(workspace, request, SUPPORTED, List.of("index.html", "script.js"), 0, false);
        assertEquals(TaskType.DIAGNOSE_ONLY, turn.plan().taskContract().type());
        assertEquals("PATH_EXISTENCE_EVIDENCE_REQUIRED", turn.plan().evidenceObligation());
        assertTrue(turn.loop().readOnlyFinalizationEvidence().available());
        assertTrue(select(workspace, turn, SUPPORTED).assessment().preserved());

        ReadOnlyWebDiagnosticResult selected = select(workspace, turn, "There are no mismatches.");

        assertEquals(ReadOnlyWebAnswerAssessment.Reason.SOURCE_EVIDENCE_UNAVAILABLE,
                selected.assessment().reason());
        assertTrue(selected.assessment().limited());
        assertTrue(selected.diagnostics().isEmpty());

        Files.delete(workspace.resolve("script.js"));
        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                SUPPORTED, turn.plan(), turn.messages(), turn.loop(), workspace, 0);
        assertEquals(SUPPORTED, outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("[Path existence verified]"));
    }

    @Test
    void boundDiagnosisKeepsCapturedContractAcrossSyntheticTails(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        Turn turn = turn(workspace, REQUEST, SUPPORTED, WEB_READS, 0);
        var messages = new ArrayList<>(turn.messages());
        messages.add(ChatMessage.user("Synthesis retry, explain this workspace instead."));
        turn = new Turn(turn.plan(), messages, turn.loop());

        ReadOnlyWebDiagnosticResult selected = select(workspace, turn, SUPPORTED);
        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                SUPPORTED, turn.plan(), turn.messages(), turn.loop(), workspace, 0);

        assertTrue(selected.assessment().preserved());
        assertFalse(selected.association().selectionId().isBlank());
        assertEquals(SUPPORTED, outcome.finalAnswer());
        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        assertFalse(outcome.selectorGroundedOverride());
    }

    @Test
    void preservedSelectionIsRetainedBySelectorOverride(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        Turn turn = turn(workspace, REQUEST, SUPPORTED, WEB_READS, 0);
        ReadOnlyWebDiagnosticResult selected = select(workspace, turn, SUPPORTED);

        assertTrue(selected.assessment().preserved());
        assertEquals(SUPPORTED, selected.answer());
        assertEquals(SUPPORTED, override(workspace, turn, SUPPORTED, selected));
    }

    @Test
    void selectionForDifferentAnswerIsNotAcceptedBySelectorOverride(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        Turn turn = turn(workspace, REQUEST, SUPPORTED, WEB_READS, 0);
        ReadOnlyWebDiagnosticResult selected = select(workspace, turn, SUPPORTED);
        assertTrue(selected.assessment().preserved());
        String changed = "The correct selector is `#calc`.";

        String result = override(workspace, turn, changed, selected);

        assertNotEquals(changed, result);
        assertEquals(StaticTaskVerifier.limitedReadOnlyWebDiagnostics(
                ReadOnlyWebAnswerAssessment.Reason.SOURCE_EVIDENCE_UNAVAILABLE).answer(), result);
    }

    @Test
    void selectorOnlyRequestIsPreservedByDirectOverrideAndOutcome(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        Turn turn = turn(workspace, "Explain the selector mismatch.", SUPPORTED,
                List.of("index.html", "script.js"), 0);
        ReadOnlyWebDiagnosticResult selected = select(workspace, turn, SUPPORTED);

        assertTrue(selected.assessment().preserved());
        assertEquals(SUPPORTED, AssistantTurnExecutor.overrideSelectorMismatchAnalysisIfNeeded(
                SUPPORTED, turn.plan(), turn.messages(), turn.loop(), workspace));
        assertEquals(SUPPORTED, override(workspace, turn, SUPPORTED, selected));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                SUPPORTED, turn.plan(), turn.messages(), turn.loop(), workspace, 0);

        assertEquals(SUPPORTED, outcome.finalAnswer());
        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
        assertFalse(outcome.selectorGroundedOverride());
        assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
    }

    @Test
    void completedObservedDiagnosisSurvivesSourceReplacementAndDeletion(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        Turn turn = turn(workspace, REQUEST, SUPPORTED, WEB_READS, 0);
        var selected = select(workspace, turn, SUPPORTED);
        assertTrue(selected.assessment().preserved());

        Files.writeString(workspace.resolve("index.html"), "<script src=\"unobserved.js\"></script>");
        Files.delete(workspace.resolve("script.js"));
        Files.delete(workspace.resolve("style.css"));

        assertEquals(selected, select(workspace, turn, SUPPORTED));
        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                SUPPORTED, turn.plan(), turn.messages(), turn.loop(), workspace, 0);
        assertEquals(SUPPORTED, outcome.finalAnswer());
        assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
        assertFalse(outcome.advisoryOnly());
    }

    @Test
    void pathOnlyLegacyResultCannotClaimObservedEvidence(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        Turn turn = turn(workspace, REQUEST, SUPPORTED, WEB_READS, 0);
        var prior = turn.loop();
        var legacy = new ToolCallLoop.LoopResult(SUPPORTED, prior.iterations(), prior.toolsInvoked(),
                prior.toolNames(), prior.messages(), 0, 0, false, 0, WEB_READS,
                0, 0, 0, 0, prior.toolOutcomes());
        var selected = select(workspace, new Turn(turn.plan(), turn.messages(), legacy), SUPPORTED);
        assertEquals(ReadOnlyWebAnswerAssessment.Reason.SOURCE_EVIDENCE_UNAVAILABLE,
                selected.assessment().reason());
        assertTrue(selected.diagnostics().isEmpty());
    }

    @Test
    void unresolvedSelectorOnlyContractDoesNotAcquireSynthesisEvidence(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        Turn turn = turn(workspace, "Explain the selector mismatch.", SUPPORTED,
                List.of("index.html", "script.js"), 0, false);
        assertFalse(turn.loop().readOnlyFinalizationEvidence().available());
        assertEquals(ReadOnlyWebAnswerAssessment.Reason.SOURCE_EVIDENCE_UNAVAILABLE,
                select(workspace, turn, SUPPORTED).assessment().reason());
    }

    @Test
    void deniedReadStillOverridesAnOtherwiseBoundAnswer(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        Turn turn = turn(workspace, REQUEST, SUPPORTED, WEB_READS, 0);
        var prior = turn.loop();
        var outcomes = new ArrayList<>(prior.toolOutcomes());
        outcomes.add(new ToolCallLoop.ToolOutcome("talos.read_file", "private.js",
                false, false, true, "Permission was denied.", ""));
        var denied = new ToolCallLoop.LoopResult(SUPPORTED, prior.iterations(), prior.toolsInvoked(),
                prior.toolNames(), prior.messages(), 1, 0, false, 0, WEB_READS,
                0, 0, 0, 0, null, outcomes, java.util.Map.of(), false, null, null,
                prior.readOnlyFinalizationEvidence());
        var selected = select(workspace, new Turn(turn.plan(), turn.messages(), denied), SUPPORTED);
        assertEquals(ReadOnlyWebAnswerAssessment.Reason.SOURCE_EVIDENCE_UNAVAILABLE,
                selected.assessment().reason());
        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                SUPPORTED, turn.plan(), turn.messages(), denied, workspace, 0);
        assertNotEquals(SUPPORTED, outcome.finalAnswer());
        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, outcome.taskOutcome().completionStatus());
        assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
        assertTrue(outcome.finalAnswer().startsWith("[Read evidence blocked:"));
    }

    @Test
    void unrelatedRequestIsNotAffectedByWebReads(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        String answer = "Configuration data is stored locally.";
        Turn turn = turn(workspace, "Summarize README.md.", answer, WEB_READS, 0);
        ReadOnlyWebDiagnosticResult selected = select(workspace, turn, answer);

        assertEquals(ReadOnlyWebAnswerAssessment.notApplicable(), selected.assessment());
        assertEquals(answer, selected.answer());
        assertEquals(answer, override(workspace, turn, answer, selected));
    }

    @Test
    void mutationRequestIsNotAffectedByReadOnlySelection(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        String answer = "The selector correction was requested.";
        Turn turn = turn(workspace, "Fix the selector mismatch in script.js.", answer, WEB_READS, 0);
        assertTrue(turn.plan().taskContract().mutationRequested());
        ReadOnlyWebDiagnosticResult selected = select(workspace, turn, answer);

        assertEquals(ReadOnlyWebAnswerAssessment.notApplicable(), selected.assessment());
        assertEquals(answer, selected.answer());
        assertEquals(answer, override(workspace, turn, answer, selected));
    }

    @Test
    void successfulMutationIsNotAffectedByReadOnlySelection(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        String answer = "A mutation was reported by the tool outcome.";
        Turn turn = turn(workspace, REQUEST, answer, WEB_READS, 1);
        ReadOnlyWebDiagnosticResult selected = select(workspace, turn, answer);

        assertEquals(ReadOnlyWebAnswerAssessment.notApplicable(), selected.assessment());
        assertEquals(answer, selected.answer());
        assertEquals(answer, override(workspace, turn, answer, selected));
    }

    private static ReadOnlyWebDiagnosticResult select(Path workspace, Turn turn, String answer) {
        return AssistantTurnExecutor.selectReadOnlyWebDiagnosticsIfNeeded(
                answer, turn.plan(), turn.messages(), turn.loop(), workspace);
    }

    private static String override(
            Path workspace, Turn turn, String answer, ReadOnlyWebDiagnosticResult selected) {
        return AssistantTurnExecutor.overrideSelectorMismatchAnalysisIfNeeded(
                answer, turn.plan(), turn.messages(), turn.loop(), workspace, selected);
    }

    private static Turn turn(
            Path workspace, String request, String answer, List<String> reads, int mutations) throws Exception {
        return turn(workspace, request, answer, reads, mutations, true);
    }

    private static Turn turn(
            Path workspace, String request, String answer, List<String> reads, int mutations,
            boolean supportedDiagnosisFixture) throws Exception {
        List<ChatMessage> messages = List.of(ChatMessage.system("sys"), ChatMessage.user(request));
        LocalTurnTraceCapture.begin("trc-observed-selection", "sid-observed-selection", 1,
                "2026-09-13T00:00:00Z", "workspace-hash", "ask", "test", "test-model", request);
        var contract = WorkspaceTargetReconciler.reconcile(TaskContractResolver.fromUserRequest(request), workspace);
        if (supportedDiagnosisFixture && (request.equals(REQUEST)
                || request.equals("Explain the selector mismatch."))) {
            contract = new TaskContract(TaskType.DIAGNOSE_ONLY, contract.mutationRequested(),
                    contract.mutationAllowed(), contract.verificationRequired(), java.util.Set.copyOf(reads),
                    contract.forbiddenTargets(), request);
        }
        CurrentTurnPlan plan = CurrentTurnPlan.create(contract, ExecutionPhase.INSPECT,
                List.of("talos.read_file"), List.of("talos.read_file"), List.of());
        var outcomes = new ArrayList<ToolCallLoop.ToolOutcome>();
        var rawSources = new LinkedHashMap<String, String>();
        for (String read : reads) {
            rawSources.put(read, Files.readString(workspace.resolve(read)));
            outcomes.add(new ToolCallLoop.ToolOutcome("talos.read_file", read, true, false, false,
                    Files.readString(workspace.resolve(read)), ""));
        }
        if (mutations > 0) {
            outcomes.add(new ToolCallLoop.ToolOutcome("talos.edit_file", "script.js", true, false, false,
                    "A file edit was reported.", ""));
        }
        ToolCallLoop.LoopResult loop = new ToolCallLoop.LoopResult(answer, outcomes.size(), outcomes.size(),
                outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(), List.of(),
                0, 0, false, mutations, reads, 0, 0, 0, 0, null, outcomes,
                java.util.Map.of(), false, null, null,
                ReadOnlyFinalizationEvidenceTestSupport.evidence(workspace, contract, answer, rawSources));
        if (supportedDiagnosisFixture && (request.equals(REQUEST)
                || request.equals("Explain the selector mismatch."))) {
            assertTrue(loop.readOnlyFinalizationEvidence().available(),
                    contract + " / " + loop.readOnlyFinalizationEvidence().reason());
        }
        return new Turn(plan, messages, loop);
    }

    private static void fixture(Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "Configuration data is stored locally.\n");
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html><head><link rel="stylesheet" href="style.css"></head><body>
                <button id="calculate" class="action">Calculate</button>
                <script src="script.js"></script></body></html>
                """);
        Files.writeString(workspace.resolve("style.css"), ".action { color: black; }\n");
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('#calc');\n");
    }

    private record Turn(CurrentTurnPlan plan, List<ChatMessage> messages, ToolCallLoop.LoopResult loop) {}
}
