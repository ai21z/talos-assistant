package dev.talos.cli.commands;

import dev.talos.cli.repl.Result;
import dev.talos.cli.repl.Context;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QuitCommand implements Command {
    private final AtomicBoolean quitFlag;
    public static final String TOKEN = "__QUIT__";

    public QuitCommand(AtomicBoolean quitFlag) { this.quitFlag = quitFlag; }

    @Override public CommandSpec spec() {
        return new CommandSpec("q", List.of("quit","exit"), "/q", "Exit.", CommandGroup.SESSION);
    }

    @Override public Result execute(String args, Context ctx) {
        quitFlag.set(true);
        return new Result.Info(TOKEN); // RunCmd loop checks for this and breaks.
    }
}
