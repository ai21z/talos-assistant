package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;

import java.nio.file.Path;
import java.util.*;

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

    /** Last dispatched route — used by PromptRouter for sticky retrieval. COMMAND is neutral. */
    private PromptRouter.Route lastRoute;

    /** Optional workspace symbol checker for PascalCase → index resolution in auto-mode. */
    private WorkspaceSymbolChecker symbolChecker;


    /** Adds a mode to the controller's registry. */
    public ModeController add(Mode m) {
        if (m != null) {
            order.add(m);
            byName.put(m.name().toLowerCase(Locale.ROOT), m);
        }
        return this;
    }

    /** Registers an alias for an existing mode (does not appear in sweep order). */
    public ModeController alias(String alias, Mode m) {
        if (alias != null && m != null) {
            byName.put(alias.toLowerCase(Locale.ROOT), m);
        }
        return this;
    }

    /** Sets a callback to refresh the REPL prompt when mode changes. */
    public void setPromptRefreshCallback(Runnable callback) {
        this.promptRefreshCallback = callback;
    }

    /** Sets the workspace symbol checker (null to disable). */
    public void setSymbolChecker(WorkspaceSymbolChecker checker) {
        this.symbolChecker = checker;
    }

    /** Returns the current symbol checker (may be null). */
    public WorkspaceSymbolChecker getSymbolChecker() {
        return symbolChecker;
    }

    /** Invalidates the symbol cache. Safe to call when no checker is set. */
    public void invalidateSymbolCache() {
        if (symbolChecker != null) {
            symbolChecker.invalidateCache();
        }
    }

    /** Returns the active mode name ("rag", "dev", "auto", "chat", etc.). */
    public String getActiveName() { return activeName; }

    /** Gets the active Mode if not "auto". */
    public Optional<Mode> getActive() { return Optional.ofNullable(byName.get(activeName)); }

    /** Sets the active mode. Returns true if accepted (registered name or "auto"). */
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

    /** Routes without hint; uses activeName. */
    public Optional<Result> route(String rawLine, Path workspace, Context ctx) throws Exception {
        return route(rawLine, workspace, ctx, null);
    }

    /** Routes with a hint. If null/blank, activeName is used. */
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

    /** Auto-mode: classify → try classified mode → fallback to ASSIST (never RAG). */
    private Optional<Result> routeAuto(String rawLine, Path workspace, Context ctx) throws Exception {

        // Classify the prompt with conversation context and workspace awareness
        PromptRouter.Route route = PromptRouter.route(rawLine, lastRoute, symbolChecker);

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

    /** Returns the last route for conversation context (visible for :route command and testing). */
    public PromptRouter.Route lastRoute() { return lastRoute; }

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
                .add(askMode)
                .add(new WebMode())
                .add(new AutoMode())
                .alias("chat", askMode);
    }
}
