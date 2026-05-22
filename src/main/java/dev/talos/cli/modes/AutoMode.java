package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Placeholder — routing is handled in {@link ModeController#route} when
 * activeMode is "auto": COMMAND → DevMode, everything else → UnifiedAssistantMode.
 *
 * @see ModeController
 */
public final class AutoMode implements Mode {
    @Override public String name() { return "auto"; }
    @Override public boolean canHandle(String rawLine) { return false; }
    @Override public Optional<Result> handle(String rawLine, Path workspace, Context ctx) { return Optional.empty(); }
}
