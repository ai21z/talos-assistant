package dev.loqj.cli.commands;

import dev.loqj.cli.modes.ModeController;
import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;

import java.util.List;

public final class ModeCommand implements Command {
    private final ModeController modes;
    public ModeCommand(ModeController modes) { this.modes = modes; }

    @Override public CommandSpec spec() {
        return new CommandSpec("mode", List.of(), ":mode ask|rag|rag+memory|dev|web|auto", "Switch active mode.", CommandGroup.RAG);
    }

    @Override public Result execute(String args, Context ctx) {
        String a = (args == null ? "" : args.trim()).toLowerCase();
        if (a.isEmpty()) {
            return new Result.Info("Current mode: " + modes.getActiveName());
        }
        boolean ok = modes.setActive(a);
        if (!ok) {
            return new Result.Error("Usage: :mode ask|rag|rag+memory|dev|web|auto", 200);
        }
        return new Result.Info("Mode: " + modes.getActiveName());
    }
}
