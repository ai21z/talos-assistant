package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Router over registered Mode strategies with an active-mode concept.
 *
 * <p>Auto-mode routing uses {@link IntentClassifier} to determine intent:
 * <ul>
 *   <li>CHAT → routes to chat/ask mode (no retrieval)</li>
 *   <li>RAG  → routes to rag mode (full retrieval pipeline)</li>
 *   <li>DEV  → routes to dev mode (file ops)</li>
 *   <li>UNKNOWN → candidate sweep: dev → rag → chat, first match wins</li>
 * </ul>
 *
 * <p>When mode is explicitly set (not "auto"), that mode is tried first,
 * then fallback sweep runs in registration order.
 */
public final class ModeController {
    private final List<Mode> order = new ArrayList<>();
    private final Map<String, Mode> byName = new HashMap<>();
    private String activeName = "auto";
    private Runnable promptRefreshCallback;

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
     * Execution is performed in a single pass over a de-duplicated ordered set of candidates.
     */
    public Optional<Result> route(String rawLine, Path workspace, Context ctx, String hint) throws Exception {
        if (rawLine == null || rawLine.isBlank()) return Optional.empty();

        String h = (hint == null || hint.isBlank()) ? activeName : hint.toLowerCase(Locale.ROOT).trim();

        // ── Auto-mode: intent-based routing ──────────────────────────────
        if ("auto".equals(h)) {

            // Special case: "list files" queries → FilesCommand shortcut
            if (LIST_FILES_PATTERN.matcher(rawLine.toLowerCase(Locale.ROOT)).find()) {
                try {
                    var filesCmd = new dev.loqj.cli.commands.FilesCommand(workspace);
                    return Optional.of(filesCmd.execute("", ctx));
                } catch (Exception e) {
                    // Fallback to normal routing
                }
            }

            // Classify intent
            IntentClassifier.Intent intent = IntentClassifier.classify(rawLine);

            switch (intent) {
                case CHAT -> {
                    Mode chatMode = resolveChat();
                    if (chatMode != null && chatMode.canHandle(rawLine)) {
                        Optional<Result> r = chatMode.handle(rawLine, workspace, ctx);
                        if (r != null && r.isPresent()) return r;
                    }
                }
                case DEV -> {
                    Mode devMode = byName.get("dev");
                    if (devMode != null && devMode.canHandle(rawLine)) {
                        Optional<Result> r = devMode.handle(rawLine, workspace, ctx);
                        if (r != null && r.isPresent()) return r;
                    }
                }
                case RAG -> {
                    Mode ragMode = byName.get("rag");
                    if (ragMode != null && ragMode.canHandle(rawLine)) {
                        Optional<Result> r = ragMode.handle(rawLine, workspace, ctx);
                        if (r != null && r.isPresent()) return r;
                    }
                }
                case UNKNOWN -> {
                    // Fall through to candidate sweep below
                }
            }
        }

        // ── Candidate sweep (explicit mode or UNKNOWN fallback) ──────────
        LinkedHashSet<Mode> seq = new LinkedHashSet<>();

        if ("auto".equals(h)) {
            // UNKNOWN intent: try dev → rag → chat
            addIfPresent(seq, byName.get("dev"));
            addIfPresent(seq, byName.get("rag"));
            addIfPresent(seq, resolveChat());
        } else {
            addIfPresent(seq, byName.get(h));
        }
        // Fallback: sweep all modes in registration order
        for (Mode m : order) addIfPresent(seq, m);

        // Single pass: first mode that canHandle + returns non-empty result wins
        for (Mode m : seq) {
            if (m == null) continue;
            if (!m.canHandle(rawLine)) continue;
            Optional<Result> r = m.handle(rawLine, workspace, ctx);
            if (r != null && r.isPresent()) return r;
        }
        return Optional.empty();
    }

    /**
     * Resolves the chat mode — prefers "chat" alias, falls back to "ask".
     */
    private Mode resolveChat() {
        Mode m = byName.get("chat");
        return m != null ? m : byName.get("ask");
    }

    private static void addIfPresent(LinkedHashSet<Mode> seq, Mode m) {
        if (m != null) seq.add(m);
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
