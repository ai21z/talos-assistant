package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnUserRequestCapture;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.outcome.TruthWarningType;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.policy.EvidenceObligationAssessment;
import dev.talos.runtime.policy.EvidenceObligationVerifier;
import dev.talos.runtime.task.AnswerResultRequirements;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real read execution and settlement, with scripted provider choices and temporary source files. */
class KnownReadEvidenceOutcomeTest {
    private static final String REQUEST =
            "Read settings.json and explain the current retry configuration. Do not change files.";
    private static final String CONTENT = "{\"retry_limit\": 2}\n";
    private static final String ANSWER = "The current retry_limit setting is 2.";

    @TempDir Path workspace;
    private final List<LlmClient> clients = new ArrayList<>();

    @AfterEach void clear() {
        clients.forEach(LlmClient::close);
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    @Test void failedRefreshCannotSettleFromAnOlderSuccessfulRequiredRead() throws Exception {
        Files.writeString(workspace.resolve("settings.json"), CONTENT);
        Run run = run(REQUEST, ANSWER, List.of(
                read("initial-read", "settings.json"),
                // A changed read allowance requests a real refresh instead of redundant-call suppression.
                read("refresh-read", Map.of("path", "settings.json", "max_lines", 100))),
                index -> { if (index == 1) Files.delete(workspace.resolve("settings.json")); });

        assertRequiredReadPlan(run);
        assertReadSequence(run, List.of(true, false), List.of("settings.json", "settings.json"));
        assertEquals(ToolError.NOT_FOUND, run.loop().toolOutcomes().get(1).errorCode());
        assertEquals(ANSWER, run.loop().finalAnswer(), "The provider supplied the stale confident candidate");
        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, run.outcome().completionStatus(),
                "An actual failed refresh must invalidate completion based on the earlier successful read");
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, run.outcome().taskOutcome().completionStatus());
        assertTrue(run.outcome().finalAnswer().startsWith(EvidenceObligationVerifier.MISSING_EVIDENCE_PREFIX),
                run.outcome().finalAnswer());
        assertFalse(run.outcome().finalAnswer().contains(ANSWER),
                "The incompatible confident conclusion must be replaced, not merely prefixed");
        assertTrue(run.outcome().taskOutcome().warnings().stream()
                .anyMatch(warning -> warning.type() == TruthWarningType.MISSING_EVIDENCE));
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(run.outcome().finalAnswer());
        var trace = LocalTurnTraceCapture.complete();
        assertEquals("ADVISORY_ONLY", trace.outcome().classification());
        assertTrue(trace.warnings().stream().anyMatch(warning -> "MISSING_EVIDENCE".equals(warning.code())));
    }

    @Test void authenticatedFullReadAfterAnInitialFailureRestoresRequiredEvidence() throws Exception {
        Run run = run(REQUEST, ANSWER, List.of(
                read("missing-read", "settings.json"), read("recovered-read", "settings.json")),
                index -> { if (index == 1) Files.writeString(workspace.resolve("settings.json"), CONTENT); });

        assertRequiredReadPlan(run);
        assertReadSequence(run, List.of(false, true), List.of("settings.json", "settings.json"));
        assertEquals(ToolError.NOT_FOUND, run.loop().toolOutcomes().getFirst().errorCode());
        assertAnswered(run, ANSWER);
    }

    @Test void unrelatedOptionalReadFailureDoesNotInvalidateTheRequiredSource() throws Exception {
        Files.writeString(workspace.resolve("settings.json"), CONTENT);
        Run run = run(REQUEST, ANSWER, List.of(
                read("required-read", "settings.json"), read("optional-read", "optional-notes.txt")), index -> {});

        assertRequiredReadPlan(run);
        assertReadSequence(run, List.of(true, false), List.of("settings.json", "optional-notes.txt"));
        assertEquals(ToolError.NOT_FOUND, run.loop().toolOutcomes().get(1).errorCode());
        assertAnswered(run, ANSWER);
    }

    @Test void successfulOrdinaryReadRemainsAnUnverifiedAnswer() throws Exception {
        Files.writeString(workspace.resolve("settings.json"), CONTENT);
        Run run = run(REQUEST, ANSWER, List.of(read("required-read", "settings.json")), index -> {});

        assertRequiredReadPlan(run);
        assertReadSequence(run, List.of(true), List.of("settings.json"));
        assertAnswered(run, ANSWER);
    }

    @Test void anExplicitMiddleOfFileRangeRemainsValidOrdinaryEvidence() throws Exception {
        Files.writeString(workspace.resolve("settings.json"), middleRangeContents());
        Run run = run("Read lines 2 and 3 of settings.json and explain retry_limit. Do not change files.",
                ANSWER, List.of(read("requested-range",
                        Map.of("path", "settings.json", "offset", 2, "max_lines", 2))), index -> {});

        assertRequiredReadPlan(run);
        assertReadSequence(run, List.of(true), List.of("settings.json"));
        assertTrue(run.loop().readFileBodies().values().stream()
                .anyMatch(body -> body.contains("2 | ") && body.contains("3 | ") && body.contains("more lines")),
                "The actual range must stop before EOF, preserving the remaining-lines marker");
        assertAnswered(run, ANSWER);
    }

    @Test void defaultLineLimitDoesNotEstablishCompleteRequiredSourceEvidence() throws Exception {
        Files.writeString(workspace.resolve("settings.json"), CONTENT.repeat(501));
        Run run = run(REQUEST, ANSWER, List.of(read("default-limit-read", "settings.json")), index -> {});

        assertRequiredReadPlan(run);
        assertReadSequence(run, List.of(true), List.of("settings.json"));
        assertTrue(run.loop().readFileBodies().values().stream().anyMatch(body -> body.contains("1 more lines")),
                "The successful tool result must actually omit the final line");
        assertIncomplete(run, ANSWER);
    }

    @Test void hardCharacterCapRemainsIncompleteEvenForAnExplicitRange() throws Exception {
        Files.writeString(workspace.resolve("settings.json"), "{\"description\": \"" + "x".repeat(18_000) + "\"}\n");
        Run run = run(REQUEST, ANSWER, List.of(read("hard-limit-read",
                Map.of("path", "settings.json", "offset", 1, "max_lines", 1))), index -> {});

        assertRequiredReadPlan(run);
        assertReadSequence(run, List.of(true), List.of("settings.json"));
        assertTrue(run.loop().readFileBodies().values().stream()
                .anyMatch(body -> body.contains("output truncated at 16K chars")),
                "The real tool output must be clipped, not merely a deliberately selected line range");
        assertIncomplete(run, ANSWER);
    }

    @Test void aLaterRangeCannotDischargeAnEarlierRequiredSourceFailure() throws Exception {
        Run run = run(REQUEST, ANSWER, List.of(read("missing-read", "settings.json"),
                read("partial-recovery", Map.of("path", "settings.json", "offset", 2, "max_lines", 2))),
                index -> { if (index == 1) Files.writeString(workspace.resolve("settings.json"), middleRangeContents()); });

        assertRequiredReadPlan(run);
        assertReadSequence(run, List.of(false, true), List.of("settings.json", "settings.json"));
        assertEquals(ToolError.NOT_FOUND, run.loop().toolOutcomes().getFirst().errorCode());
        assertTrue(run.loop().readFileBodies().values().stream().anyMatch(body -> body.contains("more lines")));
        assertIncomplete(run, ANSWER);
    }

    @Test void ordinaryExplanationWithUnknownResultParsingNeedsNoWorkspaceEvidence() throws Exception {
        String request = "Explain why retries can help when a network request fails temporarily.";
        String answer = "A retry can succeed after a temporary network failure has cleared.";
        assertTrue(AnswerResultRequirements.fromRequest(request).slots().stream()
                .anyMatch(slot -> slot.role() == AnswerResultRequirements.Role.UNKNOWN));
        Run run = run(request, answer, List.of(), index -> {});

        assertEquals(EvidenceObligation.NONE.name(), run.plan().evidenceObligation());
        assertEquals(0, run.loop().toolsInvoked());
        assertAnswered(run, answer);
    }

    // A partial two-target native loop requires a tool continuation and rejects a prose-only reply
    // with READ_ONLY_INSPECTION_UNAVAILABLE. It cannot legitimately be resumed as an outer retry.
    // Cross-target projection merging is therefore covered by synthetic projection fixtures.
    @Test void fullNativeReadInTheRetryResolvesTheEarlierFailureWithoutErasingItsHistory() throws Exception {
        Run original = run(REQUEST, ANSWER,
                List.of(read("failed-settings", "settings.json")), index -> {});
        assertReadSequence(original, List.of(false), List.of("settings.json"));
        assertEquals(1, original.loop().failedCalls());
        Files.writeString(workspace.resolve("settings.json"), CONTENT);
        Run retry = continueRun(original, ANSWER, List.of(read("recovered-settings", "settings.json")));

        Run merged = merge(original, retry);
        assertReadSequence(merged, List.of(false, true), List.of("settings.json", "settings.json"));
        assertEquals(1, merged.loop().failedCalls(), "Valid recovery cannot rewrite the execution history");
        assertEquals(ToolError.NOT_FOUND, merged.loop().toolOutcomes().getFirst().errorCode());
        assertAnswered(merged, ANSWER);
    }

    @Test void restoringAnEarlierAnswerRetainsTheMergedRequiredReadGap() throws Exception {
        Files.writeString(workspace.resolve("settings.json"), CONTENT);
        Run original = run(REQUEST, ANSWER,
                List.of(read("original-settings", "settings.json")), index -> {});
        assertAnswered(original, ANSWER);
        Files.delete(workspace.resolve("settings.json"));
        Run retry = continueRun(original, ANSWER,
                List.of(read("failed-refresh", "settings.json")));
        Run merged = merge(original, retry);
        var restoredLoop = InspectCompletenessRetry.withRestoredAnswer(merged.loop(), original.loop().finalAnswer(),
                ReadOnlyFinalizationEvidence.empty());
        Run restored = settle(original.plan(), restoredLoop);

        assertReadSequence(restored, List.of(true, false), List.of("settings.json", "settings.json"));
        assertEquals(1, restored.loop().failedCalls());
        assertIncomplete(restored, ANSWER);
    }

    private Run run(String request, String answer, List<ChatMessage.NativeToolCall> calls,
                    BeforeResponse beforeResponse) throws Exception {
        LocalTurnTraceCapture.begin("known-read-evidence-outcome", "tests", 1,
                "2026-09-28T00:00:00Z", "workspace", "auto", "llama_cpp", "fixture", request);
        return runInScope(request, answer, calls, beforeResponse, null);
    }

    private Run continueRun(Run original, String answer, List<ChatMessage.NativeToolCall> calls) throws Exception {
        assertTrue(LocalTurnTraceCapture.isActive());
        assertFalse(original.loop().failureDecision().shouldStop(), "A terminally stopped loop cannot be resumed");
        return runInScope(original.plan().originalUserRequest(), answer, calls, index -> {}, original);
    }

    private Run runInScope(String request, String answer, List<ChatMessage.NativeToolCall> calls,
                           BeforeResponse beforeResponse, Run original) throws Exception {
        var responseIndex = new AtomicInteger();
        var recorded = ScriptedNativeLlmClient.recordingResponder(providerRequest -> {
            int index = responseIndex.getAndIncrement();
            try { beforeResponse.accept(index); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
            return index < calls.size()
                    ? new LlmClient.StreamResult("", List.of(calls.get(index)))
                    : new LlmClient.StreamResult(answer, List.of());
        }, 16_384);
        clients.add(recorded.client());
        var registry = new ToolRegistry();
        var readTool = new ReadFileTool();
        registry.register(readTool);
        var descriptor = readTool.descriptor();
        var specs = List.of(new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema()));
        var config = new Config();
        var context = Context.builder(config).sandbox(new Sandbox(workspace, Map.of()))
                .llm(recorded.client()).toolRegistry(registry).nativeToolSpecs(specs).build();
        var messages = original == null
                ? new ArrayList<>(List.of(ChatMessage.system("Use source reads when needed."), ChatMessage.user(request)))
                : new ArrayList<>(original.loop().messages());
        TaskContract contract;
        if (original == null) {
            var resolution = TaskContractResolver.resolveFromMessages(messages);
            contract = resolution.contract();
            TurnTaskContractCapture.set(resolution, messages);
            TurnUserRequestCapture.set(request);
        } else {
            messages.add(ChatMessage.assistant(original.loop().finalAnswer()));
            contract = original.plan().taskContract();
            assertSame(contract, TurnTaskContractCapture.get(), "Retry must retain the original runtime contract");
        }
        assertFalse(contract.mutationAllowed());
        assertFalse(contract.mutationRequested());
        var plan = CurrentTurnPlan.create(contract, ExecutionPhase.INSPECT,
                List.of("talos.read_file"), List.of("talos.read_file"), List.of());
        recorded.client().setToolSpecs(specs);
        var initial = recorded.client().chatFull(messages, specs, ChatRequestControls.defaults()
                .withToolAuthorityMode(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE));
        if (!calls.isEmpty()) assertTrue(initial.provenance().providerToolCallEligible());
        var loop = new ToolCallLoop(new TurnProcessor(ModeController.defaultController(),
                new NoOpApprovalGate(), registry), 6)
                .run(initial.text(), initial.toolCalls(), initial.provenance(), messages, workspace, context);
        assertEquals(calls.size() + 1, recorded.requests().size(), "Only the scripted bounded path should run");
        assertFalse(loop.failureDecision().shouldStop(),
                () -> "A terminal stop would mask the evidence regression: " + loop.failureDecision().reason());
        assertFalse(loop.hitIterLimit());
        assertEquals(0, loop.mutatingToolSuccesses());
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED, loop.readOnlySynthesisStatus());
        return settle(plan, loop);
    }

    private Run merge(Run original, Run retry) {
        assertEquals(original.plan().taskContract(), retry.plan().taskContract());
        return settle(original.plan(), InspectCompletenessRetry.mergeReadOnlyRetryEvidence(original.loop(), retry.loop()));
    }

    private Run settle(CurrentTurnPlan plan, ToolCallLoop.LoopResult loop) {
        assertTrue(LocalTurnTraceCapture.isActive(), "Both routes must be assessed in the actual owning scope");
        var ordinaryAssessment = EvidenceObligationAssessment.assess(plan, loop, workspace);
        var observedAssessment = EvidenceObligationAssessment.assessObserved(plan, loop, workspace);
        assertEquals(ordinaryAssessment.result().status(), observedAssessment.result().status(),
                "Ordinary and observed assessment must agree about the same runtime-known required reads");
        var outcome = ExecutionOutcome.fromToolLoop(loop.finalAnswer(), plan, loop.messages(), loop, workspace, 0, false);
        return new Run(plan, loop, outcome);
    }

    private static ChatMessage.NativeToolCall read(String id, String path) {
        return read(id, Map.of("path", path));
    }

    private static ChatMessage.NativeToolCall read(String id, Map<String, Object> parameters) {
        return new ChatMessage.NativeToolCall(id, "talos.read_file", parameters);
    }

    private static void assertRequiredReadPlan(Run run) {
        assertEquals(EvidenceObligation.READ_TARGET_REQUIRED.name(), run.plan().evidenceObligation());
        assertEquals(java.util.Set.of("settings.json"), run.plan().taskContract().expectedTargets());
    }

    private static void assertReadSequence(Run run, List<Boolean> successes, List<String> paths) {
        assertEquals(successes.size(), run.loop().toolsInvoked());
        assertEquals(successes, run.loop().toolOutcomes().stream().map(ToolCallLoop.ToolOutcome::success).toList());
        assertEquals(paths, run.loop().toolOutcomes().stream()
                .map(outcome -> Path.of(outcome.pathHint()).getFileName().toString()).toList());
        assertTrue(run.loop().toolOutcomes().stream().allMatch(outcome -> !outcome.denied() && !outcome.mutating()));
    }

    private static void assertAnswered(Run run, String answer) {
        assertEquals(answer, run.outcome().finalAnswer());
        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, run.outcome().completionStatus());
        assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, run.outcome().taskOutcome().completionStatus());
        assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, run.outcome().verificationStatus());
        assertTrue(run.outcome().verificationReport().authoritativeProofKinds().isEmpty());
        assertTrue(run.outcome().taskOutcome().warnings().stream()
                .noneMatch(warning -> warning.type() == TruthWarningType.MISSING_EVIDENCE));
    }

    private static void assertIncomplete(Run run, String incompatibleAnswer) {
        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, run.outcome().completionStatus(),
                run.outcome().finalAnswer());
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, run.outcome().taskOutcome().completionStatus());
        assertTrue(run.outcome().finalAnswer().startsWith(EvidenceObligationVerifier.MISSING_EVIDENCE_PREFIX),
                run.outcome().finalAnswer());
        assertFalse(run.outcome().finalAnswer().contains(incompatibleAnswer),
                "Confident prose cannot override the required evidence gap");
        assertTrue(run.outcome().taskOutcome().warnings().stream()
                .anyMatch(warning -> warning.type() == TruthWarningType.MISSING_EVIDENCE));
    }

    private static String middleRangeContents() {
        return "{\n  \"retry_limit\": 2,\n  \"backoff\": true,\n  \"enabled\": true\n}\n";
    }

    @FunctionalInterface private interface BeforeResponse { void accept(int index) throws IOException; }
    private record Run(CurrentTurnPlan plan, ToolCallLoop.LoopResult loop, ExecutionOutcome outcome) {}
}
