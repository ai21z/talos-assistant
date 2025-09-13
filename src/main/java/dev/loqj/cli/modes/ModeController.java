package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;

import java.nio.file.Path;
import java.util.*;

/**
 * Router over registered Mode strategies with an active-mode concept.
 * - activeName defaults to "rag" and can be changed via :mode (ModeCommand).
 * - route(..., activeModeHint) uses the hint if provided; otherwise it uses activeName.
 * - "auto" applies the dev -> rag -> ask heuristic.
 */
public final class ModeController {
    private final List<Mode> modes = new ArrayList<>();
    private final Map<String, Mode> byName = new HashMap<>();
    private String activeName = "rag"; // default

    public ModeController add(Mode m) {
        if (m != null) {
            modes.add(m);
            byName.put(m.name().toLowerCase(Locale.ROOT), m);
        }
        return this;
    }

    /** Return the current active mode name (e.g., "rag", "dev", "auto"). */
    public String getActiveName() { return activeName; }

    /**
     * Set the active mode. Returns true if accepted.
     * Valid names are any registered mode names plus "auto".
     */
    public boolean setActive(String name) {
        if (name == null || name.isBlank()) return false;
        String n = name.toLowerCase(Locale.ROOT).trim();
        if ("auto".equals(n) || byName.containsKey(n)) {
            this.activeName = n;
            return true;
        }
        return false;
    }

    /** Back-compat API: no active mode hint; uses this.activeName. */
    public Optional<Result> route(String rawLine, Path workspace, Context ctx) throws Exception {
        return route(rawLine, workspace, ctx, null);
    }

    /**
     * Preferred: route with an active mode hint. If the hint is null/blank, we use the controller's activeName.
     * For "auto", we try dev -> rag -> ask.
     */
    public Optional<Result> route(String rawLine, Path workspace, Context ctx, String activeModeHint) throws Exception {
        if (rawLine == null || rawLine.isBlank()) return Optional.empty();

        String hint = (activeModeHint == null || activeModeHint.isBlank())
                ? this.activeName
                : activeModeHint.toLowerCase(Locale.ROOT);

        if ("auto".equals(hint)) {
            Mode dev = byName.get("dev");
            if (dev != null && dev.canHandle(rawLine)) {
                Optional<Result> r = dev.handle(rawLine, workspace, ctx);
                if (r.isPresent()) return r;
            }
            Mode rag = byName.get("rag");
            if (rag != null) {
                Optional<Result> r = rag.handle(rawLine, workspace, ctx);
                if (r.isPresent()) return r;
            }
            Mode ask = byName.get("ask");
            if (ask != null) {
                Optional<Result> r = ask.handle(rawLine, workspace, ctx);
                if (r.isPresent()) return r;
            }
            return Optional.empty();
        }

        Mode chosen = byName.get(hint);
        if (chosen != null) {
            Optional<Result> r = chosen.handle(rawLine, workspace, ctx);
            if (r != null && r.isPresent()) return r;
        }

        for (Mode m : modes) {
            if (m.canHandle(rawLine)) {
                Optional<Result> r = m.handle(rawLine, workspace, ctx);
                if (r != null && r.isPresent()) return r;
            }
        }
        return Optional.empty();
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
