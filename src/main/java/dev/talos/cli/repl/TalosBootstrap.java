package dev.talos.cli.repl;

import dev.talos.cli.repl.slash.*;
import dev.talos.cli.modes.ModeController;
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
import dev.talos.runtime.ActiveTaskContextUpdateListener;
import dev.talos.runtime.CliApprovalGate;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.MemoryUpdateListener;
import dev.talos.runtime.NoOpSessionStore;
import dev.talos.runtime.Session;
import dev.talos.runtime.SessionData;
import dev.talos.runtime.SessionStore;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.tools.FileUndoStack;
import dev.talos.tools.ToolProgressSink;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.GrepTool;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.RetrieveTool;
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
 * single place that knows <em>what gets created</em> — the router only
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
     * @param session    session state (k, debug) — typically the RunCmd instance
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
        // actively reading a prompt — which is exactly the window during
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
                // silently — the LLM still has the wall-clock + idle watchdog.
            }
        }
        llm.setCancelSupplier(cancelFlag::get);
        llm.setCancelResetHook(() -> cancelFlag.set(false));

        // ── Tools ────────────────────────────────────────────────────────
        FileUndoStack undoStack = new FileUndoStack();
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new FileWriteTool(undoStack));
        toolRegistry.register(new FileEditTool(undoStack));
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
        String sessionId = JsonSessionStore.sessionIdFor(workspace);

        RestoreSummary restoreSummary = new RestoreSummary(0, null, "");
        RestoreSummary savedSessionSummary = new RestoreSummary(0, null, "");
        if (sessionAutoLoadEnabled) {
            restoreSummary = restoreSavedSession(sessionStore, sessionId, memory, conversationManager);
        } else if (sessionPersistenceEnabled) {
            savedSessionSummary = inspectSavedSession(sessionStore, sessionId);
        }
        if (restoreSummary.model() != null && !restoreSummary.model().isBlank()) {
            llm.setModel(restoreSummary.model());
            syncActiveModelIntoConfig(cfg, llm.getModel());
        }

        // ── Mode controller ──────────────────────────────────────────────
        ModeController modes = ModeController.defaultController();
        modes.setSymbolChecker(new IndexedWorkspaceSymbolChecker(workspace));

        // ── Rendering (created early so progress sink can reference it) ──
        RenderEngine render = new RenderEngine(cfg, redactor, out);

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
            approvalGate = new CliApprovalGate(effectiveApprovalReader, out, spinnerStopper);
        } else {
            // Fallback: Scanner-based (tests, non-interactive pipelines)
            approvalGate = new CliApprovalGate();
        }

        // ── Runtime layer ────────────────────────────────────────────────
        Session        runtimeSession = new Session(workspace, cfg, memory, sessionStore);
        // Session-scoped approval policy sits above the gate. Without this,
        // the REPL falls back to ALWAYS_ASK and the user's "a = yes for
        // session" choice has no effect — the tri-state gate still reports
        // APPROVED_REMEMBER but the policy never flips the flag, because
        // ApprovalPolicy.ALWAYS_ASK.rememberApproval is a no-op.
        dev.talos.runtime.SessionApprovalPolicy approvalPolicy =
                new dev.talos.runtime.SessionApprovalPolicy();
        CheckpointService checkpointService = new CheckpointService();
        TurnProcessor  turnProcessor  = new TurnProcessor(
                modes, approvalGate, toolRegistry, approvalPolicy, checkpointService);

        // Tool progress sink: renders lightweight status lines via RenderEngine.
        // Connected before ToolCallLoop so progress events flow during tool execution.
        ToolProgressSink progressSink = render::printToolProgress;
        ToolCallLoop   toolCallLoop   = new ToolCallLoop(turnProcessor,
                ToolCallLoop.DEFAULT_MAX_ITERATIONS, progressSink);

        // ── onStreamComplete: unconditional spinner stop after chatStream ──
        // Fixes the case where tool-call-only responses are fully suppressed by
        // ToolCallStreamFilter, so the rawSink never fires stopSpinner().
        final Runnable onStreamComplete = spinnerStopper;

        if (sessionPersistenceEnabled) {
            // Auto-save session evidence on close. Saved evidence is not prompt
            // context unless session.auto_load=true or the user runs /session load.
            final ConversationManager cmRef = conversationManager;
            final SessionMemory memRef = memory;
            final String sidRef = sessionId;
            final Path wsRef = workspace;
            runtimeSession.addCloseListener(new dev.talos.runtime.SessionListener() {
                @Override public void onSessionEnd() {
                    java.util.List<SessionData.Turn> turns = memRef.getTurns().stream()
                            .map(m -> new SessionData.Turn(m.role(), m.content(), "assistant".equals(m.role()) ? "ok" : ""))
                            .toList();
                    String sketch = cmRef.sketch();
                    SessionData data = new SessionData(sidRef, wsRef.toString(),
                            sketch != null ? sketch : "", cmRef.turnCount(),
                            runtimeSession.startedAt(), turns, llm.getModel(),
                            memRef.activeTaskContext(), memRef.artifactGoal());
                    sessionStore.save(data);
                }
            });
        }

        // ── Stream sink ───────────────────────────────────────────────────
        // Wrapped in ToolCallStreamFilter to suppress text-form tool-call protocol
        // blocks from display, including JSON fallback fences and deprecated XML.
        //
        // JLine-safe output: when a LineReader is available, route streaming
        // chunks through its Terminal's writer instead of raw System.out.
        // JLine tracks the terminal's cursor/column/virtual-line state
        // internally; writes that bypass it (direct stdout.print) diverge
        // that model from reality, and on Windows (jna=true) the next
        // readLine() call's redraw sequence then corrupts the display.
        //
        // Observed: test-output.txt Apr 2026 line 306 — after a 300s
        // wall-clock-aborted repetition loop, the next prompt redraw spliced
        // leaked token content onto the same visible line as the prompt
        // ("talos [auto] >  user's prompt is 'The user's prompt is '...").
        // The tokens were never typed; JLine's cursor model just didn't
        // know the terminal had moved, so the redraw's CUP/CR/EL sequences
        // ended up reprinting scrollback as if it were the input buffer.
        //
        // Using terminal.writer() keeps JLine authoritative over every
        // character that reaches the terminal. Falls back to stdout when
        // no LineReader is supplied (headless tests, programmatic API).
        final PrintStream stdout = out;
        final RenderEngine renderRef = render;
        final java.io.PrintWriter termWriter =
                (lineReader != null) ? lineReader.getTerminal().writer() : null;
        java.util.function.Consumer<String> rawSink = chunk -> {
            renderRef.stopSpinner();
            if (termWriter != null) {
                termWriter.print(chunk);
                termWriter.flush();
            } else {
                stdout.print(chunk);
                stdout.flush();
            }
        };
        java.util.function.Consumer<String> streamSink = new ToolCallStreamFilter(rawSink);

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
                .onStreamComplete(onStreamComplete)
                .build();

        // ── Post-turn hooks ──────────────────────────────────────────────
        var memoryListener = new MemoryUpdateListener(conversationManager, llm);
        // Auto mode routes to UnifiedAssistantMode by default — use the larger
        // assist-mode compaction budget (55%, 10-pair threshold) to prevent
        // premature context loss during multi-turn editing sessions.
        memoryListener.setAssistMode(true);
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
        registerCommands(registry, session, cfg, ctx, modes, workspace, quit, undoStack,
                sessionStore, checkpointService, runtimeSession.startedAt());

        // ── Assemble router ──────────────────────────────────────────────
        String startupNotice = restoreSummary.hasSavedSession()
                ? buildRestoreNotice(restoreSummary)
                : buildSavedSessionNotice(savedSessionSummary);
        return new ReplRouter(modes, turnProcessor, runtimeSession, ctx, render,
                              registry, workspace, quit, startupNotice);
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
     * Extracted as a static method for readability — each command is a one-liner.
     */
    private static void registerCommands(CommandRegistry registry, SessionState session,
                                          Config cfg, Context ctx, ModeController modes,
                                          Path workspace, AtomicBoolean quit,
                                          FileUndoStack undoStack, SessionStore sessionStore,
                                          CheckpointService checkpointService,
                                          java.time.Instant activeSessionStartedAt) {
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
        registry.register(new AuditToggleCommand());
        registry.register(new SecretCommand(cfg, ctx.audit()));
        registry.register(new ModelsCommand());
        registry.register(new SetModelCommand());
        registry.register(new ModeCommand(modes));
        registry.register(new StatusCommand(modes, workspace));
        registry.register(new ExplainLastTurnCommand(workspace, sessionStore, activeSessionStartedAt));
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
        // File undo
        registry.register(new UndoCommand(undoStack));
        registry.register(new CheckpointCommand(workspace, checkpointService));
        // Session persistence
        registry.register(new SessionCommand(workspace, sessionStore));
    }

    // ── Session reconciliation helpers ──────────────────────────────────

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
     * list) — i.e., the crash-recovery path.
     *
     * <p><b>Status-gated replay.</b> Only records whose {@code status} is
     * {@code "ok"} — or blank, for legacy pre-status JSONL lines written
     * before the status field existed — are re-injected into
     * {@link SessionMemory}. Records tagged {@code "error"},
     * {@code "aborted"}, {@code "info"}, or {@code "stream"} are skipped.
     *
     * <p><b>Why:</b> without this filter the reconcile path blindly
     * resurrected whatever assistantText the JSONL held — including
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
        // field existed). Anything else — "error", "aborted", "info",
        // "stream", or a future tag — is non-conversational and must
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

    private static void syncActiveModelIntoConfig(Config cfg, String activeModel) {
        if (cfg == null || activeModel == null || activeModel.isBlank()) return;
        String modelName = activeModel.contains("/") ? activeModel.substring(activeModel.indexOf('/') + 1) : activeModel;
        Map<String, Object> ollama = new java.util.LinkedHashMap<>(CfgUtil.map(cfg.data.get("ollama")));
        ollama.put("model", modelName);
        cfg.data.put("ollama", ollama);
    }
}



