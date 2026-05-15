package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.runtime.policy.ProtectedReadScopePolicy;

import java.nio.file.Path;
import java.util.List;

public final class PrivacyCommand implements Command {
    private final Path workspace;

    public PrivacyCommand(Path workspace) {
        this.workspace = workspace;
    }

    @Override
    public CommandSpec spec() {
        return new CommandSpec(
                "privacy",
                List.of(),
                "/privacy [status|help|private on|private off]",
                "Inspect or change privacy mode.",
                CommandGroup.SECURITY);
    }

    @Override
    public Result execute(String args, Context ctx) {
        String normalized = args == null || args.isBlank()
                ? "status"
                : args.trim().toLowerCase(java.util.Locale.ROOT);

        if ("help".equals(normalized)) {
            return new Result.Info(helpText());
        }
        if ("status".equals(normalized)) {
            return new Result.Info(statusText(ctx));
        }
        if ("private on".equals(normalized) || "private enable".equals(normalized)) {
            ProtectedReadScopePolicy.setPrivateMode(ctx.cfg(), true);
            return new Result.Info("Privacy mode: private\n\n" + statusText(ctx));
        }
        if ("private off".equals(normalized) || "private disable".equals(normalized)) {
            ProtectedReadScopePolicy.setPrivateMode(ctx.cfg(), false);
            return new Result.Info("Privacy mode: developer\n\n" + statusText(ctx));
        }
        return new Result.Error("Unknown privacy command. Use /privacy help.", 200);
    }

    private String statusText(Context ctx) {
        var cfg = ctx.cfg();
        boolean privateMode = ProtectedReadScopePolicy.privateMode(cfg);
        boolean sendToModel = ProtectedReadScopePolicy.sendApprovedProtectedReadToModel(cfg);
        boolean ragInPrivate = ProtectedReadScopePolicy.ragEnabledInPrivateMode(cfg);
        boolean persistRaw = ProtectedReadScopePolicy.persistRawArtifacts(cfg);

        return "Privacy status\n"
                + "  workspace: " + workspace.toAbsolutePath().normalize().getFileName() + "\n"
                + "  mode: " + (privateMode ? "private" : "developer") + "\n"
                + "  protected read default scope: " + ProtectedReadScopePolicy.defaultScope(cfg) + "\n"
                + "  approved protected reads can enter model context: " + (sendToModel ? "yes" : "no") + "\n"
                + "  RAG/retrieve in private mode: " + (ragInPrivate ? "enabled" : "disabled") + "\n"
                + "  raw artifact persistence: " + (persistRaw ? "on" : "off") + "\n";
    }

    private static String helpText() {
        return """
                /privacy status
                  Show current privacy mode, model-context handoff, RAG/retrieve, and artifact persistence settings.

                /privacy private on
                  Switch this session to private mode. Approved protected reads default to LOCAL_DISPLAY_ONLY:
                  content is read locally after approval but withheld from model context and persisted artifacts.
                  RAG/retrieve is disabled by default in private mode.

                /privacy private off
                  Restore developer/default mode. Approved direct protected reads may enter model context.

                Private mode keeps prompt-debug, provider-body captures, traces, sessions, logs, and command
                stdout/stderr redacted by default. It does not make Talos ready for tax, health, legal,
                family, or admin paperwork.
                """;
    }
}
