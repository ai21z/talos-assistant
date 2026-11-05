package dev.talos.cli.repl;

import dev.talos.cli.approval.CliApprovalGate;
import dev.talos.cli.modes.ModeController;
import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.repl.slash.CommandRegistry;
import dev.talos.core.Audit;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.net.NetPolicy;
import dev.talos.core.rag.RagService;
import dev.talos.core.security.Redactor;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.JsonTurnLogAppender;
import dev.talos.runtime.MemoryUpdateListener;
import dev.talos.runtime.Session;
import dev.talos.runtime.SessionApprovalPolicy;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.SessionWriterLease;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.TurnSourceEvidenceCapture;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnTraceCapture;
import dev.talos.runtime.TurnUserRequestCapture;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.context.RepairOffer;
import dev.talos.runtime.command.CommandProfileRegistry;
import dev.talos.runtime.command.ProcessCommandRunner;
import dev.talos.runtime.command.RunCommandTool;
import dev.talos.runtime.toolcall.DocumentComparisonTool;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.workspace.BatchWorkspaceApplyTool;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.CopyPathTool;
import dev.talos.tools.impl.DeletePathTool;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.GrepTool;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.MakeDirectoryTool;
import dev.talos.tools.impl.MovePathTool;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.RenamePathTool;
import dev.talos.tools.impl.RetrieveTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A requested replacement whose old text occurred twice before the change, through the real REPL entry (finding F1,
 * P17, D11 option A), with the harness of WithheldOperationReplEntryTest: the bootstrap composition with a scripted
 * native provider that records every request, the TalosBootstrap tool registry, mode routing, the real approval gate
 * and a real session store. The provider and the command profile registry are synthetic.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@Timeout(120)
class ReplacementScopeReplEntryTest {
    private static final String REQUEST =
            "In config.ini, change only the SERVER port from 8080 to 9000. Keep the CLIENT port at 8080.";
    private static final String PRIOR = "[SERVER]\nport=8080\n[CLIENT]\nport=8080\n";
    private static final String SERVER_ONLY = "[SERVER]\nport=9000\n[CLIENT]\nport=8080\n";
    private static final String STATED =
            "[Checks passed - Exact edit replacement checks passed. Requested replacement not verified: config.ini "
                    + "held the old text 2 times before the change and 1 remains.]";

    @TempDir
    Path temp;

    @Test
    void theSessionEditIsStatedWithItsCountsAndOffersNoRepair() throws Exception {
        Run run = run(List.of(REQUEST), List.of(
                nativeCall("talos.read_file", Map.of("path", "config.ini")),
                nativeCall("talos.edit_file", Map.of("path", "config.ini",
                        "old_string", "[SERVER]\nport=8080", "new_string", "[SERVER]\nport=9000")),
                text("Changed the SERVER port to 9000 and kept the CLIENT port at 8080."),
                text("Changed the SERVER port to 9000 and kept the CLIENT port at 8080.")), "y", Set.of(),
                Map.of("config.ini", PRIOR));

        assertEquals(SERVER_ONLY, Files.readString(run.workspace().resolve("config.ini")), run.transcript());
        TurnRecord row = run.onlyRow();
        String answer = row.assistantText();
        assertTrue(answer.contains(STATED), answer);
        assertFalse(answer.contains(RepairOffer.LINE), answer);
        assertFalse(answer.contains("Static verification failed"), answer);
        assertFalse(answer.contains("Replacement verification"), answer);
        Map<String, Object> expectation = onlyEvent(run, "EXPECTATION_VERIFIED");
        assertEquals("NOT_ASSERTED", expectation.get("status"), expectation.toString());
        assertEquals("replacement-scoped-prefix-change-to", expectation.get("sourcePattern"), expectation.toString());
        Map<String, Object> scope = onlyEvent(run, "REPLACEMENT_OCCURRENCE_SCOPE_RECORDED");
        assertEquals("REPEATED", scope.get("scopeKind"), scope.toString());
        assertEquals(2, ((Number) scope.get("priorOccurrences")).intValue(), scope.toString());
        assertEquals(1, ((Number) scope.get("remainingOccurrences")).intValue(), scope.toString());
        assertEquals(true, scope.get("landed"), scope.toString());
        assertEquals("YES", scope.get("substitution"), scope.toString());
        assertEquals("PRODUCER_PREIMAGE", scope.get("priorSource"), scope.toString());
        assertFalse(scope.containsValue("8080"), scope.toString());
        assertFalse(scope.containsValue("9000"), scope.toString());
        assertFalse(String.valueOf(run.trace().outcome().classification()).contains("FAIL"),
                run.trace().outcome() + run.transcript());
    }

    @Test
    void theRequestOldTextAsAnEditAnchorIsRefusedAndNothingChanges() throws Exception {
        // The expected-target repair passes the request's old text as old_string. With two occurrences the edit tool
        // refuses it, so a repair of that shape fails closed. The repair planner itself is not driven here.
        Run run = run(List.of(REQUEST), List.of(
                nativeCall("talos.read_file", Map.of("path", "config.ini")),
                nativeCall("talos.edit_file", Map.of("path", "config.ini",
                        "old_string", "8080", "new_string", "9000")),
                text("The edit could not be applied."),
                text("The edit could not be applied.")), "y", Set.of(),
                Map.of("config.ini", PRIOR));

        assertEquals(PRIOR, Files.readString(run.workspace().resolve("config.ini")), run.transcript());
        assertEquals(0, run.onlyRow().approvalsGranted(), run.transcript());
        assertTrue(run.trace().events().stream().noneMatch(event -> "EXPECTATION_VERIFIED".equals(event.type())),
                run.transcript());
    }

    @Test
    void aWriteWithoutAReadIsStatedWithItsCountsAndOffersNoRepair() throws Exception {
        // P18, D12 option B: the write tool's preimage is the prior, so the repeated old text is recognized.
        Run run = run(List.of(REQUEST), List.of(
                nativeCall("talos.write_file", Map.of("path", "config.ini", "content", SERVER_ONLY)),
                text("Changed the SERVER port to 9000."),
                text("Changed the SERVER port to 9000.")), "y", Set.of(),
                Map.of("config.ini", PRIOR));

        assertEquals(SERVER_ONLY, Files.readString(run.workspace().resolve("config.ini")), run.transcript());
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.contains("[File write/readback passed. A requested replacement could not be assessed, so "
                + "task completion was not verified. Target/readback checks passed for 1 mutated target(s). "
                + "Requested replacement not verified: config.ini held the old text 2 times before the change and "
                + "1 remains.]"), answer);
        assertFalse(answer.contains(RepairOffer.LINE), answer);
        assertFalse(answer.contains("Replacement verification"), answer);
        assertEquals("NOT_ASSERTED", onlyEvent(run, "EXPECTATION_VERIFIED").get("status"));
        Map<String, Object> scope = onlyEvent(run, "REPLACEMENT_OCCURRENCE_SCOPE_RECORDED");
        assertEquals("PRODUCER_PREIMAGE", scope.get("priorSource"), scope.toString());
        assertEquals(1, ((Number) scope.get("remainingOccurrences")).intValue(), scope.toString());
    }

    @Test
    void twoEditsInOneResponseThatChangeBothPortsAreNotARequestBoundPass() throws Exception {
        Run run = run(List.of(REQUEST), List.of(
                nativeCall("talos.read_file", Map.of("path", "config.ini")),
                together(
                        editCall("[SERVER]\nport=8080", "[SERVER]\nport=9000"),
                        editCall("[CLIENT]\nport=8080", "[CLIENT]\nport=9000")),
                text("Changed both ports."),
                text("Changed both ports.")), "y", Set.of(),
                Map.of("config.ini", PRIOR));

        assertEquals("[SERVER]\nport=9000\n[CLIENT]\nport=9000\n",
                Files.readString(run.workspace().resolve("config.ini")), run.transcript());
        assertEquals(2, run.onlyRow().approvalsGranted(), run.transcript());
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.contains("[Checks passed - Exact edit replacement checks passed. Requested replacement not "
                + "verified: config.ini held the old text 2 times before the change and none remain.]"), answer);
        assertFalse(answer.contains("Static verification: passed"), answer);
        Map<String, Object> scope = onlyEvent(run, "REPLACEMENT_OCCURRENCE_SCOPE_RECORDED");
        assertEquals("PRODUCER_PREIMAGE", scope.get("priorSource"), scope.toString());
        assertEquals(0, ((Number) scope.get("remainingOccurrences")).intValue(), scope.toString());
    }

    @Test
    void thePriorTextReachesNoPersistedArtifact() throws Exception {
        // The canary is only in the prior file, and the write removes it. The governed checkpoint store keeps the
        // prior bytes for undo by design, before approval and independently of the preimage, so only its blobs may
        // hold the canary. No session, turn or trace file may.
        String canary = "p18-canary-7f3a";
        Run run = run(List.of(REQUEST), List.of(
                nativeCall("talos.write_file", Map.of("path", "config.ini", "content", SERVER_ONLY)),
                text("Changed the SERVER port to 9000."),
                text("Changed the SERVER port to 9000.")), "y", Set.of(),
                Map.of("config.ini", PRIOR + "# " + canary + "\n"));

        assertEquals(SERVER_ONLY, Files.readString(run.workspace().resolve("config.ini")), run.transcript());
        assertEquals("PRODUCER_PREIMAGE", onlyEvent(run, "REPLACEMENT_OCCURRENCE_SCOPE_RECORDED").get("priorSource"));
        Path home = run.workspace().getParent().resolve("home");
        List<Path> carriers;
        try (var files = Files.walk(home)) {
            carriers = files.filter(Files::isRegularFile).filter(file -> {
                try {
                    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains(canary);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toList();
        }
        Path checkpoints = home.resolve(".talos").resolve("checkpoints");
        assertEquals(List.of(), carriers.stream().filter(file -> !file.startsWith(checkpoints)).toList());
        assertTrue(carriers.stream().allMatch(file -> file.getParent().getFileName().toString().equals("blobs")),
                carriers.toString());
        try (var files = Files.walk(home.resolve(".talos").resolve("sessions"))) {
            assertTrue(files.anyMatch(Files::isRegularFile), "The session store must hold the turn files.");
        }
        assertFalse(run.onlyRow().assistantText().contains(canary));
        assertFalse(run.trace().events().toString().contains(canary));
        assertTrue(run.requests().stream().flatMap(request -> request.messages.stream())
                        .noneMatch(message -> String.valueOf(message.content()).contains(canary)),
                "The prior text must not reach the model context.");
    }

    private static Map<String, Object> onlyEvent(Run run, String type) {
        List<Map<String, Object>> events = run.trace().events().stream()
                .filter(event -> type.equals(event.type()))
                .map(event -> event.data())
                .toList();
        assertEquals(1, events.size(), type + " " + run.transcript());
        return events.getFirst();
    }

    private record Run(
            Path workspace,
            List<ChatRequest> requests,
            List<String> prompts,
            String transcript,
            List<TurnRecord> rows,
            LocalTurnTrace trace) {
        TurnRecord onlyRow() {
            assertEquals(1, rows.size(), transcript);
            return rows.getFirst();
        }
    }

    private Run run(List<String> turns, List<LlmClient.StreamResult> responses, String approval,
                    Set<String> omittedTools, Map<String, String> files) throws Exception {
        return run(turns, responses, approval, omittedTools, files, null);
    }

    private Run run(List<String> turns, List<LlmClient.StreamResult> responses, String approval,
                    Set<String> omittedTools, Map<String, String> files, String mode) throws Exception {
        String request = String.join(" / ", turns);
        Path dir = Files.createDirectories(temp.resolve("run-" + Math.abs(request.hashCode()) + "-" + approval
                + (mode == null ? "" : "-" + mode)));
        Path home = Files.createDirectories(dir.resolve("home"));
        Path ws = Files.createDirectories(dir.resolve("workspace"));
        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.writeString(ws.resolve(file.getKey()), file.getValue());
        }
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Path sessions = home.resolve(".talos").resolve("sessions");
        SessionWriterLease lease = null;
        JsonSessionStore store = null;
        RenderEngine render = null;
        try {
            Config cfg = new Config(null);
            Redactor redactor = new Redactor();
            RagService rag = new RagService(cfg);
            CommandProfileRegistry profiles = CommandProfileRegistry.defaultRegistry();
            ToolRegistry registry = productionRegistry(rag, profiles, omittedTools);
            ScriptedNativeLlmClient.RecordedClient provider =
                    ScriptedNativeLlmClient.recordingWithContextWindow(responses, 16_384);
            LlmClient llm = provider.client();
            llm.setToolSpecs(registry.descriptors().stream()
                    .map(d -> new ToolSpec(d.name(), d.description(), d.parametersSchema()))
                    .toList());

            SessionMemory memory = new SessionMemory();
            ConversationManager conversation = new ConversationManager(memory, TokenBudget.fromConfig(cfg));
            store = new JsonSessionStore(sessions);
            lease = store.reserveNewSession(ws, Instant.now());
            String sessionId = lease.sessionId();
            Session runtimeSession = new Session(ws, cfg, memory, lease);
            ModeController modes = ModeController.defaultController();
            if (mode != null) assertTrue(modes.setActive(mode), mode);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            render = new RenderEngine(cfg, redactor, out, false);
            RenderEngine renderRef = render;
            List<String> prompts = new ArrayList<>();
            CliApprovalGate gate = new CliApprovalGate(prompt -> {
                prompts.add(prompt);
                return approval;
            }, out, renderRef::stopSpinner);
            TurnProcessor processor = new TurnProcessor(modes, gate, registry, new SessionApprovalPolicy(),
                    new CheckpointService(), profiles);
            ToolCallLoop loop = new ToolCallLoop(processor, ToolCallLoop.DEFAULT_MAX_ITERATIONS,
                    renderRef::printToolProgressResumingSpinner);
            SessionState state = new SessionState() {
                private int k = 6;
                private boolean debug;
                public int getK() { return k; }
                public void setK(int value) { k = value; }
                public boolean isDebug() { return debug; }
                public void setDebug(boolean on) { debug = on; }
            };
            Context ctx = Context.builder(cfg)
                    .limits(Limits.fromConfig(cfg))
                    .session(state)
                    .audit(new Audit())
                    .redactor(redactor)
                    .sandbox(new Sandbox(ws, Map.of()))
                    .rag(rag)
                    .llm(llm)
                    .netPolicy(new NetPolicy(cfg))
                    .memory(memory)
                    .approvalGate(gate)
                    .toolRegistry(registry)
                    .conversationManager(conversation)
                    .toolCallLoop(loop)
                    .streamSink(new ToolCallStreamFilter(render.answerStreamSink(chunk -> {
                        out.print(chunk);
                        out.flush();
                    })))
                    .onStreamComplete(renderRef::stopSpinner)
                    .exactAnswerPresentation(render.exactAnswerPresentation())
                    .build();
            MemoryUpdateListener memoryListener = new MemoryUpdateListener(conversation, llm, memory);
            memoryListener.setAssistMode(true);
            processor.addListener(memoryListener);
            processor.addListener(new ActiveTaskContextUpdateListener(memory));
            processor.addListener(new JsonTurnLogAppender(lease, sessionId));
            ReplRouter router = new ReplRouter(modes, processor, runtimeSession, ctx, render,
                    new CommandRegistry(), ws, new AtomicBoolean(false), "", WorkspaceCommandTemplates.none());

            for (String turn : turns) {
                assertTrue(router.tryHandlePrompt(turn));
            }
            render.shutdown();
            render = null;
            String transcript = bytes.toString(StandardCharsets.UTF_8).replaceAll("\u001B\\[[0-9;]*m", "");
            try (JsonSessionStore reopened = new JsonSessionStore(sessions)) {
                List<TurnRecord> rows = reopened.loadTurns(sessionId);
                LocalTurnTrace trace = rows.isEmpty() ? null
                        : reopened.loadTrace(sessionId, rows.getLast().traceId()).orElse(null);
                assertTrue(trace != null, "The turn trace must persist and reload: " + transcript);
                return new Run(ws, List.copyOf(provider.requests()), List.copyOf(prompts), transcript, rows, trace);
            }
        } finally {
            if (render != null) render.shutdown();
            if (lease != null) lease.close();
            if (store != null) store.close();
            TurnUserRequestCapture.clear();
            TurnTaskContractCapture.clear();
            TurnSourceEvidenceCapture.clear();
            TurnTraceCapture.clear();
            LocalTurnTraceCapture.clear();
            if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
            PromptDebugCapture.clear();
            LastPromptCapture.clear();
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    /** The TalosBootstrap registry in its registration order, without the omitted tool names. */
    private static ToolRegistry productionRegistry(RagService rag, CommandProfileRegistry profiles,
                                                   Set<String> omittedTools) {
        ToolRegistry registry = new ToolRegistry();
        for (TalosTool tool : List.<TalosTool>of(new ReadFileTool(), new DocumentComparisonTool(),
                new FileWriteTool(), new FileEditTool(), new BatchWorkspaceApplyTool(), new MakeDirectoryTool(),
                new MovePathTool(), new CopyPathTool(), new RenamePathTool(), new DeletePathTool(),
                new RunCommandTool(profiles, new ProcessCommandRunner()), new GrepTool(), new ListDirTool(),
                new RetrieveTool(rag))) {
            if (!omittedTools.contains(tool.name())) registry.register(tool);
        }
        return registry;
    }

    private static LlmClient.StreamResult nativeCall(String tool, Map<String, Object> arguments) {
        return new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("call-" + tool, tool, arguments)),
                "tool_calls");
    }

    private static ChatMessage.NativeToolCall editCall(String oldString, String newString) {
        return new ChatMessage.NativeToolCall("call-edit-" + Math.abs(oldString.hashCode()), "talos.edit_file",
                Map.of("path", "config.ini", "old_string", oldString, "new_string", newString));
    }

    private static LlmClient.StreamResult together(ChatMessage.NativeToolCall... calls) {
        return new LlmClient.StreamResult("", List.of(calls), "tool_calls");
    }

    private static LlmClient.StreamResult text(String content) {
        return new LlmClient.StreamResult(content, List.of(), "stop");
    }

    private static List<String> toolNames(ChatRequest request) {
        return request.tools == null ? List.of() : request.tools.stream().map(ToolSpec::name).toList();
    }

    private static String systemText(ChatRequest request) {
        StringBuilder text = new StringBuilder();
        for (ChatMessage message : request.messages) {
            if ("system".equals(message.role())) text.append(message.content()).append('\n');
        }
        return text.toString();
    }
}
