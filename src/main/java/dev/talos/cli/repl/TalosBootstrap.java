package dev.talos.cli.repl;

import dev.talos.cli.approval.CliApprovalGate;
import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.slash.*;
import dev.talos.cli.ui.AnsiColor;
import dev.talos.core.Audit;
import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.index.IndexedWorkspaceSymbolChecker;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.net.NetPolicy;
import dev.talos.core.rag.RagService;
import dev.talos.core.security.Redactor;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.MemoryUpdateListener;
import dev.talos.runtime.NoOpSessionStore;
import dev.talos.runtime.Session;
import dev.talos.runtime.SessionData;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.SessionStore;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.policy.SensitiveWorkspaceDetector;
import dev.talos.tools.ToolProgressSink;
import dev.talos.tools.ToolRegistry;
import dev.talos.runtime.workspace.BatchWorkspaceApplyTool;
import dev.talos.tools.impl.DeletePathTool;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.GrepTool;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.MakeDirectoryTool;
import dev.talos.tools.impl.MovePathTool;
import dev.talos.tools.impl.CopyPathTool;
import dev.talos.tools.impl.RenamePathTool;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.RetrieveTool;
import dev.talos.runtime.command.CommandProfileRegistry;
import dev.talos.runtime.command.ProcessCommandRunner;
import dev.talos.runtime.command.RunCommandTool;
import dev.talos.runtime.command.WorkspaceCommandProfiles;
import dev.talos.runtime.command.WorkspaceCommandProfilesLoader;
import dev.talos.runtime.command.WorkspaceProfileTrustStore;
import org.jline.reader.LineReader;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Composition root for the Talos CLI.
 *
 * <p>Constructs all services, tools, commands, and runtime components,
 * then wires them into a ready-to-use {@link ReplRouter}. This is the
 * single place that knows <em>what gets created</em> - the router only
 * knows <em>how to dispatch</em>.
 *
 * <p>Separated from {@code ReplRouter} so that:
 * <ul>
 *   <li>Construction logic can be read and audited in one place</li>
 *   <li>ReplRouter can be tested with mocked/stubbed dependencies</li>
 *   <li>Future entry points (e.g., programmatic API, test harness)
 *       can reuse the wiring without the REPL dispatch</li>
 * </ul>
 */
public final class TalosBootstrap {

    public record RestoreSummary(
            int pairsReplayed,
            java.time.Instant createdAt,
            String model,
            boolean savedSessionAvailable) {
        public RestoreSummary(int pairsReplayed, java.time.Instant createdAt, String model) {
            this(pairsReplayed, createdAt, model, pairsReplayed > 0);
        }

        public boolean hasReplay() { return pairsReplayed > 0; }

        public boolean hasSavedSession() { return savedSessionAvailable; }
    }

    private TalosBootstrap() {} // static factory only

    /**
     * Create a fully wired {@link ReplRouter} ready for the REPL loop.
     *
     * @param session    session state (k, debug) - typically the RunCmd instance
     * @param cfg        loaded configuration
     * @param out        output stream (typically System.out)
     * @param workspace  workspace root directory
     * @param lineReader optional JLine LineReader for signal and stream-writer
     *                   integration; when non-null, streaming output uses the
     *                   terminal writer to preserve cursor state
     * @param approvalReader optional shared prompt reader for approval prompts;
     *                       when non-null, approval uses the same input owner as
     *                       the REPL loop
     * @return a configured ReplRouter
     */
    public static ReplRouter create(SessionState session, Config cfg, PrintStream out,
                                    Path workspace, LineReader lineReader,
                                    Function<String, String> approvalReader) {
        cfg = (cfg == null) ? new Config() : cfg;
        workspace = (workspace == null) ? Path.of(".") : workspace;
        out = (out == null) ? System.out : out;

        // ── Core services ────────────────────────────────────────────────
        Audit          audit    = new Audit();
        Redactor       redactor = new Redactor();
        Sandbox        sandbox  = new Sandbox(workspace, Map.of());
        RagService     rag      = new RagService(cfg);
        LlmClient      llm     = new LlmClient(cfg);
        NetPolicy      net      = new NetPolicy(cfg);
        Limits         limits   = Limits.fromConfig(cfg);
        SessionMemory  memory   = new SessionMemory();

        // ── P2 Ctrl-C wiring ─────────────────────────────────────────────
        // JLine saves & restores the INT handler around its own readLine(),
        // so a handler we install here only fires when the terminal is NOT
        // actively reading a prompt - which is exactly the window during
        // which an LLM call can be in flight. Pressing Ctrl-C at the prompt
        // still raises UserInterruptException (handled elsewhere); pressing
        // it mid-generation flips this flag, which LlmClient's watchdog and
        // stream loop poll. Flag is cleared at the top of each LLM call by
        // the reset hook so stale Ctrl-Cs can't leak into the next turn.
        java.util.concurrent.atomic.AtomicBoolean cancelFlag =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        if (lineReader != null) {
            try {
                lineReader.getTerminal().handle(
                        org.jline.terminal.Terminal.Signal.INT,
                        sig -> cancelFlag.set(true));
            } catch (Exception ignored) {
                // Some test terminals reject signal installation; fall back
                // silently - the LLM still has the wall-clock + idle watchdog.
            }
        }
        llm.setCancelSupplier(cancelFlag::get);
        llm.setCancelResetHook(() -> cancelFlag.set(false));

        // ── Workspace verification profiles (T789/T790) ─────────────────
        // Loaded once at startup; ws: profiles register ONLY when the
        // declaration is content-hash TRUSTED. Untrusted/invalid states are
        // carried for instructive plan-time rejections - they never reach
        // an approval prompt.
        var workspaceProfilesLoaded = WorkspaceCommandProfilesLoader.load(workspace);
        var profileTrustStore = new WorkspaceProfileTrustStore();
        var profileTrustState = profileTrustStore.state(workspace, workspaceProfilesLoaded);
        CommandProfileRegistry commandProfiles = CommandProfileRegistry.defaultRegistry()
                .withWorkspaceDeclaration(workspaceProfilesLoaded.profiles(), profileTrustState);

        // ── Tools ────────────────────────────────────────────────────────
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new FileWriteTool());
        toolRegistry.register(new FileEditTool());
        toolRegistry.register(new BatchWorkspaceApplyTool());
        toolRegistry.register(new MakeDirectoryTool());
        toolRegistry.register(new MovePathTool());
        toolRegistry.register(new CopyPathTool());
        toolRegistry.register(new RenamePathTool());
        toolRegistry.register(new DeletePathTool());
        toolRegistry.register(new RunCommandTool(commandProfiles, new ProcessCommandRunner()));
        toolRegistry.register(new GrepTool());
        toolRegistry.register(new ListDirTool());
        toolRegistry.register(new RetrieveTool(rag));

        // Wire tool definitions into LlmClient so engine requests include native tools
        llm.setToolSpecs(
                toolRegistry.descriptors().stream()
                        .map(d -> new dev.talos.spi.types.ToolSpec(d.name(), d.description(), d.parametersSchema()))
                        .collect(Collectors.toList())
        );

        // ── Conversation ─────────────────────────────────────────────────
        ConversationManager conversationManager =
                new ConversationManager(memory, TokenBudget.fromConfig(cfg));

        // ── Session persistence ──────────────────────────────────────────
        boolean sessionPersistenceEnabled = cfg.view().session().persistence();
        boolean sessionAutoLoadEnabled = sessionPersistenceEnabled && cfg.view().session().autoLoad();
        SessionStore sessionStore = sessionPersistenceEnabled ? new JsonSessionStore() : new NoOpSessionStore();
        // T799: one workspace, many sessions. The bare workspace hash keys
        // per-workspace artifacts (checkpoints, trace metadata) and is the
        // listing prefix; everything this process WRITES (close snapshot,
        // turn log, traces) goes under a fresh per-run instance id.
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String sessionId = JsonSessionStore.newSessionInstanceId(workspace, java.time.Instant.now());

        RestoreSummary restoreSummary = new RestoreSummary(0, null, "");
        RestoreSummary savedSessionSummary = new RestoreSummary(0, null, "");
        if (sessionAutoLoadEnabled) {
            restoreSummary = restoreSavedSession(sessionStore,
                    latestSessionId(sessionStore, workspaceId), memory, conversationManager);
        } else if (sessionPersistenceEnabled) {
            savedSessionSummary = inspectSavedSession(sessionStore,
                    latestSessionId(sessionStore, workspaceId));
        }
        if (restoreSummary.model() != null && !restoreSummary.model().isBlank()) {
            llm.setModel(restoreSummary.model());
            syncActiveModelIntoConfig(cfg, llm.getModel());
        }

        // ── Mode controller ──────────────────────────────────────────────
        ModeController modes = ModeController.defaultController();
        modes.setSymbolChecker(new IndexedWorkspaceSymbolChecker(workspace));

        // ── Rendering (created early so progress sink can reference it) ──
        // Interactivity is decided by the caller's terminal selection, not
        // re-detected here: a JLine LineReader exists only when RunCmd's
        // isatty checks passed, and scripted/test paths (lineReader == null)
        // must stay plain (T769). Since T774 the interactive sink is the
        // terminal-backed stream (not System.out), so reader presence alone
        // is the signal. The same selection feeds the live width source for
        // the answer pane (T772), approval window, and /status (T773).
        final LineReader lineReaderRef = lineReader;
        final java.util.function.IntSupplier terminalWidth =
                lineReader != null ? () -> lineReaderRef.getTerminal().getWidth() : null;
        RenderEngine render = new RenderEngine(cfg, redactor, out,
                lineReader != null,
                terminalWidth,
                lineReader != null ? lineReader.getTerminal() : null);
        // Live status-row context (T780): model id and 1-based turn number,
        // polled per tick. Renderer-owned values only.
        final ConversationManager statusConversation = conversationManager;
        render.setStatusContext(llm::getModel, () -> statusConversation.turnCount() + 1);

        // ── Approval gate ─────────────────────────────────────────────────
        // When a JLine LineReader is available, approval reads through the same
        // terminal input system as the REPL prompt (no competing Scanner on System.in).
        // The pre-prompt hook stops the spinner so the approval line renders cleanly.
        Runnable spinnerStopper = render::stopSpinner;
        CliApprovalGate approvalGate;
        Function<String, String> effectiveApprovalReader = approvalReader;
        if (effectiveApprovalReader == null && lineReader != null) {
            effectiveApprovalReader = prompt -> {
                try {
                    return lineReader.readLine(prompt);
                } catch (org.jline.reader.EndOfFileException | org.jline.reader.UserInterruptException e) {
                    return null; // EOF / Ctrl-C → deny
                }
            };
        }
        if (effectiveApprovalReader != null) {
            approvalGate = new CliApprovalGate(effectiveApprovalReader, out, spinnerStopper, terminalWidth);
        } else {
            // Fallback: Scanner-based (tests, non-interactive pipelines)
            approvalGate = new CliApprovalGate();
        }

        // ── Runtime layer ────────────────────────────────────────────────
        Session        runtimeSession = new Session(workspace, cfg, memory, sessionStore);
        // Session-scoped approval policy sits above the gate. Without this,
        // the REPL falls back to ALWAYS_ASK and the user's "a = yes for
        // session" choice has no effect - the tri-state gate still reports
        // APPROVED_REMEMBER but the policy never flips the flag, because
        // ApprovalPolicy.ALWAYS_ASK.rememberApproval is a no-op.
        dev.talos.runtime.SessionApprovalPolicy approvalPolicy =
                new dev.talos.runtime.SessionApprovalPolicy();
        CheckpointService checkpointService = new CheckpointService();
        TurnProcessor  turnProcessor  = new TurnProcessor(
                modes, approvalGate, toolRegistry, approvalPolicy, checkpointService,
                commandProfiles);

        // Tool progress sink: renders lightweight status lines via RenderEngine.
        // Connected before ToolCallLoop so progress events flow during tool execution.
        // T901: the resuming variant re-arms the spinner after each tool line so the
        // following model-generation wait shows a live "working" indicator instead of
        // dead air (the spinner had only run before the first output and never resumed).
        ToolProgressSink progressSink = render::printToolProgressResumingSpinner;
        ToolCallLoop   toolCallLoop   = new ToolCallLoop(turnProcessor,
                ToolCallLoop.DEFAULT_MAX_ITERATIONS, progressSink);

        // onStreamComplete (below, .onStreamComplete(spinnerStopper)) gives an
        // unconditional spinner stop after chatStream. Fixes the case where
        // tool-call-only responses are fully suppressed by ToolCallStreamFilter,
        // so the rawSink never fires stopSpinner().

        if (sessionPersistenceEnabled) {
            // Auto-save session evidence on close. Saved evidence is not prompt
            // context unless session.auto_load=true or the user runs /session load.
            final ConversationManager cmRef = conversationManager;
            final SessionMemory memRef = memory;
            final String sidRef = sessionId;
            final Path wsRef = workspace;
            runtimeSession.addCloseListener(new dev.talos.runtime.SessionListener() {
                @Override public void onSessionEnd() {
                    String sketch = cmRef.sketch();
                    // T799: an empty session is not worth a file. Skip when
                    // there are no turns, no sketch, and no active task
                    // context to carry forward (context-only snapshots are a
                    // real restore case and must still save).
                    if (memRef.getTurns().isEmpty()
                            && (sketch == null || sketch.isBlank())
                            && !hasLiveActiveContext(memRef)) {
                        return;
                    }
                    java.util.List<SessionData.Turn> turns = memRef.getTurns().stream()
                            .map(m -> new SessionData.Turn(m.role(), m.content(), "assistant".equals(m.role()) ? "ok" : ""))
                            .toList();
                    SessionData data = new SessionData(sidRef, wsRef.toString(),
                            sketch != null ? sketch : "", cmRef.turnCount(),
                            runtimeSession.startedAt(), turns, llm.getModel(),
                            memRef.activeTaskContext(), memRef.artifactGoal());
                    sessionStore.save(data);
                }
            });
        }
        runtimeSession.addCloseListener(new dev.talos.runtime.SessionListener() {
            @Override public void onSessionEnd() {
                try { llm.close(); } catch (Exception ignored) { }
            }
        });

        // ── Stream sink ───────────────────────────────────────────────────
        // Wrapped in ToolCallStreamFilter to suppress text-form tool-call protocol
        // blocks from display, including JSON fallback fences and deprecated XML.
        //
        // Single-writer design (T774): `out` IS the authoritative stream.
        // In interactive mode RunCmd passes a terminal-backed PrintStream
        // (TerminalOutput.printStreamFor), so every character - streamed
        // chunks, banner, approval window, spinner - flows through JLine's
        // terminal writer and its cursor/column/virtual-line model never
        // diverges from the screen. The pre-T774 dual-writer split (raw
        // System.out beside terminal.writer()) caused the Apr 2026 display
        // corruption: a prompt redraw spliced leaked scrollback into the
        // input line ("talos [auto] >  user's prompt is '...") because
        // JLine never saw the bytes that had moved the cursor.
        final PrintStream stdout = out;
        java.util.function.Consumer<String> terminalSink = chunk -> {
            stdout.print(chunk);
            stdout.flush();
        };
        java.util.function.Consumer<String> streamSink =
                new ToolCallStreamFilter(render.answerStreamSink(terminalSink));

        // ── Context (dependency bag for modes and commands) ──────────────
        Context ctx = Context.builder(cfg)
                .limits(limits)
                .session(session)
                .audit(audit)
                .redactor(redactor)
                .sandbox(sandbox)
                .rag(rag)
                .llm(llm)
                .netPolicy(net)
                .memory(memory)
                .approvalGate(approvalGate)
                .toolRegistry(toolRegistry)
                .conversationManager(conversationManager)
                .toolCallLoop(toolCallLoop)
                .streamSink(streamSink)
                .onStreamComplete(spinnerStopper)
                .build();

        // ── Post-turn hooks ──────────────────────────────────────────────
        var memoryListener = new MemoryUpdateListener(conversationManager, llm, memory);
        // Auto mode routes to UnifiedAssistantMode by default - use the larger
        // assist-mode compaction budget (55%, 10-pair threshold) to prevent
        // premature context loss during multi-turn editing sessions.
        // T803: ONE flag feeds the listener, /context, and /compact so the
        // three surfaces can never disagree about the active mode.
        final boolean assistModeCompaction = true;
        memoryListener.setAssistMode(assistModeCompaction);
        turnProcessor.addListener(memoryListener);
        turnProcessor.addListener(new ActiveTaskContextUpdateListener(memory));

        // Per-turn structured durability (Step 2): appends one JSON line per
        // completed turn to ~/.talos/sessions/<sid>.turns.jsonl. Complements
        // the close-only snapshot and enables crash recovery.
        if (sessionPersistenceEnabled) {
            turnProcessor.addListener(
                    new dev.talos.runtime.JsonTurnLogAppender(sessionStore, sessionId));
        }

        // ── Commands ─────────────────────────────────────────────────────
        AtomicBoolean quit = new AtomicBoolean(false);
        CommandRegistry registry = new CommandRegistry();
        registerCommands(registry, session, cfg, ctx, modes, workspace, quit,
                sessionStore, checkpointService, runtimeSession.startedAt(), terminalWidth,
                sessionId, assistModeCompaction);
        // T806: workspace template commands load AFTER registration so the
        // collision guard sees every built-in name and alias (builtin-wins;
        // a workspace help.md can never shadow /help). The templates-aware
        // HelpCommand then deliberately overwrites the plain one registered
        // above - same spec, richer dependencies.
        WorkspaceCommandTemplates templates =
                WorkspaceCommandTemplates.load(workspace, registry.names());
        registry.register(new HelpCommand(registry, templates));

        // ── Assemble router ──────────────────────────────────────────────
        String sessionNotice = restoreSummary.hasSavedSession()
                ? buildRestoreNotice(restoreSummary)
                : buildSavedSessionNotice(savedSessionSummary);
        String startupNotice = joinStartupNotices(
                buildConfigNotice(cfg.getReport()),
                sessionNotice,
                buildSensitiveWorkspaceNotice(workspace),
                buildWorkspaceProfilesNotice(workspaceProfilesLoaded));
        return new ReplRouter(modes, turnProcessor, runtimeSession, ctx, render,
                              registry, workspace, quit, startupNotice, templates);
    }

    /**
     * Backward-compatible factory without JLine LineReader.
     * Approval falls back to Scanner(System.in). Used by tests and legacy callers.
     */
    public static ReplRouter create(SessionState session, Config cfg, PrintStream out, Path workspace) {
        return create(session, cfg, out, workspace, null);
    }

    /**
     * Backward-compatible JLine factory.
     */
    public static ReplRouter create(SessionState session, Config cfg, PrintStream out,
                                    Path workspace, LineReader lineReader) {
        return create(session, cfg, out, workspace, lineReader, null);
    }

    /**
     * Register all slash commands.
     * Extracted as a static method for readability - each command is a one-liner.
     */
    private static void registerCommands(CommandRegistry registry, SessionState session,
                                          Config cfg, Context ctx, ModeController modes,
                                          Path workspace, AtomicBoolean quit,
                                          SessionStore sessionStore,
                                          CheckpointService checkpointService,
                                          java.time.Instant activeSessionStartedAt,
                                          java.util.function.IntSupplier terminalWidth,
                                          String activeSessionId,
                                          boolean assistModeCompaction) {
        CliRuntime rt = new CliRuntime() {
            @Override public int getK()                { return session.getK(); }
            @Override public void setK(int k)          { session.setK(k); }
            @Override public boolean isDebug()          { return session.isDebug(); }
            @Override public void setDebug(boolean on)  { session.setDebug(on); }
            @Override public DebugLevel getDebugLevel() { return session.getDebugLevel(); }
            @Override public void setDebugLevel(DebugLevel level) { session.setDebugLevel(level); }
        };

        registry.register(new HelpCommand(registry));
        registry.register(new KCommand(rt));
        registry.register(new DebugCommand(rt));
        registry.register(new QuitCommand(quit));
        registry.register(new PolicyCommand());
        registry.register(new PrivacyCommand(workspace));
        registry.register(new AuditToggleCommand());
        registry.register(new SecretCommand(cfg, ctx.audit()));
        registry.register(new ModelsCommand());
        registry.register(new SetModelCommand());
        registry.register(new ModeCommand(modes));
        registry.register(new StatusCommand(modes, workspace, terminalWidth));
        // T799: /last must read the ACTIVE session's log - the injected id,
        // not a re-derived workspace hash (the pre-T799 silent breaker).
        registry.register(new ExplainLastTurnCommand(workspace, sessionStore,
                activeSessionStartedAt, activeSessionId));
        registry.register(new PromptCommand(modes, workspace));
        registry.register(new PromptDebugCommand());
        registry.register(new WorkspaceCommand(workspace));
        registry.register(new ReindexCommand(workspace, modes::invalidateSymbolCache));
        registry.register(new MemoryCommand());
        registry.register(new ClearCommand());
        // DX commands
        registry.register(new FilesCommand(workspace));
        registry.register(new GrepCommand(workspace));
        registry.register(new ShowCommand(workspace));
        // Performance benchmarking
        registry.register(new BenchCommand(workspace));
        // Routing diagnostics
        registry.register(new RouteCommand(modes));
        // Tool introspection
        registry.register(new ToolsCommand());
        // Environment preflight
        registry.register(new DoctorCommand(workspace));
        // Workspace verification profiles (T791)
        registry.register(new ProfilesCommand(workspace, checkpointService));
        registry.register(new VerifyCommand(workspace));
        // Undo (gated checkpoint restore since T795)
        registry.register(new UndoCommand(workspace, checkpointService));
        registry.register(new CheckpointCommand(workspace, checkpointService));
        // Session persistence (T800: list/resume need the active instance id)
        registry.register(new SessionCommand(workspace, sessionStore, activeSessionId));
        // Context-window meter (T803) + manual compaction (T804)
        registry.register(new ContextCommand(terminalWidth, assistModeCompaction));
        registry.register(new CompactCommand(assistModeCompaction));
    }

    private static String buildSensitiveWorkspaceNotice(Path workspace) {
        var assessment = SensitiveWorkspaceDetector.assess(workspace);
        return assessment.sensitive() ? assessment.warning() : "";
    }

    /** T790: an invalid declaration registers nothing - say so once, visibly. */
    private static String buildWorkspaceProfilesNotice(
            WorkspaceCommandProfilesLoader.Loaded loaded) {
        WorkspaceCommandProfiles profiles = loaded == null
                ? WorkspaceCommandProfiles.none()
                : loaded.profiles();
        if (!profiles.declared() || profiles.valid()) return "";
        return "Workspace verification profiles were not loaded (.talos/profiles.yaml): "
                + profiles.rejectionReason();
    }

    // ── Session reconciliation helpers ──────────────────────────────────

    /**
     * The newest stored session for this workspace (legacy bare-hash and
     * instance files compete on createdAt), or the legacy id itself when
     * nothing is stored - keeping the pre-T799 lookup behavior byte-for-byte
     * for empty stores.
     */
    static String latestSessionId(SessionStore store, String workspaceId) {
        var sessions = store.listSessions(workspaceId);
        return sessions.isEmpty() ? workspaceId : sessions.get(0).sessionId();
    }

    /** Live-memory twin of {@link #hasSavedActiveContext}: is there context worth saving? */
    private static boolean hasLiveActiveContext(SessionMemory memory) {
        ActiveTaskContext context = memory.activeTaskContext();
        ArtifactGoal goal = memory.artifactGoal();
        return (context != null && context.state() != ActiveTaskContext.State.NONE)
                || (goal != null && goal.source() != ArtifactGoal.Source.NONE);
    }

    /** Restore saved session context through snapshot-first, JSONL-fallback replay. */
    public static RestoreSummary restoreSavedSession(SessionStore store, String sessionId,
                                         SessionMemory memory, ConversationManager cm) {
        RestoreSummary restoreSummary = replaySnapshot(store, sessionId, memory, cm);
        if (restoreSummary.pairsReplayed() == 0) {
            int turnLogTurnsReplayed = replayTurnLog(store, sessionId, memory);
            if (turnLogTurnsReplayed > 0) {
                restoreSummary = new RestoreSummary(
                        turnLogTurnsReplayed,
                        restoreSummary.createdAt(),
                        restoreSummary.model(),
                        true);
            }
        }
        return restoreSummary;
    }

    public static RestoreSummary inspectSavedSession(SessionStore store, String sessionId) {
        if (store == null || sessionId == null || sessionId.isBlank()) {
            return new RestoreSummary(0, null, "");
        }
        var loaded = store.load(sessionId);
        if (loaded.isPresent()) {
            SessionData data = loaded.get();
            int pairs = countReplayableSnapshotPairs(data);
            if (pairs > 0 || hasSavedActiveContext(data)) {
                return new RestoreSummary(pairs, data.createdAt(), data.model(), true);
            }
        }
        int turnLogPairs = 0;
        java.time.Instant createdAt = null;
        for (var rec : store.loadTurns(sessionId)) {
            if (isReplayableTurnRecord(rec)) {
                turnLogPairs++;
                if (createdAt == null) createdAt = rec.timestamp();
            }
        }
        return new RestoreSummary(turnLogPairs, createdAt, "");
    }

    static RestoreSummary replaySnapshot(SessionStore store, String sessionId,
                              SessionMemory memory, ConversationManager cm) {
        var loaded = store.load(sessionId);
        if (loaded.isEmpty()) return new RestoreSummary(0, null, "");
        SessionData data = loaded.get();
        int pairs = 0;
        if (data.turns() != null) {
            for (int i = 0; i < data.turns().size() - 1; i += 2) {
                SessionData.Turn u = data.turns().get(i);
                SessionData.Turn a = data.turns().get(i + 1);
                if (isReplayableSnapshotPair(u, a)) {
                    memory.update(u.content(), a.content());
                    pairs++;
                }
            }
        }
        if (data.sketch() != null && !data.sketch().isBlank()) {
            cm.setSketch(data.sketch());
        }
        memory.setActiveTaskContext(data.activeTaskContext());
        memory.setArtifactGoal(data.artifactGoal());
        return new RestoreSummary(pairs, data.createdAt(), data.model(), pairs > 0 || hasSavedActiveContext(data));
    }

    /**
     * Fallback: replay the per-turn JSONL log into memory. Invoked only
     * when the snapshot yielded zero turns (missing file or empty turns
     * list) - i.e., the crash-recovery path.
     *
     * <p><b>Status-gated replay.</b> Only records whose {@code status} is
     * {@code "ok"} - or blank, for legacy pre-status JSONL lines written
     * before the status field existed - are re-injected into
     * {@link SessionMemory}. Records tagged {@code "error"},
     * {@code "aborted"}, {@code "info"}, or {@code "stream"} are skipped.
     *
     * <p><b>Why:</b> without this filter the reconcile path blindly
     * resurrected whatever assistantText the JSONL held - including
     * wall-clock-timed-out repetition-loop bodies and error-turn residue.
     * In one real incident (gemma4:26b, test-output.txt Apr 2026) a model
     * entered a repetition attractor, the turn was aborted at the 300s
     * wall-clock budget, and on the next REPL start the confabulated body
     * was replayed as if it were authoritative history, producing
     * cross-session hallucinated memory (the model "remembered"
     * destructive edits it had made in a prior session). The in-session
     * path is already protected by
     * {@link dev.talos.runtime.MemoryUpdateListener#stripUiChromeForHistory};
     * this closes the parallel cross-session gap.
     *
     * @return number of turn records replayed
     */
    static int replayTurnLog(SessionStore store, String sessionId, SessionMemory memory) {
        var records = store.loadTurns(sessionId);
        if (records == null || records.isEmpty()) return 0;
        int replayed = 0;
        for (var rec : records) {
            if (!isReplayableTurnRecord(rec)) continue;
            memory.update(rec.userInput(), rec.assistantText());
            replayed++;
        }
        return replayed;
    }

    private static int countReplayableSnapshotPairs(SessionData data) {
        if (data == null || data.turns() == null) return 0;
        int pairs = 0;
        for (int i = 0; i < data.turns().size() - 1; i += 2) {
            SessionData.Turn u = data.turns().get(i);
            SessionData.Turn a = data.turns().get(i + 1);
            if (isReplayableSnapshotPair(u, a)) {
                pairs++;
            }
        }
        return pairs;
    }

    private static boolean hasSavedActiveContext(SessionData data) {
        if (data == null) return false;
        ActiveTaskContext context = data.activeTaskContext();
        ArtifactGoal goal = data.artifactGoal();
        return (context != null && context.state() != ActiveTaskContext.State.NONE)
                || (goal != null && goal.source() != ArtifactGoal.Source.NONE);
    }

    private static boolean isReplayableSnapshotPair(SessionData.Turn user, SessionData.Turn assistant) {
        if (user == null || assistant == null) return false;
        String status = assistant.status();
        boolean replayable = status == null || status.isBlank() || "ok".equals(status);
        return replayable
                && "user".equals(user.role())
                && "assistant".equals(assistant.role())
                && user.content() != null && !user.content().isBlank()
                && assistant.content() != null && !assistant.content().isBlank();
    }

    private static boolean isReplayableTurnRecord(dev.talos.runtime.TurnRecord rec) {
        if (rec == null) return false;
        String status = rec.status();
        // Accept "ok" and "" (legacy records written before the status
        // field existed). Anything else - "error", "aborted", "info",
        // "stream", or a future tag - is non-conversational and must
        // not re-enter SessionMemory.
        if (status != null && !status.isEmpty() && !"ok".equals(status)) return false;
        String u = rec.userInput();
        String a = rec.assistantText();
        return u != null && !u.isBlank() && a != null && !a.isBlank();
    }

    static String buildRestoreNotice(RestoreSummary summary) {
        if (summary == null || !summary.hasSavedSession()) return "";
        String age = "";
        if (summary.createdAt() != null) {
            java.time.Duration d = java.time.Duration.between(summary.createdAt(), java.time.Instant.now());
            if (d.toDays() > 0) age = d.toDays() + "d ago";
            else if (d.toHours() > 0) age = d.toHours() + "h ago";
            else if (d.toMinutes() > 0) age = d.toMinutes() + "m ago";
            else age = d.toSeconds() + "s ago";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  restored ").append(summary.pairsReplayed()).append(" prior exchange")
                .append(summary.pairsReplayed() == 1 ? "" : "s");
        if (!age.isBlank()) sb.append(" from ").append(age);
        if (summary.model() != null && !summary.model().isBlank()) {
            sb.append(AnsiColor.isUnicodeSafe() ? " · model " : " - model ")
                    .append(summary.model());
        }
        return sb.toString();
    }

    static String buildSavedSessionNotice(RestoreSummary summary) {
        if (summary == null || !summary.hasSavedSession()) return "";
        String age = "";
        if (summary.createdAt() != null) {
            java.time.Duration d = java.time.Duration.between(summary.createdAt(), java.time.Instant.now());
            if (d.toDays() > 0) age = d.toDays() + "d ago";
            else if (d.toHours() > 0) age = d.toHours() + "h ago";
            else if (d.toMinutes() > 0) age = d.toMinutes() + "m ago";
            else age = d.toSeconds() + "s ago";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  saved session found: ").append(summary.pairsReplayed()).append(" prior exchange")
                .append(summary.pairsReplayed() == 1 ? "" : "s");
        if (!age.isBlank()) sb.append(" from ").append(age);
        sb.append(". Not loaded. Use /session load to resume or /session clear to delete.");
        return sb.toString();
    }

    static String buildConfigNotice(Config.Report report) {
        if (report == null || !report.userConfigPresent || report.userConfigLoaded) return "";
        return "  config warning: " + report.userConfigPath
                + " could not be loaded. Run `talos status --verbose`, then use `talos setup models` to rewrite it.";
    }

    private static String joinStartupNotices(String... notices) {
        if (notices == null || notices.length == 0) return "";
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String notice : notices) {
            if (notice != null && !notice.trim().isBlank()) {
                lines.add(notice.trim());
            }
        }
        return String.join(System.lineSeparator(), lines);
    }

    private static void syncActiveModelIntoConfig(Config cfg, String activeModel) {
        if (cfg == null || activeModel == null || activeModel.isBlank()) return;
        String backend = null;
        String modelName = activeModel.trim();
        int slash = modelName.indexOf('/');
        if (slash > 0 && slash < modelName.length() - 1) {
            backend = modelName.substring(0, slash).trim();
            modelName = modelName.substring(slash + 1).trim();
        }
        if (modelName.isBlank()) return;

        Map<String, Object> llm = new java.util.LinkedHashMap<>(CfgUtil.map(cfg.data.get("llm")));
        if (backend != null && !backend.isBlank()) {
            llm.put("default_backend", backend);
        }
        llm.put("model", modelName);
        cfg.data.put("llm", llm);

        if ("ollama".equals(backend)) {
            Map<String, Object> ollama = new java.util.LinkedHashMap<>(CfgUtil.map(cfg.data.get("ollama")));
            ollama.put("model", modelName);
            cfg.data.put("ollama", ollama);
        }
    }
}



