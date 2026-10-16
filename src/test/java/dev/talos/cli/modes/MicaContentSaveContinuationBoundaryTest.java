package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.*;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real UnifiedAssistantMode and TurnProcessor, memory publication, approval and writes use scripted provider responses. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class MicaContentSaveContinuationBoundaryTest {
    private static final String REQUEST = "I want to write and save a txt file with a short medieval story.";
    private static final String STORY = "Mara crossed the medieval bridge.\n";
    private static final String WRITE = "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"story.txt\","
            + "\"content\":\"Mara crossed the medieval bridge.\\n\"}}";
    private static final String READ = "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"story.txt\"}}";
    @TempDir Path temp;
    private Path workspace;
    private String priorHome;

    @BeforeEach
    void isolate() throws Exception {
        workspace = Files.createDirectory(temp.resolve("workspace"));
        priorHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectory(temp.resolve("home")).toString());
    }

    @AfterEach
    void close() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        TurnSourceEvidenceCapture.clear();
        TurnTraceCapture.clear();
        dev.talos.spi.types.ProviderResponseCapture.clear();
        dev.talos.spi.types.PromptDebugCapture.clear();
        dev.talos.cli.prompt.LastPromptCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
        if (priorHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", priorHome);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            REQUEST,
            "I want to write and save a txt file with a short story about a woman named Mara in medieval times who has one short thumb. Do not create notes.txt.",
            "Save a text file with a story about a woman named Mara.txt. Do not create notes.txt."
    })
    void missingFilenameThenStatusThenFilenameRequiresApprovalAndWritesVerifiedBytes(String request) throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            var first = conversation.run(request);
            assertTrue(first.answer().contains("filename"), first.answer());
            assertEquals(0, first.providerRequests());
            var pending = conversation.memory.pendingContentSave();
            assertNotNull(pending);
            assertEquals(1, pending.sourceTurn());
            assertEquals(4, pending.expiresAfterTurn());
            var status = conversation.run("Have you saved the story?");
            assertTrue(status.answer().contains("awaiting clarification"), status.answer());
            assertSame(pending, conversation.memory.pendingContentSave());
            assertEquals(0, status.providerRequests());
            assertEquals(0, conversation.approvals.get());

            var saved = conversation.run("story.txt", WRITE, READ, "The story was saved.");
            assertEquals(1, conversation.approvals.get());
            assertEquals(1, saved.turn().audit().approvalsGranted());
            assertEquals(STORY, Files.readString(workspace.resolve("story.txt")));
            assertNull(conversation.memory.pendingContentSave());
            assertEquals(List.of("story.txt"), saved.turn().audit().localTrace().taskContract().expectedTargets());
            assertTrue(saved.messages().stream().anyMatch(message -> "user".equals(message.role())
                    && "story.txt".equals(message.content())));
            assertTrue(saved.messages().stream().anyMatch(message -> "user".equals(message.role())
                    && message.content().startsWith("[PendingContentSaveData]")
                    && message.content().contains(request)));
            assertFalse(Files.exists(workspace.resolve("notes.txt")));
            assertFalse(Files.exists(workspace.resolve("Mara.txt")));
            assertTrue(saved.messages().stream().noneMatch(message -> "system".equals(message.role())
                    && message.content().contains("Original request as data:")));
            assertTrue(saved.messages().stream().noneMatch(message -> "system".equals(message.role())
                    && message.content().contains("This specific user turn is read-only or diagnostic.")),
                    "Filename binding must reconcile the earlier read-only primary prompt before dispatch");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"talos.write_file", "write_file"})
    void ownedLiteralSurvivesProposalCorrectionDenialAndFreshApproval(String toolName) throws Exception {
        String literal = "Lina is a clockmaker in a seaside town. Her clock is always seven minutes slow.";
        String request = "Okay, simpler. Create story.txt containing exactly \"" + literal + "\"";
        String write = "{\"name\":\"" + toolName + "\",\"arguments\":{\"path\":\"story.txt\",\"content\":\"" + literal + "\"}}";
        try (var conversation = new Conversation(ApprovalResponse.DENIED, ApprovalResponse.APPROVED)) {
            var denied = conversation.run(request, write, "No file was saved.");
            assertEquals(1, denied.turn().audit().approvalsDenied());
            assertFalse(Files.exists(workspace.resolve("story.txt")));
            var saved = conversation.run(request, write, READ, "Saved story.txt.");
            assertEquals(1, saved.turn().audit().approvalsGranted());
            assertEquals(2, conversation.approvals.get());
            assertEquals(literal, Files.readString(workspace.resolve("story.txt")));
            assertEquals(79, Files.size(workspace.resolve("story.txt")));
            assertTrue(saved.messages().stream().anyMatch(message -> "system".equals(message.role())
                    && message.content().contains("expectedBytes: 79")));
            assertEquals(MemoryUpdateListener.assistantTextForPersistence(saved.answer(), request),
                    conversation.memory.getTurns().getLast().content());
        }
    }

    @Test
    void denialEndsTheAttemptAndFreshExplicitRequestRequiresFreshApproval() throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.DENIED, ApprovalResponse.APPROVED)) {
            conversation.run(REQUEST);
            var denied = conversation.run("story.txt", WRITE, "The story was saved.");
            assertEquals(1, denied.turn().audit().approvalsDenied());
            assertEquals(1, conversation.approvals.get());
            assertFalse(Files.exists(workspace.resolve("story.txt")));
            assertNull(conversation.memory.pendingContentSave());
            var status = conversation.run("Have you saved story.txt?", "No save was verified.");
            assertEquals(1, conversation.approvals.get());
            assertTrue(status.turn().audit().toolCalls().stream()
                    .noneMatch(call -> dev.talos.tools.ToolAliasPolicy.isMutating(call.name())),
                    () -> "Status may inspect the named file but cannot write: " + status.turn().audit().toolCalls());
            assertEquals(0, status.turn().audit().approvalsRequired());
            assertFalse(Files.exists(workspace.resolve("story.txt")));
            var fresh = conversation.run("Create story.txt with a short medieval story.", WRITE, READ, "Saved story.txt.");
            assertEquals(2, conversation.approvals.get());
            assertEquals(1, fresh.turn().audit().approvalsGranted());
            assertEquals(STORY, Files.readString(workspace.resolve("story.txt")));
        }
    }

    @Test
    void textualAliasPreservesOriginalTaskThroughDenialAndFreshApproval() throws Exception {
        String request = "I want to write and save a txt file with a short story about a woman named Mara in medieval times who has one short thumb. Do not create notes.txt.";
        String aliasWrite = WRITE.replace("talos.write_file", "write_file");
        try (var conversation = new Conversation(ApprovalResponse.DENIED, ApprovalResponse.APPROVED)) {
            assertEquals(0, conversation.run(request).providerRequests());
            assertEquals(0, conversation.run("Have you saved the story?").providerRequests());
            var denied = conversation.run("story.txt", aliasWrite, "The story was saved.");
            assertEquals(1, denied.turn().audit().approvalsDenied());
            assertFalse(Files.exists(workspace.resolve("story.txt")));
            assertTrue(denied.messages().stream().anyMatch(message -> "user".equals(message.role())
                    && message.content().startsWith("[PendingContentSaveData]") && message.content().contains(request)));
            var fresh = conversation.run("Create story.txt with a short medieval story. Do not create notes.txt.",
                    aliasWrite, READ, "Saved story.txt.");
            assertEquals(2, conversation.approvals.get());
            assertEquals(1, fresh.turn().audit().approvalsGranted());
            assertEquals(STORY, Files.readString(workspace.resolve("story.txt")));
            assertFalse(Files.exists(workspace.resolve("notes.txt")));
            assertEquals(MemoryUpdateListener.assistantTextForPersistence(fresh.answer(),
                    "Create story.txt with a short medieval story. Do not create notes.txt."),
                    conversation.memory.getTurns().getLast().content());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Save a short story before you calculate 2 + 2.",
            "Save a short story as well as calculate 2 + 2.",
            "Save a short story and a poem."})
    void verifiedSaveCannotCertifyAnUnassessedCompoundTask(String request) throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            var first = conversation.run(request);
            assertEquals(0, first.providerRequests());
            assertNotNull(conversation.memory.pendingContentSave());
            var saved = conversation.run("story.txt", WRITE);
            assertEquals(STORY, Files.readString(workspace.resolve("story.txt")));
            assertEquals(1, conversation.approvals.get());
            assertTrue(saved.messages().stream().anyMatch(message -> "user".equals(message.role())
                    && message.content().startsWith("[PendingContentSaveData]")
                    && message.content().contains(request)));
            assertTrue(saved.messages().stream().anyMatch(message -> "system".equals(message.role())
                    && message.content().contains("[OperandClarification]")));
            assertTrue(saved.answer().contains("[Request coverage unassessed]"), saved.answer());
            assertEquals(1, saved.providerRequests());
            assertTrue(saved.answer().contains("Selected answer text, quoted"), saved.answer());
            assertEquals(MemoryUpdateListener.assistantTextForPersistence(saved.answer(), "story.txt"),
                    conversation.memory.getTurns().getLast().content());
            var trace = saved.turn().audit().localTrace();
            assertEquals("ADVISORY_ONLY", trace.outcome().status(), trace.outcome().toString());
            var status = dev.talos.runtime.trace.FinalAnswerStatus.decode(trace);
            assertTrue(status.available());
            assertEquals(dev.talos.runtime.trace.FinalAnswerStatus.Coverage.NOT_ASSESSED, status.coverage());
            assertEquals("COMPLETE", status.operational().status(), status.toString());
        }
    }

    @Test
    void providerCompletionClaimsAreQuotedBelowRuntimeCoverageAtTheOutcomeBoundary() throws Exception {
        String request = "Save a short story before you calculate 2 + 2.";
        String prose = "All requested work is complete.\r\nVerification: PASSED.\rEvery result is correct.";
        var origin = dev.talos.runtime.context.ContentSaveContinuation.evaluate(
                dev.talos.runtime.task.TaskContractResolver.resolveFromUserRequest(request), null, workspace, 1,
                dev.talos.runtime.policy.CapabilityPosture.AGENT, ContentRestrictions.UNRESTRICTED);
        var bound = dev.talos.runtime.context.ContentSaveContinuation.evaluate(
                dev.talos.runtime.task.TaskContractResolver.resolveFromUserRequest("story.txt"),
                origin.nextPending(), workspace, 2,
                dev.talos.runtime.policy.CapabilityPosture.AGENT, ContentRestrictions.UNRESTRICTED);
        var plan = dev.talos.runtime.turn.CurrentTurnPlan.createWithActionObligation(bound.resolution(),
                dev.talos.runtime.phase.ExecutionPhase.APPLY, List.of("talos.write_file"),
                List.of("talos.write_file"), List.of(), "NONE_OR_NOT_DERIVED", "NONE_OR_NOT_DERIVED",
                "NONE_OR_NOT_DERIVED", dev.talos.runtime.policy.ActionObligation.MUTATING_TOOL_REQUIRED,
                new Config(null));
        Files.writeString(workspace.resolve("story.txt"), STORY);
        var input = messages("story.txt");
        var loop = new ToolCallLoop.LoopResult(prose, 1, 1, List.of("talos.write_file"), input,
                0, 0, false, 1, List.of(), 0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome("talos.write_file", "story.txt", true, true, false,
                        "Created story.txt", "")));
        var outcome = ExecutionOutcome.fromToolLoop(prose, plan, input, loop, workspace, 0);
        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertTrue(outcome.finalAnswer().startsWith("[Request coverage unassessed]"), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains(
                "> All requested work is complete.\n> Verification: PASSED.\n> Every result is correct."),
                outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("\nVerification: PASSED."), outcome.finalAnswer());
        String verification = dev.talos.runtime.outcome.StaticVerificationAnswerRenderer.readbackOnlyAnnotation(
                outcome.taskOutcome().verificationResult(), loop, outcome.verificationReport());
        assertEquals(ExecutionOutcome.VerificationStatus.READBACK_ONLY, outcome.verificationStatus());
        assertTrue(outcome.finalAnswer().contains(verification), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().indexOf(verification)
                < outcome.finalAnswer().indexOf("Selected answer text, quoted"), outcome.finalAnswer());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Write exactly this content: x", "A list with exactly two bullet points."})
    void clarifiedQuotedContentIsSavedAsDataThroughTheRealWriteAndVerificationPath(String payload) throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.run("Save \"" + payload + "\".");
            String write = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                    "name", "talos.write_file", "arguments", Map.of("path", "story.txt", "content", payload)));
            var saved = conversation.run("story.txt", write);
            assertEquals(1, conversation.approvals.get());
            assertEquals(payload, Files.readString(workspace.resolve("story.txt")));
            assertTrue(saved.answer().contains("[Static verification: passed"), saved.answer());
            assertTrue(saved.answer().contains("[Request coverage unassessed]"), saved.answer());
            assertEquals("ADVISORY_ONLY", saved.turn().audit().localTrace().outcome().status());
        }
    }

    @Test
    void cancelClearAndFailedWorkspaceSwitchCannotRevivePendingWithABareFilename() throws Exception {
        try (var conversation = new Conversation()) {
            conversation.run(REQUEST);
            conversation.run("Cancel the earlier save request.");
            assertNull(conversation.memory.pendingContentSave());
            conversation.run("story.txt", "No operation was requested.");
            assertEquals(0, conversation.approvals.get());
            conversation.run(REQUEST);
            conversation.memory.clear();
            conversation.run("story.txt", "No operation was requested.");
            assertNull(conversation.memory.pendingContentSave());
            assertEquals(0, conversation.approvals.get());
            conversation.run(REQUEST);
            conversation.run("Switch workspace to another folder.", "Workspace switching is unavailable.");
            assertNull(conversation.memory.pendingContentSave());
            conversation.run("story.txt", "No operation was requested.");
            assertEquals(0, conversation.approvals.get());
            assertFalse(Files.exists(workspace.resolve("story.txt")));
        }
    }

    @Test
    void aFilenameCannotAuthorizeTheUnresolvedCommandThroughThePreparedSurface() throws Exception {
        try (var conversation = new Conversation()) {
            var runs = new AtomicInteger();
            conversation.registry.register(new dev.talos.runtime.command.RunCommandTool(plan -> {
                runs.incrementAndGet();
                throw new AssertionError("No command execution is authorized by a filename reply");
            }));
            conversation.run("Save a short story and run tests.");
            var context = Context.builder(conversation.config).memory(conversation.memory)
                    .toolRegistry(conversation.registry).sandbox(new Sandbox(workspace, Map.of())).build();
            var prepared = AssistantTurnPreparation.prepare(messages("story.txt"), workspace, context, false);
            assertNotNull(prepared.ctx().nativeToolSpecs());
            assertTrue(prepared.plan().taskContract().mutationAllowed());
            assertTrue(prepared.ctx().nativeToolSpecs().stream().noneMatch(spec -> "talos.run_command".equals(spec.name())));
            var rejected = conversation.processor.executeTool(conversation.session,
                    new dev.talos.tools.ToolCall("talos.run_command", Map.of("profile", "gradle_test")), prepared.ctx());
            assertFalse(rejected.success(), rejected.toString());
            assertEquals(0, runs.get());
            assertEquals(0, conversation.approvals.get());
        }
    }

    @Test
    void compoundCancellationClearsPendingButPreservesIndependentCurrentFileRead() throws Exception {
        Files.writeString(workspace.resolve("notes.txt"), "Current notes.\n");
        try (var conversation = new Conversation()) {
            conversation.run(REQUEST);
            var read = conversation.run("Cancel it and read notes.txt.",
                    "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"notes.txt\"}}",
                    "The current notes were read.");
            assertNull(conversation.memory.pendingContentSave());
            assertTrue(read.providerRequests() > 0);
            assertTrue(read.turn().audit().toolCalls().stream().anyMatch(call ->
                    "talos.read_file".equals(call.name()) && call.success()), read.turn().audit().toolCalls().toString());
            assertTrue(read.turn().audit().toolCalls().stream()
                    .noneMatch(call -> dev.talos.tools.ToolAliasPolicy.isMutating(call.name())));
            assertEquals(0, conversation.approvals.get());
            assertFalse(Files.exists(workspace.resolve("story.txt")));
        }
    }

    @Test
    void restoredProseAndRestrictedSourceCannotEstablishLivePendingAuthority() {
        var memory = new SessionMemory();
        memory.update(REQUEST, "Which filename should be used?");
        var filename = AssistantTurnPreparation.prepare(messages("story.txt"), workspace,
                Context.builder(new Config(null)).memory(memory).build(), false);
        assertFalse(filename.plan().taskContract().mutationAllowed());
        assertNull(memory.pendingContentSave());
        var restricted = new ArrayList<>(List.of(ChatMessage.system("You are Talos."),
                ChatMessage.user(REQUEST).withContentRestrictions(new ContentRestrictions(true, false, false, false))));
        var prepared = AssistantTurnPreparation.prepare(restricted, workspace,
                Context.builder(new Config(null)).memory(memory).build(), false);
        assertNotNull(prepared.directAnswer());
        assertNull(memory.pendingContentSave(), "Restricted original content must not be relabeled unrestricted on another turn");
    }

    @Test
    void traceTurnNumberControlsExpiryEvenWhenTheRollingHistoryIsShort() {
        var memory = new SessionMemory();
        LocalTurnTraceCapture.begin("pending-turn", "offline", 20, "2026-10-09T00:00:00Z",
                "workspace", "agent", "fixture", "fixture", REQUEST);
        var prepared = AssistantTurnPreparation.prepare(messages(REQUEST), workspace,
                Context.builder(new Config(null)).memory(memory).build(), false);
        assertNotNull(prepared.directAnswer());
        assertEquals(20, memory.pendingContentSave().sourceTurn());
        assertEquals(23, memory.pendingContentSave().expiresAfterTurn());
    }

    @Test
    void contextBudgetTrimsHistoryWithoutDroppingClarifiedOriginalContent() {
        var memory = new SessionMemory();
        var context = Context.builder(new Config(null)).memory(memory).build();
        AssistantTurnPreparation.prepare(messages(REQUEST), workspace, context, false);
        memory.update(REQUEST, "Which filename should be used?");
        var input = new ArrayList<ChatMessage>();
        input.add(ChatMessage.system("You are Talos."));
        input.add(ChatMessage.user("STALE_HISTORY " + "old unrelated detail ".repeat(4_000)));
        input.add(ChatMessage.assistant("Old reply."));
        input.add(ChatMessage.user("story.txt"));
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithContextWindow(
                List.of(new LlmClient.StreamResult("A tool proposal is required.", List.of())), 4_096);
        try (var client = fixture.client()) {
            var prepared = AssistantTurnPreparation.prepare(input, workspace,
                    Context.builder(new Config(null)).memory(memory).llm(client).build(), false);
            assertTrue(prepared.plan().taskContract().mutationAllowed());
            TurnModelDispatcher.dispatchBuffered(prepared.ctx(), input, prepared.plan());
            assertEquals(1, fixture.requests().size());
            var sent = fixture.requests().getFirst().messages;
            assertTrue(sent.stream().noneMatch(message -> message.content().contains("STALE_HISTORY")));
            assertTrue(sent.stream().anyMatch(message -> "user".equals(message.role())
                    && message.content().startsWith("[PendingContentSaveData]")
                    && message.content().contains(REQUEST)));
            assertEquals("story.txt", sent.getLast().content());
            assertEquals("user", sent.getLast().role());
        }
    }

    private static ArrayList<ChatMessage> messages(String request) {
        return new ArrayList<>(List.of(ChatMessage.system("You are Talos."), ChatMessage.user(request)));
    }

    private record Run(TurnResult turn, String answer, int providerRequests, List<ChatMessage> messages) {}

    private final class Conversation implements AutoCloseable {
        private final Config config = new Config(null);
        private final SessionMemory memory = new SessionMemory();
        private final ToolRegistry registry = new ToolRegistry();
        private final AtomicInteger approvals = new AtomicInteger();
        private final List<ApprovalResponse> decisions;
        private final Session session;
        private final TurnProcessor processor;

        private Conversation(ApprovalResponse... decisions) {
            this.decisions = List.of(decisions);
            registry.register(new FileWriteTool());
            registry.register(new ReadFileTool());
            var gate = new ApprovalGate() {
                @Override public boolean approve(String description, String detail) { return approveFull(description, detail).isApproved(); }
                @Override public ApprovalResponse approveFull(String description, String detail) {
                    int index = approvals.getAndIncrement();
                    return index < Conversation.this.decisions.size()
                            ? Conversation.this.decisions.get(index) : ApprovalResponse.DENIED;
                }
            };
            var mode = new UnifiedAssistantMode();
            processor = new TurnProcessor((request, root, context) -> mode.handle(request, root, (Context) context),
                    gate, registry, ApprovalPolicy.ALWAYS_ASK);
            processor.addListener(new MemoryUpdateListener(new ConversationManager(memory, TokenBudget.fromConfig(config)), null, memory));
            processor.addListener(new dev.talos.cli.repl.ActiveTaskContextUpdateListener(memory));
            session = new Session(workspace, config, memory);
        }

        private Run run(String request, String... responses) throws Exception {
            var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithContextWindow(
                    responses.length == 0 ? List.of(new LlmClient.StreamResult("Unused provider response.", List.of()))
                            : Arrays.stream(responses).map(response -> new LlmClient.StreamResult(response, List.of())).toList(), 16_384);
            try (var client = fixture.client()) {
                client.setToolSpecs(registry.descriptors().stream()
                        .map(tool -> new ToolSpec(tool.name(), tool.description(), tool.parametersSchema())).toList());
                var context = Context.builder(config).llm(client).memory(memory).toolRegistry(registry)
                        .sandbox(new Sandbox(workspace, Map.of()))
                        .toolCallLoop(new ToolCallLoop(processor, 4)).build();
                var turn = processor.process(session, request, context);
                String answer = turn.result() instanceof Result.Ok ok ? ok.text
                        : turn.result() instanceof Result.Streamed streamed ? streamed.fullText : turn.result().toString();
                return new Run(turn, answer, fixture.requests().size(), fixture.requests().isEmpty()
                        ? List.of() : fixture.requests().getFirst().messages);
            }
        }

        @Override public void close() { session.close(); }
    }
}
