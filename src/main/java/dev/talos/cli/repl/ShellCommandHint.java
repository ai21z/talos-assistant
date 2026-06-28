package dev.talos.cli.repl;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Detects when a user has typed a shell invocation of the {@code talos} binary
 * into the REPL prompt (for example {@code "talos setup models --write --force"})
 * instead of running it in their terminal.
 *
 * <p>Without this guard such a line is classified as a {@link LineClassifier.LineType#PROMPT}
 * and routed to the model, which produces a confused or hallucinated answer
 * (observed live with the 35B model). This is a pure, side-effect-free detector;
 * the router (see {@link ReplRouter#tryHandlePrompt}) decides what to do with the
 * returned hint. It is deliberately narrow: a normal question that merely mentions
 * the word "talos" is not matched.
 *
 * <p>Scope: this is a presentation/UX nudge only. It never touches approval,
 * permissions, checkpointing, or any trust-surface guarantee.
 */
public final class ShellCommandHint {

    private ShellCommandHint() {}

    /** Real top-level subcommand names from {@link dev.talos.cli.launcher.RootCmd}. */
    private static final Set<String> SUBCOMMANDS = Set.of(
            "setup", "run", "net", "status", "version",
            "diagnose", "doctor", "rag-index", "rag-ask", "prompt-render");

    /** Names the talos executable is invoked by across platforms. */
    private static final Set<String> BINARY_NAMES = Set.of(
            "talos", "talos.bat", "talos.exe", "talos.cmd");

    /**
     * Returns a hint message when {@code rawLine} looks like a shell invocation
     * of the talos binary (leading binary name followed by a known subcommand or
     * a flag), otherwise empty.
     */
    public static Optional<String> detect(String rawLine) {
        if (rawLine == null) return Optional.empty();
        String trimmed = rawLine.strip();
        if (trimmed.isEmpty()) return Optional.empty();

        String[] tokens = trimmed.split("\\s+");
        if (tokens.length < 2) return Optional.empty(); // bare "talos" is a normal word

        String binary = tokens[0].toLowerCase(Locale.ROOT);
        if (!BINARY_NAMES.contains(binary)) return Optional.empty();

        String second = tokens[1].toLowerCase(Locale.ROOT);
        boolean looksShell = second.startsWith("-") || SUBCOMMANDS.contains(second);
        return looksShell ? Optional.of(message()) : Optional.empty();
    }

    private static String message() {
        return "That looks like a shell command for the talos binary. "
                + "Run it in your terminal (outside Talos), not at this prompt. "
                + "In here, use slash-commands instead: /models to list models, "
                + "/set model <backend/model> to switch the running model, /help for all commands.";
    }
}
