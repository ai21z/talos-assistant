package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;

import java.nio.file.Path;
import java.util.*;

/**
 * Router over registered Mode strategies with an active-mode concept.
 *
 * <h3>Auto-mode routing (unified-first)</h3>
 * <p>Uses {@link PromptRouter} for classification, but only deterministic
 * commands dispatch to a separate mode:
 * <ul>
 *   <li>{@code COMMAND}  → DevMode (structural file ops: ls, dir, show, open)</li>
 *   <li>Everything else → UnifiedAssistantMode (tools + retrieval-as-tool)</li>
 * </ul>
 *
 * <p>RagMode is still available via explicit {@code /mode rag} but is never
 * selected by auto-mode. The unified assistant handles retrieval by calling
 * {@code talos.retrieve} as a tool when it needs workspace context.
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

    /**
     * Auto-mode: deterministic commands → DevMode, everything else → UnifiedAssistantMode.
     *
     * <p>The PromptRouter still classifies for diagnostics (route hint, lastRoute tracking),
     * but only COMMAND triggers deterministic dispatch. RETRIEVE and ASSIST both go to
     * the unified assistant, which decides when to retrieve via tools.
     */
    private Optional<Result> routeAuto(String rawLine, Path workspace, Context ctx) throws Exception {

        // Classify the prompt (used for diagnostics and route hints, not hard dispatch)
        PromptRouter.Route route = PromptRouter.route(rawLine, lastRoute, symbolChecker);

        // Deterministic: structural commands (ls, dir, show, open) → DevMode
        if (route == PromptRouter.Route.COMMAND) {
            Optional<Result> r = tryMode(byName.get("dev"), rawLine, workspace, ctx);
            if (r.isPresent()) {
                updateLastRoute(route);
                return r;
            }
        }

        // Everything else → UnifiedAssistantMode (via "chat" alias → unified)
        Optional<Result> r = tryMode(resolveChat(), rawLine, workspace, ctx);
        if (r.isPresent()) {
            updateLastRoute(route);
            return r;
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
     *
     * <p>Registration order matters for sweep fallback.
     * "chat" is registered as an alias for UnifiedAssistantMode (used by auto-mode).
     * AskMode remains registered for backward compatibility and explicit /mode ask.
     */
    public static ModeController defaultController() {
        AskMode askMode = new AskMode();
        UnifiedAssistantMode unifiedMode = new UnifiedAssistantMode();
        return new ModeController()
                .add(new DevMode())
                .add(new RagMode())
                .add(askMode)
                .add(unifiedMode)
                .add(new WebMode())
                .add(new AutoMode())
                .alias("chat", unifiedMode)  // auto-mode resolveChat() → unified
                .alias("ask", askMode);       // explicit /mode ask still works
    }
}
