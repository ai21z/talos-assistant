package dev.loqj.cli.cmds;

import dev.loqj.cli.commands.*;
import dev.loqj.cli.modes.ModeController;
import dev.loqj.cli.repl.*;
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
 * Non-invasive bridge:
 * - Colonel commands: via CommandRegistry
 * - Prompts: via ModeController (active mode aware)
 * - Rendering: RenderEngine
 */
public final class RunCmdGlue {

    private final Object host;                 // RunCmd instance (for reflection on mode/k/debug)
    private final Config cfg;
    private final RenderEngine render;
    private final ExecutionPipeline pipe = new ExecutionPipeline();
    private final AtomicBoolean quit = new AtomicBoolean(false);
    private final CommandRegistry registry = new CommandRegistry();
    private final LineClassifier classifier = new LineClassifier();
    private final Context ctx;

    private final ModeController modes = ModeController.defaultController();

    public RunCmdGlue(Object runCmdHost, Config cfg, PrintStream out) {
        this.host = runCmdHost;
        this.cfg = (cfg == null ? new Config() : cfg);

        Redactor redactor = new Redactor();
        Sandbox sandbox = new Sandbox(Path.of("."), Map.of());

        this.ctx = new Context(
                this.cfg,
                new Audit(),
                redactor,
                sandbox,
                new RagService(this.cfg),
                new LlmClient(this.cfg),
                new NetPolicy(this.cfg)
        );
        this.render = new RenderEngine(this.cfg, redactor, out == null ? System.out : out);

        registerCommands();
    }

    public boolean tryHandle(String line) {
        LineClassifier.Classified c = classifier.classify(line);
        if (c.type() != LineClassifier.LineType.COMMAND) return false;

        String name = c.commandName();
        if (!registry.has(name)) return false;

        Result r = pipe.run(() -> registry.execute(name, c.argsText(), ctx), ctx, ":" + name);
        render.render(r);
        return true;
    }

    public boolean tryHandlePrompt(String rawLine, Path workspace, String activeModeName) {
        LineClassifier.Classified c = classifier.classify(rawLine);
        if (c.type() != LineClassifier.LineType.PROMPT) return false;

        Result r = pipe.run(() ->
                        modes.route(rawLine, workspace, ctx, activeModeName).orElse(null),
                ctx, "(prompt)"
        );
        if (r == null) return false;
        render.render(r);
        return true;
    }

    public boolean shouldQuit() { return quit.get(); }

    /* -------------------- internals -------------------- */

    private void registerCommands() {
        // adapter over RunCmd's fields
        CliRuntime rt = new CliRuntime() {
            @Override public int getK() { return reflectGetInt(host, new String[]{"k","topK"}, 8); }
            @Override public void setK(int k) { reflectSetInt(host, new String[]{"k","topK"}, k); }
            @Override public boolean isDebug() { return reflectGetBool(host, new String[]{"debug","verbose"}, false); }
            @Override public void setDebug(boolean on) { reflectSetBool(host, new String[]{"debug","verbose"}, on); }
        };

        registry.register(new HelpCommand(registry));
        registry.register(new KCommand(rt));
        registry.register(new DebugCommand(rt));
        registry.register(new QuitCommand(quit));
        registry.register(new PolicyCommand());
        registry.register(new AuditToggleCommand());
        registry.register(new SecretCommand(cfg, ctx.audit()));
        registry.register(new ModelsCommand());
        registry.register(new SetCommand());
    }

    private static int reflectGetInt(Object o, String[] fields, int def) {
        for (String f : fields) {
            try {
                var fld = o.getClass().getDeclaredField(f);
                fld.setAccessible(true);
                Object v = fld.get(o);
                if (v instanceof Integer iv) return iv;
            } catch (Exception ignore) {}
        }
        return def;
    }

    private static void reflectSetInt(Object o, String[] fields, int val) {
        for (String f : fields) {
            try {
                var fld = o.getClass().getDeclaredField(f);
                fld.setAccessible(true);
                fld.set(o, val);
                return;
            } catch (Exception ignore) {}
        }
    }

    private static boolean reflectGetBool(Object o, String[] fields, boolean def) {
        for (String f : fields) {
            try {
                var fld = o.getClass().getDeclaredField(f);
                fld.setAccessible(true);
                Object v = fld.get(o);
                if (v instanceof Boolean bv) return bv;
            } catch (Exception ignore) {}
        }
        return def;
    }

    private static void reflectSetBool(Object o, String[] fields, boolean val) {
        for (String f : fields) {
            try {
                var fld = o.getClass().getDeclaredField(f);
                fld.setAccessible(true);
                fld.set(o, val);
                return;
            } catch (Exception ignore) {}
        }
    }
}
