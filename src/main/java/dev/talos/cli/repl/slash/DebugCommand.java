package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.DebugLevel;
import dev.talos.cli.repl.Result;
import dev.talos.cli.repl.Context;

import java.util.List;

public final class DebugCommand implements Command {
    private final CliRuntime rt;
    public DebugCommand(CliRuntime rt) { this.rt = rt; }

    @Override public CommandSpec spec() {
        return new CommandSpec("debug", List.of(), "/debug [off|brief|rag|tools|prompt|trace]",
                "Set debug output level.", CommandGroup.DEBUG);
    }

    @Override public Result execute(String args, Context ctx) {
        String a = (args == null ? "" : args.trim().toLowerCase());
        if (a.isEmpty()) return new Result.Info("debug = " + rt.getDebugLevel().label());

        return DebugLevel.parse(a)
                .<Result>map(level -> {
                    rt.setDebugLevel(level);
                    return new Result.Info("debug = " + level.label());
                })
                .orElseGet(() -> new Result.Error("Usage: /debug off|brief|rag|tools|prompt|trace", 201));
    }
}
