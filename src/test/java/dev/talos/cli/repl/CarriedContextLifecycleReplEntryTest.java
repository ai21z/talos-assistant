package dev.talos.cli.repl;

import dev.talos.cli.approval.CliApprovalGate;
import dev.talos.cli.modes.ModeController;
import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.repl.slash.CommandRegistry;
import dev.talos.cli.repl.slash.SessionCommand;
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
import dev.talos.runtime.SessionData;
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
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.intent.WorkspaceOperationObligation;
import dev.talos.runtime.toolcall.DocumentComparisonTool;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.workspace.BatchWorkspaceApplyTool;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.PromptDebugCapture;
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
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifecycle of carried operation context (P8) through the real REPL entry, across a session restore. The
 * bootstrap composition is rebuilt as in OperationSurfaceReplEntryTest, with the TalosBootstrap tool registry, mode
 * routing, the real approval gate, the real session store and the active task context listener. The provider is a
 * synthetic scripted native client that records every request. A restore into a new launch is the startup restore,
 * after the first session's snapshot was saved as its close listener saves it. A restore inside a running launch is
 * the routed /session resume command.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@Timeout(120)
class CarriedContextLifecycleReplEntryTest {
    private static final String PAGE = "<main>Main</main>\n";
    private static final String P7 = "ok can you delete this page now please? I want this workspace clean";
    private static final String EDIT = "Update index.html so the heading says Done.";
    private static final String READ_ONLY_CONTRACT = "Do not call talos.write_file or talos.edit_file in this turn.";
    private static final String NOTHING_PENDING =
            "Nothing is pending, so Talos did not change any file. Name the file and the change you want.";
    private static final Pattern ADVERTISED_TOOL = Pattern.compile("(?m)^- \\*\\*(talos\\.[a-z0-9_]+)\\*\\*");

    @TempDir
    Path temp;

    @Test
    void aDeniedDeleteLeavesNoCarriedContext() throws Exception {
        try (Harness harness = new Harness(temp)) {
            Launch launch = harness.launch();
            Turn denied = launch.turn(P7, Script.DELETE, "n");

            assertEquals(1, denied.prompts(), denied.transcript());
            assertTrue(Files.exists(harness.workspace.resolve("index.html")), denied.transcript());
            assertEquals(ActiveTaskContext.State.NONE, launch.memory.activeTaskContext().state(),
                    launch.memory.activeTaskContext().toString());
        }
    }

    @ParameterizedTest
    @CsvSource({"do it now,false", "do it now,true", "apply it,false", "apply it,true"})
    void aFollowUpAfterTheApprovedDeleteIsResolvedFromItsOwnMessage(String followUp, boolean restore)
            throws Exception {
        try (Harness harness = new Harness(temp)) {
            Launch launch = harness.launch();
            launch.turn(P7, Script.DELETE, "n");
            Turn deleted = launch.turn(P7, Script.DELETE, "y");
            assertFalse(Files.exists(harness.workspace.resolve("index.html")), deleted.transcript());
            assertEquals(ActiveTaskContext.State.NONE, launch.memory.activeTaskContext().state(),
                    launch.memory.activeTaskContext().toString());
            if (restore) launch = harness.restore(launch);

            // Nothing is pending, so the runtime answers without a request. A scripted write would be observed.
            Turn follow = launch.turn(followUp, Script.WRITE, "y");

            assertNotCarried(follow);
            assertAnsweredThatNothingIsPending(follow);
            assertFalse(Files.exists(harness.workspace.resolve("index.html")), follow.transcript());
        }
    }

    @ParameterizedTest
    @CsvSource({"apply it", "make the changes", "Check it and edit it", "do it now", "Apply that proposal now."})
    void aReferentlessDirectiveWithNothingPendingChangesNothing(String followUp) throws Exception {
        try (Harness harness = new Harness(temp)) {
            Launch launch = harness.launch();

            Turn follow = launch.turn(followUp, Script.EDIT, "y");

            assertAnsweredThatNothingIsPending(follow);
            assertFalse(follow.trace().taskContract().mutationAllowed(), follow.trace().taskContract().toString());
            assertEquals(PAGE, Files.readString(harness.workspace.resolve("index.html")));
            assertEquals(ActiveTaskContext.State.NONE, launch.memory.activeTaskContext().state());
        }
    }

    @Test
    void aDeniedDeleteThenApplyItThenDoItNowAfterARestoreChangesNothing() throws Exception {
        // Case B of the live acceptance of P8 and P9, where "apply it" forced an edit of a file the model chose and
        // its denial was carried into "do it now" after the resume.
        try (Harness harness = new Harness(temp)) {
            Launch launch = harness.launch();
            launch.turn(P7, Script.DELETE, "n");
            Turn apply = launch.turn("apply it", Script.EDIT, "n");
            assertAnsweredThatNothingIsPending(apply);
            assertEquals(ActiveTaskContext.State.NONE, launch.memory.activeTaskContext().state(),
                    launch.memory.activeTaskContext().toString());

            launch = harness.restore(launch);
            Turn follow = launch.turn("do it now", Script.EDIT, "y");

            assertNotCarried(follow);
            assertAnsweredThatNothingIsPending(follow);
            assertEquals(PAGE, Files.readString(harness.workspace.resolve("index.html")));
        }
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void aDeniedEditOfAFileTheModelChoseIsNotCarried(boolean restore) throws Exception {
        // "the page" names no file, so the model chose index.html. Its denial is no request of the user.
        try (Harness harness = new Harness(temp)) {
            Launch launch = harness.launch();
            Turn denied = launch.turn("fix the page", Script.EDIT, "n");
            assertEquals(1, denied.prompts(), denied.transcript());
            assertEquals(List.of(), denied.trace().taskContract().expectedTargets());
            assertEquals(ActiveTaskContext.State.NONE, launch.memory.activeTaskContext().state(),
                    launch.memory.activeTaskContext().toString());
            if (restore) launch = harness.restore(launch);

            Turn follow = launch.turn("do it now", Script.EDIT, "y");

            assertNotCarried(follow);
            assertAnsweredThatNothingIsPending(follow);
            assertEquals(PAGE, Files.readString(harness.workspace.resolve("index.html")));
        }
    }

    @Test
    void anApprovedDeleteRetiresTheCarriedContextOfADeniedEditOnTheSameFile() throws Exception {
        try (Harness harness = new Harness(temp)) {
            Launch launch = harness.launch();
            launch.turn(EDIT, Script.EDIT, "n");
            assertEquals(ActiveTaskContext.Kind.DENIED_MUTATION, launch.memory.activeTaskContext().kind());

            launch.turn("delete index.html", Script.DELETE, "y");
            assertFalse(Files.exists(harness.workspace.resolve("index.html")));
            assertEquals(ActiveTaskContext.State.NONE, launch.memory.activeTaskContext().state(),
                    launch.memory.activeTaskContext().toString());

            Turn follow = launch.turn("do it now", Script.WRITE, "n");
            assertNotCarried(follow);
            assertEquals(0, follow.prompts(), follow.transcript());
        }
    }

    @Test
    void aDeleteOfAnotherFileKeepsTheCarriedContextOfADeniedEdit() throws Exception {
        try (Harness harness = new Harness(temp, Map.of("notes.txt", "notes\n"))) {
            Launch launch = harness.launch();
            launch.turn(EDIT, Script.EDIT, "n");
            ActiveTaskContext denied = launch.memory.activeTaskContext();

            launch.turn("delete notes.txt", Script.DELETE_NOTES, "y");

            assertFalse(Files.exists(harness.workspace.resolve("notes.txt")));
            assertEquals(denied, launch.memory.activeTaskContext());
        }
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void aDeniedEditIsStillResumedByDoItNowWithAPromptForItsSurface(boolean restore) throws Exception {
        try (Harness harness = new Harness(temp)) {
            Launch launch = harness.launch();
            Turn denied = launch.turn(EDIT, Script.EDIT, "n");
            assertEquals(1, denied.prompts(), denied.transcript());
            assertEquals(ActiveTaskContext.Kind.DENIED_MUTATION, launch.memory.activeTaskContext().kind());
            if (restore) launch = harness.restore(launch);

            Turn follow = launch.turn("do it now", Script.EDIT, "y");

            ChatRequest first = follow.requests().getFirst();
            assertTrue(toolNames(first).contains("talos.edit_file"), toolNames(first).toString());
            assertEquals("FILE_EDIT", follow.trace().taskContract().type(), follow.transcript());
            assertEquals(List.of("index.html"), follow.trace().taskContract().expectedTargets());
            assertEquals(1, follow.prompts(), follow.transcript());
            assertEquals("<main>Done</main>\n", Files.readString(harness.workspace.resolve("index.html")));
            // The provider-visible prose describes the surface selected for the consumed context.
            String system = systemText(first);
            assertFalse(system.contains(READ_ONLY_CONTRACT), system);
            assertEquals(new TreeSet<>(toolNames(first)), advertisedTools(first), system);
        }
    }

    @Test
    void aRestoredContextKeepsOnlyTheRemainderOfItsWindow() throws Exception {
        try (Harness harness = new Harness(temp)) {
            Launch launch = harness.launch();
            launch.turn(EDIT, Script.EDIT, "n");
            launch.turn("what is in index.html?", Script.TEXT, "n");
            launch.turn("thanks", Script.TEXT, "n");
            ActiveTaskContext before = launch.memory.activeTaskContext();
            assertEquals(4, before.expiresAfterTurnNumber(), before.toString());

            launch = harness.restore(launch);

            ActiveTaskContext restored = launch.memory.activeTaskContext();
            assertEquals(1, restored.expiresAfterTurnNumber(), restored.toString());
            assertEquals(before.sourceTurnNumber(), restored.sourceTurnNumber());
            assertEquals(before.proposalProvenance(), restored.proposalProvenance());
            Turn first = launch.turn("thanks again", Script.TEXT, "n");
            Turn second = launch.turn("do it now", Script.WRITE, "n");
            assertEquals(1, first.trace().turnNumber());
            assertNotCarried(second);
        }
    }

    @Test
    void anApprovedRenameOfTheFileOfADeniedEditRetiresItsContext() throws Exception {
        try (Harness harness = new Harness(temp)) {
            Launch launch = harness.launch();
            launch.turn(EDIT, Script.EDIT, "n");
            assertEquals(ActiveTaskContext.Kind.DENIED_MUTATION, launch.memory.activeTaskContext().kind());

            Turn renamed = launch.turn("rename index.html to home.html", Script.RENAME, "y");

            assertTrue(Files.exists(harness.workspace.resolve("home.html")), renamed.transcript());
            assertEquals(ActiveTaskContext.State.NONE, launch.memory.activeTaskContext().state(),
                    launch.memory.activeTaskContext().toString());
        }
    }

    @Test
    void aSecondRestoreKeepsWhatIsLeftOfTheWindow() throws Exception {
        // The denial is the last turn of the first session, so its update turn is later than the last turn of the
        // second session. A second restore that mixed the two numberings would expire it.
        try (Harness harness = new Harness(temp)) {
            Launch first = harness.launch();
            first.turn("thanks", Script.TEXT, "n");
            first.turn("thanks again", Script.TEXT, "n");
            first.turn(EDIT, Script.EDIT, "n");
            Launch second = harness.restore(first);
            second.turn("still there?", Script.TEXT, "n");
            Launch third = harness.restore(second);

            Turn follow = third.turn("do it now", Script.EDIT, "y");

            assertEquals("FILE_EDIT", follow.trace().taskContract().type(), follow.transcript());
            assertEquals(List.of("index.html"), follow.trace().taskContract().expectedTargets());
            assertEquals("<main>Done</main>\n", Files.readString(harness.workspace.resolve("index.html")));
        }
    }

    @Test
    void aResumeInsideARunningSessionJoinsItsTurnNumbering() throws Exception {
        try (Harness harness = new Harness(temp)) {
            Launch saved = harness.launch();
            saved.turn(EDIT, Script.EDIT, "n");
            saved.saveSnapshotAndClose();
            Launch running = harness.launch();
            running.turn("thanks", Script.TEXT, "n");
            running.turn("thanks again", Script.TEXT, "n");
            running.turn("still there?", Script.TEXT, "n");

            assertTrue(running.router.tryHandle("/session resume " + saved.sessionId));
            assertEquals(ActiveTaskContext.Kind.DENIED_MUTATION, running.memory.activeTaskContext().kind());
            Turn follow = running.turn("do it now", Script.EDIT, "y");

            assertEquals(4, follow.trace().turnNumber());
            assertEquals("FILE_EDIT", follow.trace().taskContract().type(), follow.transcript());
            assertEquals("<main>Done</main>\n", Files.readString(harness.workspace.resolve("index.html")));
        }
    }

    @Test
    void aFollowUpAfterTheApprovedDeleteIsResolvedFromItsOwnMessageAfterASessionResume() throws Exception {
        // The probe sequence, with the follow-up in a running session that resumed the saved one.
        try (Harness harness = new Harness(temp)) {
            Launch saved = harness.launch();
            saved.turn(P7, Script.DELETE, "n");
            Turn deleted = saved.turn(P7, Script.DELETE, "y");
            assertFalse(Files.exists(harness.workspace.resolve("index.html")), deleted.transcript());
            saved.saveSnapshotAndClose();
            Launch running = harness.launch();
            running.turn("thanks", Script.TEXT, "n");

            assertTrue(running.router.tryHandle("/session resume " + saved.sessionId));
            assertEquals(ActiveTaskContext.State.NONE, running.memory.activeTaskContext().state(),
                    running.memory.activeTaskContext().toString());
            Turn follow = running.turn("do it now", Script.WRITE, "y");

            assertNotCarried(follow);
            assertAnsweredThatNothingIsPending(follow);
            assertFalse(Files.exists(harness.workspace.resolve("index.html")), follow.transcript());
        }
    }

    @ParameterizedTest
    @CsvSource({"n,index.html", "y,backup.html"})
    void aCopyIsPersistedWithBothOperandsAndEndsTheDeniedEditOfItsSource(String approval, String changedPath)
            throws Exception {
        // The persisted audit names each operand with its role, while pathHints keep naming the changed path.
        try (Harness harness = new Harness(temp)) {
            Launch launch = harness.launch();
            launch.turn(EDIT, Script.EDIT, "n");
            assertEquals(ActiveTaskContext.Kind.DENIED_MUTATION, launch.memory.activeTaskContext().kind());

            Turn copied = launch.turn("copy index.html to backup.html", Script.COPY, approval);

            assertEquals(1, copied.prompts(), copied.transcript());
            assertEquals("y".equals(approval), Files.exists(harness.workspace.resolve("backup.html")),
                    copied.transcript());
            assertEquals(ActiveTaskContext.State.NONE, launch.memory.activeTaskContext().state(),
                    launch.memory.activeTaskContext().toString());
            List<TurnRecord.ToolCallSummary> copies = harness.store.loadTurns(launch.sessionId).getLast().toolCalls()
                    .stream().filter(call -> "talos.copy_path".equals(call.name())).toList();
            assertEquals(1, copies.size(), copies.toString());
            assertEquals(List.of(
                            new TurnRecord.OperationOperand("index.html", WorkspaceOperationObligation.Role.SOURCE),
                            new TurnRecord.OperationOperand(
                                    "backup.html", WorkspaceOperationObligation.Role.DESTINATION)),
                    copies.getFirst().operands());
            assertEquals(List.of(changedPath), copies.getFirst().pathHints());
        }
    }

    /** The runtime answered the turn itself, so no provider request, approval or tool call took place. */
    private static void assertAnsweredThatNothingIsPending(Turn follow) {
        assertEquals(List.of(), follow.requests(), follow.transcript());
        assertEquals(0, follow.prompts(), follow.transcript());
        assertTrue(follow.transcript().contains(NOTHING_PENDING), follow.transcript());
    }

    private static void assertNotCarried(Turn follow) {
        LocalTurnTrace.TaskContractSummary contract = follow.trace().taskContract();
        assertFalse("active-static-web-context".equals(contract.classificationReason()), contract.toString());
        assertFalse(contract.expectedTargets().contains("index.html"), contract.toString());
        for (ChatRequest request : follow.requests()) {
            for (ChatMessage message : request.messages) {
                String content = message.content() == null ? "" : message.content();
                assertFalse(content.contains("requiredTargets: index.html"), content);
                assertFalse(content.contains("kind=DENIED_MUTATION"), content);
            }
        }
    }

    /** The scripted model answer of a turn. EDIT reads index.html and then edits it. */
    private enum Script { TEXT, DELETE, DELETE_NOTES, EDIT, WRITE, RENAME, COPY }

    private record Turn(List<ChatRequest> requests, int prompts, String transcript, LocalTurnTrace trace) {}

    /** One temporary workspace and session store, shared by the launches of a test. */
    private static final class Harness implements AutoCloseable {
        final Path workspace;
        final JsonSessionStore store;
        final String previousHome;
        final AtomicReference<Script> script = new AtomicReference<>(Script.TEXT);
        final AtomicInteger turnRequests = new AtomicInteger();
        final Deque<String> approvals = new ArrayDeque<>();
        final List<String> prompts = new ArrayList<>();
        final ScriptedNativeLlmClient.RecordedClient provider;
        final List<Launch> launches = new ArrayList<>();

        Harness(Path temp) throws Exception {
            this(temp, Map.of());
        }

        Harness(Path temp, Map<String, String> files) throws Exception {
            Path home = Files.createDirectories(temp.resolve("home"));
            workspace = Files.createDirectories(temp.resolve("workspace"));
            Files.writeString(workspace.resolve("index.html"), PAGE);
            for (Map.Entry<String, String> file : files.entrySet()) {
                Files.writeString(workspace.resolve(file.getKey()), file.getValue());
            }
            previousHome = System.getProperty("user.home");
            System.setProperty("user.home", home.toString());
            store = new JsonSessionStore(home.resolve(".talos").resolve("sessions"));
            provider = ScriptedNativeLlmClient.recordingResponder(request -> {
                int index = turnRequests.getAndIncrement();
                if (index == 1 && script.get() == Script.EDIT) {
                    return nativeCall("talos.edit_file", Map.of("path", "index.html",
                            "old_string", "<main>Main</main>", "new_string", "<main>Done</main>"));
                }
                if (index == 0) {
                    switch (script.get()) {
                        case DELETE -> { return nativeCall("talos.delete_path", Map.of("path", "index.html")); }
                        case DELETE_NOTES -> { return nativeCall("talos.delete_path", Map.of("path", "notes.txt")); }
                        case EDIT -> { return nativeCall("talos.read_file", Map.of("path", "index.html")); }
                        case WRITE -> {
                            return nativeCall("talos.write_file", Map.of("path", "index.html", "content", PAGE));
                        }
                        case RENAME -> {
                            return nativeCall("talos.rename_path", Map.of("path", "index.html", "new_name", "home.html"));
                        }
                        case COPY -> {
                            return nativeCall("talos.copy_path", Map.of("from", "index.html", "to", "backup.html"));
                        }
                        case TEXT -> { }
                    }
                }
                return new LlmClient.StreamResult("Nothing is pending, so nothing was done.", List.of(), "stop");
            }, 16_384);
        }

        Launch launch() {
            Launch launch = new Launch(this);
            launches.add(launch);
            return launch;
        }

        /** Ends the launch as its close listener does, then restores its session into a new launch. */
        Launch restore(Launch previous) {
            previous.saveSnapshotAndClose();
            Launch next = launch();
            TalosBootstrap.restoreSavedSession(store, previous.sessionId, next.memory, next.conversation);
            return next;
        }

        @Override
        public void close() {
            for (Launch launch : launches) launch.close();
            store.close();
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

    /** One process of the REPL entry composition, as TalosBootstrap builds it, with its own session lease. */
    private static final class Launch {
        final Harness harness;
        final SessionWriterLease lease;
        final String sessionId;
        final SessionMemory memory = new SessionMemory();
        final ConversationManager conversation;
        final Session runtimeSession;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final RenderEngine render;
        final ReplRouter router;
        boolean closed;

        Launch(Harness harness) {
            this.harness = harness;
            Config cfg = new Config(null);
            conversation = new ConversationManager(memory, TokenBudget.fromConfig(cfg));
            lease = harness.store.reserveNewSession(harness.workspace, Instant.now());
            sessionId = lease.sessionId();
            runtimeSession = new Session(harness.workspace, cfg, memory, lease);
            Redactor redactor = new Redactor();
            RagService rag = new RagService(cfg);
            CommandProfileRegistry profiles = CommandProfileRegistry.defaultRegistry();
            ToolRegistry registry = productionRegistry(rag, profiles);
            LlmClient llm = harness.provider.client();
            llm.setToolSpecs(registry.descriptors().stream()
                    .map(d -> new ToolSpec(d.name(), d.description(), d.parametersSchema()))
                    .toList());
            ModeController modes = ModeController.defaultController();
            PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            render = new RenderEngine(cfg, redactor, out, false);
            RenderEngine renderRef = render;
            CliApprovalGate gate = new CliApprovalGate(prompt -> {
                harness.prompts.add(prompt);
                String answer = harness.approvals.poll();
                return answer == null ? "n" : answer;
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
                    .sandbox(new Sandbox(harness.workspace, Map.of()))
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
            CommandRegistry commands = new CommandRegistry();
            commands.register(new SessionCommand(harness.workspace, harness.store, sessionId,
                    runtimeSession::turnCount));
            router = new ReplRouter(modes, processor, runtimeSession, ctx, render,
                    commands, harness.workspace, new AtomicBoolean(false), "",
                    WorkspaceCommandTemplates.none());
        }

        Turn turn(String text, Script script, String approval) {
            int requestsBefore = harness.provider.requests().size();
            int promptsBefore = harness.prompts.size();
            int transcriptBefore = bytes.size();
            harness.approvals.clear();
            harness.approvals.add(approval);
            harness.script.set(script);
            harness.turnRequests.set(0);
            assertTrue(router.tryHandlePrompt(text));
            harness.script.set(Script.TEXT);
            List<ChatRequest> all = List.copyOf(harness.provider.requests());
            String transcript = bytes.toString(StandardCharsets.UTF_8).substring(transcriptBefore)
                    .replaceAll("\u001B\\[[0-9;]*m", "");
            List<TurnRecord> rows = harness.store.loadTurns(sessionId);
            assertFalse(rows.isEmpty(), transcript);
            LocalTurnTrace trace = harness.store.loadTrace(sessionId, rows.getLast().traceId()).orElse(null);
            assertTrue(trace != null, "The turn trace must persist and reload: " + transcript);
            return new Turn(all.subList(requestsBefore, all.size()), harness.prompts.size() - promptsBefore,
                    transcript, trace);
        }

        /** The close listener of TalosBootstrap saves this snapshot when a session ends. */
        void saveSnapshotAndClose() {
            List<SessionData.Turn> turns = memory.getTurns().stream()
                    .map(m -> new SessionData.Turn(m.role(), m.content(),
                            "assistant".equals(m.role()) ? "ok" : ""))
                    .toList();
            String sketch = conversation.sketch();
            lease.save(new SessionData(sessionId, runtimeSession.workspace().toString(),
                    sketch != null ? sketch : "", conversation.turnCount(), runtimeSession.startedAt(), turns,
                    harness.provider.client().getModel(), memory.activeTaskContext(), memory.artifactGoal()));
            close();
        }

        void close() {
            if (closed) return;
            closed = true;
            render.shutdown();
            lease.close();
        }
    }

    /** The TalosBootstrap registry in its registration order. */
    private static ToolRegistry productionRegistry(RagService rag, CommandProfileRegistry profiles) {
        ToolRegistry registry = new ToolRegistry();
        for (TalosTool tool : List.<TalosTool>of(new ReadFileTool(), new DocumentComparisonTool(),
                new FileWriteTool(), new FileEditTool(), new BatchWorkspaceApplyTool(), new MakeDirectoryTool(),
                new MovePathTool(), new CopyPathTool(), new RenamePathTool(), new DeletePathTool(),
                new RunCommandTool(profiles, new ProcessCommandRunner()), new GrepTool(), new ListDirTool(),
                new RetrieveTool(rag))) {
            registry.register(tool);
        }
        return registry;
    }

    private static LlmClient.StreamResult nativeCall(String tool, Map<String, Object> arguments) {
        return new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("call-" + tool, tool, arguments)),
                "tool_calls");
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

    /** The tool entries the provider-visible system prose lists, in the form SystemPromptBuilder renders them. */
    private static Set<String> advertisedTools(ChatRequest request) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = ADVERTISED_TOOL.matcher(systemText(request));
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }
}
