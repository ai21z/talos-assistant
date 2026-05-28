package dev.talos.cli.prompt;

import java.nio.file.Path;

/** Resolves prompt-debug artifact destination directories. */
public final class PromptDebugDestinationResolver {
    private static final String PROMPT_DEBUG_DIR_PROPERTY = "talos.promptDebugDir";
    private static final String PROMPT_DEBUG_DIR_ENV = "TALOS_PROMPT_DEBUG_DIR";

    private PromptDebugDestinationResolver() {}

    public static Path resolve(String explicitDir) {
        return resolve(
                explicitDir,
                System.getProperty(PROMPT_DEBUG_DIR_PROPERTY),
                System.getenv(PROMPT_DEBUG_DIR_ENV),
                System.getProperty("user.home", "."));
    }

    static Path resolve(String explicitDir, String propertyDir, String envDir, String userHome) {
        String configured = firstNonBlank(
                explicitDir,
                propertyDir,
                envDir);
        if (configured == null) {
            configured = Path.of(
                    userHome == null || userHome.isBlank() ? "." : userHome,
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
