package dev.talos.cli.repl.slash;
import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.runtime.SessionMemory;
import dev.talos.cli.repl.TalosBootstrap;
import dev.talos.core.context.ConversationManager;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.SessionData;
import dev.talos.runtime.SessionStore;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
/**
 * /session - manage session persistence.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code /session info} - show current session status</li>
 *   <li>{@code /session save} - manually save session to disk</li>
 *   <li>{@code /session load} - restore the previous session for this workspace</li>
 *   <li>{@code /session clear} - delete the saved session file</li>
 * </ul>
 */
@SuppressWarnings("resource") // ctx.llm() is borrowed from the active REPL context.
public final class SessionCommand implements Command {
    private final Path workspace;
    private final SessionStore store;
    private final String sessionId;
    public SessionCommand(Path workspace, SessionStore store) {
        this.workspace = workspace;
        this.store = store;
        this.sessionId = JsonSessionStore.sessionIdFor(workspace);
    }
    @Override
    public CommandSpec spec() {
        return new CommandSpec("session", List.of(), "/session [info|save|load|clear]",
                "Manage session persistence.", CommandGroup.SESSION);
    }
    @Override
    public Result execute(String args, Context ctx) {
        String sub = (args == null ? "" : args.trim().toLowerCase());
        return switch (sub) {
            case ""      -> info(ctx);
            case "info"  -> info(ctx);
            case "save"  -> save(ctx);
            case "load"  -> load(ctx);
            case "clear" -> clear();
            default -> new Result.Error(
                    "Unknown subcommand: " + sub + "\nUsage: /session [info|save|load|clear]", 200);
        };
    }
    // -- Subcommands --
    private Result info(Context ctx) {
        int turns = ctx.conversationManager() != null
                ? ctx.conversationManager().turnCount() : 0;
        String sketch = ctx.conversationManager() != null
                ? ctx.conversationManager().sketch() : null;
        boolean hasSaved = store.load(sessionId).isPresent();
        StringBuilder sb = new StringBuilder();
        sb.append("Session ID:  ").append(sessionId, 0, Math.min(8, sessionId.length())).append("\u2026\n");
        sb.append("Workspace:   ").append(workspace.getFileName()).append('\n');
        sb.append("Turns:       ").append(turns).append('\n');
        sb.append("Has sketch:  ").append(sketch != null && !sketch.isBlank() ? "yes" : "no").append('\n');
        sb.append("Saved file:  ").append(hasSaved ? "yes" : "no");
        return new Result.Info(sb.toString());
    }
    private Result save(Context ctx) {
        SessionData data = snapshot(ctx);
        store.save(data);
        return new Result.Info("Session saved (" + data.turnCount() + " exchange"
                + (data.turnCount() == 1 ? "" : "s") + ", "
                + data.turns().size() + " messages).");
    }
    private Result load(Context ctx) {
        TalosBootstrap.RestoreSummary available = TalosBootstrap.inspectSavedSession(store, sessionId);
        if (!available.hasSavedSession()) {
            return new Result.Info("No saved session found for this workspace.");
        }
        ConversationManager cm = ctx.conversationManager();
        SessionMemory mem = ctx.memory();
        if (cm == null && mem == null) {
            return new Result.Error("Session context is unavailable.", 200);
        }

        if (cm != null) cm.clear();
        else mem.clear();

        ConversationManager targetCm = cm != null ? cm : new ConversationManager(mem);
        TalosBootstrap.RestoreSummary restored = TalosBootstrap.restoreSavedSession(store, sessionId, mem, targetCm);
        if (ctx.llm() != null && restored.model() != null && !restored.model().isBlank()) {
            ctx.llm().setModel(restored.model());
        }
        String age = formatAge(restored.createdAt());
        return new Result.Info("Session restored: " + restored.pairsReplayed() + " exchange"
                + (restored.pairsReplayed() == 1 ? "" : "s")
                + " (saved " + age + " ago).");
    }
    private Result clear() {
        boolean deleted = store.delete(sessionId);
        return deleted
                ? new Result.Info("Saved session deleted.")
                : new Result.Info("No saved session to delete.");
    }
    // -- Snapshot / Restore --
    /** Capture current conversation state into a SessionData record. */
    SessionData snapshot(Context ctx) {
        ConversationManager cm = ctx.conversationManager();
        SessionMemory mem = ctx.memory();
        String sketch = cm != null ? cm.sketch() : null;
        int turnCount = cm != null ? cm.turnCount() : 0;
        List<SessionData.Turn> turns;
        if (mem != null) {
            turns = mem.getTurns().stream()
                    .map(m -> new SessionData.Turn(m.role(), m.content(), "assistant".equals(m.role()) ? "ok" : ""))
                    .toList();
        } else {
            turns = List.of();
        }
        ActiveTaskContext activeTaskContext = mem == null ? ActiveTaskContext.none() : mem.activeTaskContext();
        ArtifactGoal artifactGoal = mem == null ? ArtifactGoal.none() : mem.artifactGoal();
        return new SessionData(sessionId, workspace.toString(), sketch != null ? sketch : "",
                turnCount, Instant.now(), turns, ctx.llm() != null ? ctx.llm().getModel() : "",
                activeTaskContext, artifactGoal);
    }
    /** The session ID for this workspace (for external use, e.g. auto-save). */
    public String sessionId() {
        return sessionId;
    }
    // -- Helpers --
    private static String formatAge(Instant then) {
        if (then == null) return "unknown";
        Duration d = Duration.between(then, Instant.now());
        if (d.toDays() > 0) return d.toDays() + "d";
        if (d.toHours() > 0) return d.toHours() + "h";
        if (d.toMinutes() > 0) return d.toMinutes() + "m";
        return d.toSeconds() + "s";
    }
}
