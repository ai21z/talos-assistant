package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Result;
import dev.talos.cli.repl.Context;
import dev.talos.cli.ui.AnsiColor;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /help displays layered slash command help.
 *
 * <p>The default page is intentionally short. The full command inventory and
 * focused debug/security/RAG pages are available on demand.
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
        return new CommandSpec("help", List.of("h", "?"), "/help [all|debug|security|rag|cmd]",
                "Show this help.",
                CommandGroup.SESSION);
    }

    @Override public Result execute(String args, Context ctx) {
        String q = normalize(args);
        if (q.isEmpty()) return new Result.Ok(defaultHelp());

        return switch (q) {
            case "all", "commands", "full" -> new Result.Ok(fullInventory());
            case "debug", "trace" -> new Result.Ok(topicHelp(
                    "Debug Help",
                    "Normal mode keeps internals quiet. Use these commands when you need diagnostics.",
                    CommandGroup.DEBUG,
                    List.of(
                            "/debug brief keeps compatible debug hints on.",
                            "/debug rag, /debug tools, and /debug trace reserve deeper diagnostic intent.",
                            "/last, /last tools, /last sources, and /last trace inspect the latest recorded turn.",
                            "/help all lists every registered command.")));
            case "security", "safety", "approval" -> new Result.Ok(topicHelp(
                    "Security Help",
                    "Talos is local-first. Risky mutations stay approval-gated and fail closed.",
                    CommandGroup.SECURITY,
                    List.of(
                            "/policy shows active safety policy.",
                            "/audit controls audit logging.",
                            "/secret manages local secrets without printing protected values by default.")));
            case "rag", "retrieval", "knowledge" -> new Result.Ok(topicHelp(
                    "RAG Help",
                    "Use local index and workspace tools before guessing.",
                    CommandGroup.KNOWLEDGE,
                    List.of(
                            "/reindex refreshes the local workspace index.",
                            "/files and /show inspect indexed context.",
                            "/grep searches workspace text directly.")));
            default -> findSpec(q)
                    .map(spec -> (Result) new Result.Ok(detail(spec)))
                    .orElseGet(() -> new Result.Error("No such help topic or command: " + q, 204));
        };
    }

    private String defaultHelp() {
        var sb = new StringBuilder();
        sb.append('\n');
        sb.append("  ").append(AnsiColor.bold("Talos Help")).append('\n').append('\n');
        sb.append("  ").append(AnsiColor.grey("Ask normally: "))
                .append("describe what to inspect, explain, or change.").append('\n');
        sb.append("  ").append(AnsiColor.grey("Common commands")).append('\n');

        appendIfRegistered(sb, "status", "workspace, model, index, policy");
        appendIfRegistered(sb, "mode", "switch operating mode");
        appendIfRegistered(sb, "reindex", "refresh local index");
        appendIfRegistered(sb, "files", "list indexed files");
        appendIfRegistered(sb, "k", "set retrieval depth");
        appendIfRegistered(sb, "debug", "toggle developer hints");
        appendIfRegistered(sb, "clear", "reset conversation context; alias /reset");
        appendIfRegistered(sb, "q", "exit");

        sb.append('\n');
        sb.append("  ").append(AnsiColor.grey("More help")).append('\n');
        sb.append("    ").append(AnsiColor.blue("/help all")).append("       all commands").append('\n');
        sb.append("    ").append(AnsiColor.blue("/help rag")).append("       retrieval and workspace context").append('\n');
        sb.append("    ").append(AnsiColor.blue("/help security")).append("  approvals, audit, secrets").append('\n');
        sb.append("    ").append(AnsiColor.blue("/help debug")).append("     diagnostics and traces").append('\n');
        sb.append("    ").append(AnsiColor.blue("/help <cmd>")).append("     command details").append('\n');
        return sb.toString();
    }

    private String fullInventory() {
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
                String desc   = listSummary(spec.summary());
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

        return sb.toString();
    }

    private String topicHelp(String title, String intro, CommandGroup group, List<String> notes) {
        var sb = new StringBuilder();
        sb.append('\n');
        sb.append("  ").append(AnsiColor.bold(title)).append('\n').append('\n');
        sb.append("  ").append(intro).append('\n').append('\n');

        List<CommandSpec> specs = reg.allSpecs().stream()
                .filter(spec -> spec.group() == group)
                .sorted(Comparator.comparing(CommandSpec::name))
                .toList();
        if (!specs.isEmpty()) {
            sb.append("  ").append(AnsiColor.grey(group.getDisplayName() + " commands")).append('\n');
            for (CommandSpec spec : specs) {
                appendCommandLine(sb, spec, null);
            }
            sb.append('\n');
        }

        if (notes != null && !notes.isEmpty()) {
            sb.append("  ").append(AnsiColor.grey("Notes")).append('\n');
            for (String note : notes) {
                sb.append("    ").append(note).append('\n');
            }
        }
        return sb.toString();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static String normalize(String args) {
        String q = args == null ? "" : args.trim().toLowerCase(Locale.ROOT);
        while (q.startsWith("/")) q = q.substring(1);
        return q;
    }

    private Optional<CommandSpec> findSpec(String nameOrAlias) {
        String q = normalize(nameOrAlias);
        return reg.allSpecs().stream()
                .filter(s -> s.name().equals(q) || s.aliases().contains(q))
                .findFirst();
    }

    private void appendIfRegistered(StringBuilder sb, String name, String summary) {
        findSpec(name).ifPresent(spec -> appendCommandLine(sb, spec, summary));
    }

    private void appendCommandLine(StringBuilder sb, CommandSpec spec, String summaryOverride) {
        String usage = compactUsage(spec);
        String desc = summaryOverride == null ? listSummary(spec.summary()) : summaryOverride;
        sb.append("    ")
                .append(AnsiColor.blue(pad(usage, USAGE_COL)))
                .append(AnsiColor.grey(desc))
                .append('\n');
    }

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

    /** Keep command lists from wrapping in dumb/non-interactive transcripts. */
    private static String listSummary(String s) {
        String value = trimDot(Objects.toString(s, "")).replaceAll("\\s+", " ");
        int max = 80;
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
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
