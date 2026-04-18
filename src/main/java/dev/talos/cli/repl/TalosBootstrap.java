package dev.talos.cli.repl;

import dev.talos.cli.commands.*;
import dev.talos.cli.modes.ModeController;
import dev.talos.core.Audit;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.index.IndexedWorkspaceSymbolChecker;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.net.NetPolicy;
import dev.talos.core.rag.RagService;
import dev.talos.core.security.Redactor;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.CliApprovalGate;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.MemoryUpdateListener;
import dev.talos.runtime.Session;
import dev.talos.runtime.SessionData;
import dev.talos.runtime.SessionStore;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.runtime.TurnProcessor;
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

    private TalosBootstrap() {} // static factory only

    /**
     * Create a fully wired {@link ReplRouter} ready for the REPL loop.
     *
     * @param session    session state (k, debug) — typically the RunCmd instance
     * @param cfg        loaded configuration
     * @param out        output stream (typically System.out)
     * @param workspace  workspace root directory
     * @param lineReader optional JLine LineReader for approval prompts; when non-null,
     *                   approval uses the same terminal input system as the REPL
     * @return a configured ReplRouter
     */
    public static ReplRouter create(SessionState session, Config cfg, PrintStream out,
                                    Path workspace, LineReader lineReader) {
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
        SessionStore sessionStore = new JsonSessionStore();
        String sessionId = JsonSessionStore.sessionIdFor(workspace);

        // Auto-load previous session if one exists
        sessionStore.load(sessionId).ifPresent(data -> {
            // Replay turns into memory
            if (data.turns() != null) {
                for (int i = 0; i < data.turns().size() - 1; i += 2) {
                    SessionData.Turn u = data.turns().get(i);
                    SessionData.Turn a = data.turns().get(i + 1);
                    if ("user".equals(u.role()) && "assistant".equals(a.role())) {
                        memory.update(u.content(), a.content());
                    }
                }
            }
            // Restore compaction sketch
            if (data.sketch() != null && !data.sketch().isBlank()) {
                conversationManager.setSketch(data.sketch());
            }
        });

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
        if (lineReader != null) {
            Function<String, String> jlineReader = prompt -> {
                try {
                    return lineReader.readLine(prompt);
                } catch (org.jline.reader.EndOfFileException | org.jline.reader.UserInterruptException e) {
                    return null; // EOF / Ctrl-C → deny
                }
            };
            approvalGate = new CliApprovalGate(jlineReader, out, spinnerStopper);
        } else {
            // Fallback: Scanner-based (tests, non-interactive pipelines)
            approvalGate = new CliApprovalGate();
        }

        // ── Runtime layer ────────────────────────────────────────────────
        Session        runtimeSession = new Session(workspace, cfg, memory, sessionStore);
        TurnProcessor  turnProcessor  = new TurnProcessor(modes, approvalGate, toolRegistry);

        // Tool progress sink: renders lightweight status lines via RenderEngine.
        // Connected before ToolCallLoop so progress events flow during tool execution.
        ToolProgressSink progressSink = render::printToolProgress;
        ToolCallLoop   toolCallLoop   = new ToolCallLoop(turnProcessor,
                ToolCallLoop.DEFAULT_MAX_ITERATIONS, progressSink);

        // ── onStreamComplete: unconditional spinner stop after chatStream ──
        // Fixes the case where tool-call-only responses are fully suppressed by
        // ToolCallStreamFilter, so the rawSink never fires stopSpinner().
        final Runnable onStreamComplete = spinnerStopper;

        // Auto-save session on close
        final ConversationManager cmRef = conversationManager;
        final SessionMemory memRef = memory;
        final String sidRef = sessionId;
        final Path wsRef = workspace;
        runtimeSession.addCloseListener(new dev.talos.runtime.SessionListener() {
            @Override public void onSessionEnd() {
                java.util.List<SessionData.Turn> turns = memRef.getTurns().stream()
                        .map(m -> new SessionData.Turn(m.role(), m.content()))
                        .toList();
                String sketch = cmRef.sketch();
                SessionData data = new SessionData(sidRef, wsRef.toString(),
                        sketch != null ? sketch : "", cmRef.turnCount(),
                        runtimeSession.startedAt(), turns);
                sessionStore.save(data);
            }
        });

        // ── Stream sink ───────────────────────────────────────────────────
        // Wrapped in ToolCallStreamFilter to suppress <tool_call> XML from display.
        final PrintStream stdout = out;
        final RenderEngine renderRef = render;
        java.util.function.Consumer<String> rawSink = chunk -> {
            renderRef.stopSpinner();
            stdout.print(chunk);
            stdout.flush();
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

        // ── Commands ─────────────────────────────────────────────────────
        AtomicBoolean quit = new AtomicBoolean(false);
        CommandRegistry registry = new CommandRegistry();
        registerCommands(registry, session, cfg, ctx, modes, workspace, quit, undoStack, sessionStore);

        // ── Assemble router ──────────────────────────────────────────────
        return new ReplRouter(modes, turnProcessor, runtimeSession, ctx, render,
                              registry, workspace, quit);
    }

    /**
     * Backward-compatible factory without JLine LineReader.
     * Approval falls back to Scanner(System.in). Used by tests and legacy callers.
     */
    public static ReplRouter create(SessionState session, Config cfg, PrintStream out, Path workspace) {
        return create(session, cfg, out, workspace, null);
    }

    /**
     * Register all slash commands.
     * Extracted as a static method for readability — each command is a one-liner.
     */
    private static void registerCommands(CommandRegistry registry, SessionState session,
                                         Config cfg, Context ctx, ModeController modes,
                                         Path workspace, AtomicBoolean quit,
                                         FileUndoStack undoStack, SessionStore sessionStore) {
        CliRuntime rt = new CliRuntime() {
            @Override public int getK()                { return session.getK(); }
            @Override public void setK(int k)          { session.setK(k); }
            @Override public boolean isDebug()          { return session.isDebug(); }
            @Override public void setDebug(boolean on)  { session.setDebug(on); }
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
        // Session persistence
        registry.register(new SessionCommand(workspace, sessionStore));
    }
}



