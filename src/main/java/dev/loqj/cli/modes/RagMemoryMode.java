package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;

import java.nio.file.Path;
import java.util.Optional;

/** Placeholder: RAG + lightweight memory. */
public final class RagMemoryMode implements Mode {
    @Override public String name() { return "rag+memory"; }
    @Override public boolean canHandle(String rawLine) { return false; }
    @Override public Optional<Result> handle(String rawLine, Path workspace, Context ctx) { return Optional.empty(); }
}
