package dev.loqj.cli.commands;

import dev.loqj.cli.repl.Result;
import dev.loqj.cli.repl.Context;

import java.util.List;

public final class KCommand implements Command {
    private final CliRuntime rt;
    public KCommand(CliRuntime rt) { this.rt = rt; }

    @Override public CommandSpec spec() {
        return new CommandSpec("k", List.of(), ":k <int>", "Set or show retrieval breadth (top-k).");
    }

    @Override public Result execute(String args, Context ctx) {
        String a = args == null ? "" : args.trim();
        if (a.isEmpty()) {
            return new Result.Info("k = " + rt.getK());
        }
        try {
            int v = Integer.parseInt(a);
            if (v < 1) return new Result.Error("k must be >= 1", 201);
            rt.setK(v);
            return new Result.Info("k set to " + v);
        } catch (NumberFormatException nfe) {
            return new Result.Error("Invalid integer: " + a, 201);
        }
    }
}
