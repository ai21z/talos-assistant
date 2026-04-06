package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Placeholder — routing is handled in ModeController when activeMode is "auto":
 * dev -> rag -> ask heuristic.
 */
public final class AutoMode implements Mode {
    @Override public String name() { return "auto"; }
    @Override public boolean canHandle(String rawLine) { return false; }
    @Override public Optional<Result> handle(String rawLine, Path workspace, Context ctx) { return Optional.empty(); }
}
