package dev.loqj.cli.commands;

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
}

enum CommandGroup {
    BASICS("Basics"),
    MODELS("Models"),
    RAG("RAG"),
    DEBUG("Debug"),
    SECURITY("Security"),
    WORKSPACE("Workspace");

    private final String displayName;

    CommandGroup(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
