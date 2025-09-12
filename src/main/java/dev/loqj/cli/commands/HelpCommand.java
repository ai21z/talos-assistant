package dev.loqj.cli.commands;

import dev.loqj.cli.repl.Result;
import dev.loqj.cli.repl.Context;

import java.util.List;

public final class HelpCommand implements Command {
    private final CommandRegistry reg;

    public HelpCommand(CommandRegistry reg) { this.reg = reg; }

    @Override public CommandSpec spec() {
        return new CommandSpec("help", List.of("h","?"), ":help [cmd]",
                "Show available commands or details for a specific command.");
    }

    @Override public Result execute(String args, Context ctx) {
        String q = args == null ? "" : args.trim();
        if (!q.isEmpty()) {
            // simple exact lookup
            return reg.has(q)
                    ? new Result.Ok(detail(reg.allSpecs().stream().filter(s -> s.name().equals(q)).findFirst().orElse(null)))
                    : new Result.Error("No such command: :" + q, 204);
        }
        // list
        var specs = reg.allSpecs();
        var cols = List.of("Command", "Usage", "Summary");
        var rows = specs.stream()
                .map(s -> List.of(":" + s.name(), s.usage(), s.summary()))
                .toList();
        return new Result.Table("Commands", cols, rows);
    }

    private static String detail(CommandSpec s) {
        if (s == null) return "(no details)";
        return ":" + s.name() + "\n  usage  : " + s.usage() + "\n  summary: " + s.summary();
    }
}
