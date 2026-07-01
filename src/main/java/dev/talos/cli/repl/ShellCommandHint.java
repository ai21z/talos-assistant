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

        // T903: accept a path-qualified invocation (./talos, /usr/local/bin/talos,
        // C:\...\talos.exe) by comparing the basename of the first token.
        String binary = basename(tokens[0]).toLowerCase(Locale.ROOT);
        if (!BINARY_NAMES.contains(binary)) return Optional.empty();

        String second = tokens[1].toLowerCase(Locale.ROOT);
        boolean looksShell;
        if (second.startsWith("-")) {
            looksShell = true; // a flag second token, e.g. "talos -v"
        } else if (SUBCOMMANDS.contains(second)) {
            // T906: token count alone is too broad. Short prose such as
            // "talos run tests" must reach the model; keep exact commands,
            // explicit flags, and documented positional command shapes.
            looksShell = tokens.length == 2 || hasFlagToken(tokens) || isDocumentedPositionalInvocation(tokens);
        } else {
            looksShell = false;
        }
        return looksShell ? Optional.of(message()) : Optional.empty();
    }

    private static String basename(String token) {
        int sep = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        return sep >= 0 ? token.substring(sep + 1) : token;
    }

    private static boolean hasFlagToken(String[] tokens) {
        for (int i = 1; i < tokens.length; i++) {
            if (tokens[i].startsWith("-")) return true;
        }
        return false;
    }

    private static boolean isDocumentedPositionalInvocation(String[] tokens) {
        return tokens.length == 3
                && "setup".equalsIgnoreCase(tokens[1])
                && "models".equalsIgnoreCase(tokens[2]);
    }

    private static String message() {
        return "That looks like a shell command for the talos binary. "
                + "Run it in your terminal (outside Talos), not at this prompt. "
                + "In here, use slash-commands instead: /models to list models, "
                + "/set model <backend/model> to switch the running model, /help for all commands.";
    }
}
