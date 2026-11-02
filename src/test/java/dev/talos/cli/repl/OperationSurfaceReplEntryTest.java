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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Operation surface reconciliation (P3d) through the real REPL entry. The bootstrap composition is rebuilt with a
 * scripted native provider that records every request, the TalosBootstrap tool registry, mode routing, the real
 * approval gate and a real session store. The provider is synthetic, and so is the command profile registry, which
 * carries no workspace declarations. A test that omits a tool from the registry says so, because the shipped registry
 * always carries every tool.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@Timeout(120)
class OperationSurfaceReplEntryTest {
    private static final String PAGE = "<main>Main</main>\n";
    private static final String P7 = "ok can you delete this page now please? I want this workspace clean";
    private static final String DELETE_TOOL = "talos.delete_path";
    private static final String EXPOSED_CLAIM = "Talos exposed a dedicated workspace operation tool";
    private static final Pattern ADVERTISED_TOOL = Pattern.compile("(?m)^- \\*\\*(talos\\.[a-z0-9_]+)\\*\\*");

    @TempDir
    Path temp;

    @ParameterizedTest
    @ValueSource(strings = {P7, "get rid of index.html", "erase index.html", "rm index.html",
            "delete the html file"})
    void aBoundDeleteDispatchesOnlyTheExactDeleteTool(String request) throws Exception {
        Run run = run(request, nativeDelete(), "y", true);

        ChatRequest first = run.requests().getFirst();
        assertEquals(List.of(DELETE_TOOL), toolNames(first), run.transcript());
        assertEquals(ToolChoiceMode.NAMED, first.controls.toolChoice(), run.transcript());
        assertEquals(DELETE_TOOL, first.controls.namedTool(), run.transcript());
        assertEquals(1, run.prompts().size(), run.transcript());
        assertFalse(Files.exists(run.workspace().resolve("index.html")), run.transcript());
        // The provider-visible prose lists exactly the dispatched surface, and nothing maps the delete to a write.
        assertEquals(Set.of(DELETE_TOOL), advertisedTools(first), systemText(first));
        for (ChatMessage message : first.messages) {
            if (!"system".equals(message.role())) continue;
            assertFalse(message.content().contains("talos.write_file"), message.content());
            assertFalse(message.content().contains("talos.edit_file"), message.content());
        }
        TurnRecord row = run.onlyRow();
        assertEquals(request, row.userInput());
        assertEquals(1, row.approvalsGranted());
        assertEquals("SUCCEEDED", String.valueOf(run.trace().outcome().mutationStatus()), run.transcript());
    }

    @Test
    void aTypedDeleteStaysOnTheExactDeleteTool() throws Exception {
        Run run = run("delete index.html", nativeDelete(), "y", true);

        ChatRequest first = run.requests().getFirst();
        assertEquals(List.of(DELETE_TOOL), toolNames(first), run.transcript());
        assertEquals(ToolChoiceMode.NAMED, first.controls.toolChoice(), run.transcript());
        assertEquals(new TreeSet<>(toolNames(first)), advertisedTools(first), systemText(first));
        assertEquals(1, run.prompts().size(), run.transcript());
        assertFalse(Files.exists(run.workspace().resolve("index.html")), run.transcript());
        assertEquals(1, run.onlyRow().approvalsGranted());
    }

    @Test
    void aBoundContentRemovalGetsTheSurfaceAndPromptOfItsEdit() throws Exception {
        // The operand preflight rewrites the removal into an edit of index.html. No operation tool is involved, so
        // only the request-replaced signal reselects the surface that the mode chose for the raw text.
        Run run = run(List.of("remove the footer from index.html"), List.of(
                nativeCall("talos.read_file", Map.of("path", "index.html")),
                nativeCall("talos.edit_file", Map.of("path", "index.html",
                        "old_string", "<footer>Old footer</footer>\n", "new_string", "")),
                text("Removed the footer from index.html."),
                text("Removed the footer from index.html.")), "y", Set.of(),
                Map.of("index.html", PAGE + "<footer>Old footer</footer>\n"));

        ChatRequest first = run.requests().getFirst();
        assertTrue(toolNames(first).contains("talos.edit_file"), toolNames(first) + run.transcript());
        assertEquals(new TreeSet<>(toolNames(first)), advertisedTools(first), systemText(first));
        assertEquals(PAGE, Files.readString(run.workspace().resolve("index.html")), run.transcript());
    }

    @Test
    void aTypedDeletePromptNeverMapsTheOperationToAContentWrite() throws Exception {
        Run run = run("delete index.html", nativeDelete(), "y", true);

        String system = systemText(run.requests().getFirst());
        assertFalse(system.contains("you have a talos.write_file tool"), system);
        assertFalse(system.contains("or DELETE a file"), system);
        assertTrue(system.contains("Never empty, rewrite, or edit a file to emulate"), system);
        assertTrue(system.contains("Do not emulate delete, move, copy, rename, or mkdir"), system);
        assertFalse(system.contains("unless a write/edit tool actually succeeded"), system);
        assertTrue(system.contains(
                "Do not claim you changed a file unless a mutation tool actually succeeded in this turn."), system);
    }

    @Test
    void aDeniedBoundDeleteKeepsThePage() throws Exception {
        Run run = run(P7, nativeDelete(), "n", true);

        assertEquals(List.of(DELETE_TOOL), toolNames(run.requests().getFirst()), run.transcript());
        assertEquals(1, run.prompts().size(), run.transcript());
        assertEquals(PAGE, Files.readString(run.workspace().resolve("index.html")), run.transcript());
        TurnRecord row = run.onlyRow();
        assertEquals(1, row.approvalsDenied());
        assertEquals(0, row.approvalsGranted());
        assertFalse("SUCCEEDED".equals(String.valueOf(run.trace().outcome().mutationStatus())), run.transcript());
    }

    @Test
    void aBoundDeleteWrittenAsTextIsReportedTruthfully() throws Exception {
        Run run = run(P7, List.of(
                text("{\"name\":\"talos.delete_path\",\"arguments\":{\"path\":\"index.html\"}}"),
                text("I deleted index.html.")), "y", true);

        assertEquals(PAGE, Files.readString(run.workspace().resolve("index.html")), run.transcript());
        assertEquals(0, run.prompts().size(), run.transcript());
        for (ChatRequest request : run.requests()) {
            assertEquals(List.of(DELETE_TOOL), toolNames(request), run.transcript());
        }
        String saved = run.onlyRow().assistantText();
        assertFalse(saved.contains("I deleted index.html"), saved);
    }

    @Test
    void aTextCallOfAToolThatIsNotOfferedIsNamedAsNotOffered() throws Exception {
        Run run = run("delete index.html", List.of(
                text("{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"index.html\",\"content\":\"\"}}"),
                text("Done.")), "y", true);

        assertEquals(PAGE, Files.readString(run.workspace().resolve("index.html")), run.transcript());
        assertEquals(0, run.prompts().size(), run.transcript());
        assertTrue(run.requests().size() >= 2, run.transcript());
        String correction = runtimeNotice(run.requests().get(1));
        assertTrue(correction.contains("[Runtime notice]"), correction);
        assertTrue(correction.contains("not offered"), correction);
        assertFalse(correction.contains("Call the tool through the native tool interface"), correction);
    }

    @Test
    void aDeleteWithoutARegisteredDeleteToolSaysTheToolWasNotAvailable() throws Exception {
        // The shipped registry carries talos.delete_path, so this registry is synthetic.
        Run run = run("delete index.html", List.of(text("Deleted it.")), "y", false);

        assertEquals(PAGE, Files.readString(run.workspace().resolve("index.html")), run.transcript());
        assertEquals(1, run.requests().size(), "A retry without its required tools must not be sent");
        String saved = run.onlyRow().assistantText();
        assertFalse(saved.contains(EXPOSED_CLAIM), saved);
        assertTrue(saved.contains("was not available"), saved);
        assertTrue(run.trace().events().stream().anyMatch(event ->
                        "ACTION_OBLIGATION_EVALUATED".equals(event.type())
                                && String.valueOf(event.data().get("reason")).contains("not in the dispatched tool surface")),
                run.trace().events().toString());
    }

    @Test
    void aMissingOperationToolIsNotReplacedByTheContentToolsOfItsPredecessor() throws Exception {
        // The shipped registry carries every operation tool, so this registry is synthetic. The content predecessor
        // keeps write and edit tools in the retry surface, and they must not stand in for the missing operation tool.
        Run run = run(List.of("Edit index.html so it works, then delete style.css."),
                List.of(text("Done."), text("Done.")), "y", Set.of(DELETE_TOOL), Map.of("style.css", "body {}\n"));

        assertEquals(1, run.requests().size(), "A retry without the operation tool must not be sent: "
                + run.transcript());
        assertTrue(Files.exists(run.workspace().resolve("style.css")), run.transcript());
        String saved = run.onlyRow().assistantText();
        assertFalse(saved.contains(EXPOSED_CLAIM), saved);
        assertTrue(saved.contains("was not available"), saved);
        assertTrue(obligationReasons(run).stream().anyMatch(reason ->
                reason.startsWith("talos.delete_path not in the dispatched tool surface")), obligationReasons(run)
                .toString());
    }

    @Test
    void missingContentToolsOfAPendingPredecessorAreNotReplacedByTheOperationTool() throws Exception {
        // The shipped registry carries the write and edit tools, so this registry is synthetic. The operation tool
        // alone cannot complete the content predecessor that has to run first.
        Run run = run(List.of("Edit index.html so it works, then delete style.css."),
                List.of(text("Done."), text("Done.")), "y", Set.of("talos.write_file", "talos.edit_file"),
                Map.of("style.css", "body {}\n"));

        assertEquals(1, run.requests().size(), "A retry without the content tools must not be sent: "
                + run.transcript());
        assertTrue(Files.exists(run.workspace().resolve("style.css")), run.transcript());
        String saved = run.onlyRow().assistantText();
        assertFalse(saved.contains(EXPOSED_CLAIM), saved);
        assertTrue(saved.contains("write/edit tools this request needs were not available"), saved);
        assertTrue(obligationReasons(run).stream().anyMatch(reason ->
                reason.startsWith("talos.write_file/talos.edit_file not in the dispatched tool surface")),
                obligationReasons(run).toString());
    }

    @Test
    void anInvalidCallIsNotRecordedAsRetriedWhenNoRetryCanBeSent() throws Exception {
        // The shipped registry carries every operation tool, so this registry is synthetic.
        Run run = run(List.of("Edit index.html so it works, then delete style.css."), List.of(
                nativeCall("talos.edit_file", Map.of("path", "index.html", "new_string", "<main>Works</main>\n")),
                text("Done."),
                text("Done.")), "y", Set.of(DELETE_TOOL), Map.of("style.css", "body {}\n"));

        List<String> reasons = obligationReasons(run);
        assertTrue(reasons.stream().anyMatch(reason ->
                reason.startsWith("mutating tool call failed with invalid parameters")), reasons.toString());
        assertTrue(reasons.stream().noneMatch(reason -> reason.contains("bounded corrected retry")), reasons.toString());
        assertTrue(reasons.stream().anyMatch(reason ->
                reason.startsWith("talos.delete_path not in the dispatched tool surface")), reasons.toString());
    }

    @Test
    void aWriteWithoutRegisteredWriteToolsSaysTheToolsWereNotAvailable() throws Exception {
        // The shipped registry carries the write and edit tools, so this registry is synthetic.
        Run run = run(List.of("create notes.txt containing hello"),
                List.of(text("Created notes.txt."), text("Created notes.txt.")), "y",
                Set.of("talos.write_file", "talos.edit_file"), Map.of());

        assertEquals(1, run.requests().size(), run.transcript());
        assertFalse(Files.exists(run.workspace().resolve("notes.txt")), run.transcript());
        String saved = run.onlyRow().assistantText();
        assertTrue(saved.contains("write/edit tools this request needs were not available"), saved);
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

    @Test
    void aReplayedRequestGetsTheSurfaceAndPromptOfTheReplayedRequest() throws Exception {
        String change = "create notes.txt containing hello";
        Run run = run(List.of("please switch workspace to my desktop", change, "yes"), List.of(
                new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                        "call-1", "talos.write_file", Map.of("path", "notes.txt", "content", "hello"))), "tool_calls"),
                text("Created notes.txt.")), "y", true);

        assertEquals(1, run.requests().size(), "Only the replayed request reaches the provider: " + run.transcript());
        ChatRequest replayed = run.requests().getFirst();
        assertTrue(toolNames(replayed).contains("talos.write_file"), toolNames(replayed).toString());
        String system = systemText(replayed);
        assertEquals(new TreeSet<>(toolNames(replayed)), advertisedTools(replayed), system);
        assertFalse(system.contains("Do not call write/edit tools"), system);
        assertEquals("hello", Files.readString(run.workspace().resolve("notes.txt")), run.transcript());
        assertEquals(3, run.rows().size(), run.transcript());
    }

    @Test
    void aTextCallOfAnAliasOfTheOfferedToolIsNotNamedAsNotOffered() throws Exception {
        Run run = run("delete index.html", List.of(
                text("{\"name\":\"talos.delete_file\",\"arguments\":{\"path\":\"index.html\"}}"),
                text("Done.")), "y", true);

        assertTrue(run.requests().size() >= 2, run.transcript());
        String correction = runtimeNotice(run.requests().get(1));
        assertTrue(correction.contains("Call the tool through the native tool interface"), correction);
        assertFalse(correction.contains("not offered"), correction);
    }

    private Run run(String request, List<LlmClient.StreamResult> responses, String approval,
                    boolean registerDelete) throws Exception {
        return run(List.of(request), responses, approval, registerDelete);
    }

    private Run run(List<String> turns, List<LlmClient.StreamResult> responses, String approval,
                    boolean registerDelete) throws Exception {
        return run(turns, responses, approval, registerDelete ? Set.of() : Set.of(DELETE_TOOL), Map.of());
    }

    private Run run(List<String> turns, List<LlmClient.StreamResult> responses, String approval,
                    Set<String> omittedTools, Map<String, String> files) throws Exception {
        String request = String.join(" / ", turns);
        Path dir = Files.createDirectories(temp.resolve("run-" + Math.abs(request.hashCode()) + "-" + approval));
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

    private static List<LlmClient.StreamResult> nativeDelete() {
        return List.of(
                new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                        "call-1", DELETE_TOOL, Map.of("path", "index.html"))), "tool_calls"),
                text("Deleted index.html."));
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

    private static List<String> obligationReasons(Run run) {
        return run.trace().events().stream()
                .filter(event -> "ACTION_OBLIGATION_EVALUATED".equals(event.type()))
                .map(event -> String.valueOf(event.data().get("reason")))
                .toList();
    }

    /** The tool entries the provider-visible system prose lists, in the form SystemPromptBuilder renders them. */
    private static Set<String> advertisedTools(ChatRequest request) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = ADVERTISED_TOOL.matcher(systemText(request));
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }

    private static String runtimeNotice(ChatRequest request) {
        for (int i = request.messages.size() - 1; i >= 0; i--) {
            ChatMessage message = request.messages.get(i);
            if ("user".equals(message.role()) && message.content() != null
                    && message.content().startsWith("[Runtime notice]")) {
                return message.content();
            }
        }
        return "";
    }
}
