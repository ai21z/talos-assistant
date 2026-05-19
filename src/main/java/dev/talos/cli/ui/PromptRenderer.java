package dev.talos.cli.ui;

/**
 * Stable Talos REPL prompt renderer.
 */
public final class PromptRenderer {
    private PromptRenderer() {}

    public static String render(String mode, boolean styled, CliTheme theme) {
        String safeMode = mode == null || mode.isBlank() ? "auto" : mode.strip();
        if (!styled) {
            return "talos [" + safeMode + "] > ";
        }
        CliTheme effective = theme == null ? CliTheme.current() : theme;
        return effective.brand("talos") + " "
                + effective.muted("[")
                + effective.active(safeMode)
                + effective.muted("]")
                + " > ";
    }
}
