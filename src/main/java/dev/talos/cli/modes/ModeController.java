package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.RuntimeTurnContext;
import dev.talos.runtime.Result;
import dev.talos.runtime.TurnRouter;
import dev.talos.core.index.WorkspaceSymbolChecker;

import java.nio.file.Path;
import java.util.*;

/**
 * Router over registered Mode strategies with an active-mode concept.
 *
 * <h3>Auto-mode routing (agent-first)</h3>
 * <p>Uses {@link PromptClassifier} for classification, but only deterministic
 * commands dispatch to a separate mode:
 * <ul>
 *   <li>{@code COMMAND}  → DevMode (structural file ops: ls, dir, show, open)</li>
 *   <li>Everything else -> Agent (tools + retrieval-as-tool)</li>
 * </ul>
 *
 * <p>RagMode is still available via explicit {@code /mode rag} but is never
 * selected by auto-mode. Agent handles retrieval by calling
 * {@code talos.retrieve} as a tool when it needs workspace context.
 *
 * <p>When mode is explicitly set (not "auto"), that mode handles the input
 * directly. Explicit mode selection overrides the router.
 */
public final class ModeController implements TurnRouter {
    private final List<ModeEntry> order = new ArrayList<>();
    private final Map<String, ModeEntry> byName = new HashMap<>();
    private Mode structuralMode;
    private String activeName = "auto";
    private Runnable promptRefreshCallback;

    /** Last dispatched route - used by PromptClassifier for sticky retrieval. COMMAND is neutral. */
    private PromptClassifier.Route lastRoute;

    /** Optional workspace symbol checker for PascalCase → index resolution in auto-mode. */
    private WorkspaceSymbolChecker symbolChecker;


    /** Adds a mode to the controller's registry. */
    public ModeController add(Mode m) {
        return register(m, m != null && m.available(), m != null && !m.available());
    }

    /** Adds a selectable compatibility mode that is intentionally not advertised. */
    public ModeController addHidden(Mode m) {
        return register(m, false, false);
    }

    /** Sets the deterministic structural-command handler used by auto and agent modes. */
    ModeController structuralMode(Mode m) {
        this.structuralMode = m;
        return this;
    }

    /** Registers an alias for an existing mode (does not appear in sweep order). */
    public ModeController alias(String alias, Mode m) {
        if (alias != null && m != null) {
            ModeEntry entry = entryFor(m);
            if (entry != null) {
                byName.put(normalize(alias), entry);
            }
        }
        return this;
    }

    private ModeController register(Mode m, boolean advertised, boolean reserved) {
        if (m == null) return this;
        String canonical = normalize(m.name());
        ModeEntry entry = new ModeEntry(canonical, m, advertised && !reserved, reserved);
        order.add(entry);
        byName.put(canonical, entry);
        return this;
    }

    private ModeEntry entryFor(Mode mode) {
        for (ModeEntry entry : order) {
            if (entry.mode() == mode) return entry;
        }
        return null;
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

    /** Gets the active Mode if registered. */
    public Optional<Mode> getActive() {
        ModeEntry entry = byName.get(activeName);
        return entry == null ? Optional.empty() : Optional.of(entry.mode());
    }

    /**
     * Sets the active mode. Returns true if accepted. "auto" (the router default)
     * and any registered, available mode are accepted; reserved/stub modes
     * ({@link Mode#available()} false) and unknown names are rejected so the user
     * can never switch into a dead mode.
     */
    public boolean setActive(String name) {
        if (name == null || name.isBlank()) return false;
        String n = normalize(name);
        ModeEntry entry = byName.get(n);
        if ("auto".equals(n) || (entry != null && entry.selectable())) {
            this.activeName = "auto".equals(n) ? "auto" : entry.canonicalName();
            if (promptRefreshCallback != null) {
                promptRefreshCallback.run();
            }
            return true;
        }
        return false;
    }

    /**
     * Canonical mode names a user can switch into: "auto" plus every registered,
     * available mode, with aliases excluded. Drives {@code /mode} help so the
     * advertised set is generated from the registry and never drifts from what
     * {@link #setActive} actually accepts.
     */
    public List<String> availableModeNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add("auto");
        for (ModeEntry entry : order) {
            if (entry.advertised() && entry.selectable()) names.add(entry.canonicalName());
        }
        return List.copyOf(names);
    }

    /** Registered modes reserved/unavailable for selection (e.g. the web stub). */
    public List<String> reservedModeNames() {
        List<String> names = new ArrayList<>();
        for (ModeEntry entry : order) {
            if (entry.reserved()) names.add(entry.canonicalName());
        }
        return List.copyOf(names);
    }

    /** Routes without hint; uses activeName. */
    public Optional<Result> route(String rawLine, Path workspace, Context ctx) throws Exception {
        return route(rawLine, workspace, ctx, null);
    }

    /** Runtime port adapter; production passes the CLI Context composition object. */
    @Override
    public Optional<Result> route(String rawLine, Path workspace, RuntimeTurnContext ctx) throws Exception {
        return route(rawLine, workspace, requireCliContext(ctx), null);
    }

    /** Routes with a hint. If null/blank, activeName is used. */
    public Optional<Result> route(String rawLine, Path workspace, Context ctx, String hint) throws Exception {
        if (rawLine == null || rawLine.isBlank()) return Optional.empty();

        String h = (hint == null || hint.isBlank()) ? activeName : normalize(hint);

        // ── Auto-mode: assistant-first routing ───────────────────────────
        if ("auto".equals(h)) {
            return routeAuto(rawLine, workspace, ctx);
        }

        // ── Explicit mode: hard boundary, no sweep into other modes ───────
        ModeEntry entry = byName.get(h);
        if (entry == null || !entry.selectable()) return Optional.empty();
        if (isAgent(entry) && structuralMode != null && isStructuralCommand(rawLine)) {
            Optional<Result> structural = tryMode(structuralMode, rawLine, workspace, ctx);
            if (structural.isPresent()) return structural;
        }
        return tryMode(entry.mode(), rawLine, workspace, ctx);
    }

    /**
     * Auto-mode: deterministic commands -> structural handler, everything else -> Agent.
     *
     * <p>The PromptClassifier still classifies for diagnostics (route hint, lastRoute tracking),
     * but only COMMAND triggers deterministic dispatch. RETRIEVE and ASSIST both go to
     * Agent, which decides when to retrieve via tools.
     */
    private Optional<Result> routeAuto(String rawLine, Path workspace, Context ctx) throws Exception {

        // Classify the prompt (used for diagnostics and route hints, not hard dispatch)
        PromptClassifier.Route route = PromptClassifier.route(rawLine, lastRoute, symbolChecker);

        // Deterministic: structural commands (ls, dir, show, open) → DevMode
        if (route == PromptClassifier.Route.COMMAND) {
            Optional<Result> r = tryMode(structuralMode, rawLine, workspace, ctx);
            if (r.isPresent()) {
                updateLastRoute(route);
                return r;
            }
        }

        // Everything else -> Agent (legacy chat/dev/unified aliases resolve there).
        Optional<Result> r = tryMode(resolveAgent(), rawLine, workspace, ctx);
        if (r.isPresent()) {
            updateLastRoute(route);
            return r;
        }

        return Optional.empty();
    }

    /**
     * Updates conversation context. COMMAND is neutral - it doesn't reset
     * the retrieval context, so "explain X" → "ls src/" → "what about Y?"
     * correctly stays in retrieval mode.
     */
    private void updateLastRoute(PromptClassifier.Route route) {
        if (route != PromptClassifier.Route.COMMAND) {
            this.lastRoute = route;
        }
    }

    /** Returns the last route for conversation context (visible for :route command and testing). */
    public PromptClassifier.Route lastRoute() { return lastRoute; }

    /**
     * Attempts to execute a mode. Returns empty if mode is null,
     * can't handle the input, or returns empty.
     */
    private static Optional<Result> tryMode(Mode mode, String rawLine, Path workspace, Context ctx) throws Exception {
        if (mode == null || !mode.canHandle(rawLine)) return Optional.empty();
        Optional<Result> r = mode.handle(rawLine, workspace, ctx);
        return Objects.requireNonNullElse(r, Optional.empty());
    }

    private static Context requireCliContext(RuntimeTurnContext ctx) {
        if (ctx instanceof Context cliContext) {
            return cliContext;
        }
        throw new IllegalArgumentException("ModeController requires dev.talos.cli.repl.Context");
    }

    /**
     * Resolves the chat mode - prefers "chat" alias, falls back to "ask".
     */
    private Mode resolveAgent() {
        ModeEntry m = byName.get("chat");
        if (m == null) m = byName.get("agent");
        if (m == null) m = byName.get("ask");
        return m == null ? null : m.mode();
    }

    /**
     * Creates a default controller with standard modes registered.
     *
     * <p>Registration order matters for advertised mode ordering.
     * Legacy chat/dev/unified aliases resolve to Agent without being advertised.
     * Ask and Plan are explicit read-only public postures.
     */
    public static ModeController defaultController() {
        AskMode askMode = new AskMode();
        PlanMode planMode = new PlanMode();
        UnifiedAssistantMode agentMode = new UnifiedAssistantMode();
        return new ModeController()
                .structuralMode(new DevMode())
                .addHidden(new RagMode())
                .add(askMode)
                .add(planMode)
                .add(agentMode)
                .add(new WebMode())
                .add(new AutoMode())
                .alias("chat", agentMode)
                .alias("unified", agentMode)
                .alias("dev", agentMode)
                .alias("ask", askMode)
                .alias("plan", planMode);
    }

    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean isAgent(ModeEntry entry) {
        return entry != null && "agent".equals(entry.canonicalName());
    }

    private PromptClassifier.Route classify(String rawLine) {
        return PromptClassifier.route(rawLine, lastRoute, symbolChecker);
    }

    private boolean isStructuralCommand(String rawLine) {
        return classify(rawLine) == PromptClassifier.Route.COMMAND;
    }

    private record ModeEntry(String canonicalName, Mode mode, boolean advertised, boolean reserved) {
        boolean selectable() {
            return mode != null && mode.available() && !reserved;
        }
    }
}
