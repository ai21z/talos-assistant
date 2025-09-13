package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;

import java.nio.file.Path;
import java.util.*;

/**
 * Router over registered Mode strategies.
 * - With active mode hint: use that; "auto" tries dev -> rag -> ask.
 * - Else: first match on canHandle().
 */
public final class ModeController {
    private final List<Mode> modes = new ArrayList<>();
    private final Map<String, Mode> byName = new HashMap<>();

    public ModeController add(Mode m) {
        if (m != null) {
            modes.add(m);
            byName.put(m.name().toLowerCase(Locale.ROOT), m);
        }
        return this;
    }

    public Optional<Result> route(String rawLine, Path workspace, Context ctx) throws Exception {
        return route(rawLine, workspace, ctx, null);
    }

    public Optional<Result> route(String rawLine, Path workspace, Context ctx, String activeMode) throws Exception {
        if (rawLine == null || rawLine.isBlank()) return Optional.empty();

        if (activeMode != null && !activeMode.isBlank()) {
            String am = activeMode.toLowerCase(Locale.ROOT);
            if (!am.equals("auto")) {
                Mode m = byName.get(am);
                if (m != null) {
                    Optional<Result> r = m.handle(rawLine, workspace, ctx);
                    if (r != null && r.isPresent()) return r;
                }
            } else {
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
