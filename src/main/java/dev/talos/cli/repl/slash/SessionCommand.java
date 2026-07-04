package dev.talos.cli.repl.slash;
import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.runtime.SessionMemory;
import dev.talos.cli.repl.TalosBootstrap;
import dev.talos.core.context.ConversationManager;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.SessionData;
import dev.talos.runtime.SessionStore;
import dev.talos.runtime.SessionSummary;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.policy.ProtectedContentPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
/**
 * /session - manage session persistence.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code /session info} - show current session status</li>
 *   <li>{@code /session list} - list stored sessions for this workspace</li>
 *   <li>{@code /session resume [id]} - restore a stored session (latest
 *       other session by default; unique id prefix otherwise)</li>
 *   <li>{@code /session save} - manually save this session to disk</li>
 *   <li>{@code /session load} - alias of {@code resume}</li>
 *   <li>{@code /session clear} - delete the current session's stored files</li>
 * </ul>
 *
 * <p>T800: sessions are per-run instance files since T799
 * ({@code <ws-hash>-<UTC timestamp>}). {@code save}/{@code clear} and the
 * {@code Saved file} row operate on the ACTIVE instance id injected by the
 * composition root; {@code list}/{@code resume} see every stored session
 * of the workspace, legacy bare-hash files included. Display ids are the
 * timestamp suffix (all instance ids of a workspace share the same 40-hex
 * prefix, so the hash head cannot distinguish them); the single possible
 * legacy file displays as the first hash characters.
 */
@SuppressWarnings("resource") // ctx.llm() is borrowed from the active REPL context.
public final class SessionCommand implements Command {
    private static final String USAGE = "/session [info|list|resume|save|load|clear|export]";
    private static final DateTimeFormatter EXPORT_FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path workspace;
    private final SessionStore store;
    private final String workspaceId;
    private final String activeSessionId;
    private final Path exportsDir;

    /** Legacy wiring: the active session IS the bare-hash slot (pre-T799 tests). */
    public SessionCommand(Path workspace, SessionStore store) {
        this(workspace, store, JsonSessionStore.sessionIdFor(workspace));
    }

    public SessionCommand(Path workspace, SessionStore store, String activeSessionId) {
        this(workspace, store, activeSessionId,
                Path.of(System.getProperty("user.home"), ".talos", "exports"));
    }

    public SessionCommand(Path workspace, SessionStore store, String activeSessionId,
                          Path exportsDir) {
        this.workspace = workspace;
        this.store = store;
        this.workspaceId = JsonSessionStore.sessionIdFor(workspace);
        this.activeSessionId = activeSessionId == null || activeSessionId.isBlank()
                ? this.workspaceId
                : activeSessionId;
        this.exportsDir = exportsDir;
    }
    @Override
    public CommandSpec spec() {
        return new CommandSpec("session", List.of(), USAGE,
                "Manage session persistence.", CommandGroup.SESSION);
    }
    @Override
    public Result execute(String args, Context ctx) {
        String trimmed = args == null ? "" : args.trim();
        int space = trimmed.indexOf(' ');
        String sub = (space < 0 ? trimmed : trimmed.substring(0, space)).toLowerCase();
        String rest = space < 0 ? "" : trimmed.substring(space + 1).trim();
        return switch (sub) {
            case "", "info"   -> info(ctx);
            case "list"   -> list();
            case "resume", "load" -> resume(rest, ctx);
            case "save"   -> save(ctx);
            case "clear"  -> clear();
            case "export" -> export(rest);
            default -> new Result.Error(
                    "Unknown subcommand: " + sub + "\nUsage: " + USAGE, 200);
        };
    }
    // -- Subcommands --
    private Result info(Context ctx) {
        int turns = ctx.conversationManager() != null
                ? ctx.conversationManager().turnCount() : 0;
        String sketch = ctx.conversationManager() != null
                ? ctx.conversationManager().sketch() : null;
        boolean hasSaved = store.load(activeSessionId).isPresent();
        String info = "Session ID:  " + workspaceId.substring(0, Math.min(8, workspaceId.length())) + "…\n"
                + "Session:     " + displayId(activeSessionId) + '\n'
                + "Workspace:   " + workspace.getFileName() + '\n'
                + "Turns:       " + turns + '\n'
                + "Has sketch:  " + (sketch != null && !sketch.isBlank() ? "yes" : "no") + '\n'
                + "Saved file:  " + (hasSaved ? "yes" : "no");
        return new Result.Info(info);
    }
    private Result list() {
        List<SessionSummary> sessions = store.listSessions(workspaceId);
        if (sessions.isEmpty()) {
            return new Result.Info("No saved session found for this workspace.");
        }
        List<String[]> rows = new ArrayList<>();
        for (SessionSummary s : sessions) {
            rows.add(new String[] {
                    displayId(s.sessionId()),
                    ageColumn(s.createdAt()),
                    s.turnCount() + (s.turnCount() == 1 ? " exchange" : " exchanges"),
                    s.model() == null || s.model().isBlank() ? "-" : s.model(),
                    markers(s)
            });
        }
        int[] widths = new int[4];
        for (String[] row : rows) {
            for (int i = 0; i < 4; i++) widths[i] = Math.max(widths[i], row[i].length());
        }
        StringBuilder sb = new StringBuilder("Sessions (newest first):");
        for (String[] row : rows) {
            sb.append("\n  ");
            for (int i = 0; i < 4; i++) {
                sb.append(pad(row[i], widths[i]));
                if (i < 3) sb.append("  ");
            }
            if (!row[4].isEmpty()) sb.append("  ").append(row[4]);
            // trailing spaces from padding the last filled column are noise
            while (sb.charAt(sb.length() - 1) == ' ') sb.setLength(sb.length() - 1);
        }
        return new Result.Info(sb.toString());
    }
    private Result resume(String idPrefix, Context ctx) {
        List<SessionSummary> sessions = store.listSessions(workspaceId);
        String targetId;
        if (idPrefix.isBlank()) {
            // Default: the latest OTHER session - "pick up where the
            // previous session left off". The active session's own files
            // are usually the newest and resuming them is a no-op.
            targetId = sessions.stream()
                    .map(SessionSummary::sessionId)
                    .filter(id -> !id.equals(activeSessionId))
                    .findFirst()
                    .orElse(null);
            if (targetId == null) {
                return new Result.Info("No saved session found for this workspace.");
            }
        } else {
            // Explicit prefix may target any session, the current one
            // included (a deliberate reload-from-disk).
            List<String> matches = sessions.stream()
                    .map(SessionSummary::sessionId)
                    .filter(id -> matchesPrefix(id, idPrefix))
                    .toList();
            if (matches.isEmpty()) {
                return new Result.Error(
                        "No session matches '" + idPrefix + "'. See /session list.", 200);
            }
            if (matches.size() > 1) {
                return new Result.Error("Ambiguous session id '" + idPrefix + "': matches "
                        + String.join(", ", matches.stream().map(this::displayId).toList()), 200);
            }
            targetId = matches.get(0);
        }
        return restoreInto(targetId, ctx);
    }
    private Result restoreInto(String targetId, Context ctx) {
        TalosBootstrap.RestoreSummary available = TalosBootstrap.inspectSavedSession(store, targetId);
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
        TalosBootstrap.RestoreSummary restored = TalosBootstrap.restoreSavedSession(store, targetId, mem, targetCm);
        if (ctx.llm() != null && restored.model() != null && !restored.model().isBlank()) {
            ctx.llm().setModel(restored.model());
        }
        String age = formatAge(restored.createdAt());
        return new Result.Info("Session restored: " + restored.pairsReplayed() + " exchange"
                + (restored.pairsReplayed() == 1 ? "" : "s")
                + " (saved " + age + " ago).");
    }
    private Result save(Context ctx) {
        SessionData data = snapshot(ctx);
        store.save(data);
        return new Result.Info("Session saved (" + data.turnCount() + " exchange"
                + (data.turnCount() == 1 ? "" : "s") + ", "
                + data.turns().size() + " messages).");
    }
    private Result clear() {
        boolean deleted = store.delete(activeSessionId);
        return deleted
                ? new Result.Info("Current saved session deleted.")
                : new Result.Info("No current saved session to delete.");
    }
    // -- Export (T801) --
    /**
     * {@code export [id-prefix] [path] [--raw]}: write a markdown
     * transcript of a stored session. No approval - the default target
     * lives under the user's own {@code ~/.talos/exports/}, never the
     * workspace unless an explicit path says so (PromptCommand
     * precedent). Content was redacted at write time; the assembled
     * document gets one more idempotent
     * {@link ProtectedContentPolicy#sanitizeText} pass.
     */
    private Result export(String args) {
        boolean raw = false;
        List<String> positionals = new ArrayList<>();
        for (String token : args.isBlank() ? new String[0] : args.split("\\s+")) {
            if ("--raw".equals(token)) raw = true;
            else positionals.add(token);
        }
        if (positionals.size() > 2) {
            return new Result.Error("Usage: /session export [id-prefix] [path] [--raw]", 200);
        }

        List<SessionSummary> sessions = store.listSessions(workspaceId);
        if (sessions.isEmpty()) {
            return new Result.Info("No saved session found for this workspace.");
        }

        // Positional split: [prefix, path] when two; a single token is a
        // prefix when it matches a session, otherwise an explicit path.
        String prefix = null;
        String explicitPath = null;
        if (positionals.size() == 2) {
            prefix = positionals.get(0);
            explicitPath = positionals.get(1);
        } else if (positionals.size() == 1) {
            String token = positionals.get(0);
            boolean anyMatch = sessions.stream()
                    .anyMatch(s -> matchesPrefix(s.sessionId(), token));
            if (anyMatch) prefix = token;
            else if (looksLikePath(token)) explicitPath = token;
            else return new Result.Error("No session matches '" + token + "'. See /session list.", 200);
        }

        String targetId;
        if (prefix == null) {
            targetId = sessions.get(0).sessionId(); // newest
        } else {
            final String p = prefix;
            List<String> matches = sessions.stream()
                    .map(SessionSummary::sessionId)
                    .filter(id -> matchesPrefix(id, p))
                    .toList();
            if (matches.isEmpty()) {
                return new Result.Error("No session matches '" + prefix + "'. See /session list.", 200);
            }
            if (matches.size() > 1) {
                return new Result.Error("Ambiguous session id '" + prefix + "': matches "
                        + String.join(", ", matches.stream().map(this::displayId).toList()), 200);
            }
            targetId = matches.get(0);
        }

        String document = renderTranscript(targetId);
        if (document == null) {
            return new Result.Info("Session '" + displayId(targetId) + "' has no exportable turns.");
        }

        try {
            Path target;
            if (explicitPath != null) {
                target = workspace.resolve(explicitPath).toAbsolutePath().normalize();
            } else {
                Files.createDirectories(exportsDir);
                target = exportsDir.resolve("talos-session-" + fileSafeId(targetId) + "-"
                        + EXPORT_FILE_TS.format(LocalDateTime.now()) + ".md")
                        .toAbsolutePath().normalize();
            }
            if (Files.exists(target)) {
                return new Result.Error("Refusing to overwrite existing file: " + target, 200);
            }
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, document, StandardCharsets.UTF_8);

            StringBuilder message = new StringBuilder("Session exported to: ").append(target);
            if (raw) {
                message.append('\n').append(copyRawTurnLog(targetId, target));
            }
            return new Result.TrustedInfo(message.toString());
        } catch (Exception e) {
            return new Result.Error("Export failed: " + e.getMessage(), 200);
        }
    }

    /** Markdown transcript: snapshot turns, else ok-status turn-log rows. Null when empty. */
    private String renderTranscript(String sessionId) {
        SessionData data = store.load(sessionId).orElse(null);
        StringBuilder body = new StringBuilder();
        Instant created = null;
        String model = "";
        String sketch = "";
        int exchanges;

        if (data != null && data.turns() != null && !data.turns().isEmpty()) {
            created = data.createdAt();
            model = data.model();
            sketch = data.sketch();
            exchanges = data.turnCount();
            int turnNo = 0;
            for (SessionData.Turn t : data.turns()) {
                boolean isUser = "user".equals(t.role());
                if (isUser || turnNo == 0) {
                    turnNo++;
                    body.append("\n## Turn ").append(turnNo).append('\n');
                }
                body.append("\n**").append(isUser ? "User" : "Assistant").append(":**\n\n")
                        .append(t.content() == null ? "" : t.content()).append('\n');
            }
        } else {
            // Crash log: only completed-ok rows are part of the record the
            // user can vouch for (same filter as restore - no aborted-turn
            // confabulation in an artifact that leaves the machine).
            List<TurnRecord> rows = store.loadTurns(sessionId).stream()
                    .filter(r -> r.status() == null || r.status().isEmpty() || "ok".equals(r.status()))
                    .filter(r -> r.userInput() != null && !r.userInput().isBlank()
                            && r.assistantText() != null && !r.assistantText().isBlank())
                    .toList();
            if (rows.isEmpty()) return null;
            if (data != null) {
                created = data.createdAt();
                model = data.model();
                sketch = data.sketch();
            } else if (rows.get(0).timestamp() != null) {
                created = rows.get(0).timestamp();
            }
            exchanges = rows.size();
            int turnNo = 0;
            for (TurnRecord row : rows) {
                turnNo++;
                body.append("\n## Turn ").append(turnNo).append('\n');
                body.append("\n**User:**\n\n").append(row.userInput()).append('\n');
                body.append("\n**Assistant:**\n\n").append(row.assistantText()).append('\n');
            }
        }

        StringBuilder doc = new StringBuilder();
        doc.append("# Talos session ").append(displayId(sessionId)).append('\n');
        doc.append('\n');
        doc.append("- Session ID: ").append(sessionId).append('\n');
        doc.append("- Workspace: ").append(workspace.toAbsolutePath().normalize()).append('\n');
        doc.append("- Created: ").append(created == null ? "unknown" : created.toString()).append('\n');
        doc.append("- Model: ").append(model == null || model.isBlank() ? "-" : model).append('\n');
        doc.append("- Exchanges: ").append(exchanges).append('\n');
        doc.append("- Sketch: ").append(sketch == null || sketch.isBlank() ? "no" : "yes").append('\n');
        if (sketch != null && !sketch.isBlank()) {
            doc.append("\n## Context sketch\n\n").append(sketch).append('\n');
        }
        doc.append(body);
        return ProtectedContentPolicy.sanitizeText(doc.toString());
    }

    /** Best-effort raw copy of the per-turn JSONL beside the markdown export. */
    private String copyRawTurnLog(String sessionId, Path markdownTarget) {
        if (!(store instanceof JsonSessionStore js)) {
            return "Raw turn log unavailable: sessions are not file-backed in this process.";
        }
        Path source = js.sessionsDir().resolve(sessionId + ".turns.jsonl");
        if (!Files.exists(source)) {
            return "No turn log exists for this session; raw copy skipped.";
        }
        String name = markdownTarget.getFileName().toString();
        Path rawTarget = markdownTarget.resolveSibling(
                (name.endsWith(".md") ? name.substring(0, name.length() - 3) : name) + ".jsonl");
        try {
            if (Files.exists(rawTarget)) {
                return "Refusing to overwrite existing file: " + rawTarget;
            }
            Files.copy(source, rawTarget);
            return "Raw turn log copied to: " + rawTarget;
        } catch (Exception e) {
            return "Raw turn log copy failed: " + e.getMessage();
        }
    }

    private static boolean looksLikePath(String token) {
        return token.contains("/") || token.contains("\\") || token.contains(".");
    }

    /** Display id without the ellipsis - safe inside a file name. */
    private String fileSafeId(String id) {
        if (id.startsWith(workspaceId + "-")) {
            return id.substring(workspaceId.length() + 1);
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
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
        return new SessionData(activeSessionId, workspace.toString(), sketch != null ? sketch : "",
                turnCount, Instant.now(), turns, ctx.llm() != null ? ctx.llm().getModel() : "",
                activeTaskContext, artifactGoal);
    }
    /** The active session's storage id - the save/clear target. */
    public String sessionId() {
        return activeSessionId;
    }
    // -- Helpers --
    /**
     * Human-facing session id: the timestamp suffix for instance ids
     * (the shared 40-hex hash head carries no information within one
     * workspace), the first hash characters for the legacy file.
     */
    private String displayId(String id) {
        if (id.startsWith(workspaceId + "-")) {
            return id.substring(workspaceId.length() + 1);
        }
        return id.length() <= 8 ? id : id.substring(0, 8) + "…";
    }
    /** A prefix matches on the display key (timestamp suffix) or the full id. */
    private boolean matchesPrefix(String id, String prefix) {
        String key = id.startsWith(workspaceId + "-")
                ? id.substring(workspaceId.length() + 1)
                : id;
        return key.startsWith(prefix) || id.startsWith(prefix);
    }
    private String markers(SessionSummary s) {
        if (s.sessionId().equals(activeSessionId)) return "(current)";
        List<String> tags = new ArrayList<>();
        if (s.legacy()) tags.add("legacy");
        if (!s.hasSnapshot()) tags.add("crash log only");
        return tags.isEmpty() ? "" : "(" + String.join(", ", tags) + ")";
    }
    private static String ageColumn(Instant createdAt) {
        if (createdAt == null || Instant.EPOCH.equals(createdAt)) return "unknown";
        return formatAge(createdAt) + " ago";
    }
    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }
    private static String formatAge(Instant then) {
        if (then == null) return "unknown";
        Duration d = Duration.between(then, Instant.now());
        if (d.toDays() > 0) return d.toDays() + "d";
        if (d.toHours() > 0) return d.toHours() + "h";
        if (d.toMinutes() > 0) return d.toMinutes() + "m";
        return d.toSeconds() + "s";
    }
}
