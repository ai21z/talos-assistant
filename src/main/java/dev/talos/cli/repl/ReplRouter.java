package dev.talos.cli.repl;

import dev.talos.cli.commands.*;
import dev.talos.cli.modes.ModeController;
import dev.talos.core.Audit;
import dev.talos.core.Config;
import dev.talos.core.index.IndexedWorkspaceSymbolChecker;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.net.NetPolicy;
import dev.talos.core.rag.RagService;
import dev.talos.core.security.Redactor;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.Session;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnResult;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.GrepTool;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.RetrieveTool;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * REPL router that dispatches commands and prompts:
 *  - Colon-commands are dispatched via CommandRegistry and ExecutionPipeline
 *  - Non-colon prompts are routed through ModeController
 *  - Results are rendered via RenderEngine
 */
public final class ReplRouter {

    private final SessionState session;
    private final Config cfg;
    private final RenderEngine render;
    private final ExecutionPipeline pipe = new ExecutionPipeline();
    private final AtomicBoolean quit = new AtomicBoolean(false);
    private final CommandRegistry registry = new CommandRegistry();
    private final LineClassifier classifier = new LineClassifier();
    private final Context ctx;
    private final Path workspace;
    private final Session runtimeSession;
    private final TurnProcessor turnProcessor;

    private final ModeController modes = ModeController.defaultController();

    public ReplRouter(SessionState session, Config cfg, PrintStream out, Path workspace) {
        this.session   = session;
        this.cfg       = (cfg == null ? new Config() : cfg);
        this.workspace = (workspace == null ? Path.of(".") : workspace);

        // Wire workspace-aware PascalCase resolution for auto-mode routing.
        // Bare PascalCase identifiers (e.g. "RagService") that match indexed
        // workspace symbols will trigger retrieval without question context.
        modes.setSymbolChecker(new IndexedWorkspaceSymbolChecker(this.workspace));

        // All components are composed explicitly
        Audit    audit    = new Audit();
        Redactor redactor = new Redactor();
        Sandbox  sandbox  = new Sandbox(this.workspace, Map.of());
        RagService rag    = new RagService(this.cfg);
        LlmClient llm     = new LlmClient(this.cfg);
        NetPolicy net     = new NetPolicy(this.cfg);
        Limits    limits  = Limits.fromConfig(this.cfg);
        SessionMemory memory = new SessionMemory();

        // Register concrete tools
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new GrepTool());
        toolRegistry.register(new RetrieveTool(rag));

        this.ctx = Context.builder(this.cfg)
                .limits(limits)
                .session(this.session)
                .audit(audit)
                .redactor(redactor)
                .sandbox(sandbox)
                .rag(rag)
                .llm(llm)
                .netPolicy(net)
                .memory(memory)
                .toolRegistry(toolRegistry)
                .build();

        // Create runtime session and turn processor
        this.runtimeSession = new Session(this.workspace, this.cfg, memory);
        this.turnProcessor  = new TurnProcessor(modes, new NoOpApprovalGate(), toolRegistry);

        this.render = new RenderEngine(this.cfg, redactor, out == null ? System.out : out);

        registerCommands();
    }

    public boolean tryHandle(String line) {
        LineClassifier.Classified c = classifier.classify(line);
        if (c.type() != LineClassifier.LineType.COMMAND) return false;
        String name = c.commandName();
        if (!registry.has(name)) return false;

        Result r = pipe.run(() ->
                        registry.execute(name, c.argsText(), ctx),
                ctx, ":" + name
        );

        render.render(r);
        return true;
    }

    public boolean tryHandlePrompt(String rawLine, Path workspaceOverride, String activeModeName) {
        LineClassifier.Classified c = classifier.classify(rawLine);
        if (c.type() != LineClassifier.LineType.PROMPT) return false;

        // Spinner is started before execution
        render.startSpinner();

        Result r = pipe.run(() -> {
                    TurnResult tr = turnProcessor.process(runtimeSession, rawLine, ctx);
                    return (tr == null) ? null : tr.result();
                },
                ctx, "(prompt)"
        );

        // Spinner is stopped automatically by render
        if (r == null) return false;
        render.render(r);
        return true;
    }

    public boolean shouldQuit() { return quit.get(); }

    public ModeController getModes() { return modes; }

    /** The runtime session bound to this router. */
    public Session getRuntimeSession() { return runtimeSession; }

    private void registerCommands() {
        // :k and :debug operate on SessionState
        CliRuntime rt = new CliRuntime() {
            @Override public int getK() { return session.getK(); }
            @Override public void setK(int k) { session.setK(k); }
            @Override public boolean isDebug() { return session.isDebug(); }
            @Override public void setDebug(boolean on) { session.setDebug(on); }
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
        registry.register(new StatusCommand(modes, this.workspace));
        registry.register(new WorkspaceCommand(this.workspace));
        registry.register(new ReindexCommand(this.workspace, modes::invalidateSymbolCache));
        registry.register(new MemoryCommand());
        // DX commands for workspace exploration
        registry.register(new FilesCommand(this.workspace));
        registry.register(new GrepCommand(this.workspace));
        registry.register(new ShowCommand(this.workspace));
        // Performance benchmarking
        registry.register(new BenchCommand(this.workspace));
        // Routing diagnostics
        registry.register(new RouteCommand(modes));
        // Tool introspection
        registry.register(new ToolsCommand());
    }
}
