package dev.talos.cli.commands;

import dev.talos.cli.repl.Result;
import dev.talos.cli.repl.Context;
import dev.talos.cli.ui.AnsiColor;

import java.util.*;
import java.util.stream.Collectors;

public final class HelpCommand implements Command {
    private final CommandRegistry reg;

    public HelpCommand(CommandRegistry reg) { this.reg = reg; }

    @Override public CommandSpec spec() {
        return new CommandSpec("help", List.of("h","?"), ":help [cmd]",
                "Show available commands or details for a specific command.",
                CommandGroup.BASICS);
    }

    @Override public Result execute(String args, Context ctx) {
        String q = args == null ? "" : args.trim();
        if (!q.isEmpty()) {
            return reg.has(q)
                    ? new Result.Ok(detail(reg.allSpecs().stream().filter(s -> s.name().equals(q)).findFirst().orElse(null)))
                    : new Result.Error("No such command: :" + q, 204);
        }

        var specs = reg.allSpecs();
        Map<CommandGroup, List<CommandSpec>> grouped = specs.stream()
            .collect(Collectors.groupingBy(CommandSpec::group));

        var sb = new StringBuilder();
        sb.append(AnsiColor.bold("Commands")).append("\n");

        var groups = Arrays.asList(
            CommandGroup.BASICS,
            CommandGroup.MODELS,
            CommandGroup.RAG,
            CommandGroup.DEBUG,
            CommandGroup.SECURITY,
            CommandGroup.WORKSPACE
        );

        for (CommandGroup group : groups) {
            List<CommandSpec> groupSpecs = grouped.get(group);
            if (groupSpecs == null || groupSpecs.isEmpty()) continue;

            sb.append("\n  ").append(AnsiColor.violet(group.getDisplayName())).append("\n");

            groupSpecs.sort(Comparator.comparing(CommandSpec::name));

            int maxUsageLen = groupSpecs.stream().mapToInt(s -> s.usage().length()).max().orElse(20);

            for (CommandSpec spec : groupSpecs) {
                sb.append("    ")
                  .append(AnsiColor.blue(String.format("%-" + Math.max(maxUsageLen, 24) + "s", spec.usage())))
                  .append("  ")
                  .append(AnsiColor.grey(spec.summary()))
                  .append("\n");
            }
        }

        sb.append("\n  ").append(AnsiColor.grey(":help <command> for details")).append("\n");
        return new Result.Ok(sb.toString());
    }

    private static String detail(CommandSpec s) {
        if (s == null) return "(no details)";

        var sb = new StringBuilder();
        sb.append(AnsiColor.bold(":" + s.name())).append("\n\n");
        sb.append("  ").append(AnsiColor.grey("Usage   ")).append(AnsiColor.blue(s.usage())).append("\n");
        sb.append("  ").append(AnsiColor.grey("Summary ")).append(s.summary()).append("\n");

        if (!s.aliases().isEmpty()) {
            sb.append("  ").append(AnsiColor.grey("Aliases "));
            sb.append(s.aliases().stream()
                .map(alias -> AnsiColor.blue(":" + alias))
                .collect(Collectors.joining(", ")));
            sb.append("\n");
        }

        sb.append("  ").append(AnsiColor.grey("Group   ")).append(s.group().getDisplayName()).append("\n");
        return sb.toString();
    }
}
