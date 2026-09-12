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
        LocalTurnTraceCapture.begin("pending-diagnosis", "tests", 1, "2026-09-12T00:00:00Z",
                "workspace", "auto", "llama_cpp", "fixture", REQUEST);
    }

    @AfterEach void clear() {
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
        for (var type : List.of(TaskType.READ_ONLY_QA, TaskType.UNKNOWN)) {
            var recorded = recorded();
            var state = state(recorded.client(), 1);
            TurnTaskContractCapture.set(new TaskContract(type, false, false, false,
                    Set.copyOf(TARGETS), Set.of(), REQUEST));
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
        clients.add(client);
        var messages = new ArrayList<>(List.of(ChatMessage.system("Read the required sources."), ChatMessage.user(REQUEST)));
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
        TurnUserRequestCapture.set(REQUEST);
        var descriptor = new ReadFileTool().descriptor();
        var specs = List.of(new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema()));
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
