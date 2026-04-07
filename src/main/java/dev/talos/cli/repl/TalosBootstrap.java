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
import dev.talos.runtime.MemoryUpdateListener;
import dev.talos.runtime.Session;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.GrepTool;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.RetrieveTool;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * @param session   session state (k, debug) — typically the RunCmd instance
     * @param cfg       loaded configuration
     * @param out       output stream (typically System.out)
     * @param workspace workspace root directory
     * @return a configured ReplRouter
     */
    public static ReplRouter create(SessionState session, Config cfg, PrintStream out, Path workspace) {
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

        // ── Tools ────────────────────────────────────────────────────────
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new FileWriteTool());
        toolRegistry.register(new FileEditTool());
        toolRegistry.register(new GrepTool());
        toolRegistry.register(new ListDirTool());
        toolRegistry.register(new RetrieveTool(rag));

        // ── Conversation ─────────────────────────────────────────────────
        ConversationManager conversationManager =
                new ConversationManager(memory, TokenBudget.fromConfig(cfg));

        // ── Mode controller ──────────────────────────────────────────────
        ModeController modes = ModeController.defaultController();
        modes.setSymbolChecker(new IndexedWorkspaceSymbolChecker(workspace));

        // ── Runtime layer ────────────────────────────────────────────────
        Session        runtimeSession = new Session(workspace, cfg, memory);
        TurnProcessor  turnProcessor  = new TurnProcessor(modes, new CliApprovalGate(), toolRegistry);
        ToolCallLoop   toolCallLoop   = new ToolCallLoop(turnProcessor);

        // ── Rendering ────────────────────────────────────────────────────
        RenderEngine render = new RenderEngine(cfg, redactor, out);

        // Stream sink: stops spinner on first chunk and prints directly to stdout.
        final PrintStream stdout = out;
        final RenderEngine renderRef = render;
        java.util.function.Consumer<String> streamSink = chunk -> {
            renderRef.stopSpinner();
            stdout.print(chunk);
            stdout.flush();
        };

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
                .build();

        // ── Post-turn hooks ──────────────────────────────────────────────
        turnProcessor.addListener(new MemoryUpdateListener(conversationManager, llm));

        // ── Commands ─────────────────────────────────────────────────────
        AtomicBoolean quit = new AtomicBoolean(false);
        CommandRegistry registry = new CommandRegistry();
        registerCommands(registry, session, cfg, ctx, modes, workspace, quit);

        // ── Assemble router ──────────────────────────────────────────────
        return new ReplRouter(modes, turnProcessor, runtimeSession, ctx, render,
                              registry, workspace, quit);
    }

    /**
     * Register all slash commands.
     * Extracted as a static method for readability — each command is a one-liner.
     */
    private static void registerCommands(CommandRegistry registry, SessionState session,
                                         Config cfg, Context ctx, ModeController modes,
                                         Path workspace, AtomicBoolean quit) {
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
    }
}



