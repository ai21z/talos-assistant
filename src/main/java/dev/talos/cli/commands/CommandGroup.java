package dev.talos.cli.commands;

/**
 * Grouping categories for slash commands.
 * Used by {@link HelpCommand} for display and by
 * {@link dev.talos.cli.repl.SlashCommandCompleter} for autocomplete grouping.
 */
public enum CommandGroup {
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

