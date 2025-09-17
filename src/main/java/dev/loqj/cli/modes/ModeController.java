package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;

import java.nio.file.Path;
import java.util.*;

/**
 * Router over registered Mode strategies with an active-mode concept.
 * Single-pass logic:
 *   - If hint == "auto": try dev -> rag -> ask, then sweep all
 *   - Else if hint matches a mode: try hinted first, then sweep all
 *   - Sweep is in registration order and only runs once
 */
public final class ModeController {
    private final List<Mode> order = new ArrayList<>();
    private final Map<String, Mode> byName = new HashMap<>();
    private String activeName = "ask"; // default to ask mode
    private Runnable promptRefreshCallback;

    public ModeController add(Mode m) {
        if (m != null) {
            order.add(m);
            byName.put(m.name().toLowerCase(Locale.ROOT), m);
        }
        return this;
    }

    /** Set a callback to refresh the REPL prompt when mode changes. */
    public void setPromptRefreshCallback(Runnable callback) {
        this.promptRefreshCallback = callback;
    }

    /** Return the current active mode name (e.g., "rag", "dev", "auto"). */
    public String getActiveName() { return activeName; }

    /** Optional: get the active Mode if it's not "auto". */
    public Optional<Mode> getActive() { return Optional.ofNullable(byName.get(activeName)); }

    /**
     * Set the active mode. Returns true if accepted.
     * Valid names are any registered mode names plus "auto".
     */
    public boolean setActive(String name) {
        if (name == null || name.isBlank()) return false;
        String n = name.toLowerCase(Locale.ROOT).trim();
        if ("auto".equals(n) || byName.containsKey(n)) {
            this.activeName = n;
            // Trigger prompt refresh if callback is set
            if (promptRefreshCallback != null) {
                promptRefreshCallback.run();
            }
            return true;
        }
        return false;
    }

    /** Back-compat API: no hint provided; controller uses its activeName. */
    public Optional<Result> route(String rawLine, Path workspace, Context ctx) throws Exception {
        return route(rawLine, workspace, ctx, null);
    }

    /**
     * Preferred: route with a hint. If null/blank, uses activeName.
     * Executes in a single pass over a de-duplicated ordered set of candidates.
     */
    public Optional<Result> route(String rawLine, Path workspace, Context ctx, String hint) throws Exception {
        if (rawLine == null || rawLine.isBlank()) return Optional.empty();

        String h = (hint == null || hint.isBlank()) ? activeName : hint.toLowerCase(Locale.ROOT).trim();

        // Build candidate sequence once
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

    private static void addIfPresent(LinkedHashSet<Mode> seq, Mode m) {
        if (m != null) seq.add(m);
    }

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
