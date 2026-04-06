package dev.talos.cli.commands;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;

import java.util.List;

public final class MemoryCommand implements Command {
    @Override public CommandSpec spec() {
        return new CommandSpec("memory", List.of(), "/memory clear", "Clear session memory (RAG+MEMORY).");
    }

    @Override public Result execute(String args, Context ctx) {
        String a = args == null ? "" : args.trim().toLowerCase();
        if (!a.equals("clear")) return new Result.Error("Usage: /memory clear", 200);
        ctx.memory().clear();
        return new Result.Info("Memory cleared.");
    }
}
