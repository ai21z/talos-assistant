package dev.loqj.cli.cmds;

import dev.loqj.cli.commands.*;
import dev.loqj.cli.modes.ModeController;
import dev.loqj.cli.repl.*;
import dev.loqj.core.Audit;
import dev.loqj.core.Config;
import dev.loqj.core.net.NetPolicy;
import dev.loqj.core.security.Redactor;
import dev.loqj.core.security.Sandbox;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal, non-invasive bridge for RunCmd:
 * - Intercepts a small set of colon commands via the new framework
 * - Executes via ExecutionPipeline
 * - Prints via RenderEngine
 * - (NEW) Optional prompt handler for modes (DevMode first)
 */
public final class RunCmdGlue {

    private final Object host;                 // the RunCmd instance (for reflection on k/debug)
    private final Config cfg;
    private final RenderEngine render;
    private final ExecutionPipeline pipe = new ExecutionPipeline();
    private final AtomicBoolean quit = new AtomicBoolean(false);
    private final CommandRegistry registry = new CommandRegistry();
    private final LineClassifier classifier = new LineClassifier();
    private final Context ctx;

    // NEW: modes
    private final ModeController modes = ModeController.defaultController();

    public RunCmdGlue(Object runCmdHost, Config cfg, PrintStream out) {
        this.host = runCmdHost;
        this.cfg = (cfg == null ? new Config() : cfg);

        Redactor redactor = new Redactor(); // config-aware redactor
        Sandbox sandbox = new Sandbox(Path.of("."), Map.of());

        this.render = new RenderEngine(this.cfg, redactor, out == null ? System.out : out);
        this.ctx = new Context(
                this.cfg,
                new Audit(),
                redactor,
                sandbox,
                null,                 // RagService: not needed for these commands yet
                null,                 // LlmClient: not needed for these commands yet
                new NetPolicy(this.cfg)
        );
        registerCommands();
    }

    /** Returns true if the colon command line was handled here; caller should 'continue' the REPL loop. */
    public boolean tryHandle(String line) {
        LineClassifier.Classified c = classifier.classify(line);
        if (c.type() != LineClassifier.LineType.COMMAND) return false;

        String name = c.commandName();
        if (!registry.has(name)) return false;

        Result r = pipe.run(() -> registry.execute(name, c.argsText(), ctx), ctx, ":" + name);
        render.render(r);
        return true;
    }

    /**
     * NEW: Try to handle a PROMPT (non-colon line) via ModeController. Returns true if handled & rendered.
     * Safe to call early in the RunCmd loop, e.g. before native dev logic, to start draining behavior gradually.
     */
    public boolean tryHandlePrompt(String rawLine, Path workspace) {
        LineClassifier.Classified c = classifier.classify(rawLine);
        if (c.type() != LineClassifier.LineType.PROMPT) return false;

        Result r = pipe.run(() ->
                        modes.route(rawLine, workspace, ctx).orElse(null),
                ctx, "(prompt)"
        );
        if (r == null) return false;
        render.render(r);
        return true;
    }

    public boolean shouldQuit() { return quit.get(); }

    /* -------------------- internals -------------------- */

    private void registerCommands() {
        // lightweight adapter over RunCmd's fields
        CliRuntime rt = new CliRuntime() {
            @Override public int getK() { return reflectGetInt(host, new String[]{"k","topK"}, 8); }
            @Override public void setK(int k) { reflectSetInt(host, new String[]{"k","topK"}, k); }
            @Override public boolean isDebug() { return reflectGetBool(host, new String[]{"debug","verbose"}, false); }
            @Override public void setDebug(boolean on) { reflectSetBool(host, new String[]{"debug","verbose"}, on); }
        };

        registry.register(new KCommand(rt));
        registry.register(new DebugCommand(rt));
        registry.register(new QuitCommand(quit));
        registry.register(new PolicyCommand());
        registry.register(new AuditToggleCommand());
        // extend as needed (e.g., :secret set|get|del)
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
