package dev.talos.cli.repl;

import java.util.Locale;
import java.util.Optional;

/**
 * Transitional CLI debug depth.
 *
 * <p>The current runtime still gates most behavior on {@link #enabled()}, but
 * the CLI can now expose intent more precisely than a boolean.
 */
public enum DebugLevel {
    OFF("off"),
    BRIEF("brief"),
    RAG("rag"),
    TOOLS("tools"),
    PROMPT("prompt"),
    TRACE("trace");

    private final String label;

    DebugLevel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean enabled() {
        return this != OFF;
    }

    public static Optional<DebugLevel> parse(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) return Optional.empty();
        return switch (value) {
            case "off", "false", "0", "disable", "disabled" -> Optional.of(OFF);
            case "on", "true", "1", "enable", "enabled", "brief" -> Optional.of(BRIEF);
            case "rag", "retrieval" -> Optional.of(RAG);
            case "tool", "tools" -> Optional.of(TOOLS);
            case "prompt", "prompts", "frame" -> Optional.of(PROMPT);
            case "trace", "all" -> Optional.of(TRACE);
            default -> Optional.empty();
        };
    }
}
