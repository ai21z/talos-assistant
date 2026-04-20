package dev.talos.cli.repl.slash;

/**
 * Grouping categories for slash commands.
 * Used by {@link HelpCommand} for display and by
 * {@link dev.talos.cli.repl.SlashCommandCompleter} for autocomplete grouping.
 */
public enum CommandGroup {
    SESSION("Session"),
    MODELS("Models"),
    KNOWLEDGE("Knowledge"),
    SECURITY("Security"),
    DEBUG("Debug");

    private final String displayName;

    CommandGroup(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

