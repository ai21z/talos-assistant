package dev.talos.runtime.toolcall;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnUserRequestCapture;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.intent.ArtifactTargetSet;
import dev.talos.runtime.intent.CoordinatedMutationTailPlan;
import dev.talos.runtime.intent.SequentialMutationClausePlan;
import dev.talos.runtime.intent.TaskContractCompiler;
import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.intent.TargetRef;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyEvidenceContinuationTest {
    private static final List<String> TARGETS = List.of("README.md", "index.html", "script.js", "scripts.js");
    private static final String REQUEST = "Read README.md, index.html, script.js and scripts.js. Diagnose why the "
            + "Calculate button does not work, identify the correct selector, calculate BMI for 72 kg and 1.8 m, "
            + "and distinguish script.js from scripts.js. Do not edit any files or run tests.";
    @TempDir Path workspace;
    private final List<LlmClient> clients = new ArrayList<>();

    @BeforeEach void trace() {
        dev.talos.runtime.TurnAuditCapture.begin();
        LocalTurnTraceCapture.begin("pending-diagnosis", "tests", 1, "2026-09-12T00:00:00Z",
                "workspace", "auto", "llama_cpp", "fixture", REQUEST);
    }

    @AfterEach void clear() {
        dev.talos.runtime.TurnAuditCapture.end();
        clients.forEach(LlmClient::close);
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
    }

    @Test void eachPendingReadUsesOnlyTheCapturedReadSurfaceAndRemainingTargets() throws Exception {
        for (int completed = 1; completed <= 3; completed++) {
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(read("initial", "README.md"), read("next", TARGETS.get(completed))), 16_384);
            var state = state(recorded.client(), completed);
            var originalMessages = List.copyOf(state.messages);
            assertTrue(ReadOnlyEvidenceContinuation.tryHandle(state).orElseThrow());
            assertEquals(2, recorded.requests().size());
            var request = recorded.requests().getLast();
            assertEquals(ToolChoiceMode.REQUIRED, request.controls.toolChoice());
            assertEquals(ResponseFormatMode.TEXT, request.controls.responseFormat());
            assertEquals(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE, request.controls.toolAuthorityMode());
            assertEquals(512, request.controls.maxOutputTokens());
            assertTrue(request.controls.singleAttempt());
            assertEquals(List.of("talos.read_file"), request.tools.stream().map(ToolSpec::name).toList());
            assertTrue(request.controls.debugTags().contains("bounded-read-only-inspection"));
            var instruction = request.messages.getLast();
            assertEquals("user", instruction.role());
            assertTrue(instruction.content().startsWith("[Pending diagnosis reads]\n"));
            var paths = new ObjectMapper().readTree(instruction.content().split("\n", 3)[1]);
            var remaining = new java.util.HashSet<String>();
            paths.forEach(path -> remaining.add(path.asText()));
            assertEquals(Set.copyOf(TARGETS.subList(completed, 4)), remaining);
            assertTrue(request.messages.stream().anyMatch(message -> "user".equals(message.role())
                    && REQUEST.equals(message.content())));
            assertEquals(completed, request.messages.stream().filter(message -> "tool".equals(message.role())).count());
            int anchor = -1;
            for (int index = 0; index < request.messages.size(); index++) {
                var message = request.messages.get(index);
                if ("system".equals(message.role()) && message.content().startsWith("[CurrentTurnCapability]")) anchor = index;
                if (!"tool".equals(message.role())) continue;
                assertTrue(anchor >= 0 && anchor < index);
                assertEquals(message.toolCallId(), request.messages.get(index - 1).toolCalls().getFirst().id());
                assertTrue(message.content().contains("observed contents"));
            }
            assertEquals(originalMessages, state.messages, "The bounded request does not rewrite durable loop history");
            assertEquals(completed, state.totalToolsInvoked, "Generating a proposal is not executing another read");
            assertEquals(0, state.mutatingToolSuccesses);
        }
    }

    @Test void compactedValidatedBodiesAreRestoredForPendingInspectionWithoutRewritingHistory() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(read("initial", "README.md"), read("next", "scripts.js")), 16_384);
        var state = state(recorded.client(), 3);
        ToolCallSupport.compactOlderToolResultsInPlace(state.messages, state.compactedToolResultOriginals);
        assertTrue(state.messages.stream().anyMatch(message -> "tool".equals(message.role())
                && message.content().startsWith("[compacted:")));
        var before = List.copyOf(state.messages);
        assertTrue(ReadOnlyEvidenceContinuation.tryHandle(state).orElseThrow());
        for (String target : TARGETS.subList(0, 3))
            assertEquals(1, recorded.requests().getLast().messages.stream().filter(message -> "tool".equals(message.role())
                    && message.content().contains("observed contents of " + target)).count());
        assertEquals(before, state.messages);
    }

    @Test void mutationPrivacyFailuresAndStoppedOrUnboundAuthorityDoNotAcquireNewReads() throws Exception {
        List<Consumer<LoopState>> changes = List.of(
                state -> state.mutationSinceStart = true,
                state -> state.mutatingToolSuccesses = 1,
                state -> state.contentWithheldFromModelContext = true,
                state -> state.failedCalls = 1,
                state -> state.failureDecision = FailureDecision.stop(FailureAction.ASK_USER, "fixture terminal"),
                state -> ReadEvidenceStateAccounting.clearSuccessfulReadCaches(state),
                state -> state.currentResponseReceipt = ProviderResponseCapture.Receipt.none(),
                state -> state.setPendingActionObligation(PendingActionObligation.expectedTargets(List.of("new.txt"))));
        for (var change : changes) {
            var recorded = recorded();
            var state = state(recorded.client(), 1);
            change.accept(state);
            assertTrue(ReadOnlyEvidenceContinuation.tryHandle(state).isEmpty());
            assertEquals(1, recorded.requests().size(), "Only the original foreground request exists");
        }
    }

    @Test void unrelatedOrMutatingTasksNeverAcquireRequiredDiagnosisReads() throws Exception {
        for (var type : List.of(TaskType.WORKSPACE_EXPLAIN, TaskType.UNKNOWN)) {
            var recorded = recorded();
            var state = state(recorded.client(), 1);
            TurnTaskContractCapture.set(new TaskContract(type, false, false, false,
                    Set.copyOf(TARGETS), Set.of(), type == TaskType.UNKNOWN ? REQUEST
                            : "README.md, index.html, script.js and scripts.js are mentioned."));
            assertTrue(ReadOnlyEvidenceContinuation.tryHandle(state).isEmpty());
            assertEquals(1, recorded.requests().size());
        }
        var recorded = recorded();
        var state = state(recorded.client(), 1);
        TurnTaskContractCapture.set(new TaskContract(TaskType.DIAGNOSE_ONLY, true, true, false,
                Set.copyOf(TARGETS), Set.copyOf(TARGETS), Set.of(), REQUEST, "fixture mutation"));
        assertTrue(ReadOnlyEvidenceContinuation.tryHandle(state).isEmpty());
        assertEquals(1, recorded.requests().size());
    }

    @Test void explicitOutputVerifyMentionedAndForbiddenRolesAreNotRequiredReadTargets() throws Exception {
        for (var role : List.of(TargetRole.OUTPUT_DESTINATION, TargetRole.VERIFY_ONLY,
                TargetRole.MENTIONED_ONLY, TargetRole.FORBIDDEN)) {
            var recorded = recorded();
            var state = state(recorded.client(), 1);
            var refs = TARGETS.stream().map(path -> TargetRef.of(path,
                    "README.md".equals(path) ? TargetRole.MUST_READ : role)).toList();
            var intent = new TaskIntent(TaskType.DIAGNOSE_ONLY, false, false, false,
                    new ArtifactTargetSet(refs), REQUEST, "explicit role exclusion fixture");
            TurnTaskContractCapture.set(new TaskResolution(TaskContractCompiler.compile(intent), intent,
                    CoordinatedMutationTailPlan.empty(REQUEST), SequentialMutationClausePlan.empty(REQUEST)));
            assertTrue(ReadOnlyEvidenceContinuation.tryHandle(state).isEmpty(), role.toString());
            assertEquals(1, recorded.requests().size());
        }
    }

    @Test void typedRequiredReadsDoNotDependOnDiagnosisSynthesisAdmission() throws Exception {
        var recorded = recorded();
        var state = state(recorded.client(), 1);
        captureReadRoles(TaskType.WORKSPACE_EXPLAIN, TARGETS.stream()
                .map(path -> TargetRef.of(path, TargetRole.MUST_READ)).toList());

        assertFalse(ReadOnlyEvidenceContext.select(state).available());
        assertEquals(java.util.Optional.of(true), ReadOnlyEvidenceContinuation.tryHandle(state));
        assertEquals(ToolChoiceMode.REQUIRED, recorded.requests().getLast().controls.toolChoice());
        assertFalse(ReadOnlyEvidenceContext.select(state).available(), "Acquisition does not admit synthesis");
        assertEquals(1, state.totalToolsInvoked, "A generated read proposal is not an executed read");
    }

    @Test void requiredReadAcquisitionPreservesRolesAndDoesNotPromoteOutputOrMentionedTargets() throws Exception {
        for (var excluded : List.of(TargetRole.OUTPUT_DESTINATION, TargetRole.VERIFY_ONLY,
                TargetRole.MENTIONED_ONLY, TargetRole.MAY_MUTATE, TargetRole.FORBIDDEN)) {
            var recorded = recorded();
            var state = state(recorded.client(), 1);
            captureReadRoles(TaskType.WORKSPACE_EXPLAIN, List.of(
                    TargetRef.of("README.md", TargetRole.MUST_READ),
                    TargetRef.of("index.html", TargetRole.SOURCE_EVIDENCE),
                    TargetRef.of("script.js", excluded)));

            assertEquals(java.util.Optional.of(true), ReadOnlyEvidenceContinuation.tryHandle(state), excluded.toString());
            var pending = recorded.requests().getLast().messages.getLast().content().split("\n", 3)[1];
            assertEquals(List.of("index.html"), new ObjectMapper().readValue(pending, List.class));
        }
    }

    @Test void acquisitionRetentionFailureStopsWithoutGenericAutoFallback() throws Exception {
        var recorded = recorded();
        var state = state(recorded.client(), 1);
        captureReadRoles(TaskType.WORKSPACE_EXPLAIN, TARGETS.stream()
                .map(path -> TargetRef.of(path, TargetRole.MUST_READ)).toList());
        ReadOnlyEvidenceContextTest.read(state, "README.md", "x".repeat(ReadOnlyEvidenceContext.MAX_EVIDENCE_CHARS),
                Map.of("path", "README.md"));

        assertEquals(java.util.Optional.of(false), ReadOnlyEvidenceContinuation.tryHandle(state));
        assertEquals(TerminalReadOnlyStopAnswer.RETENTION_LIMIT_REASON, state.failureDecision.reason());
        assertTrue(state.failureDecision.shouldStop());
        assertEquals(1, recorded.requests().size());
    }

    @Test void requiredAcquisitionHonorsRestrictionsAddedAfterCarrierBinding() throws Exception {
        var recorded = recorded();
        var state = state(recorded.client(), 1);
        captureReadRoles(TaskType.WORKSPACE_EXPLAIN, TARGETS.stream()
                .map(path -> TargetRef.of(path, TargetRole.MUST_READ)).toList());
        int index = state.messages.size() - 1;
        var restrictions = new dev.talos.safety.ContentRestrictions(true, false, false, false);
        state.messages.set(index, state.messages.get(index).withContentRestrictions(restrictions));

        assertEquals(java.util.Optional.of(true), ReadOnlyEvidenceContinuation.tryHandle(state));
        var restored = recorded.requests().getLast().messages.stream()
                .filter(message -> "tool".equals(message.role())).findFirst().orElseThrow();
        assertEquals(restrictions, restored.contentRestrictions());
        assertEquals(restrictions, state.messages.get(index).contentRestrictions());
    }

    @Test void workspaceExplanationCannotUseLegacyMutationRolesAsReadAuthority() throws Exception {
        var recorded = recorded();
        var state = state(recorded.client(), 1);
        captureReadRoles(TaskType.WORKSPACE_EXPLAIN, TARGETS.stream()
                .map(path -> TargetRef.of(path, TargetRole.MUST_MUTATE)).toList(),
                "README.md, index.html, script.js and scripts.js are mentioned.");
        assertTrue(ReadOnlyEvidenceContinuation.tryHandle(state).isEmpty());
        assertEquals(1, recorded.requests().size());
    }

    @Test void requiredPostReadMutationRoleCannotCompileIntoReadOnlyAuthority() {
        var intent = new TaskIntent(TaskType.WORKSPACE_EXPLAIN, false, false, false,
                ArtifactTargetSet.of(TargetRef.of("script.js", TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE)),
                REQUEST, "post-read mutation exclusion fixture");
        assertThrows(IllegalArgumentException.class, () -> TaskContractCompiler.compile(intent));
    }

    @Test void acquisitionDoesNotRestoreDisallowedUnboundOrProtectedSources() throws Exception {
        List<Consumer<LoopState>> changes = List.of(
                state -> {
                    int index = state.messages.size() - 1;
                    state.messages.set(index, state.messages.get(index).withContentRestrictions(
                            new dev.talos.safety.ContentRestrictions(false, false, false, false)));
                },
                state -> state.messages.set(state.messages.size() - 1,
                        ChatMessage.toolResult("unbound-call", "unbound content")),
                state -> captureReadRoles(TaskType.WORKSPACE_EXPLAIN, List.of(
                        TargetRef.of("README.md", TargetRole.MUST_READ),
                        TargetRef.of(".env", TargetRole.MUST_READ))),
                state -> captureReadRoles(TaskType.WORKSPACE_EXPLAIN, List.of(
                        TargetRef.of("README.md", TargetRole.MUST_READ),
                        TargetRef.of("../outside.txt", TargetRole.MUST_READ))));
        for (var change : changes) {
            var recorded = recorded();
            var state = state(recorded.client(), 1);
            captureReadRoles(TaskType.WORKSPACE_EXPLAIN, TARGETS.stream()
                    .map(path -> TargetRef.of(path, TargetRole.MUST_READ)).toList());
            change.accept(state);
            assertFalse(ReadOnlyEvidenceContext.selectRequiredAcquisition(state).available());
            assertTrue(ReadOnlyEvidenceContinuation.tryHandle(state).isEmpty());
            assertEquals(1, recorded.requests().size());
        }
    }

    @Test void tooManyRequiredSourcesStopBeforeDispatch() throws Exception {
        var recorded = recorded();
        var state = state(recorded.client(), 1);
        captureReadRoles(TaskType.WORKSPACE_EXPLAIN, java.util.stream.IntStream.range(0, 9)
                .mapToObj(index -> TargetRef.of("file" + index + ".txt", TargetRole.MUST_READ)).toList());
        assertEquals(java.util.Optional.of(false), ReadOnlyEvidenceContinuation.tryHandle(state));
        assertEquals(TerminalReadOnlyStopAnswer.RETENTION_LIMIT_REASON, state.failureDecision.reason());
        assertEquals(1, recorded.requests().size());
    }

    @Test void oversizedLegacyReadRequestStopsBeforeInterpretationWithRequestLimitReason() throws Exception {
        var recorded = recorded();
        var state = state(recorded.client(), 1);
        captureReadRoles(TaskType.WORKSPACE_EXPLAIN, TARGETS.stream()
                .map(path -> TargetRef.of(path, TargetRole.MUST_MUTATE)).toList(),
                REQUEST + " Neutral background detail. ".repeat(1_400));
        var evidence = ReadOnlyEvidenceContext.selectRequiredAcquisition(state);
        assertTrue(evidence.requestLimited());
        assertFalse(evidence.retentionLimited());
        assertEquals(java.util.Optional.of(false), ReadOnlyEvidenceContinuation.tryHandle(state));
        assertEquals(TerminalReadOnlyStopAnswer.INSPECTION_REQUEST_LIMIT_REASON, state.failureDecision.reason());
        assertEquals(1, recorded.requests().size());
    }

    private void captureReadRoles(TaskType type, List<TargetRef> refs) {
        captureReadRoles(type, refs, REQUEST);
    }

    private void captureReadRoles(TaskType type, List<TargetRef> refs, String request) {
        var intent = new TaskIntent(type, false, false, false, new ArtifactTargetSet(refs), request,
                "required acquisition role fixture");
        TurnTaskContractCapture.set(new TaskResolution(TaskContractCompiler.compile(intent), intent,
                CoordinatedMutationTailPlan.empty(request), SequentialMutationClausePlan.empty(request)));
    }

    @Test void completedEvidenceOrToolCapDoesNotSpendAnotherInspectionGeneration() throws Exception {
        var complete = recorded();
        var completeState = state(complete.client(), 4);
        assertTrue(ReadOnlyEvidenceContinuation.tryHandle(completeState).isEmpty());
        assertEquals(1, complete.requests().size());
        var capped = recorded();
        var cappedState = state(capped.client(), 1);
        cappedState.iterations = cappedState.maxIterations;
        assertFalse(ReadOnlyEvidenceContinuation.tryHandle(cappedState).orElseThrow());
        assertEquals(1, capped.requests().size());
        assertTrue(new ToolCallRepromptStage().hitIterationLimit(cappedState));
        assertTrue(cappedState.currentNativeCalls.isEmpty());
    }

    @Test void supportingAcquisitionRetainsTheReachedCapAfterPendingCallsAreCleared() throws Exception {
        var recorded = recorded();
        var state = state(recorded.client(), 4);
        state.iterations = state.maxIterations;

        assertFalse(ReadOnlyEvidenceContinuation.tryContinueAcquisition(state).orElseThrow());

        assertEquals(1, recorded.requests().size());
        assertTrue(state.currentNativeCalls.isEmpty());
        assertTrue(new ToolCallRepromptStage().hitIterationLimit(state));
        assertEquals(TerminalReadOnlyStopAnswer.ACQUISITION_LIMIT_REASON, state.failureDecision.reason());
    }

    @Test void authorityRejectionAndApprovalDenialWinBeforeAnyFollowUpGeneration() throws Exception {
        for (boolean authorityRejected : List.of(true, false)) {
            var recorded = recorded();
            var state = state(recorded.client(), 1);
            var outcome = new ToolCallExecutionStage.IterationOutcome(0, List.of(), 1,
                    !authorityRejected, false, false, 0, List.of(), authorityRejected);
            assertFalse(new ToolCallRepromptStage().reprompt(state, outcome));
            assertEquals(1, recorded.requests().size());
            assertEquals(1, state.totalToolsInvoked);
        }
    }

    @Test void proseAndEmptyInspectionResponsesStopWithoutFallbackOrReinvocation() throws Exception {
        for (String response : List.of("", "The diagnosis is complete.")) {
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(read("initial", "README.md"), new LlmClient.StreamResult(response, List.of())), 16_384);
            var state = state(recorded.client(), 1);
            assertFalse(ReadOnlyEvidenceContinuation.tryHandle(state).orElseThrow());
            assertTrue(state.failureDecision.shouldStop());
            assertTrue(state.currentText.contains("diagnosis remains incomplete"));
            assertTrue(state.currentNativeCalls.isEmpty());
            assertTrue(ReadOnlyEvidenceContinuation.tryHandle(state).isEmpty());
            assertEquals(2, recorded.requests().size());
            assertEquals(1, state.totalToolsInvoked);
        }
    }

    @Test void contextBudgetFailureUsesOneAttemptAndNeverSelectsGenericCompactRecovery() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingThenFailureWithContextWindow(List.of(read("initial", "README.md")), 16_384,
                new EngineException.ContextBudgetExceeded(5_946, 5_635, 8_192, 0));
        var state = state(recorded.client(), 1);
        assertFalse(ReadOnlyEvidenceContinuation.tryHandle(state).orElseThrow());
        assertTrue(state.failureDecision.shouldStop());
        assertEquals(TerminalReadOnlyStopAnswer.INSPECTION_REQUEST_LIMIT_REASON, state.failureDecision.reason());
        assertTrue(state.currentText.contains("estimated 5946 input tokens, budget 5635, context window 8192"),
                state.currentText);
        assertFalse(state.currentText.contains("Action obligation failed"), state.currentText);
        assertTrue(ReadOnlyEvidenceContinuation.tryHandle(state).isEmpty());
        assertEquals(2, recorded.requests().size());
        var events = LocalTurnTraceCapture.complete().events();
        assertEquals(2, events.stream().filter(event -> "GENERATION_STARTED".equals(event.type())).count());
        assertEquals(2, events.stream().filter(event -> "GENERATION_ATTEMPT_STARTED".equals(event.type())).count());
        assertEquals(1, state.totalToolsInvoked);
    }

    @Test void cancellationPropagatesWithoutAnExtraGeneration() throws Exception {
        var cancellation = new CancellationException("fixture cancelled");
        var recorded = ScriptedNativeLlmClient.recordingThenFailureWithContextWindow(
                List.of(read("initial", "README.md")), 16_384, cancellation);
        var state = state(recorded.client(), 1);
        assertSame(cancellation, assertThrows(CancellationException.class, () -> ReadOnlyEvidenceContinuation.tryHandle(state)));
        assertEquals(2, recorded.requests().size());
        var events = LocalTurnTraceCapture.complete().events();
        assertEquals(2, events.stream().filter(event -> "GENERATION_ATTEMPT_STARTED".equals(event.type())).count());
        assertEquals(1, state.totalToolsInvoked);
    }

    @Test void providerWithoutRequiredChoiceDoesNotAcquireThisContinuation() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingTextToolFallbackWithContextWindow(
                List.of(new LlmClient.StreamResult("read-only text fixture", List.of())), 16_384);
        var state = state(recorded.client(), 1);
        assertFalse(recorded.client().supportsRequiredToolChoice());
        assertTrue(ReadOnlyEvidenceContinuation.tryHandle(state).isEmpty());
        assertEquals(1, recorded.requests().size());
    }

    private static ScriptedNativeLlmClient.RecordedClient recorded() {
        return ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(read("initial", "README.md"), read("next", "index.html")), 16_384);
    }

    private LoopState state(LlmClient client, int completed) throws Exception {
        return state(client, completed, REQUEST, false);
    }
    @Test void completeReadsTransitionToRequiredOrderedComparisonWithoutConflictingInstructions() throws Exception {
        String request = "Read README.md, index.html, script.js and scripts.js. Explain why the Calculate button fails and give the correct selector. "
                + "For span 12 m and rise 2.5 m, give the panel area according to README.md and the mathematical value of the expression in script.js separately. "
                + "Explain any disagreement and distinguish the two JavaScript files. Do not edit files or claim tests were run.";
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(List.of(read("initial", "README.md"),
                new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("compare", DocumentComparisonTool.NAME,
                        Map.of("left", "script.js", "right", "scripts.js"))))), 16_384);
        var state = state(recorded.client(), 4, request, true);
        assertTrue(dev.talos.runtime.task.DocumentComparisonRequest.from(TurnTaskContractCapture.get()).active());
        assertTrue(ReadOnlyEvidenceContext.select(state).complete());
        assertTrue(DocumentComparisonContinuation.tryHandle(state).orElseThrow());
        var selected = recorded.requests().getLast();
        assertEquals(ToolChoiceMode.REQUIRED, selected.controls.toolChoice());
        assertEquals(List.of(DocumentComparisonTool.NAME), selected.tools.stream().map(ToolSpec::name).toList());
        assertEquals(DocumentComparisonTool.NAME, state.currentNativeCalls.getFirst().name());
        assertTrue(selected.messages.getLast().content().startsWith("[Required ordered comparisons]"));
        assertTrue(selected.messages.getLast().content().contains("script.js"));
        assertTrue(selected.messages.stream().noneMatch(message -> message.content().contains("Read only these remaining required paths")));
        assertEquals(0, selected.messages.stream().filter(message -> message.role().equals("tool")).count());
        assertEquals(2, recorded.requests().size());
    }
    private LoopState state(LlmClient client, int completed, String request, boolean compare) throws Exception {
        clients.add(client);
        var messages = new ArrayList<>(List.of(ChatMessage.system("Read the required sources."), ChatMessage.user(request)));
        var resolution = TaskContractResolver.resolveFromMessages(messages);
        assertEquals(TaskType.DIAGNOSE_ONLY, resolution.contract().type());
        assertFalse(resolution.contract().mutationAllowed());
        assertFalse(resolution.contract().mutationRequested());
        assertFalse(resolution.contract().verificationRequired());
        assertEquals(Set.copyOf(TARGETS), resolution.contract().expectedTargets());
        // Characterize the actual legacy label, not the prompt-debug reporting projection.
        // These non-mutating diagnosis targets still carry MUST_MUTATE internally; their
        // effective read obligation must not be lost or manufactured by this fixture.
        assertEquals(Set.copyOf(TARGETS), resolution.intent().targets().pathsByRole(TargetRole.MUST_MUTATE));
        assertTrue(resolution.intent().targets().pathsByRole(TargetRole.MUST_READ).isEmpty());
        TurnTaskContractCapture.set(resolution, messages);
        TurnUserRequestCapture.set(request);
        var descriptor = new ReadFileTool().descriptor();
        var specs = new ArrayList<>(List.of(new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema())));
        if (compare) {
            var additional = new DocumentComparisonTool().descriptor();
            specs.add(new ToolSpec(additional.name(), additional.description(), additional.parametersSchema()));
        }
        client.setToolSpecs(specs);
        var initial = client.chatFull(messages, specs, ChatRequestControls.defaults()
                .withToolAuthorityMode(client.toolAuthorityModeForToolSurface(specs)));
        var context = Context.builder(new Config()).sandbox(new Sandbox(workspace, Map.of()))
                .llm(client).nativeToolSpecs(specs).build();
        var state = new LoopState(initial.text(), initial.toolCalls(), initial.provenance(),
                messages, workspace, context, null, 10, 0);
        for (String target : TARGETS.subList(0, completed))
            ReadOnlyEvidenceContextTest.read(state, target, "1 | observed contents of " + target, Map.of("path", target));
        state.iterations = completed;
        state.totalToolsInvoked = completed;
        return state;
    }

    private static LlmClient.StreamResult read(String id, String path) {
        return new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(id, "talos.read_file", Map.of("path", path))));
    }
}
