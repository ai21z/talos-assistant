package dev.loqj.cli.repl;

import dev.loqj.cli.commands.*;
import dev.loqj.cli.modes.ModeController;
import dev.loqj.core.Audit;
import dev.loqj.core.Config;
import dev.loqj.core.llm.LlmClient;
import dev.loqj.core.net.NetPolicy;
import dev.loqj.core.rag.RagService;
import dev.loqj.core.security.Redactor;
import dev.loqj.core.security.Sandbox;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ReplRouter:
 *  - Dispatches colon-commands via CommandRegistry + ExecutionPipeline
 *  - Routes non-colon prompts through ModeController
 *  - Renders Results via RenderEngine
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

    private final ModeController modes = ModeController.defaultController();

    public ReplRouter(SessionState session, Config cfg, PrintStream out, Path workspace) {
        this.session   = session;
        this.cfg       = (cfg == null ? new Config() : cfg);
        this.workspace = (workspace == null ? Path.of(".") : workspace);

        // compose all pieces explicitly
        Audit    audit    = new Audit();
        Redactor redactor = new Redactor();
        Sandbox  sandbox  = new Sandbox(this.workspace, Map.of());
        RagService rag    = new RagService(this.cfg);
        LlmClient llm     = new LlmClient(this.cfg);
        NetPolicy net     = new NetPolicy(this.cfg);
        Limits    limits  = Limits.fromConfig(this.cfg);

        this.ctx = Context.builder(this.cfg)
                .limits(limits)
                .session(this.session)
                .audit(audit)
                .redactor(redactor)
                .sandbox(sandbox)
                .rag(rag)
                .llm(llm)
                .netPolicy(net)
                .build();

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

        Path ws = (workspaceOverride == null ? this.workspace : workspaceOverride);

        Result r = pipe.run(() ->
                        modes.route(rawLine, ws, ctx, activeModeName).orElse(null),
                ctx, "(prompt)"
        );
        if (r == null) return false;
        render.render(r);
        return true;
    }

    public boolean shouldQuit() { return quit.get(); }

    public ModeController getModes() { return modes; }

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
        registry.register(new WorkspaceCommand(this.workspace));  // NEW: :workspace command
        registry.register(new ReindexCommand(this.workspace));
        registry.register(new MemoryCommand());
        // DX commands for workspace exploration
        registry.register(new GrepCommand(this.workspace));
        registry.register(new ShowCommand(this.workspace));
        // Performance benchmarking
        registry.register(new BenchCommand(this.workspace));
    }
}
