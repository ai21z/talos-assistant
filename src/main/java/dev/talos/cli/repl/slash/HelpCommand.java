package dev.talos.cli.repl.slash;

import dev.talos.runtime.Result;
import dev.talos.cli.repl.Context;
import dev.talos.cli.ui.AnsiColor;
import dev.talos.cli.ui.CommandTextLayout;

import java.util.*;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

/**
 * /help displays layered slash command help.
 *
 * <p>The default page is intentionally short. The full command inventory and
 * focused debug/security/RAG pages are available on demand.
 */
public final class HelpCommand implements Command {
    private final CommandRegistry reg;
    private final dev.talos.cli.repl.WorkspaceCommandTemplates templates;
    private final IntSupplier terminalWidth;

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

    public HelpCommand(CommandRegistry reg) {
        this(reg, null);
    }

    /** T806: the workspace template catalog feeds the "/help all" section. */
    public HelpCommand(CommandRegistry reg, dev.talos.cli.repl.WorkspaceCommandTemplates templates) {
        this(reg, templates, null);
    }

    public HelpCommand(CommandRegistry reg, dev.talos.cli.repl.WorkspaceCommandTemplates templates,
                       IntSupplier terminalWidth) {
        this.reg = reg;
        this.templates = templates == null
                ? dev.talos.cli.repl.WorkspaceCommandTemplates.none()
                : templates;
        this.terminalWidth = terminalWidth;
    }

    @Override public CommandSpec spec() {
        return new CommandSpec("help", List.of("h", "?"), "/help [all|models|debug|security|rag|cmd]",
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
                            "/debug rag, /debug tools, /debug prompt, and /debug trace reserve deeper diagnostic intent.",
                            "Level aliases: retrieval=rag, tool=tools, prompts/frame=prompt, all=trace.",
                            "/debug prompt on turns on prompt-level debug output; /debug prompt off disables debug output.",
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
            case "models", "model" -> new Result.Ok(topicHelp(
                    "Model Help",
                    "Inspect local candidates and select the active chat model.",
                    CommandGroup.MODELS,
                    List.of(
                            "/models lists managed aliases, local availability and catalog disposition. /model is an alias.",
                            "/models --verbose shows additional local model details without starting a model.",
                            "/models use <alias> selects a listed managed model. /set model <alias> is a compatibility form of the same operation.",
                            "/models add <alias> \"<absolute GGUF path>\" registers another local model for this session without starting it.",
                            "Selection checks the local artifact and restarts the owned backend. Talos stays open. No model is downloaded.",
                            "If another server occupies the managed port, no replacement is launched. That server must be stopped explicitly or a different port configured.",
                            "Imports and selections apply to this session. Saved defaults, conversation history, sampling and global tool/privacy policy are preserved.",
                            "Local availability and catalog disposition do not establish artifact verification, current readiness or qualification.",
                            "Connect-only servers are not restarted. Other configured backends use /set model <backend/model>.",
                            "Use `talos setup models` outside the REPL to write persistent configuration.",
                            "Accepted beta profiles: qwen2.5-coder-14b and gpt-oss-20b. Other listed profiles retain their own disposition.")));
            default -> findSpec(q)
                    .map(spec -> (Result) new Result.Ok(detail(spec)))
                    .orElseGet(() -> new Result.Error("No such help topic or command: " + q, 204));
        };
    }

    private String defaultHelp() {
        int width = CommandTextLayout.width(terminalWidth);
        var sb = new StringBuilder();
        sb.append('\n');
        sb.append("  ").append(AnsiColor.bold("Talos Help")).append('\n').append('\n');
        sb.append(CommandTextLayout.wrap("  Ask normally: describe what to inspect, explain, or change.", width))
                .append('\n');
        sb.append("  ").append(AnsiColor.grey("Common commands")).append('\n');

        appendIfRegistered(sb, "status", "workspace, model, index, policy");
        appendIfRegistered(sb, "mode", null);
        appendIfRegistered(sb, "models", "inspect local candidates. Select with /models use <alias>");
        appendIfRegistered(sb, "reindex", "refresh local index");
        appendIfRegistered(sb, "files", "list indexed files");
        appendIfRegistered(sb, "k", "set retrieval depth");
        appendIfRegistered(sb, "debug", "set debug level: off|brief|rag|tools|prompt|trace");
        appendIfRegistered(sb, "clear", "reset conversation context; aliases /reset, /cls");
        appendIfRegistered(sb, "q", "exit");

        sb.append('\n');
        sb.append("  ").append(AnsiColor.grey("More help")).append('\n');
        appendEntry(sb, "/help all", "all commands", width);
        appendEntry(sb, "/help models", "local candidates and switching", width);
        appendEntry(sb, "/help rag", "retrieval and workspace context", width);
        appendEntry(sb, "/help security", "approvals, audit, secrets", width);
        appendEntry(sb, "/help debug", "diagnostics and traces", width);
        appendEntry(sb, "/help <cmd>", "command details", width);
        return sb.toString();
    }

    private String fullInventory() {
        int width = CommandTextLayout.width(terminalWidth);
        Map<CommandGroup, List<CommandSpec>> grouped = reg.allSpecs().stream()
                .filter(spec -> !spec.hidden())
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
              .append(AnsiColor.dim(rule(group.getDisplayName().length(), width)))
              .append('\n');

            // ── commands (sorted alphabetically) ───────────────────────
            specs.sort(Comparator.comparing(CommandSpec::name));
            for (CommandSpec spec : specs) {
                String usage  = compactUsage(spec);
                appendEntry(sb, usage, listSummary(spec.summary()), width);
            }
            sb.append('\n');
        }

        // ── workspace template commands (T806) ─────────────────────────
        if (!templates.isEmpty()) {
            sb.append("  ")
              .append(AnsiColor.violet("Workspace commands"))
              .append(' ')
              .append(AnsiColor.dim(rule("Workspace commands".length(), width)))
              .append('\n');
            for (String name : templates.names()) {
                appendEntry(sb, "/" + name + " [args]", ".talos/commands/" + name + ".md", width);
            }
            sb.append(AnsiColor.grey(CommandTextLayout.wrap(
                      "    Workspace templates run as typed prompts; restart to reload.", width)))
              .append('\n')
              .append('\n');
        }

        // ── footer ─────────────────────────────────────────────────────
        String dot = AnsiColor.isUnicodeSafe() ? " · " : " - ";
        sb.append("  ")
          .append(AnsiColor.dim(hRule(width)))
          .append('\n')
          .append(AnsiColor.grey(CommandTextLayout.wrap(
                  "  /help <cmd> for full options" + dot + "Tab to autocomplete", width)))
          .append('\n');

        return sb.toString();
    }

    private String topicHelp(String title, String intro, CommandGroup group, List<String> notes) {
        int width = CommandTextLayout.width(terminalWidth);
        var sb = new StringBuilder();
        sb.append('\n');
        sb.append("  ").append(AnsiColor.bold(title)).append('\n').append('\n');
        sb.append(CommandTextLayout.wrap("  " + intro, width)).append('\n').append('\n');

        List<CommandSpec> specs = reg.allSpecs().stream()
                .filter(spec -> !spec.hidden())
                .filter(spec -> spec.group() == group)
                .sorted(Comparator.comparing(CommandSpec::name))
                .toList();
        if (!specs.isEmpty()) {
            sb.append("  ").append(AnsiColor.grey(group.getDisplayName() + " commands")).append('\n');
            for (CommandSpec spec : specs) {
                appendCommandLine(sb, spec, null, width);
            }
            sb.append('\n');
        }

        if (notes != null && !notes.isEmpty()) {
            sb.append("  ").append(AnsiColor.grey("Notes")).append('\n');
            for (String note : notes) {
                if (width < 76) {
                    sb.append("    ").append(CommandTextLayout.wrap(note, Math.max(1, width - 4))
                            .replace("\n", "\n    ")).append('\n');
                } else {
                    sb.append("    ").append(note).append('\n');
                }
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
                .filter(s -> !s.hidden())
                .filter(s -> s.name().equals(q) || s.aliases().contains(q))
                .findFirst();
    }

    private void appendIfRegistered(StringBuilder sb, String name, String summary) {
        findSpec(name).ifPresent(spec -> appendCommandLine(sb, spec, summary,
                CommandTextLayout.width(terminalWidth)));
    }

    private void appendCommandLine(StringBuilder sb, CommandSpec spec, String summaryOverride, int width) {
        String usage = compactUsage(spec);
        String desc = summaryOverride == null ? listSummary(spec.summary()) : summaryOverride;
        appendEntry(sb, usage, desc, width);
    }

    private static void appendEntry(StringBuilder sb, String label, String description, int width) {
        sb.append(AnsiColor.grey(CommandTextLayout.entry(label, description, width))).append('\n');
    }

    /** Shorten long usage strings for the overview list. */
    private static String compactUsage(CommandSpec spec) {
        String usage = spec.usage();
        if (usage.length() <= USAGE_COL) return usage;

        // Derive the command token from the usage string itself (the form the user
        // types), NOT spec.name(): a command whose usage leads with an alias - e.g.
        // explain-last-turn with usage "/last ..." - would otherwise be sliced
        // mid-token by substring(("/" + name).length()).
        int sp = usage.indexOf(' ');
        String cmd = sp < 0 ? usage : usage.substring(0, sp);
        String rest = sp < 0 ? "" : usage.substring(sp + 1).trim();

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

    /** Normalize summaries for display without dropping any words. */
    private static String listSummary(String s) {
        return trimDot(Objects.toString(s, "")).replaceAll("\\s+", " ");
    }

    /** Horizontal rule filling remaining width after a group name. */
    private static String rule(int headerLen, int width) {
        int dashes = Math.min(RULE_WIDTH, width - 2) - headerLen - 3; // 2 indent + 1 space
        if (dashes <= 0) return "";
        String ch = AnsiColor.isUnicodeSafe() ? "─" : "-";
        return ch.repeat(dashes);
    }

    /** Full-width horizontal rule for the footer. */
    private static String hRule(int width) {
        String ch = AnsiColor.isUnicodeSafe() ? "─" : "-";
        return ch.repeat(Math.max(0, Math.min(RULE_WIDTH, width - 2)));
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
