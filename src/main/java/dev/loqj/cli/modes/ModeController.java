package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Router over registered Mode strategies with an active-mode concept.
 *
 * <h3>Auto-mode routing (assistant-first)</h3>
 * <p>Uses {@link PromptRouter} to make a definitive routing decision:
 * <ul>
 *   <li>{@code COMMAND}  → DevMode (structural file ops)</li>
 *   <li>{@code RETRIEVE} → RagMode (strong workspace evidence)</li>
 *   <li>{@code ASSIST}   → AskMode/ChatMode (default — no retrieval)</li>
 * </ul>
 *
 * <p>There is no UNKNOWN state and no retrieval-biased fallback sweep.
 * If the classified mode fails, the fallback is always ASSIST, never RAG.
 *
 * <p>When mode is explicitly set (not "auto"), that mode handles the input
 * directly. Explicit mode selection overrides the router.
 */
public final class ModeController {
    private final List<Mode> order = new ArrayList<>();
    private final Map<String, Mode> byName = new HashMap<>();
    private String activeName = "auto";
    private Runnable promptRefreshCallback;

    /**
     * Conversation context: the route of the last successfully dispatched turn.
     * Used by {@link PromptRouter} for sticky retrieval (follow-up detection).
     * COMMAND routes are neutral — they don't reset the conversation context.
     */
    private PromptRouter.Route lastRoute;

    // Intent pattern: "list files" queries → FilesCommand shortcut
    private static final Pattern LIST_FILES_PATTERN = Pattern.compile(
        "(?i)(?:what|which|show|list)\\s+(?:files|docs|documents)|" +
        "(?:list|show)\\s+(?:all\\s+)?files|" +
        "what.*(?:inside|in).*(?:dir|directory|folder|workspace)|" +
        "files\\s+(?:are\\s+)?(?:here|available|indexed)"
    );

    /**
     * Adds a mode to the controller's registry.
     */
    public ModeController add(Mode m) {
        if (m != null) {
            order.add(m);
            byName.put(m.name().toLowerCase(Locale.ROOT), m);
        }
        return this;
    }

    /**
     * Registers an additional alias for an existing mode instance.
     * The alias does not appear in the order list (no duplicate sweep).
     */
    public ModeController alias(String alias, Mode m) {
        if (alias != null && m != null) {
            byName.put(alias.toLowerCase(Locale.ROOT), m);
        }
        return this;
    }

    /**
     * Sets a callback to refresh the REPL prompt when mode changes.
     */
    public void setPromptRefreshCallback(Runnable callback) {
        this.promptRefreshCallback = callback;
    }

    /**
     * Returns the current active mode name (e.g., "rag", "dev", "auto", "chat").
     */
    public String getActiveName() { return activeName; }

    /**
     * Gets the active Mode if it's not "auto".
     */
    public Optional<Mode> getActive() { return Optional.ofNullable(byName.get(activeName)); }

    /**
     * Sets the active mode. Returns true if accepted.
     * Valid names are any registered mode names, aliases, plus "auto".
     */
    public boolean setActive(String name) {
        if (name == null || name.isBlank()) return false;
        String n = name.toLowerCase(Locale.ROOT).trim();
        if ("auto".equals(n) || byName.containsKey(n)) {
            this.activeName = n;
            if (promptRefreshCallback != null) {
                promptRefreshCallback.run();
            }
            return true;
        }
        return false;
    }

    /**
     * Back-compatibility API: routes without hint; controller uses its activeName.
     */
    public Optional<Result> route(String rawLine, Path workspace, Context ctx) throws Exception {
        return route(rawLine, workspace, ctx, null);
    }

    /**
     * Routes with a hint. If null/blank, activeName is used.
     */
    public Optional<Result> route(String rawLine, Path workspace, Context ctx, String hint) throws Exception {
        if (rawLine == null || rawLine.isBlank()) return Optional.empty();

        String h = (hint == null || hint.isBlank()) ? activeName : hint.toLowerCase(Locale.ROOT).trim();

        // ── Auto-mode: assistant-first routing ───────────────────────────
        if ("auto".equals(h)) {
            return routeAuto(rawLine, workspace, ctx);
        }

        // ── Explicit mode: use the selected mode, fallback to sweep ──────
        Optional<Result> r = tryMode(byName.get(h), rawLine, workspace, ctx);
        if (r.isPresent()) return r;

        // Explicit mode failed — sweep all modes in registration order
        for (Mode m : order) {
            r = tryMode(m, rawLine, workspace, ctx);
            if (r.isPresent()) return r;
        }
        return Optional.empty();
    }

    /**
     * Auto-mode routing: assistant-first, retrieval requires evidence.
     *
     * <p>Flow:
     * <ol>
     *   <li>"list files" shortcut → FilesCommand</li>
     *   <li>PromptRouter classifies → COMMAND / RETRIEVE / ASSIST</li>
     *   <li>Classified mode is tried</li>
     *   <li>If classified mode fails → always fall back to ASSIST</li>
     * </ol>
     *
     * <p>RAG is never a fallback. If the router doesn't say RETRIEVE,
     * retrieval doesn't happen.
     */
    private Optional<Result> routeAuto(String rawLine, Path workspace, Context ctx) throws Exception {
        // Special case: "list files" queries → FilesCommand shortcut
        // This intercept runs before PromptRouter because it maps to a
        // specific CLI command (Lucene index listing), not a Mode.
        if (LIST_FILES_PATTERN.matcher(rawLine.toLowerCase(Locale.ROOT)).find()) {
            try {
                var filesCmd = new dev.loqj.cli.commands.FilesCommand(workspace);
                return Optional.of(filesCmd.execute("", ctx));
            } catch (Exception e) {
                // Fallback to normal routing
            }
        }

        // Classify the prompt with conversation context
        PromptRouter.Route route = PromptRouter.route(rawLine, lastRoute);

        // Try the classified mode
        Optional<Result> r = switch (route) {
            case COMMAND  -> tryMode(byName.get("dev"), rawLine, workspace, ctx);
            case RETRIEVE -> tryMode(byName.get("rag"), rawLine, workspace, ctx);
            case ASSIST   -> tryMode(resolveChat(), rawLine, workspace, ctx);
        };
        if (r.isPresent()) {
            updateLastRoute(route);
            return r;
        }

        // Universal fallback: always assistant, never RAG
        if (route != PromptRouter.Route.ASSIST) {
            r = tryMode(resolveChat(), rawLine, workspace, ctx);
            if (r.isPresent()) {
                updateLastRoute(PromptRouter.Route.ASSIST);
                return r;
            }
        }

        return Optional.empty();
    }

    /**
     * Updates conversation context. COMMAND is neutral — it doesn't reset
     * the retrieval context, so "explain X" → "ls src/" → "what about Y?"
     * correctly stays in retrieval mode.
     */
    private void updateLastRoute(PromptRouter.Route route) {
        if (route != PromptRouter.Route.COMMAND) {
            this.lastRoute = route;
        }
    }

    /** Returns the last route for conversation context (visible for testing). */
    PromptRouter.Route lastRoute() { return lastRoute; }

    /**
     * Attempts to execute a mode. Returns empty if mode is null,
     * can't handle the input, or returns empty.
     */
    private static Optional<Result> tryMode(Mode mode, String rawLine, Path workspace, Context ctx) throws Exception {
        if (mode == null || !mode.canHandle(rawLine)) return Optional.empty();
        Optional<Result> r = mode.handle(rawLine, workspace, ctx);
        return (r != null) ? r : Optional.empty();
    }

    /**
     * Resolves the chat mode — prefers "chat" alias, falls back to "ask".
     */
    private Mode resolveChat() {
        Mode m = byName.get("chat");
        return m != null ? m : byName.get("ask");
    }

    /**
     * Creates a default controller with standard modes registered.
     * "chat" is registered as an alias for AskMode.
     */
    public static ModeController defaultController() {
        AskMode askMode = new AskMode();
        return new ModeController()
                .add(new DevMode())
                .add(new RagMode())
                .add(new RagMemoryMode())
                .add(askMode)
                .add(new WebMode())
                .add(new AutoMode())
                .alias("chat", askMode);
    }
}
