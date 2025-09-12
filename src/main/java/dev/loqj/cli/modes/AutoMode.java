package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Placeholder: will route to Dev/Rag/Ask heuristically. */
public final class AutoMode implements Mode {
    @Override public String name() { return "auto"; }
    @Override public boolean canHandle(String rawLine) {
        // For now keep passive to avoid any behavior changes.
        return false;
    }
    @Override public Optional<Result> handle(String rawLine, Path workspace, Context ctx) { return Optional.empty(); }
}
