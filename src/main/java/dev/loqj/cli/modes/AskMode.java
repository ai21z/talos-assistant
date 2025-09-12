package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;

import java.nio.file.Path;
import java.util.Optional;

/** Placeholder: will hook LLM chat without RAG. */
public final class AskMode implements Mode {
    @Override public String name() { return "ask"; }
    @Override public boolean canHandle(String rawLine) { return false; } // router falls through
    @Override public Optional<Result> handle(String rawLine, Path workspace, Context ctx) { return Optional.empty(); }
}
