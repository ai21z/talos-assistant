package dev.talos.cli.commands;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.cli.ui.AnsiColor;

import java.util.List;

public final class ModeCommand implements Command {
    private final ModeController modes;
    public ModeCommand(ModeController modes) { this.modes = modes; }

    @Override public CommandSpec spec() {
        return new CommandSpec("mode", List.of(), ":mode auto|rag|chat|dev|ask", "Switch active mode.", CommandGroup.RAG);
    }

    @Override public Result execute(String args, Context ctx) {
        String a = (args == null ? "" : args.trim()).toLowerCase();
        if (a.isEmpty()) {
            return new Result.Info("Mode: " + AnsiColor.blue(modes.getActiveName()));
        }
        boolean ok = modes.setActive(a);
        if (!ok) {
            return new Result.Error("Unknown mode. Available: auto, rag, chat, dev, ask, web", 200);
        }
        return new Result.Info("Mode: " + AnsiColor.blue(modes.getActiveName()));
    }
}
