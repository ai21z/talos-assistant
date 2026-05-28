package dev.talos.cli.prompt;

import java.nio.file.Path;

/** Resolves prompt-debug artifact destination directories. */
public final class PromptDebugDestinationResolver {
    private static final String PROMPT_DEBUG_DIR_PROPERTY = "talos.promptDebugDir";
    private static final String PROMPT_DEBUG_DIR_ENV = "TALOS_PROMPT_DEBUG_DIR";

    private PromptDebugDestinationResolver() {}

    public static Path resolve(String explicitDir) {
        String configured = firstNonBlank(
                explicitDir,
                System.getProperty(PROMPT_DEBUG_DIR_PROPERTY),
                System.getenv(PROMPT_DEBUG_DIR_ENV));
        if (configured == null) {
            configured = Path.of(
                    System.getProperty("user.home", "."),
                    ".talos",
                    "prompt-debug").toString();
        }
        return Path.of(stripOptionalQuotes(configured)).toAbsolutePath().normalize();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.strip();
        }
        return null;
    }

    private static String stripOptionalQuotes(String value) {
        if (value == null) return "";
        String stripped = value.strip();
        if (stripped.length() >= 2
                && ((stripped.startsWith("\"") && stripped.endsWith("\""))
                || (stripped.startsWith("'") && stripped.endsWith("'")))) {
            return stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }
}
