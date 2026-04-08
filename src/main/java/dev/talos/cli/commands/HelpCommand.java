package dev.talos.cli.commands;

import dev.talos.cli.repl.Result;
import dev.talos.cli.repl.Context;
import dev.talos.cli.ui.AnsiColor;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /help — displays available slash commands grouped by category.
 *
 * <p>The overview is designed for scannability: tight columns, short
 * descriptions, visual group headers, and a footer hint for detail
 * and tab-completion.
 */
public final class HelpCommand implements Command {
    private final CommandRegistry reg;

    /** Visual width of group header rules. */
    private static final int RULE_WIDTH = 46;

    /** Column width for the compact usage string. */
    private static final int USAGE_COL = 24;

    /** Display order for command groups. */
    private static final List<CommandGroup> GROUP_ORDER = List.of(
            CommandGroup.SESSION,
            CommandGroup.MODELS,
            CommandGroup.KNOWLEDGE,
            CommandGroup.SECURITY,
            CommandGroup.DEBUG
    );

    public HelpCommand(CommandRegistry reg) { this.reg = reg; }

    @Override public CommandSpec spec() {
        return new CommandSpec("help", List.of("h", "?"), "/help [cmd]",
                "Show this help.",
                CommandGroup.SESSION);
    }

    @Override public Result execute(String args, Context ctx) {
        String q = args == null ? "" : args.trim();
        if (!q.isEmpty()) {
            return reg.has(q)
                    ? new Result.Ok(detail(reg.allSpecs().stream()
                            .filter(s -> s.name().equals(q)).findFirst().orElse(null)))
                    : new Result.Error("No such command: /" + q, 204);
        }

        Map<CommandGroup, List<CommandSpec>> grouped = reg.allSpecs().stream()
                .collect(Collectors.groupingBy(CommandSpec::group));

        var sb = new StringBuilder();
        sb.append('\n');

        for (CommandGroup group : GROUP_ORDER) {
            List<CommandSpec> specs = grouped.get(group);
            if (specs == null || specs.isEmpty()) continue;

            // ── group header ───────────────────────────────────────────
            sb.append("  ")
              .append(AnsiColor.violet(group.getDisplayName()))
              .append(' ')
              .append(AnsiColor.dim(rule(group.getDisplayName().length())))
              .append('\n');

            // ── commands (sorted alphabetically) ───────────────────────
            specs.sort(Comparator.comparing(CommandSpec::name));
            for (CommandSpec spec : specs) {
                String usage  = compactUsage(spec);
                String desc   = trimDot(spec.summary());
                sb.append("    ")
                  .append(AnsiColor.blue(pad(usage, USAGE_COL)))
                  .append(AnsiColor.grey(desc))
                  .append('\n');
            }
            sb.append('\n');
        }

        // ── footer ─────────────────────────────────────────────────────
        String dot = AnsiColor.isUnicodeSafe() ? " · " : " - ";
        sb.append("  ")
          .append(AnsiColor.dim(hRule()))
          .append('\n')
          .append("  ")
          .append(AnsiColor.grey("/help <cmd> for details"))
          .append(AnsiColor.dim(dot))
          .append(AnsiColor.grey("Tab to autocomplete"))
          .append('\n');

        return new Result.Ok(sb.toString());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Pad string to exactly {@code width} characters. */
    private static String pad(String s, int width) {
        return s.length() >= width ? s + " " : String.format("%-" + width + "s", s);
    }

    /** Shorten long usage strings for the overview list. */
    private static String compactUsage(CommandSpec spec) {
        String usage = spec.usage();
        if (usage.length() <= USAGE_COL) return usage;

        String cmd = "/" + spec.name();
        String rest = usage.substring(cmd.length()).trim();

        // Collapse multiple bracketed flags → [opts]
        rest = rest.replaceAll("\\[--[^]]+]", "[opts]")
                   .replaceAll("\\[opts](?:\\s+\\[opts])+", "[opts]");

        String result = cmd + (rest.isEmpty() ? "" : " " + rest.trim());
        return result.length() <= USAGE_COL ? result : cmd + " [opts]";
    }

    /** Strip trailing period for clean list display. */
    private static String trimDot(String s) {
        return (s != null && s.endsWith(".")) ? s.substring(0, s.length() - 1) : s;
    }

    /** Horizontal rule filling remaining width after a group name. */
    private static String rule(int headerLen) {
        int dashes = RULE_WIDTH - headerLen - 3; // 2 indent + 1 space
        if (dashes <= 0) return "";
        String ch = AnsiColor.isUnicodeSafe() ? "─" : "-";
        return ch.repeat(dashes);
    }

    /** Full-width horizontal rule for the footer. */
    private static String hRule() {
        String ch = AnsiColor.isUnicodeSafe() ? "─" : "-";
        return ch.repeat(RULE_WIDTH);
    }

    /** Detailed view for /help <command>. */
    private static String detail(CommandSpec s) {
        if (s == null) return "(no details)";

        var sb = new StringBuilder();
        sb.append("\n  ").append(AnsiColor.bold("/" + s.name())).append("\n\n");
        sb.append("    ").append(AnsiColor.grey("Usage    ")).append(AnsiColor.blue(s.usage())).append("\n");
        sb.append("    ").append(AnsiColor.grey("Summary  ")).append(s.summary()).append("\n");

        if (!s.aliases().isEmpty()) {
            sb.append("    ").append(AnsiColor.grey("Aliases  "));
            sb.append(s.aliases().stream()
                    .map(alias -> AnsiColor.blue("/" + alias))
                    .collect(Collectors.joining(AnsiColor.dim(", "))));
            sb.append("\n");
        }

        sb.append("    ").append(AnsiColor.grey("Group    ")).append(s.group().getDisplayName()).append("\n");
        return sb.toString();
    }
}
