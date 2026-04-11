package dev.talos.cli.commands;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.cli.repl.SessionMemory;
import dev.talos.core.context.ConversationManager;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.SessionData;
import dev.talos.runtime.SessionStore;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
        Optional<SessionData> opt = store.load(sessionId);
        if (opt.isEmpty()) {
            return new Result.Info("No saved session found for this workspace.");
        }
        SessionData data = opt.get();
        restore(data, ctx);
        String age = formatAge(data.createdAt());
        return new Result.Info("Session restored: " + data.turnCount() + " exchange"
                + (data.turnCount() == 1 ? "" : "s")
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
                    .map(m -> new SessionData.Turn(m.role(), m.content()))
                    .toList();
        } else {
            turns = List.of();
        }
        return new SessionData(sessionId, workspace.toString(), sketch != null ? sketch : "",
                turnCount, Instant.now(), turns);
    }
    /** Restore conversation state from a SessionData record. */
    void restore(SessionData data, Context ctx) {
        ConversationManager cm = ctx.conversationManager();
        SessionMemory mem = ctx.memory();
        // Clear existing state
        if (cm != null) cm.clear();
        else if (mem != null) mem.clear();
        // Replay turns into memory
        if (mem != null && data.turns() != null) {
            List<SessionData.Turn> turns = data.turns();
            for (int i = 0; i < turns.size() - 1; i += 2) {
                SessionData.Turn user = turns.get(i);
                SessionData.Turn asst = turns.get(i + 1);
                if ("user".equals(user.role()) && "assistant".equals(asst.role())) {
                    mem.update(user.content(), asst.content());
                }
            }
        }
        // Restore sketch
        if (cm != null && data.sketch() != null && !data.sketch().isBlank()) {
            cm.setSketch(data.sketch());
        }
    }
    /** The session ID for this workspace (for external use, e.g. auto-save). */
    public String sessionId() {
        return sessionId;
    }
    // -- Helpers --
    private static String formatAge(Instant then) {
        Duration d = Duration.between(then, Instant.now());
        if (d.toDays() > 0) return d.toDays() + "d";
        if (d.toHours() > 0) return d.toHours() + "h";
        if (d.toMinutes() > 0) return d.toMinutes() + "m";
        return d.toSeconds() + "s";
    }
}