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
 * Typed workspace-operation dispositions (P14, RC2-I) through the real REPL entry, with the harness of
 * OperationSurfaceReplEntryTest: the bootstrap composition with a scripted native provider that records every request,
 * the TalosBootstrap tool registry, mode routing, the real approval gate and a real session store. The provider and the
 * command profile registry are synthetic.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@Timeout(120)
class WithheldOperationReplEntryTest {
    private static final String PAGE = "<main>Main</main>\n";
    private static final String DELETE_TOOL = "talos.delete_path";
    private static final String NOTES = "# Notes\n";
    private static final String AMBIGUOUS_DELETE =
            "[Not performed: delete notes.md. Its order relative to the requested content change could not be established.]";

    @TempDir
    Path temp;

    @Test
    void aWithheldDeleteAfterAnEditIsStatedAsNotPerformed() throws Exception {
        Run run = run(List.of("add a footer to index.html and then delete notes.md"), List.of(
                nativeCall("talos.read_file", Map.of("path", "index.html")),
                nativeCall("talos.edit_file", Map.of("path", "index.html",
                        "old_string", "<main>Main</main>", "new_string", "<main>Main</main>\n<footer>Footer</footer>")),
                text("Added the footer and deleted notes.md."),
                text("Added the footer and deleted notes.md.")), "y", Set.of(),
                Map.of("notes.md", NOTES));

        ChatRequest first = run.requests().getFirst();
        assertFalse(toolNames(first).contains(DELETE_TOOL), toolNames(first) + run.transcript());
        assertTrue(toolNames(first).contains("talos.edit_file"), toolNames(first) + run.transcript());
        String system = systemText(first);
        assertTrue(system.contains("[WithheldWorkspaceOperations]"), system);
        assertTrue(system.contains("- delete notes.md\n"), system);
        assertFalse(system.contains("call the listed workspace operation tool"), system);
        assertTrue(Files.readString(run.workspace().resolve("index.html")).contains("<footer>Footer</footer>"),
                run.transcript());
        assertEquals(NOTES, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        TurnRecord row = run.onlyRow();
        // The runtime statement comes first, before the model's claim, as the other runtime notices do.
        assertTrue(row.assistantText().startsWith(AMBIGUOUS_DELETE + "\n\n"), row.assistantText());
        assertFalse(row.assistantText().toLowerCase(java.util.Locale.ROOT).contains("failed to delete"),
                row.assistantText());
        assertEquals("PARTIAL", run.trace().outcome().classification(), run.transcript());
        // The executed edit keeps its operational status. Only the request is partial.
        Map<String, Object> finalStatus = onlyEvent(run, "ANSWER_FINAL_STATUS");
        assertEquals("COMPLETE", finalStatus.get("operationalStatus"), finalStatus.toString());
        assertEquals("PARTIAL", finalStatus.get("combinedStatus"), finalStatus.toString());
        assertEquals("PARTIAL", finalStatus.get("combinedClassification"), finalStatus.toString());
        assertEquals("NOT_ASSESSED", finalStatus.get("coverage"), finalStatus.toString());
        Map<String, Object> dispositions = onlyDispositionsEvent(run);
        assertEquals(false, dispositions.get("answeredByRuntime"));
        assertEquals(List.of("WITHHELD:DELETE_PATH:workspace-operation-primary-content-authority-ambiguous"),
                dispositionRows(dispositions), dispositions.toString());
        assertTrue(String.valueOf(dispositions.get("toolSurfaceReason")).contains("workspace-operation tail blocked"),
                dispositions.toString());
    }

    @Test
    void onlyWithheldOperationsAreAnsweredByTheRuntimeWithoutAProviderRequest() throws Exception {
        Run run = run(List.of("delete a.md and delete b.md"), List.of(text("Deleted both files.")), "y",
                Set.of(), Map.of("a.md", "a\n", "b.md", "b\n"));

        assertEquals(List.of(), run.requests(), run.transcript());
        assertEquals("a\n", Files.readString(run.workspace().resolve("a.md")));
        assertEquals("b\n", Files.readString(run.workspace().resolve("b.md")));
        assertEquals("""
                No file was changed in this turn. Only the workspace operations below were assessed, and no other \
                part of the request was answered.
                Not performed: delete a.md. Only one workspace operation can run per request.
                Not performed: delete b.md. Only one workspace operation can run per request.""",
                run.onlyRow().assistantText());
        assertEquals("BLOCKED_BY_POLICY", run.trace().outcome().classification(), run.transcript());
        assertEquals(0, run.prompts().size(), run.transcript());
        assertEquals(true, onlyDispositionsEvent(run).get("answeredByRuntime"));
    }

    /**
     * The admitted control. Its completion through this entry is still stopped by the open edit-then-delete defect,
     * where the delete is forced while the edit is pending (P3d record), so only the dispositions are asserted here.
     */
    @Test
    void theOrderedRequestWithADirectContentTargetKeepsItsOperationAdmitted() throws Exception {
        Run run = run(List.of("Edit index.html to add a footer, then delete notes.md."), List.of(
                nativeCall("talos.read_file", Map.of("path", "index.html")),
                nativeCall("talos.edit_file", Map.of("path", "index.html",
                        "old_string", "<main>Main</main>", "new_string", "<main>Main</main>\n<footer>Footer</footer>")),
                nativeCall(DELETE_TOOL, Map.of("path", "notes.md")),
                text("Added the footer and deleted notes.md."),
                text("Added the footer and deleted notes.md.")), "y", Set.of(),
                Map.of("notes.md", NOTES));

        ChatRequest first = run.requests().getFirst();
        assertTrue(toolNames(first).contains(DELETE_TOOL), toolNames(first) + run.transcript());
        assertFalse(systemText(first).contains("[WithheldWorkspaceOperations]"), systemText(first));
        assertFalse(run.onlyRow().assistantText().contains("[Not performed"), run.onlyRow().assistantText());
        assertEquals(List.of("ADMITTED:DELETE_PATH:"), dispositionRows(onlyDispositionsEvent(run)));
    }

    @Test
    void aWithheldFolderCreatedByTheTurnsWriteIsNotStatedAsNotPerformed() throws Exception {
        Run run = run(List.of("create a folder named archive and then write archive/notes.md"), List.of(
                nativeCall("talos.write_file", Map.of("path", "archive/notes.md", "content", NOTES)),
                text("Created archive/notes.md."),
                text("Created archive/notes.md.")), "y", Set.of(), Map.of());

        assertEquals(NOTES, Files.readString(run.workspace().resolve("archive/notes.md")), run.transcript());
        assertFalse(run.onlyRow().assistantText().contains("[Not performed"), run.onlyRow().assistantText());
        assertEquals(List.of("WITHHELD:MKDIR:workspace-operation-before-content-order-unsupported"),
                dispositionRows(onlyDispositionsEvent(run)));
    }

    @Test
    void aWithheldFolderIsStatedWhenTheTurnsWriteWasDenied() throws Exception {
        Run run = run(List.of("create a folder named archive and then write archive/notes.md"), List.of(
                nativeCall("talos.write_file", Map.of("path", "archive/notes.md", "content", NOTES)),
                text("Created archive/notes.md."),
                text("Created archive/notes.md.")), "n", Set.of(), Map.of());

        assertFalse(Files.exists(run.workspace().resolve("archive")), run.transcript());
        assertTrue(run.onlyRow().assistantText().startsWith("[Not performed: create folder archive. An operation "
                + "before a content change in the same request is not supported.]"), run.onlyRow().assistantText());
    }

    @Test
    void aTextOnlyAnswerStillStatesTheWithheldDelete() throws Exception {
        Run run = run(List.of("add a footer to index.html and then delete notes.md"), List.of(
                text("I added the footer and deleted notes.md."),
                text("I added the footer and deleted notes.md."),
                text("I added the footer and deleted notes.md.")), "y", Set.of(), Map.of("notes.md", NOTES));

        assertEquals(PAGE, Files.readString(run.workspace().resolve("index.html")), run.transcript());
        assertEquals(NOTES, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertTrue(run.onlyRow().assistantText().startsWith(AMBIGUOUS_DELETE), run.onlyRow().assistantText());
        assertFalse(run.trace().outcome().classification().startsWith("COMPLETED"), run.transcript());
    }

    /**
     * A plan keeps its model turn instead of the runtime answer, and its operation is stated as not allowed. The
     * planned surface of a plan with a blocked workspace operation is empty on HEAD (ToolSurfacePlanner), so the plan
     * cannot read index.html and the existing evidence notice replaces its text. That open defect is outside P14,
     * whose stop conditions forbid adding a tool, so only the turn, the statement and the files are asserted here.
     */
    @Test
    void aPlanOfAnEditAndADeleteKeepsItsModelTurnAndStatesTheDelete() throws Exception {
        String plan = "Plan: add a footer to index.html, then delete notes.md.";
        Run run = run(List.of("Edit index.html to add a footer, then delete notes.md."), List.of(text(plan),
                text(plan)), "y", Set.of(), Map.of("notes.md", NOTES), "plan");

        assertFalse(run.requests().isEmpty(), run.transcript());
        assertFalse(toolNames(run.requests().getFirst()).contains(DELETE_TOOL), run.transcript());
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.strip().startsWith("[Not performed: delete notes.md. This turn does not allow changes.]"),
                answer);
        assertFalse(answer.contains("No file was changed in this turn."), answer);
        assertEquals(PAGE, Files.readString(run.workspace().resolve("index.html")), run.transcript());
        assertEquals(NOTES, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        Map<String, Object> dispositions = onlyDispositionsEvent(run);
        assertEquals(false, dispositions.get("answeredByRuntime"));
        assertEquals(List.of("WITHHELD:DELETE_PATH:workspace-operation-mutation-not-allowed"),
                dispositionRows(dispositions), dispositions.toString());
    }

    @Test
    void aPlanOfTwoDeletesIsNotReplacedByTheRuntimeAnswer() throws Exception {
        String plan = "Plan: delete a.md, then delete b.md.";
        Run run = run(List.of("delete a.md and delete b.md"), List.of(text(plan), text(plan)), "y", Set.of(),
                Map.of("a.md", "a\n", "b.md", "b\n"), "plan");

        assertFalse(run.requests().isEmpty(), run.transcript());
        String answer = run.onlyRow().assistantText();
        assertTrue(answer.contains(plan), answer);
        assertTrue(answer.contains("[Not performed: delete a.md. This turn does not allow changes.]\n"
                + "[Not performed: delete b.md. This turn does not allow changes.]"), answer);
        assertFalse(answer.contains("No file was changed in this turn."), answer);
        assertEquals("a\n", Files.readString(run.workspace().resolve("a.md")));
        assertEquals("b\n", Files.readString(run.workspace().resolve("b.md")));
        assertEquals(false, onlyDispositionsEvent(run).get("answeredByRuntime"));
    }

    @Test
    void aWriteThatWouldEmulateTheWithheldDeleteIsRefused() throws Exception {
        Run run = run(List.of("add a footer to index.html and then delete notes.md"), List.of(
                nativeCall("talos.write_file", Map.of("path", "notes.md", "content", "")),
                text("Deleted notes.md."),
                text("Deleted notes.md."),
                text("Deleted notes.md.")), "y", Set.of(), Map.of("notes.md", NOTES));

        assertEquals(NOTES, Files.readString(run.workspace().resolve("notes.md")), run.transcript());
        assertTrue(run.onlyRow().assistantText().contains(AMBIGUOUS_DELETE), run.onlyRow().assistantText());
        assertEquals(0, run.onlyRow().approvalsGranted(), run.transcript());
    }

    private static Map<String, Object> onlyDispositionsEvent(Run run) {
        return onlyEvent(run, "WORKSPACE_OPERATION_DISPOSITIONS_RECORDED");
    }

    private static Map<String, Object> onlyEvent(Run run, String type) {
        List<Map<String, Object>> events = run.trace().events().stream()
                .filter(event -> type.equals(event.type()))
                .map(event -> event.data())
                .toList();
        assertEquals(1, events.size(), type + " " + run.transcript());
        return events.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static List<String> dispositionRows(Map<String, Object> event) {
        return ((List<Map<String, Object>>) event.get("dispositions")).stream()
                .map(row -> row.get("state") + ":" + row.get("kind") + ":" + row.get("reason"))
                .toList();
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
        Files.writeString(ws.resolve("index.html"), PAGE);
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
