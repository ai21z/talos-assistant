package dev.talos.cli.modes;

import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.EvidenceObligationAssessment;
import dev.talos.runtime.policy.EvidenceObligationVerifier;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.WorkspaceTargetReconciler;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence;
import dev.talos.runtime.toolcall.RejectedProposalSynthesisTestSupport;
import dev.talos.runtime.toolcall.TerminalReadOnlyStopAnswer;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.runtime.verification.ReadOnlyWebAnswerAssessment;
import dev.talos.runtime.verification.TaskVerificationStatus;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Failed answer generation is finalized independently of successful source inspection. */
class RejectedProposalSynthesisIntegrationTest {
    @TempDir Path workspace;
    private final List<LlmClient> clients = new ArrayList<>();
    private static final String REQUEST = "Please read README.md, index.html, script.js, scripts.js and notes.md. "
            + "Explain exactly why the Calculate button is broken. Identify the selector that should be used, "
            + "calculate the BMI for 72 kg and 1.8 m, and explain how script.js differs from scripts.js. "
            + "Do not edit files or claim tests were run.";

    @AfterEach void clear() {
        clients.forEach(LlmClient::close);
        TurnTaskContractCapture.clear();
        dev.talos.runtime.TurnAuditCapture.end();
        dev.talos.runtime.TurnUserRequestCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    @Test void providerFailurePreservesCompletedReadsWithoutRestoringAnAnswer() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingThenFailureWithContextWindow(List.of(), 8192,
                new EngineException.MalformedResponse("synthesis fixture", "malformed provider response"));
        Turn turn = turn(recorded.client());
        assertRejectedWithObservedReads(turn, recorded.requests().size());
        ExecutionOutcome outcome = finish(turn, turn.loop());
        assertSynthesisLimited(outcome);
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(outcome.finalAnswer());
        var trace = LocalTurnTraceCapture.complete();
        assertEquals("ADVISORY_ONLY", trace.outcome().status());
        assertEquals("ADVISORY_ONLY", trace.outcome().classification());
        assertEquals("NOT_RUN", trace.outcome().verificationStatus());
        assertTrue(trace.events().stream().anyMatch(event -> "READ_ONLY_WEB_ANSWER_ASSESSED".equals(event.type())
                && "SYNTHESIS_UNAVAILABLE".equals(event.data().get("reasonCode"))));
        assertTrue(trace.events().stream().filter(event -> "OUTCOME_EVALUATED".equals(event.type())
                        || "OUTCOME_RENDERED".equals(event.type()))
                .noneMatch(event -> "COMPLETE".equals(event.data().get("status"))));
        assertEquals(1, recorded.requests().size());
    }

    @Test void providerLengthPreservesTheSameTruthfulLimitedFinalization() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Incomplete provider answer with 99.", List.of(), "length")), 8192);
        Turn turn = turn(recorded.client());
        assertRejectedWithObservedReads(turn, recorded.requests().size());
        assertSynthesisLimited(finish(turn, turn.loop()));
        assertEquals(1, recorded.requests().size());
    }

    @Test void missingActualReadCannotUseTheSynthesisFailureExceptionAsCoverage() throws Exception {
        Turn turn = lengthTurn();
        List<ToolCallLoop.ToolOutcome> missing = turn.loop().toolOutcomes().stream()
                .filter(outcome -> !outcome.pathHint().equals("notes.md")).toList();
        var loop = replace(turn.loop(), missing, turn.loop().readOnlySynthesisStatus(),
                turn.loop().failureDecision(), turn.loop().readOnlyFinalizationEvidence().invalidate());
        assertTrue(loop.readOnlyFinalizationEvidence().proposalSelected());
        assertFalse(loop.readOnlyFinalizationEvidence().available());
        assertTrue(EvidenceObligationAssessment.assessObserved(turn.plan(), loop).missingEvidence());
        assertNotEquals(ReadOnlyWebAnswerAssessment.Reason.SYNTHESIS_UNAVAILABLE,
                AssistantTurnExecutor.selectReadOnlyWebDiagnosticsIfNeeded(loop.finalAnswer(), turn.plan(),
                        turn.messages(), loop, workspace).assessment().reason());
        ExecutionOutcome outcome = finish(turn, loop);
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        assertFalse(outcome.finalAnswer().contains("Read evidence complete"));
    }

    @Test void deniedFailedAndMutatingOutcomesRemainControlling() throws Exception {
        Turn turn = lengthTurn();
        for (var adverse : List.of(
                new ToolCallLoop.ToolOutcome("talos.read_file", "private.js", false, false, true, "denied", ""),
                new ToolCallLoop.ToolOutcome("talos.read_file", "notes.md", false, false, false, "read failed", ""),
                new ToolCallLoop.ToolOutcome("talos.write_file", "script.js", true, true, false, "changed", ""))) {
            var outcomes = new ArrayList<>(turn.loop().toolOutcomes());
            outcomes.add(adverse);
            var loop = replace(turn.loop(), outcomes, turn.loop().readOnlySynthesisStatus(),
                    turn.loop().failureDecision(), turn.loop().readOnlyFinalizationEvidence());
            assertFalse(TerminalReadOnlyStopAnswer.rejectedProposalSynthesis(loop));
            assertNotEquals(EvidenceObligationVerifier.Status.SATISFIED,
                    EvidenceObligationAssessment.assessObserved(turn.plan(), loop).result().status());
            var selected = AssistantTurnExecutor.selectReadOnlyWebDiagnosticsIfNeeded(loop.finalAnswer(), turn.plan(),
                    turn.messages(), loop, workspace);
            assertNotEquals(ReadOnlyWebAnswerAssessment.Reason.SYNTHESIS_UNAVAILABLE, selected.assessment().reason());
            if (adverse.denied()) {
                ExecutionOutcome outcome = finish(turn, loop);
                assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
                assertFalse(outcome.finalAnswer().contains("Read evidence complete"));
            }
        }
    }

    @Test void invalidatedCandidateOrLegacyFallbackCannotManufactureTheException() throws Exception {
        Turn turn = lengthTurn();
        var prior = turn.loop();
        List<ToolCallLoop.LoopResult> controls = List.of(
                replace(prior, prior.toolOutcomes(), ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED,
                        prior.failureDecision(), prior.readOnlyFinalizationEvidence().invalidate()),
                replace(prior, prior.toolOutcomes(), ToolCallLoop.ReadOnlySynthesisStatus.REJECTED,
                        FailureDecision.stop(FailureAction.ASK_USER, "unrelated stop"), prior.readOnlyFinalizationEvidence()),
                replace(prior, prior.toolOutcomes(), ToolCallLoop.ReadOnlySynthesisStatus.REJECTED,
                        prior.failureDecision(), ReadOnlyFinalizationEvidence.empty()),
                replace(prior, List.of(), ToolCallLoop.ReadOnlySynthesisStatus.REJECTED,
                        prior.failureDecision(), prior.readOnlyFinalizationEvidence()));
        for (var loop : controls) {
            assertFalse(TerminalReadOnlyStopAnswer.rejectedProposalSynthesis(loop));
            assertTrue(EvidenceObligationAssessment.assessObserved(turn.plan(), loop).missingEvidence());
            assertNotEquals(ReadOnlyWebAnswerAssessment.Reason.SYNTHESIS_UNAVAILABLE,
                    AssistantTurnExecutor.selectReadOnlyWebDiagnosticsIfNeeded(loop.finalAnswer(), turn.plan(),
                            turn.messages(), loop, workspace).assessment().reason());
        }
    }

    @Test void presentationWithholdingCannotRestoreTheFailedSynthesisText() throws Exception {
        Turn turn = lengthTurn();
        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(turn.loop().finalAnswer(), turn.plan(),
                turn.messages(), turn.loop(), workspace, 0, false, (answer, components) -> {
                    assertFalse(components.active());
                    return "[answer withheld]";
                });
        assertEquals("[answer withheld]", outcome.finalAnswer());
        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
    }

    private Turn lengthTurn() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("", List.of(), "length")), 8192);
        Turn turn = turn(recorded.client());
        assertRejectedWithObservedReads(turn, recorded.requests().size());
        return turn;
    }

    private Turn turn(LlmClient client) throws Exception {
        clients.add(client);
        var sources = sources();
        for (var source : sources.entrySet()) Files.writeString(workspace.resolve(source.getKey()), source.getValue());
        LocalTurnTraceCapture.begin("rejected-proposal-integration", "local-test", 1,
                "2026-09-13T00:00:00Z", "workspace-hash", "ask", "test", "test-model", REQUEST);
        var contract = WorkspaceTargetReconciler.reconcile(TaskContractResolver.fromUserRequest(REQUEST), workspace);
        assertEquals(REQUEST, contract.originalUserRequest());
        assertFalse(contract.mutationRequested());
        assertFalse(contract.mutationAllowed());
        TurnTaskContractCapture.set(contract);
        dev.talos.runtime.TurnAuditCapture.begin();
        dev.talos.runtime.TurnUserRequestCapture.set(REQUEST);
        var plan = CurrentTurnPlan.create(contract, ExecutionPhase.INSPECT,
                List.of("talos.read_file"), List.of("talos.read_file"), List.of());
        var loop = RejectedProposalSynthesisTestSupport.reject(workspace, contract, client, sources);
        return new Turn(plan, List.of(ChatMessage.system("sys"), ChatMessage.user(REQUEST)), loop);
    }

    private static void assertRejectedWithObservedReads(Turn turn, int requests) {
        var loop = turn.loop();
        assertEquals(1, requests);
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.REJECTED, loop.readOnlySynthesisStatus());
        assertTrue(loop.failureDecision().shouldStop());
        assertTrue(TerminalReadOnlyStopAnswer.rejectedProposalSynthesis(loop));
        assertTrue(loop.readOnlyFinalizationEvidence().proposalSelected());
        assertFalse(loop.readOnlyFinalizationEvidence().available());
        assertTrue(loop.readOnlyFinalizationEvidence().sources().isEmpty());
        assertTrue(loop.readOnlyFinalizationEvidence().generationId().isEmpty());
        assertEquals(6, loop.toolOutcomes().size());
        assertEquals(1, loop.toolOutcomes().stream()
                .filter(outcome -> outcome.toolName().equals("talos.compare_documents")).count());
        assertEquals(EvidenceObligationVerifier.Status.SATISFIED,
                EvidenceObligationAssessment.assessObserved(turn.plan(), loop).result().status());
    }

    private static void assertSynthesisLimited(ExecutionOutcome outcome) {
        assertEquals(TerminalReadOnlyStopAnswer.synthesisUnavailableAnswer(), outcome.finalAnswer());
        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
        assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
        assertEquals(TaskVerificationStatus.NOT_RUN, outcome.taskOutcome().verificationResult().status());
        assertTrue(outcome.verificationReport().authoritativeProofKinds().isEmpty());
        assertFalse(outcome.finalAnswer().contains("did not inspect"));
        assertFalse(outcome.finalAnswer().contains("evidence was not gathered"));
        assertFalse(outcome.finalAnswer().contains("99"));
    }

    private ExecutionOutcome finish(Turn turn, ToolCallLoop.LoopResult loop) {
        return ExecutionOutcome.fromToolLoop(loop.finalAnswer(), turn.plan(), turn.messages(), loop, workspace, 0, false);
    }

    private static ToolCallLoop.LoopResult replace(ToolCallLoop.LoopResult prior,
            List<ToolCallLoop.ToolOutcome> outcomes, ToolCallLoop.ReadOnlySynthesisStatus status,
            FailureDecision failure, ReadOnlyFinalizationEvidence evidence) {
        return new ToolCallLoop.LoopResult(prior.finalAnswer(), prior.iterations(), prior.toolsInvoked(),
                prior.toolNames(), prior.messages(), prior.failedCalls(), prior.retriedCalls(), prior.hitIterLimit(),
                prior.mutatingToolSuccesses(), prior.readPaths(), 0, 0, 0, 0, failure, outcomes, prior.readFileBodies(),
                prior.finalAnswerOutputLimitReached(), status, prior.readOnlyEvidenceHandoff(), evidence);
    }

    private static Map<String, String> sources() {
        var sources = new LinkedHashMap<String, String>();
        sources.put("README.md", "# Calculator review fixture\n\n"
                + "The calculator in index.html loads script.js. scripts.js is an unrelated sentinel and must stay unchanged. "
                + "The Calculate button should compute BMI as weight divided by height squared. This fixture is local test data.\n");
        sources.put("index.html", """
                <!doctype html>
                <html lang="en">
                <head><meta charset="utf-8"><title>BMI fixture</title></head>
                <body>
                <label>Weight (kg)<input id="weight" value="72"></label>
                <label>Height (m)<input id="height" value="1.8"></label>
                <button id="calculate">Calculate</button>
                <p id="result"></p>
                <script src="script.js"></script>
                </body>
                </html>
                """);
        sources.put("notes.md", "# Review notes\n\nRetention: local.\n");
        sources.put("script.js", """
                const button = document.querySelector('#calc');
                button.addEventListener('click', () => {
                  const weight = Number(document.querySelector('#weight').value);
                  const height = Number(document.querySelector('#height').value);
                  document.querySelector('#result').textContent = (weight / (height * height)).toFixed(1);
                });
                """);
        sources.put("scripts.js", "// Unrelated sentinel. This file is not loaded by index.html.\n"
                + "const sentinel = 'KEEP_UNCHANGED';\n");
        return sources;
    }

    private record Turn(CurrentTurnPlan plan, List<ChatMessage> messages, ToolCallLoop.LoopResult loop) {}
}
