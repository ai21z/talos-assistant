package dev.talos.cli.commands;

import java.util.List;

public record CommandSpec(
        String name,
        List<String> aliases,
        String usage,
        String summary,
        CommandGroup group
) {
    // Backward compatibility constructor
    public CommandSpec(String name, List<String> aliases, String usage, String summary) {
        this(name, aliases, usage, summary, CommandGroup.BASICS);
    }

    /** Returns the display name of the command group (e.g., "Basics", "RAG"). */
    public String groupDisplayName() {
        return group != null ? group.getDisplayName() : null;
    }
}
