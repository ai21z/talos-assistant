package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;

import java.nio.file.Path;
import java.util.Optional;

/** Placeholder: gated web mode; honors NetPolicy (still local-only in this phase). */
public final class WebMode implements Mode {
    @Override public String name() { return "web"; }
    @Override public boolean canHandle(String rawLine) { return false; }
    @Override public Optional<Result> handle(String rawLine, Path workspace, Context ctx) {
        return Optional.of(new Result.Info("Web mode is reserved. No external network calls are performed in this build.\n"));
    }
}
