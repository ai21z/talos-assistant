package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Router over registered Mode strategies with an active-mode concept.
 * Single-pass logic is used:
 *   - If hint == "auto": dev -> rag -> ask is tried, then all modes are swept
 *   - Else if hint matches a mode: hinted mode is tried first, then all modes are swept
 *   - Sweep is executed in registration order and runs only once
 */
public final class ModeController {
    private final List<Mode> order = new ArrayList<>();
    private final Map<String, Mode> byName = new HashMap<>();
    private String activeName = "auto";
    private Runnable promptRefreshCallback;

    // Intent patterns for auto-mode routing
    private static final Pattern LIST_FILES_PATTERN = Pattern.compile(
        "(?i)(?:what|which|show|list)\\s+(?:files|docs|documents)|" +
        "(?:list|show)\\s+(?:all\\s+)?files|" +
        "what.*(?:inside|in).*(?:dir|directory|folder|workspace)|" +
        "files\\s+(?:are\\s+)?(?:here|available|indexed)"
    );

    private static final Pattern TRIVIAL_QUERY_PATTERN = Pattern.compile(
        "(?i)(?:how many|count)\\s+['\"]?[a-z]['\"]?\\s+in\\s+|" +
        "(?:spell|define|what is|what does|who is|who was|when did)\\s+|" +
        "(?:calculate|compute|solve)\\s+|" +
        "\\d+\\s*[+\\-*/]\\s*\\d+"
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
     * Sets a callback to refresh the REPL prompt when mode changes.
     */
    public void setPromptRefreshCallback(Runnable callback) {
        this.promptRefreshCallback = callback;
    }

    /**
     * Returns the current active mode name (e.g., "rag", "dev", "auto").
     */
    public String getActiveName() { return activeName; }

    /**
     * Gets the active Mode if it's not "auto".
     */
    public Optional<Mode> getActive() { return Optional.ofNullable(byName.get(activeName)); }

    /**
     * Sets the active mode. Returns true if accepted.
     * Valid names are any registered mode names plus "auto".
     */
    public boolean setActive(String name) {
        if (name == null || name.isBlank()) return false;
        String n = name.toLowerCase(Locale.ROOT).trim();
        if ("auto".equals(n) || byName.containsKey(n)) {
            this.activeName = n;
            // Prompt refresh is triggered if callback is set
            if (promptRefreshCallback != null) {
                promptRefreshCallback.run();
            }
            return true;
        }
        return false;
    }

    /**
     * Back-compatibility API: routes without hint provided; controller uses its activeName.
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

        // Auto-mode intent detection
        if ("auto".equals(h)) {
            String lower = rawLine.toLowerCase(Locale.ROOT);

            // Intent 1: "list files" queries -> FilesCommand is invoked directly
            if (LIST_FILES_PATTERN.matcher(lower).find()) {
                try {
                    var filesCmd = new dev.loqj.cli.commands.FilesCommand(workspace);
                    return Optional.of(filesCmd.execute("", ctx));
                } catch (Exception e) {
                    // Fallback to normal routing if command fails
                }
            }

            // Intent 2: Trivial/non-workspace queries -> ASK mode is used directly
            // Query is checked for file tokens and trivial patterns
            if (TRIVIAL_QUERY_PATTERN.matcher(rawLine).find() && !containsFileTokens(rawLine)) {
                Mode askMode = byName.get("ask");
                if (askMode != null && askMode.canHandle(rawLine)) {
                    Optional<Result> r = askMode.handle(rawLine, workspace, ctx);
                    if (r != null && r.isPresent()) return r;
                }
            }
        }

        // Candidate sequence is built once
        LinkedHashSet<Mode> seq = new LinkedHashSet<>();

        if ("auto".equals(h)) {
            addIfPresent(seq, byName.get("dev"));
            addIfPresent(seq, byName.get("rag"));
            addIfPresent(seq, byName.get("ask"));
        } else {
            addIfPresent(seq, byName.get(h));
        }
        // Fallback sweep in declared order
        for (Mode m : order) addIfPresent(seq, m);

        // Single pass: first mode that both "canHandle" and returns a non-empty result wins
        for (Mode m : seq) {
            if (m == null) continue;
            if (!m.canHandle(rawLine)) continue;
            Optional<Result> r = m.handle(rawLine, workspace, ctx);
            if (r != null && r.isPresent()) return r;
        }
        return Optional.empty();
    }

    /**
     * Checks if the raw line contains any file-like tokens (paths with extensions).
     */
    private static boolean containsFileTokens(String rawLine) {
        return rawLine.matches(".*\\b\\w+\\.(java|md|txt|yaml|yml|json|xml|properties|html|js|py|go|rs|cpp)\\b.*");
    }

    /**
     * Adds a mode to the sequence if it's not null.
     */
    private static void addIfPresent(LinkedHashSet<Mode> seq, Mode m) {
        if (m != null) seq.add(m);
    }

    /**
     * Creates a default controller with standard modes registered.
     */
    public static ModeController defaultController() {
        return new ModeController()
                .add(new DevMode())
                .add(new RagMode())
                .add(new RagMemoryMode())
                .add(new AskMode())
                .add(new WebMode())
                .add(new AutoMode());
    }
}
