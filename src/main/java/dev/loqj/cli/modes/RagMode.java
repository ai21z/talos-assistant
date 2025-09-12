package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;

import java.nio.file.Path;
import java.util.Optional;

/** Placeholder: will build snippets + citations around local workspace. */
public final class RagMode implements Mode {
    @Override public String name() { return "rag"; }
    @Override public boolean canHandle(String rawLine) { return false; }
    @Override public Optional<Result> handle(String rawLine, Path workspace, Context ctx) { return Optional.empty(); }
}
