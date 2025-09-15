package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;

import java.nio.file.Path;
import java.util.Optional;

/**
 * @deprecated This mode is a thin wrapper that only delegates to RagMode without adding functionality.
 * Use RagMode directly instead. Will be removed in a future version.
 */
@Deprecated(since = "0.1.0", forRemoval = true)
public final class RagMemoryMode implements Mode {
    private final RagMode delegate = new RagMode();

    @Override public String name() { return "rag+memory"; }

    @Override public boolean canHandle(String rawLine) { return delegate.canHandle(rawLine); }

    @Override public Optional<Result> handle(String rawLine, Path workspace, Context ctx) throws Exception {
        // Future: enable/disable memory around the call.
        return delegate.handle(rawLine, workspace, ctx);
    }
}
