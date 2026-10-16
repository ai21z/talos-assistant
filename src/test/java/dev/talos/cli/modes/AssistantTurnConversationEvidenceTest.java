package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.core.security.Sandbox;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.spi.types.ToolSpec;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ModelIdentitySnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssistantTurnConversationEvidenceTest {
    static final String CONCEPTUAL = "Another thing I've been thinking about. If an AI runs locally and has "
            + "policies, guards and a judge checking its answers, does that mean its answers stop being a black box "
            + "and hallucinations are solved? Give me an honest short explanation, not marketing. "
            + "No web search or project inspection is needed.";
    static final String IDENTITY = "By the way, which model is actually answering me in this session? "
            + "If you cannot verify it, say so. Don't guess a model name from how you write.";
    static final String HISTORY = "Have you actually run tests or changed files during this conversation, "
            + "or only inspected them? Separate observed file changes from work performed by you and from "
            + "proposed fixes. Don't run anything now.";
    @TempDir Path workspace;

    @AfterEach
    void clear() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        dev.talos.runtime.TurnTraceCapture.clear();
        dev.talos.cli.prompt.LastPromptCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test
    void conceptualScopeDoesNotRequireFreshWorkspaceEvidence() {
        var contract = TaskContractResolver.resolveFromUserRequest(CONCEPTUAL).contract();
        assertEquals(TaskType.READ_ONLY_QA, contract.type());
        assertFalse(contract.mutationAllowed());
        var fixture = fixture();
        String answer = execute(fixture, CONCEPTUAL, new SessionMemory());
        assertEquals("Local execution alone does not establish answer correctness.", answer);
        assertEquals(1, fixture.requests().size());
        assertTrue(fixture.requests().getFirst().messages.stream().anyMatch(message ->
                "user".equals(message.role()) && CONCEPTUAL.equals(message.content())));
    }

    @Test
    void identityQualifiersUseTypedFactsWithoutProviderOrWorkspaceWork() {
        var fixture = fixture();
        String answer = execute(fixture, IDENTITY, new SessionMemory());
        assertTrue(answer.contains("Runtime-qualified model: gpt-oss-20b."), answer);
        assertTrue(answer.contains("does not establish current process liveness"), answer);
        assertTrue(fixture.requests().isEmpty());
    }

    @Test
    void retrospectiveQuestionUsesRetainedRuntimeEvidenceWithoutFreshReads() {
        var memory = new SessionMemory();
        memory.update("Please inspect the files.", "All tests passed and files were edited.");
        var fixture = fixture();
        String answer = execute(fixture, HISTORY, memory);
        assertTrue(answer.contains("Retained runtime action records"), answer);
        assertTrue(answer.contains("not proof that no earlier action occurred"), answer);
        assertTrue(answer.contains("External file changes"), answer);
        assertFalse(answer.contains("All tests passed"), answer);
        assertTrue(fixture.requests().isEmpty());
    }

    @Test
    void realReadIsAccountedForOnTheNextTurnWithoutAnotherProviderCall() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "A local example.\n");
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(List.of(
                new LlmClient.StreamResult("{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}", List.of()),
                new LlmClient.StreamResult("The README describes a local example.", List.of())),
                ModelIdentitySnapshot::empty);
        fixture.client().setToolSpecs(registry.descriptors().stream().map(descriptor ->
                new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema())).toList());
        var memory = new SessionMemory();
        var context = Context.builder(new Config(null)).memory(memory).llm(fixture.client())
                .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, new NoOpApprovalGate(), registry), 3)).build();
        LocalTurnTraceCapture.begin("conversation-read", "offline", 1,
                "2026-09-29T00:00:00Z", "workspace", "test", "llama_cpp", "fixture", "Read README.md and explain it.");
        TurnAuditCapture.begin();
        String readAnswer = AssistantTurnExecutor.execute(new ArrayList<>(List.of(ChatMessage.system("You are Talos."),
                ChatMessage.user("Read README.md and explain it."))), workspace, context,
                new AssistantTurnExecutor.Options()).text();
        var audit = TurnAuditCapture.end();
        assertEquals(1, audit.toolCalls().size(), readAnswer + " " + audit.toolCalls() + " " + fixture.requests());
        assertTrue(audit.toolCalls().getFirst().success());
        memory.recordToolEvidence(1, audit.toolCalls());
        int dispatches = fixture.requests().size();
        LocalTurnTraceCapture.clear();
        LocalTurnTraceCapture.begin("conversation-history", "offline", 2,
                "2026-09-29T00:00:00Z", "workspace", "test", "llama_cpp", "fixture", HISTORY);
        TurnAuditCapture.begin();
        String answer = AssistantTurnExecutor.execute(new ArrayList<>(List.of(ChatMessage.system("You are Talos."),
                ChatMessage.user(HISTORY))), workspace, context, new AssistantTurnExecutor.Options()).text();
        var historyAudit = TurnAuditCapture.end();
        assertTrue(historyAudit.toolCalls().isEmpty());
        assertEquals(dispatches, fixture.requests().size());
        assertTrue(answer.contains("Inspection calls: 1 reached execution, 1 reported success"), answer);
        assertTrue(answer.contains("Command calls: 0 reached execution"), answer);
        assertEquals("A local example.\n", Files.readString(workspace.resolve("README.md")));
    }

    @ParameterizedTest
    @ValueSource(strings = {HISTORY, CONCEPTUAL, "Have you changed files? Do not read files."})
    void conversationEvidenceDoesNotDiscoverOrLoadProjectMemory(String request) throws Exception {
        Files.writeString(workspace.resolve("TALOS.md"), "Workspace memory must not be loaded for this request.");
        Files.createDirectories(workspace.resolve(".talos"));
        Files.writeString(workspace.resolve(".talos/rules.md"), "Directory rules must not be loaded for this request.");
        var fixture = fixture();
        execute(fixture, request, new SessionMemory());
        var trace = LocalTurnTraceCapture.complete();
        assertTrue(trace.promptAudit().projectMemoryStatus().contains("status=SUPPRESSED"),
                trace.promptAudit().projectMemoryStatus());
        assertFalse(fixture.requests().stream().flatMap(call -> call.messages.stream())
                .anyMatch(message -> message.content().contains("Workspace memory must not")
                        || message.content().contains("Directory rules must not")));
    }

    @Test
    void runtimeQuestionsKeepWorkspacePreloadsOutOfActualModePrompts() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "FRESH_WORKSPACE_CONTENT_SENTINEL");
        Files.writeString(workspace.resolve("TALOS.md"), "FRESH_MEMORY_CONTENT_SENTINEL");
        for (Mode mode : List.of(new UnifiedAssistantMode(), new AskMode(), new PlanMode())) {
            for (String request : List.of(HISTORY, IDENTITY)) {
                clear();
                LocalTurnTraceCapture.begin("mode-evidence", "offline", 1,
                        "2026-09-29T00:00:00Z", "workspace", "test", "llama_cpp", "fixture", request);
                var registry = new ToolRegistry();
                registry.register(new ReadFileTool());
                var fixture = fixture();
                try (var client = fixture.client()) {
                    var context = Context.builder(new Config(null)).memory(new SessionMemory()).llm(client)
                            .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry).build();
                    mode.handle(request, workspace, context);
                    assertTrue(fixture.requests().isEmpty(), mode.name());
                    var capture = dev.talos.cli.prompt.LastPromptCapture.latest().orElseThrow();
                    assertFalse(capture.messages().toString().contains("FRESH_WORKSPACE_CONTENT_SENTINEL"));
                    assertFalse(capture.messages().toString().contains("FRESH_MEMORY_CONTENT_SENTINEL"));
                    var trace = LocalTurnTraceCapture.complete();
                    assertTrue(trace.promptAudit().nativeTools().isEmpty(), mode.name() + trace.promptAudit());
                    assertTrue(trace.promptAudit().projectMemoryStatus().contains("status=SUPPRESSED"));
                }
            }
        }
    }

    private ScriptedNativeLlmClient.RecordedClient fixture() {
        return ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(List.of(
                new LlmClient.StreamResult("Local execution alone does not establish answer correctness.", List.of())),
                () -> ModelIdentitySnapshot.builder().backend("llama_cpp").configuredModel("gpt-oss-20b")
                        .resolvedProfile("gpt-oss-20b").artifactState("EXACT").servedIdentityState("MATCHED")
                        .resolution("QUALIFIED").propsState("AVAILABLE").chatAvailable(true).build());
    }

    private String execute(ScriptedNativeLlmClient.RecordedClient fixture, String request, SessionMemory memory) {
        LocalTurnTraceCapture.begin("conversation-evidence", "offline", 1,
                "2026-09-29T00:00:00Z", "workspace", "test", "llama_cpp", "fixture", request);
        return AssistantTurnExecutor.execute(new ArrayList<>(List.of(ChatMessage.system("You are Talos."),
                        ChatMessage.user(request))), workspace,
                Context.builder(new Config(null)).memory(memory).llm(fixture.client()).build(),
                new AssistantTurnExecutor.Options()).text();
    }
}
