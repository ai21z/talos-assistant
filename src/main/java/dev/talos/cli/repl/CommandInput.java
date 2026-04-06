package dev.talos.cli.repl;

import java.util.List;

/** Parsed input for commands; args are raw tokens (a richer Args parser can replace this later). */
public record CommandInput(String raw, List<String> args) {
    public static CommandInput of(String raw) {
        if (raw == null) return new CommandInput("", List.of());
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return new CommandInput("", List.of());
        String[] toks = trimmed.split("\\s+");
        return new CommandInput(trimmed, java.util.List.of(toks));
    }
}
