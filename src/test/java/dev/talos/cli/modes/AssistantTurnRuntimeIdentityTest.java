package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.prompt.PromptInspector;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssistantTurnRuntimeIdentityTest {
    @TempDir Path workspace;

    @AfterEach
    void clearCaptures() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        dev.talos.runtime.TurnTraceCapture.clear();
        LastPromptCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test
    void recordedIdentityQuestionUsesTheCurrentSnapshotWithoutGeneration() {
        var client = new IdentityClient(qualified("gpt-oss-20b"));
        String answer = execute(client, "Hey. Which model is being used for this conversation?");
        assertTrue(answer.contains("Runtime-qualified model: gpt-oss-20b."), answer);
        assertFalse(answer.contains("GPT-4"), answer);
        assertEquals(0, client.calls());
        assertEquals(1, client.identityReads);
    }

    @Test
    void unavailableIdentityIsNotInventedByTheProvider() {
        var client = new IdentityClient(ModelIdentitySnapshot.empty());
        String answer = execute(client, "What model are you running?");
        assertTrue(answer.contains("identity is unavailable"), answer);
        assertEquals(0, client.calls());
    }

    @Test
    void workspacePrivacyQualifiedIdentityUsesNoGenerationOrTools() {
        var client = new IdentityClient(qualified("gpt-oss-20b"));
        String answer = execute(client, "Without reading files, which model are you running?");
        assertTrue(answer.contains("Runtime-qualified model: gpt-oss-20b."), answer);
        assertEquals(0, client.calls());
        assertEquals(1, client.identityReads);
    }

    @Test
    void successiveTurnsUseFreshQualifiedOrUnverifiedSnapshots() {
        var client = new IdentityClient(qualified("gpt-oss-20b"));
        assertTrue(execute(client, "What model are you running?").contains("gpt-oss-20b"));
        client.identity = qualified("qwen2.5-coder-14b");
        String switched = execute(client, "What model are you running?");
        assertTrue(switched.contains("Runtime-qualified model: qwen2.5-coder-14b."), switched);
        assertFalse(switched.contains("gpt-oss"), switched);
        client.identity = ModelIdentitySnapshot.builder().backend("llama_cpp")
                .configuredModel("requested-model").resolution("UNVERIFIED")
                .propsState("UNAVAILABLE").build();
        String failed = execute(client, "What model are you running?");
        assertTrue(failed.contains("Configured model: requested-model."), failed);
        assertFalse(failed.contains("Runtime-qualified model:"), failed);
        assertEquals(0, client.calls());
        assertEquals(3, client.identityReads);
    }

    @Test
    void compoundIdentityQuestionRetainsItsOtherRequestedAnswerAndRuntimeFacts() {
        for (String request : List.of(
                "Which model are you running? Explain dependency injection.",
                "Who are you? Explain dependency injection.")) {
            var client = new IdentityClient(qualified("gpt-oss-20b"));
            String answer = execute(client, request);
            assertEquals(1, client.calls(), request);
            assertEquals(1, client.identityReads);
            assertTrue(answer.contains("Runtime-qualified model: gpt-oss-20b."), answer);
            assertTrue(answer.contains("A dependency is supplied by its caller."), answer);
            assertTrue(client.messages().stream().anyMatch(message -> "user".equals(message.role())
                    && "Explain dependency injection.".equals(message.content().strip())), request);
            assertFalse(client.messages().stream().anyMatch(message -> "user".equals(message.role())
                    && request.equals(message.content())), request);
            assertEquals(1, client.messages().stream().filter(message -> "system".equals(message.role())
                    && message.content().startsWith("[RuntimeModelIdentity]")).count());
            assertTrue(client.messages().stream().anyMatch(message -> message.content()
                    .contains("Runtime-qualified model: gpt-oss-20b.")));
        }
    }

    @Test
    void compoundIdentityAndReadRetainTheActualReadBoundary() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "The retry limit is three.\n");
        for (String request : List.of(
                "Which model are you running? Read README.md and explain it.",
                "Who are you? Read README.md and explain it.")) {
            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(List.of(
                    new LlmClient.StreamResult("{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}", List.of()),
                    new LlmClient.StreamResult("The retry limit in README.md is three.", List.of())),
                    () -> qualified("gpt-oss-20b"));
            fixture.client().setToolSpecs(registry.descriptors().stream().map(descriptor ->
                    new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema())).toList());
            var context = Context.builder(new Config()).llm(fixture.client())
                    .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry)
                    .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, new NoOpApprovalGate(), registry), 3)).build();
            beginTrace();
            TurnAuditCapture.begin();
            var originalMessages = new ArrayList<>(List.of(ChatMessage.system("You are Talos."), ChatMessage.user(request)));
            String answer = AssistantTurnExecutor.execute(originalMessages, workspace, context,
                    new AssistantTurnExecutor.Options()).text();
            assertTrue(answer.contains("Runtime-qualified model: gpt-oss-20b."), answer);
            assertTrue(answer.contains("The retry limit in README.md is three."), answer);
            var audit = TurnAuditCapture.end();
            assertTrue(audit.toolCalls().stream().anyMatch(call -> "talos.read_file".equals(call.name())
                    && call.success()), request + " " + audit.toolCalls() + " requests=" + fixture.requests());
            assertEquals("The retry limit is three.\n", Files.readString(workspace.resolve("README.md")));
            assertTrue(originalMessages.stream().anyMatch(message -> request.equals(message.content())));
            for (var dispatch : fixture.requests()) {
                assertTrue(dispatch.messages.stream().anyMatch(message -> "user".equals(message.role())
                        && "Read README.md and explain it.".equals(message.content().strip())), dispatch.messages.toString());
                assertFalse(dispatch.messages.stream().anyMatch(message -> "user".equals(message.role())
                        && request.equals(message.content())), dispatch.messages.toString());
            }
        }
    }

    @Test
    void dispatchedRuntimeFrameOmitsPrivateFieldsAndKeepsUnknownExplicit() {
        var client = new IdentityClient(ModelIdentitySnapshot.builder().backend("llama_cpp")
                .configuredModel("sk-private-credential").configuredModelFile("C:/private/models/model.gguf")
                .engineBuildInfo("http://user:password@private.internal:9000")
                .resolution("UNVERIFIED").propsState("UNAVAILABLE").build());
        execute(client, "Which model are you running? Explain dependency injection.");
        String frame = client.messages().stream().filter(message -> "system".equals(message.role())
                        && message.content().startsWith("[RuntimeModelIdentity]"))
                .findFirst().orElseThrow().content();
        assertTrue(frame.contains("The loaded model is not verified."), frame);
        assertFalse(frame.contains("Runtime-qualified model:"), frame);
        for (String forbidden : List.of("sk-private", "C:", "/private", "password", "private.internal")) {
            assertFalse(frame.contains(forbidden), frame);
        }
        assertEquals(1, client.identityReads);
    }

    @Test
    void modeCaptureAndDispatchShareOneSnapshot() throws Exception {
        for (Mode mode : List.of(new UnifiedAssistantMode(), new AskMode(), new PlanMode(), new RagMode())) {
            var client = new IdentityClient(qualified("gpt-oss-20b"));
            var context = Context.builder(new Config()).llm(client.client()).build();
            beginTrace();
            mode.handle("Explain dependency injection.", workspace, context);
            assertEquals(1, client.identityReads, mode.name());
            String captured = identityFrame(LastPromptCapture.latest().orElseThrow().messages());
            assertEquals(captured, identityFrame(client.messages()), mode.name());
            assertTrue(captured.contains("Runtime-qualified model: gpt-oss-20b."), captured);
        }
    }

    @Test
    void standalonePreviewAndActualTurnUseTheSameQualifiedProjection() throws Exception {
        var client = new IdentityClient(qualified("gpt-oss-20b"));
        var context = Context.builder(new Config()).llm(client.client()).build();
        String request = "Explain dependency injection.";
        var preview = PromptInspector.renderNext("auto", request, workspace, context);
        assertEquals(1, client.identityReads);
        beginTrace();
        new UnifiedAssistantMode().handle(request, workspace, context);
        assertEquals(2, client.identityReads);
        assertEquals(preview.messages(), client.messages());
    }

    @Test
    void mixedPreviewAndDispatchShareTheCompositionInstruction() throws Exception {
        var client = new IdentityClient(ModelIdentitySnapshot.empty());
        var context = Context.builder(new Config()).llm(client.client()).build();
        String request = "Who are you? Explain dependency injection.";
        var preview = PromptInspector.renderNext("auto", request, workspace, context);
        assertEquals(1, client.identityReads);
        beginTrace();
        new UnifiedAssistantMode().handle(request, workspace, context);
        assertEquals(2, client.identityReads);
        assertEquals(preview.messages(), client.messages());
        assertTrue(identityFrame(client.messages()).contains("Talos will render the runtime identity separately"));
        assertTrue(client.messages().stream().anyMatch(message -> "user".equals(message.role())
                && "Explain dependency injection.".equals(message.content().strip())));
    }

    @Test
    void rawWhitespaceAndCrLfSharePreviewAndDispatchOwnership() throws Exception {
        for (String request : List.of("  Who are you? Explain dependency injection.  ",
                "Who are you?\r\nExplain dependency injection.")) {
            var client = new IdentityClient(qualified("gpt-oss-20b"));
            var context = Context.builder(new Config()).llm(client.client()).build();
            var preview = PromptInspector.renderNext("auto", request, workspace, context);
            beginTrace();
            new UnifiedAssistantMode().handle(request, workspace, context);
            assertEquals(preview.messages(), client.messages(), request);
            assertTrue(client.messages().stream().anyMatch(message -> "user".equals(message.role())
                    && "Explain dependency injection.".equals(message.content().strip())), request);
            assertFalse(client.messages().stream().anyMatch(message -> "user".equals(message.role())
                    && message.content().contains("Who are you?")), request);
        }
    }

    @Test
    void rawControlBytesCannotCreateProjectionAfterPreviewSanitization() throws Exception {
        for (String request : List.of("\rWho are you? Explain dependency injection.",
                "Who\u000bare you? Explain dependency injection.")) {
            var client = new IdentityClient(qualified("gpt-oss-20b"));
            var context = Context.builder(new Config()).llm(client.client()).build();
            var preview = PromptInspector.renderNext("auto", request, workspace, context);
            beginTrace();
            new UnifiedAssistantMode().handle(request, workspace, context);
            assertEquals(preview.messages(), client.messages(), request);
            assertTrue(client.messages().stream().anyMatch(message -> "user".equals(message.role())
                    && message.content().contains("Who")), request);
            assertFalse(identityFrame(client.messages()).contains("Talos will render the runtime identity separately"));
        }
    }

    @Test
    void explicitlyUnavailableSnapshotDoesNotTriggerAnotherIdentityRead() {
        var client = new IdentityClient(qualified("gpt-oss-20b"));
        beginTrace();
        String answer = AssistantTurnExecutor.execute(new ArrayList<>(List.of(ChatMessage.system("You are Talos."),
                        ChatMessage.user("What model are you running?"))), workspace,
                Context.builder(new Config()).llm(client.client()).build(),
                new AssistantTurnExecutor.Options().runtimeIdentity(ModelIdentitySnapshot.empty())).text();
        assertTrue(answer.contains("identity is unavailable"), answer);
        assertEquals(0, client.identityReads);
        assertEquals(0, client.calls());
    }

    @Test
    void staleRuntimeFrameIsReplacedWithoutChangingTheUserRequest() {
        var client = new IdentityClient(qualified("qwen2.5-coder-14b"));
        var messages = new ArrayList<>(List.of(ChatMessage.system("You are Talos."),
                ChatMessage.system("[RuntimeModelIdentity]\nRuntime-qualified model: old-model."),
                ChatMessage.user("Explain dependency injection.")));
        beginTrace();
        AssistantTurnExecutor.execute(messages, workspace,
                Context.builder(new Config()).llm(client.client()).build(), new AssistantTurnExecutor.Options());
        assertEquals(1, client.calls());
        assertFalse(client.messages().toString().contains("old-model"));
        assertTrue(client.messages().toString().contains("Runtime-qualified model: qwen2.5-coder-14b."));
    }

    private String identityFrame(List<ChatMessage> messages) {
        var frames = messages.stream().filter(message -> "system".equals(message.role())
                && message.content().startsWith("[RuntimeModelIdentity]")).toList();
        assertEquals(1, frames.size());
        return frames.getFirst().content();
    }

    private String execute(IdentityClient client, String request) {
        beginTrace();
        return AssistantTurnExecutor.execute(new ArrayList<>(List.of(
                ChatMessage.system("You are Talos."), ChatMessage.user(request))), workspace,
                Context.builder(new Config()).llm(client.client()).build(), new AssistantTurnExecutor.Options()).text();
    }

    private void beginTrace() {
        LocalTurnTraceCapture.clear();
        LocalTurnTraceCapture.begin("identity-boundary", "identity-tests", 1,
                "2026-09-27T00:00:00Z", "workspace-hash", "test", "llama_cpp", "fixture", "identity");
    }

    private static ModelIdentitySnapshot qualified(String model) {
        return ModelIdentitySnapshot.builder().backend("llama_cpp").configuredModel(model)
                .resolvedProfile(model).artifactState("EXACT").servedIdentityState("MATCHED")
                .resolution("QUALIFIED").propsState("AVAILABLE").chatAvailable(true).build();
    }

    private static final class IdentityClient {
        private ModelIdentitySnapshot identity;
        private int identityReads;
        private final ScriptedNativeLlmClient.RecordedClient recording;

        IdentityClient(ModelIdentitySnapshot identity) {
            this.identity = identity;
            recording = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(
                    "A dependency is supplied by its caller.", () -> {
                        identityReads++;
                        return this.identity;
                    });
        }

        LlmClient client() {
            return recording.client();
        }

        int calls() {
            return recording.requests().size();
        }

        List<ChatMessage> messages() {
            return recording.requests().getLast().messages;
        }
    }
}
