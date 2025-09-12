package dev.loqj.cli.commands;

import dev.loqj.cli.repl.Result;
import dev.loqj.cli.repl.Context;

import java.util.List;

public final class DebugCommand implements Command {
    private final CliRuntime rt;
    public DebugCommand(CliRuntime rt) { this.rt = rt; }

    @Override public CommandSpec spec() {
        return new CommandSpec("debug", List.of(), ":debug on|off", "Toggle debug printing.");
    }

    @Override public Result execute(String args, Context ctx) {
        String a = (args == null ? "" : args.trim().toLowerCase());
        if (a.isEmpty()) return new Result.Info("debug = " + rt.isDebug());
        boolean on = a.equals("on") || a.equals("true") || a.equals("1") || a.equals("enable");
        boolean off = a.equals("off") || a.equals("false") || a.equals("0") || a.equals("disable");
        if (!on && !off) return new Result.Error("Usage: :debug on|off", 201);
        rt.setDebug(on);
        return new Result.Info("debug " + (on ? "ON" : "OFF"));
    }
}
