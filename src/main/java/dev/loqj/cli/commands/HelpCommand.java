package dev.loqj.cli.commands;

import dev.loqj.cli.repl.Result;
import dev.loqj.cli.repl.Context;

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
            // simple exact lookup
            return reg.has(q)
                    ? new Result.Ok(detail(reg.allSpecs().stream().filter(s -> s.name().equals(q)).findFirst().orElse(null)))
                    : new Result.Error("No such command: :" + q, 204);
        }

        // Group commands by their CommandGroup
        var specs = reg.allSpecs();
        Map<CommandGroup, List<CommandSpec>> grouped = specs.stream()
            .collect(Collectors.groupingBy(CommandSpec::group));

        var sb = new StringBuilder();
        sb.append("Available Commands:\n\n");

        // Process each group in order with proper table format
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

            sb.append(group.getDisplayName()).append(":\n");

            // Sort commands within each group alphabetically
            groupSpecs.sort(Comparator.comparing(CommandSpec::name));

            for (CommandSpec spec : groupSpecs) {
                // Command column
                sb.append("  :").append(spec.name());

                // Aliases column
                String aliasesStr = "";
                if (!spec.aliases().isEmpty()) {
                    aliasesStr = spec.aliases().stream()
                        .map(alias -> ":" + alias)
                        .collect(Collectors.joining(", "));
                }

                // Usage column
                String usageStr = spec.usage();

                // Format as table: Command | Aliases | Usage | Summary
                sb.append(String.format(" | %s | %s | %s%n",
                    aliasesStr.isEmpty() ? "-" : aliasesStr,
                    usageStr,
                    spec.summary()));
            }
            sb.append("\n");
        }

        sb.append("Use :help <command> for details about a specific command.\n");

        return new Result.Ok(sb.toString());
    }

    private static String detail(CommandSpec s) {
        if (s == null) return "(no details)";

        var sb = new StringBuilder();
        sb.append(":").append(s.name()).append("\n");
        sb.append("  Usage   : ").append(s.usage()).append("\n");
        sb.append("  Summary : ").append(s.summary()).append("\n");

        if (!s.aliases().isEmpty()) {
            sb.append("  Aliases : ");
            sb.append(s.aliases().stream()
                .map(alias -> ":" + alias)
                .collect(Collectors.joining(", ")));
            sb.append("\n");
        }

        sb.append("  Group   : ").append(s.group().getDisplayName()).append("\n");

        return sb.toString();
    }
}
