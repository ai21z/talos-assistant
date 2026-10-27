package dev.talos.runtime.toolcall;

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
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.GrepTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real native read/grep execution, with scripted provider choices and candidate prose. */
class ReadOnlyMixedEvidenceTest {
    private static final String REQUEST = "Please inspect retry.py again. With the configured retry limit, "
            + "list the attempt numbers that should run and explain when should_retry becomes false. "
            + "Use the current files, not just your previous answer.";
    private static final String SOURCE = "def total_attempts(retry_limit):\n    return retry_limit\n";
    private static final String MATCH = "DEPENDENCY_MARKER=73";
    private static final String CANDIDATE = "The source returns retry_limit unchanged. The search found "
            + MATCH + ". The caller convention is still unknown. No files were changed.";

    @TempDir Path workspace;
    private LlmClient client;

    @AfterEach void clear() {
        if (client != null) client.close();
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    @Test void ordinaryContinuationRetainsRealGrepAlongsideRequiredRead() throws Exception {
        Fixture fixture = fixture(5, List.of(read("primary", "retry.py"), grep(), answer(CANDIDATE)));
        ToolCallLoop.LoopResult result = run(fixture);
        assertExecutedMixedEvidence(result);

        assertEquals(3, fixture.recorded().requests().size());
        ChatRequest continuation = fixture.recorded().requests().getLast();
        assertGrepCarrier(continuation.messages);
        assertTrue(continuation.messages.stream().anyMatch(message -> "tool".equals(message.role())
                && "primary".equals(message.toolCallId()) && message.content().contains("return retry_limit")));
        assertEquals(CANDIDATE, result.finalAnswer());
        assertFalse(result.failureDecision().shouldStop());
    }

    @Test void mixedEvidenceAtCapStopsBeforeFileOnlyRecoveryGeneration() throws Exception {
        Fixture fixture = fixture(2, List.of(read("primary", "retry.py"), grep(),
                answer("This candidate must not be generated from dropped search evidence.")));
        ToolCallLoop.LoopResult result = run(fixture);
        assertExecutedMixedEvidence(result);

        assertRetentionStop(result);
        assertEquals(2, fixture.recorded().requests().size(),
                "No response-only generation may silently omit the real grep result");
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED, result.readOnlySynthesisStatus());
    }

    @Test void pendingRequiredReadAlsoDefersToOrdinaryMixedEvidenceContinuation() throws Exception {
        String request = REQUEST.replace("retry.py again", "retry.py and settings.txt again");
        var initialBatch = new LlmClient.StreamResult("", List.of(
                read("primary", "retry.py").toolCalls().getFirst(), grep().toolCalls().getFirst()));
        Fixture fixture = fixture(5, List.of(initialBatch, read("required-support", "settings.txt"),
                answer(CANDIDATE)), request);
        assertTrue(TurnTaskContractCapture.get().expectedTargets().contains("settings.txt"),
                "The second source must be required before the mixed batch executes");
        ToolCallLoop.LoopResult result = run(fixture);
        assertEquals(3, result.toolsInvoked());
        assertEquals(0, result.failedCalls());
        assertTrue(result.toolOutcomes().stream().allMatch(ToolCallLoop.ToolOutcome::success));
        assertGrepCarrier(result.messages());
        assertUnchanged();

        assertEquals(3, fixture.recorded().requests().size());
        assertGrepCarrier(fixture.recorded().requests().get(1).messages);
        assertEquals(CANDIDATE, result.finalAnswer());
        assertFalse(result.failureDecision().shouldStop());
    }

    @Test void mixedEvidenceRestrictionSurvivesAuthenticatedHandoffIntoReadOnlyNestedLoop() throws Exception {
        Fixture fixture = fixture(5, List.of(read("primary", "retry.py"), grep(), answer(CANDIDATE),
                read("nested-support", "settings.txt"), answer("Dropped-evidence synthesis must not run.")));
        ToolCallLoop.LoopResult original = run(fixture);
        assertExecutedMixedEvidence(original);
        assertFalse(original.failureDecision().shouldStop());
        var retryMessages = new ArrayList<>(original.messages());
        retryMessages.add(ChatMessage.assistant(original.finalAnswer()));
        retryMessages.add(ChatMessage.user("Read the discovered source before answering."));

        // A real captured handoff must seed the prior read. An empty handoff cannot pass this test.
        LoopState probe = new LoopState("", List.of(), retryMessages, workspace, fixture.context(), null, 1, 0);
        original.readOnlyEvidenceHandoff().seed(probe);
        assertNotNull(probe.readEvidence.latestReadFileForPath("retry.py", true));
        assertTrue(probe.toolOutcomes.isEmpty(), "Inherited evidence must not be fabricated as new execution");

        var initial = client.chatFull(retryMessages, fixture.specs(), nativeControls());
        assertTrue(initial.provenance().providerToolCallEligible());
        ToolCallLoop.LoopResult nested = fixture.loop().run(initial.text(), initial.toolCalls(),
                initial.provenance(), retryMessages, workspace, fixture.context(),
                original.readOnlyEvidenceHandoff(), 1);
        assertEquals(1, nested.toolsInvoked());
        assertEquals(0, nested.failedCalls());
        assertEquals(List.of("talos.read_file"), nested.toolOutcomes().stream()
                .map(ToolCallLoop.ToolOutcome::toolName).toList());
        assertTrue(nested.messages().stream().anyMatch(message -> "tool".equals(message.role())
                && "nested-support".equals(message.toolCallId()) && message.content().contains(MATCH)),
                "The nested real source read must succeed before the limitation assertion");

        assertRetentionStop(nested);
        assertEquals(4, fixture.recorded().requests().size(),
                "The original mixed evidence must prevent a file-only recovery in the nested loop");
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED, nested.readOnlySynthesisStatus());
        assertUnchanged();
    }

    @Test void grepOnlyHandoffRetainsRestrictionWithoutReadCarriersAndRejectsAnotherTrace() throws Exception {
        Fixture fixture = fixture(5, List.of(grep(), answer("The configured source was found but retry.py remains unread."),
                read("nested-primary", "retry.py"), answer("Dropped-evidence synthesis must not run.")));
        ToolCallLoop.LoopResult original = run(fixture);
        assertEquals(1, original.toolsInvoked());
        assertEquals(0, original.failedCalls());
        assertEquals("talos.grep", original.toolOutcomes().getFirst().toolName());
        assertTrue(original.toolOutcomes().getFirst().success());
        assertTrue(original.readPaths().isEmpty());
        assertGrepCarrier(original.messages());
        assertFalse(original.failureDecision().shouldStop());
        var retryMessages = new ArrayList<>(original.messages());
        retryMessages.add(ChatMessage.assistant(original.finalAnswer()));
        retryMessages.add(ChatMessage.user("Read the required source before answering."));
        var handoff = original.readOnlyEvidenceHandoff();
        LoopState probe = new LoopState("", List.of(), retryMessages, workspace, fixture.context(), null, 1, 0);
        handoff.seed(probe);
        assertTrue(probe.readEvidence.isEmpty(), "The original loop observed no read_file source");
        assertTrue(probe.toolOutcomes.isEmpty());
        assertTrue(ReadOnlyEvidenceContext.requiresGeneralEvidenceContext(probe),
                "A scope-authenticated mixed-only handoff must carry the restriction without source entries");

        var initial = client.chatFull(retryMessages, fixture.specs(), nativeControls());
        assertTrue(initial.provenance().providerToolCallEligible());
        var nested = fixture.loop().run(initial.text(), initial.toolCalls(), initial.provenance(), retryMessages,
                workspace, fixture.context(), handoff, 1);
        assertEquals(1, nested.toolsInvoked());
        assertEquals(0, nested.failedCalls());
        assertEquals("talos.read_file", nested.toolOutcomes().getFirst().toolName());
        assertTrue(nested.toolOutcomes().getFirst().success());
        assertRetentionStop(nested);
        assertEquals(3, fixture.recorded().requests().size(), "A mixed-only handoff must prevent file-only recovery");
        assertUnchanged();

        LocalTurnTraceCapture.begin("different-mixed-trace", "tests", 2, "2026-09-27T00:00:01Z",
                "workspace", "auto", "scripted", "fixture", REQUEST);
        LoopState anotherTurn = new LoopState("", List.of(), retryMessages, workspace, fixture.context(), null, 1, 0);
        handoff.seed(anotherTurn);
        assertFalse(ReadOnlyEvidenceContext.requiresGeneralEvidenceContext(anotherTurn),
                "The mixed-only restriction must not leak into another trace or turn");
        assertTrue(anotherTurn.readEvidence.isEmpty());
    }

    @Test void partialSupportingReadCannotDisappearWhenRetryReadsTheNamedTarget() throws Exception {
        var partial = new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                "partial-support", "talos.read_file", Map.of("path", "settings.txt", "offset", 1))));
        Fixture fixture = fixture(5, List.of(partial, answer("Only a supporting source range was observed."),
                read("nested-primary", "retry.py"), answer("A discarded range must not permit complete recovery.")));
        var original = run(fixture);
        assertEquals(1, original.toolsInvoked());
        assertEquals(0, original.failedCalls());
        assertTrue(original.toolOutcomes().getFirst().success());
        assertEquals("talos.read_file", original.toolOutcomes().getFirst().toolName());
        assertEquals(List.of("settings.txt"), original.readPaths());
        assertFalse(original.failureDecision().shouldStop());
        assertTrue(original.messages().stream().anyMatch(message -> "tool".equals(message.role())
                && "partial-support".equals(message.toolCallId()) && message.content().contains(MATCH)),
                "The real range result must exist before its inability to bind is tested");
        var retryMessages = new ArrayList<>(original.messages());
        retryMessages.add(ChatMessage.assistant(original.finalAnswer()));
        retryMessages.add(ChatMessage.user("Read the required source before answering."));
        var handoff = original.readOnlyEvidenceHandoff();
        LoopState probe = new LoopState("", List.of(), retryMessages, workspace, fixture.context(), null, 1, 0);
        handoff.seed(probe);
        assertTrue(probe.readEvidence.isEmpty(), "A ranged read must not be imported as a complete source");
        assertTrue(probe.inheritedUnretainedReadEvidence,
                "The rejected source carrier must survive as a runtime restriction");
        assertTrue(ReadOnlyEvidenceContext.requiresGeneralEvidenceContext(probe));

        var initial = client.chatFull(retryMessages, fixture.specs(), nativeControls());
        assertTrue(initial.provenance().providerToolCallEligible());
        var nested = fixture.loop().run(initial.text(), initial.toolCalls(), initial.provenance(), retryMessages,
                workspace, fixture.context(), handoff, 1);
        assertEquals(1, nested.toolsInvoked());
        assertEquals(0, nested.failedCalls());
        assertTrue(nested.toolOutcomes().getFirst().success());
        assertEquals(List.of("retry.py"), nested.readPaths());
        assertRetentionStop(nested);
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED, nested.readOnlySynthesisStatus());
        assertEquals(3, fixture.recorded().requests().size(),
                "A fresh target read must not erase the earlier unretainable supporting observation");
        assertUnchanged();

        LocalTurnTraceCapture.begin("different-partial-trace", "tests", 2, "2026-09-27T00:00:01Z",
                "workspace", "auto", "scripted", "fixture", REQUEST);
        LoopState anotherTurn = new LoopState("", List.of(), retryMessages, workspace, fixture.context(), null, 1, 0);
        handoff.seed(anotherTurn);
        assertFalse(anotherTurn.inheritedUnretainedReadEvidence);
        assertFalse(ReadOnlyEvidenceContext.requiresGeneralEvidenceContext(anotherTurn));
    }

    private Fixture fixture(int maxIterations, List<LlmClient.StreamResult> responses) throws Exception {
        return fixture(maxIterations, responses, REQUEST);
    }

    private Fixture fixture(int maxIterations, List<LlmClient.StreamResult> responses, String request) throws Exception {
        Files.writeString(workspace.resolve("retry.py"), SOURCE);
        Files.writeString(workspace.resolve("settings.txt"), MATCH + "\n");
        LocalTurnTraceCapture.begin("mixed-read-evidence", "tests", 1, "2026-09-27T00:00:00Z",
                "workspace", "auto", "scripted", "fixture", request);
        Config config = new Config();
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(config, responses, 16_384);
        client = recorded.client();
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new GrepTool());
        List<ToolSpec> specs = registry.descriptors().stream().map(descriptor ->
                new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema())).toList();
        var loop = new ToolCallLoop(new TurnProcessor(null, new NoOpApprovalGate(), registry), maxIterations);
        var context = Context.builder(config).sandbox(new Sandbox(workspace, Map.of()))
                .llm(client).nativeToolSpecs(specs).build();
        var messages = new ArrayList<>(List.of(ChatMessage.system("Inspect permitted workspace evidence."),
                ChatMessage.user(request)));
        var resolution = TaskContractResolver.resolveFromMessages(messages);
        assertEquals(TaskType.DIAGNOSE_ONLY, resolution.contract().type());
        assertFalse(resolution.contract().mutationAllowed());
        assertFalse(resolution.contract().mutationRequested());
        assertTrue(resolution.contract().expectedTargets().contains("retry.py"));
        TurnTaskContractCapture.set(resolution, messages);
        TurnUserRequestCapture.set(request);
        client.setToolSpecs(specs);
        return new Fixture(recorded, loop, context, specs, messages);
    }

    private ToolCallLoop.LoopResult run(Fixture fixture) throws Exception {
        var initial = client.chatFull(fixture.messages(), fixture.specs(), nativeControls());
        assertTrue(initial.provenance().providerToolCallEligible());
        return fixture.loop().run(initial.text(), initial.toolCalls(), initial.provenance(),
                fixture.messages(), workspace, fixture.context());
    }

    private void assertExecutedMixedEvidence(ToolCallLoop.LoopResult result) throws Exception {
        assertEquals(2, result.toolsInvoked());
        assertEquals(0, result.failedCalls());
        assertEquals(List.of("talos.read_file", "talos.grep"), result.toolOutcomes().stream()
                .map(ToolCallLoop.ToolOutcome::toolName).toList());
        assertTrue(result.toolOutcomes().stream().allMatch(ToolCallLoop.ToolOutcome::success));
        assertTrue(result.toolOutcomes().stream().noneMatch(outcome -> outcome.mutating()
                || outcome.denied() || outcome.mutationEffects().hasEffects()));
        assertGrepCarrier(result.messages());
        assertUnchanged();
    }

    private void assertUnchanged() throws Exception {
        assertEquals(SOURCE, Files.readString(workspace.resolve("retry.py")));
        assertEquals(MATCH + "\n", Files.readString(workspace.resolve("settings.txt")));
    }

    private static void assertGrepCarrier(List<ChatMessage> messages) {
        assertTrue(messages.stream().anyMatch(message -> "tool".equals(message.role())
                && "search".equals(message.toolCallId()) && message.content().contains(MATCH)),
                "The executed native grep result must remain in the answer input");
        assertTrue(messages.stream().filter(message -> "assistant".equals(message.role()) && message.hasNativeToolCalls())
                .flatMap(message -> message.toolCalls().stream()).anyMatch(call -> "search".equals(call.id())
                        && "talos.grep".equals(call.name()) && "DEPENDENCY_MARKER".equals(call.arguments().get("pattern"))),
                "The original native search/result association must be retained");
    }

    private static void assertRetentionStop(ToolCallLoop.LoopResult result) {
        assertTrue(result.failureDecision().shouldStop());
        assertEquals("READ_ONLY_EVIDENCE_RETENTION_LIMIT", result.failureDecision().reason());
        assertTrue(result.finalAnswer().toLowerCase(java.util.Locale.ROOT).contains("incomplete"));
    }

    private static ChatRequestControls nativeControls() {
        return ChatRequestControls.defaults().withToolAuthorityMode(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE);
    }

    private static LlmClient.StreamResult read(String id, String path) {
        return new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(id, "talos.read_file", Map.of("path", path))));
    }

    private static LlmClient.StreamResult grep() {
        return new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("search", "talos.grep",
                Map.of("pattern", "DEPENDENCY_MARKER", "include", "*.txt"))));
    }

    private static LlmClient.StreamResult answer(String text) { return new LlmClient.StreamResult(text, List.of()); }

    private record Fixture(ScriptedNativeLlmClient.RecordedClient recorded, ToolCallLoop loop, Context context,
                           List<ToolSpec> specs, ArrayList<ChatMessage> messages) {}
}
