package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lightweight router over registered Mode strategies.
 * Order matters: first match on canHandle() wins.
 */
public final class ModeController {
    private final List<Mode> modes = new ArrayList<>();

    public ModeController add(Mode m) {
        if (m != null) modes.add(m);
        return this;
    }

    public Optional<Result> route(String rawLine, Path workspace, Context ctx) throws Exception {
        if (rawLine == null || rawLine.isBlank()) return Optional.empty();
        for (Mode m : modes) {
            try {
                if (m.canHandle(rawLine)) {
                    Optional<Result> r = m.handle(rawLine, workspace, ctx);
                    if (r != null && r.isPresent()) return r;
                }
            } catch (Exception e) {
                // Push errors up; ExecutionPipeline should handle and render safely.
                throw e;
            }
        }
        return Optional.empty();
    }

    /** Default controller with Dev first; others stubbed for later extraction. */
    public static ModeController defaultController() {
        return new ModeController()
                .add(new DevMode())
                .add(new RagMode())          // stub for now
                .add(new RagMemoryMode())    // stub
                .add(new AskMode())          // stub
                .add(new WebMode())          // stub
                .add(new AutoMode());        // stub
    }
}
