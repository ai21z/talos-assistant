package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;

import java.nio.file.Path;
import java.util.Optional;

/** Thin wrapper for now — delegates to RagMode. */
public final class RagMemoryMode implements Mode {
    private final RagMode delegate = new RagMode();

    @Override public String name() { return "rag+memory"; }

    @Override public boolean canHandle(String rawLine) { return delegate.canHandle(rawLine); }

    @Override public Optional<Result> handle(String rawLine, Path workspace, Context ctx) throws Exception {
        // Future: enable/disable memory around the call.
        return delegate.handle(rawLine, workspace, ctx);
    }
}
