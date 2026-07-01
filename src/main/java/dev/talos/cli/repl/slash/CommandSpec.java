package dev.talos.cli.repl.slash;

import java.util.List;

public record CommandSpec(
        String name,
        List<String> aliases,
        String usage,
        String summary,
        CommandGroup group,
        boolean hidden
) {
    // Backward compatibility constructor
    public CommandSpec(String name, List<String> aliases, String usage, String summary) {
        this(name, aliases, usage, summary, CommandGroup.SESSION);
    }

    public CommandSpec(String name, List<String> aliases, String usage, String summary, CommandGroup group) {
        this(name, aliases, usage, summary, group, false);
    }

    /** Returns the display name of the command group (e.g., "Basics", "RAG"). */
    public String groupDisplayName() {
        return group != null ? group.getDisplayName() : null;
    }
}
