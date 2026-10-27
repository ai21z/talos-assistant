package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.JsonTurnLogAppender;
import dev.talos.runtime.MemoryUpdateListener;
import dev.talos.runtime.Result;
import dev.talos.runtime.Session;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnRouter;
import dev.talos.runtime.outcome.ReadOnlyProposalGroundingGuard;
import dev.talos.runtime.policy.RuntimeModelIdentityAnswerPolicy;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.ListDirTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AssistantTurnRuntimeIdentityCompositionTest {
    @TempDir Path workspace;

    @AfterEach
    void clearCaptures() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        dev.talos.runtime.TurnTraceCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
        Thread.interrupted();
    }

    @Test
    void actualReadAndTypedIdentityHaveIndependentGrounding() throws Exception {
        String request = "Which model is answering this session? Read README.md and tell me the review owner and pending item.";
        var result = read(request, "The review owner is Mara. Packaging remains pending.", identity());
        assertTrue(result.startsWith("Runtime identity\n" + RuntimeModelIdentityAnswerPolicy.render(identity())), result);
        assertTrue(result.contains("Remaining request\n"), result);
        assertTrue(result.substring(result.indexOf("Remaining request"))
                .contains("The review owner is Mara. Packaging remains pending."), result);
        assertFalse(result.contains(ReadOnlyProposalGroundingGuard.GROUNDED_PROPOSAL_WARNING), result);
    }

    @Test
    void matchingArtifactDoesNotAuthenticateAClaimAboutReadme() throws Exception {
        String result = read("Who are you? Review README.md.",
                "README.md describes Selected artifact: actual.gguf.", identity());
        assertTrue(result.contains("Selected artifact: actual.gguf."), result);
        assertTrue(result.contains(ReadOnlyProposalGroundingGuard.GROUNDED_PROPOSAL_WARNING), result);
        assertTrue(result.indexOf(ReadOnlyProposalGroundingGuard.GROUNDED_PROPOSAL_WARNING)
                > result.indexOf("Remaining request"), result);
        assertTrue(result.endsWith("README.md describes Selected artifact: actual.gguf."), result);
    }

    @Test
    void arbitraryProviderIdentityContradictionIsNotSilentlyRemovedOrVerified() throws Exception {
        String result = read("Who are you? Review README.md.",
                "I am GPT-4. The owner is Mara.", identity());
        assertTrue(result.startsWith("Runtime identity\n" + RuntimeModelIdentityAnswerPolicy.render(identity())), result);
        assertTrue(result.substring(result.indexOf("Remaining request")).contains("I am GPT-4."), result);
    }

    @Test
    void deterministicReadPresentationStillContainsTheRequestedIdentity() throws Exception {
        String result = read("Who are you? Read README.md verbatim.",
                "Review owner: Mara. Packaging remains pending.", identity());
        assertTrue(result.contains("Runtime-qualified model: gpt-oss-20b."), result);
        assertTrue(result.contains("Mara"), result);
        assertTrue(result.contains("Packaging"), result);
    }

    @Test
    void unavailableSnapshotDoesNotRemoveTheOtherAnswer() throws Exception {
        String result = read("Who are you? Review README.md.", "The owner is Mara.", ModelIdentitySnapshot.empty());
        assertTrue(result.contains("Runtime model identity is unavailable."), result);
        assertFalse(result.contains("Runtime-qualified model:"), result);
        assertTrue(result.contains("The owner is Mara."), result);
    }

    @Test
    void unsupportedDocumentPreflightRetainsIdentityAndItsExistingLimitation() throws Exception {
        Files.writeString(workspace.resolve("slides.pptx"), "Not a supported presentation source.");
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity("This must not be generated.",
                AssistantTurnRuntimeIdentityCompositionTest::identity);
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        var context = Context.builder(new Config()).llm(fixture.client()).sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry).toolCallLoop(new ToolCallLoop(
                        new TurnProcessor(null, new NoOpApprovalGate(), registry), 3)).build();
        var result = execute("Who are you? Summarize slides.pptx.", context, new AssistantTurnExecutor.Options());
        assertTrue(result.text().contains("Runtime-qualified model: gpt-oss-20b."), result.text());
        assertTrue(result.text().contains("[Document capability note:"), result.text());
        assertTrue(result.text().contains("slides.pptx"));
        assertEquals(0, fixture.requests().size());
    }

    @Test
    void deterministicDirectoryAnswerDoesNotBypassComposition() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "Review notes.");
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(List.of(
                new LlmClient.StreamResult("{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}", List.of()),
                new LlmClient.StreamResult("README.md is present.", List.of())),
                AssistantTurnRuntimeIdentityCompositionTest::identity);
        var registry = new ToolRegistry();
        registry.register(new ListDirTool());
        fixture.client().setToolSpecs(registry.descriptors().stream().map(descriptor ->
                new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema())).toList());
        var context = Context.builder(new Config()).llm(fixture.client()).sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry).toolCallLoop(new ToolCallLoop(
                        new TurnProcessor(null, new NoOpApprovalGate(), registry), 3)).build();
        String request = "Who are you? List top-level files.";
        assertEquals(dev.talos.runtime.task.TaskType.DIRECTORY_LISTING,
                dev.talos.runtime.task.TaskContractResolver.fromUserRequest(request).type());
        var result = execute(request, context, new AssistantTurnExecutor.Options());
        assertTrue(result.text().contains("Runtime-qualified model: gpt-oss-20b."), result.text());
        assertTrue(result.text().contains("Directory entries:"), result.text());
        assertTrue(result.text().contains("README.md"));
    }

    @Test
    void reusedOptionsDoNotCaptureIdentityAcrossTurns() {
        var current = new java.util.concurrent.atomic.AtomicReference<>(identity());
        var reads = new AtomicInteger();
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity("Recursion calls itself.", () -> {
            reads.incrementAndGet();
            return current.get();
        });
        var context = Context.builder(new Config()).llm(fixture.client()).build();
        var options = new AssistantTurnExecutor.Options();
        assertTrue(execute("Who are you? Explain recursion.", context, options).text().contains("gpt-oss-20b"));
        current.set(ModelIdentitySnapshot.empty());
        var next = execute("Who are you? Explain recursion.", context, options);
        assertTrue(next.text().contains("Runtime model identity is unavailable."), next.text());
        assertFalse(next.text().contains("Runtime-qualified model:"));
        assertEquals(2, reads.get());
    }

    @Test
    void mixedStreamingRequestReturnsCompleteCompositionWithoutEmission() {
        var visible = new StringBuilder();
        var completions = new AtomicInteger();
        var identityReads = new AtomicInteger();
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity("Recursion calls itself.", () -> {
            identityReads.incrementAndGet();
            return identity();
        });
        var context = Context.builder(new Config()).llm(fixture.client())
                .streamSink(new ToolCallStreamFilter(visible::append))
                .onStreamComplete(completions::incrementAndGet).build();
        var result = execute("Who are you? Explain recursion.", context, new AssistantTurnExecutor.Options());
        assertFalse(result.streamed());
        assertTrue(visible.isEmpty(), "Composition must not emit provider or selected text");
        assertEquals(1, completions.get());
        assertEquals(1, identityReads.get());
        assertEquals(1, fixture.requests().size());
        assertEquals(1, result.text().split("Runtime identity", -1).length - 1);
        assertTrue(result.text().contains("Recursion calls itself."));
    }

    @Test
    void cancellationDuringComposedCompletionIsPropagatedWithoutDisplay() {
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity("Recursion calls itself.",
                AssistantTurnRuntimeIdentityCompositionTest::identity);
        var completions = new AtomicInteger();
        var visible = new StringBuilder();
        var context = Context.builder(new Config()).llm(fixture.client())
                .streamSink(visible::append)
                .onStreamComplete(() -> {
                    completions.incrementAndGet();
                    assertTrue(visible.isEmpty());
                    throw new CancellationException("Completion cancelled");
                }).build();
        assertThrows(CancellationException.class, () -> execute("Who are you? Explain recursion.", context,
                new AssistantTurnExecutor.Options()));
        assertEquals(1, completions.get());
        assertTrue(visible.isEmpty());
        assertEquals(1, fixture.requests().size(), "Cancellation must not trigger another provider request");
        assertNull(TurnTaskContractCapture.get());
    }

    @Test
    void tinyBudgetDoesNotExposeAnIdentityWithoutItsQualification() {
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity("Recursion calls itself.",
                AssistantTurnRuntimeIdentityCompositionTest::identity);
        var result = execute("Who are you? Explain recursion.", Context.builder(new Config()).llm(fixture.client()).build(),
                new AssistantTurnExecutor.Options().responseMaxChars(50));
        assertTrue(result.text().contains("Runtime identity omitted"), result.text());
        assertFalse(result.text().contains("Runtime-qualified model:"));
        assertTrue(result.text().contains("[output truncated]"));
        var trace = LocalTurnTraceCapture.complete();
        assertNotNull(trace);
        assertEquals("ADVISORY_ONLY", trace.outcome().status());
        assertEquals("ADVISORY_ONLY", trace.outcome().classification());
    }

    @Test
    void tinyComposedBudgetPersistsAnIncompleteOutcome() throws Exception {
        assertPersistedComposition(50, false, true);
    }

    @Test
    void truncatingOnlyTheRemainingAnswerPersistsAnIncompleteOutcome() throws Exception {
        assertPersistedComposition(composedPrefix().length() + 5, false, true);
    }

    @Test
    void exactComposedBudgetRetainsTheCompleteOutcome() throws Exception {
        assertPersistedComposition(composedPrefix().length() + "Recursion calls itself.".length(), false, false);
    }

    @Test
    void normalComposedBudgetRetainsTheCompleteOutcome() throws Exception {
        assertPersistedComposition(10_000, false, false);
    }

    @Test
    void streamedTinyComposedBudgetPersistsTheSameIncompleteAnswer() throws Exception {
        assertPersistedComposition(50, true, true);
    }

    private void assertPersistedComposition(long maxChars, boolean streaming, boolean truncated) throws Exception {
        String request = "Who are you? Explain recursion.";
        String providerAnswer = "Recursion calls itself.";
        assertTrue(providerAnswer.length() <= maxChars,
                "The fixture must lose completeness only at identity composition");
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(providerAnswer,
                AssistantTurnRuntimeIdentityCompositionTest::identity);
        var visible = new StringBuilder();
        var completions = new AtomicInteger();
        var config = new Config();
        var contextBuilder = Context.builder(config).llm(fixture.client());
        if (streaming) contextBuilder.streamSink(new ToolCallStreamFilter(visible::append))
                .onStreamComplete(completions::incrementAndGet);
        var context = contextBuilder.build();
        String sessionId = JsonSessionStore.newSessionInstanceId(workspace, Instant.parse("2026-09-30T00:00:00Z"));
        Path storeRoot = workspace.resolve("store");
        var store = new JsonSessionStore(storeRoot);
        try (LlmClient llm = fixture.client(); var writer = store.acquireSessionWriter(sessionId)) {
            TurnRouter router = (raw, routedWorkspace, ignoredContext) -> {
                var output = AssistantTurnExecutor.execute(new ArrayList<>(List.of(
                        ChatMessage.system("You are Talos."), ChatMessage.user(raw))), routedWorkspace,
                        context, new AssistantTurnExecutor.Options().responseMaxChars(maxChars));
                assertFalse(output.streamed());
                return Optional.of(new Result.Ok(output.text()));
            };
            var processor = new TurnProcessor(router);
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));
            var result = processor.process(new Session(workspace, config), request, context);
            String answer = assertInstanceOf(Result.Ok.class, result.result()).text;
            assertEquals(1, fixture.requests().size(), "Composition must not generate another provider answer");
            assertTrue(result.audit().toolCalls().isEmpty());
            assertEquals(truncated, answer.contains("[output truncated]"), answer);
            if (truncated) assertFalse(answer.contains(providerAnswer), answer);
            else assertEquals(composedPrefix() + providerAnswer, answer);
            assertTrue(visible.isEmpty());
            assertEquals(streaming ? 1 : 0, completions.get());

            var reopened = new JsonSessionStore(storeRoot);
            assertEquals(1, reopened.loadTurns(sessionId).size());
            var row = reopened.loadTurns(sessionId).getFirst();
            assertEquals("ok", row.status());
            assertEquals(MemoryUpdateListener.assistantTextForPersistence(answer, request), row.assistantText());
            assertTrue(row.toolCalls().isEmpty());
            var trace = reopened.loadTrace(sessionId, row.traceId()).orElseThrow();
            String status = truncated ? "ADVISORY_ONLY" : "COMPLETE";
            String classification = truncated ? "ADVISORY_ONLY" : "READ_ONLY_ANSWERED";
            assertEquals(status, trace.outcome().status());
            assertEquals(classification, trace.outcome().classification());
            assertEquals("NOT_RUN", trace.outcome().verificationStatus());
            var rendered = trace.events().stream().filter(event -> "OUTCOME_RENDERED".equals(event.type())).toList();
            assertEquals(1, rendered.size(), "Exactly one final rendered outcome must be persisted");
            assertEquals(status, rendered.getFirst().data().get("status"));
            assertEquals(classification, rendered.getFirst().data().get("classification"));
            assertEquals(truncated, trace.warnings().stream().anyMatch(warning ->
                    "RUNTIME_IDENTITY_OUTPUT_LIMIT".equals(warning.code())), trace.warnings().toString());
        }
    }

    private static String composedPrefix() {
        return "Runtime identity\n" + RuntimeModelIdentityAnswerPolicy.render(identity()) + "\n\nRemaining request\n";
    }

    @Test
    void aPriorIdentityQuestionCannotSelectTheCurrentComponent() {
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity("Recursion calls itself.",
                AssistantTurnRuntimeIdentityCompositionTest::identity);
        beginTrace();
        var result = AssistantTurnExecutor.execute(new ArrayList<>(List.of(ChatMessage.system("You are Talos."),
                ChatMessage.user("Who are you?"), ChatMessage.assistant("An older answer."),
                ChatMessage.user("Explain recursion."))), workspace,
                Context.builder(new Config()).llm(fixture.client()).build(), new AssistantTurnExecutor.Options());
        assertEquals("Recursion calls itself.", result.text());
    }

    private String read(String request, String providerAnswer, ModelIdentitySnapshot snapshot) throws Exception {
        String original = "Review owner: Mara.\nPackaging remains pending.\n";
        Files.writeString(workspace.resolve("README.md"), original);
        var reads = new AtomicInteger();
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(List.of(
                new LlmClient.StreamResult("{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}", List.of()),
                new LlmClient.StreamResult(providerAnswer, List.of())), () -> {
                    reads.incrementAndGet();
                    return snapshot;
                });
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        fixture.client().setToolSpecs(registry.descriptors().stream().map(descriptor ->
                new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema())).toList());
        var context = Context.builder(new Config()).llm(fixture.client()).sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry).toolCallLoop(new ToolCallLoop(
                        new TurnProcessor(null, new NoOpApprovalGate(), registry), 3)).build();
        TurnAuditCapture.begin();
        String answer = execute(request, context, new AssistantTurnExecutor.Options()).text();
        var audit = TurnAuditCapture.end();
        assertEquals(1, reads.get());
        assertTrue(audit.toolCalls().stream().anyMatch(call -> "talos.read_file".equals(call.name()) && call.success()),
                audit.toolCalls().toString());
        assertEquals(original, Files.readString(workspace.resolve("README.md")));
        assertTrue(fixture.requests().getLast().messages.stream().anyMatch(message ->
                message.content().contains("Talos will render the runtime identity separately")), fixture.requests().toString());
        return answer;
    }

    private AssistantTurnExecutor.TurnOutput execute(String request, Context context, AssistantTurnExecutor.Options options) {
        beginTrace();
        return AssistantTurnExecutor.execute(new ArrayList<>(List.of(ChatMessage.system("You are Talos."),
                ChatMessage.user(request))), workspace, context, options);
    }

    private void beginTrace() {
        LocalTurnTraceCapture.clear();
        LocalTurnTraceCapture.begin("identity-composition", "identity-tests", 1,
                "2026-09-29T00:00:00Z", "workspace-hash", "test", "llama_cpp", "fixture", "identity");
    }

    private static ModelIdentitySnapshot identity() {
        return ModelIdentitySnapshot.builder().backend("llama_cpp").configuredModel("gpt-oss-20b")
                .configuredModelFile("actual.gguf").observedModelFile("actual.gguf")
                .resolvedProfile("gpt-oss-20b").artifactState("EXACT").servedIdentityState("MATCHED")
                .resolution("QUALIFIED").propsState("AVAILABLE").chatAvailable(true).build();
    }
}
