package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.runtime.policy.PrivateDocumentPolicy;
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
        boolean privateDocModel = PrivateDocumentPolicy.privateDocumentModelHandoffOptIn(cfg);
        boolean privateDocArtifacts = PrivateDocumentPolicy.privateDocumentRawArtifactPersistenceOptIn(cfg);
        boolean privateDocRag = PrivateDocumentPolicy.privateDocumentRagIndexingOptIn(cfg);

        return "Privacy status\n"
                + "  workspace: " + workspace.toAbsolutePath().normalize().getFileName() + "\n"
                + "  mode: " + (privateMode ? "private" : "developer") + "\n"
                + "  protected read default scope: " + ProtectedReadScopePolicy.defaultScope(cfg) + "\n"
                + "  approved protected reads can enter model context: " + (sendToModel ? "yes" : "no") + "\n"
                + "  private-mode document extraction model-context opt-in: " + (privateDocModel ? "enabled" : "disabled") + "\n"
                + "  private-mode document extraction raw artifact persistence: " + (privateDocArtifacts ? "on" : "off") + "\n"
                + "  private-mode document extraction RAG indexing: " + (privateDocRag ? "enabled" : "disabled") + "\n"
                + "  RAG/retrieve in private mode: " + (ragInPrivate ? "enabled" : "disabled") + "\n"
                + "  raw artifact persistence: " + (persistRaw ? "on" : "off") + "\n"
                + "  persistence: current session/config state only; edit ~/.talos/config.yaml for persistent defaults\n";
    }

    private static String helpText() {
        return """
                /privacy status
                  Show current privacy mode, protected-read handoff, private document extraction controls,
                  RAG/retrieve, and artifact persistence settings.

                /privacy private on
                  Switch the current session/config state to private mode. Approved protected reads default to LOCAL_DISPLAY_ONLY:
                  content is read locally after approval but withheld from model context and persisted artifacts.
                  RAG/retrieve is disabled by default in private mode.

                Private document extraction
                  In private mode, extracted PDF/DOCX/XLS/XLSX text is treated as local-display-only by default.
                  It is not sent to model context, not persisted raw, and not indexed by RAG unless the
                  separate privacy.document_extraction opt-ins are enabled in config.
                  Ordinary personal facts in normal .md/.txt/.csv files are not private by provenance unless the
                  file path or content matches protected-policy signals.

                /privacy private off
                  Restore developer/default mode for the current session/config state. Approved direct protected reads may enter model context.

                Persistence
                  This command does not write ~/.talos/config.yaml. Edit ~/.talos/config.yaml for persistent defaults.

                Private mode keeps prompt-debug, provider-body captures, traces, sessions, logs, and command
                stdout/stderr redacted by default. It does not make Talos ready for tax, health, legal,
                family, or admin paperwork.
                """;
    }
}
