package dev.talos.cli.repl.slash;

import dev.talos.cli.prompt.PromptDebugArtifactWriter;
import dev.talos.cli.prompt.PromptDebugDestinationResolver;
import dev.talos.cli.prompt.PromptDebugInspector;
import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;

import java.util.List;
import java.util.Locale;

/** Hidden maintainer command for inspecting the latest assembled/provider prompt. */
public final class PromptDebugCommand implements Command {
    @Override
    public CommandSpec spec() {
        return new CommandSpec(
                "prompt-debug",
                List.of(),
                "/prompt-debug [help|last|save]",
                "Internal prompt/provider request diagnostics.",
                CommandGroup.DEBUG,
                true);
    }

    @Override
    public Result execute(String args, Context ctx) throws Exception {
        String raw = args == null ? "" : args.trim();
        String q = raw.toLowerCase(Locale.ROOT);
        if (q.isEmpty() || "help".equals(q)) {
            return new Result.TrustedInfo(help());
        }
        if ("last".equals(q) || "show".equals(q)) {
            return PromptDebugCapture.latest()
                    .<Result>map(snapshot -> new Result.TrustedInfo(PromptDebugInspector.format(snapshot)))
                    .orElseGet(PromptDebugCommand::missingCaptureInfo);
        }
        if (matchesCommand(raw, "save")) {
            return saveLatest(commandArgument(raw, "save"));
        }
        if (matchesCommand(raw, "save-all")) {
            return saveAll(commandArgument(raw, "save-all"));
        }
        if (matchesCommand(raw, "saveall")) {
            return saveAll(commandArgument(raw, "saveall"));
        }
        return new Result.Error("Usage: /prompt-debug [help|last|save [directory]|save-all [directory]]", 204);
    }

    private static Result saveLatest(String explicitDir) throws Exception {
        var latest = PromptDebugCapture.latest();
        if (latest.isEmpty()) {
            return missingCaptureInfo();
        }
        PromptDebugSnapshot snapshot = latest.get();
        var dir = PromptDebugDestinationResolver.resolve(explicitDir);
        PromptDebugArtifactWriter.LatestArtifact artifact =
                PromptDebugArtifactWriter.writeLatest(dir, snapshot);

        StringBuilder result = new StringBuilder();
        result.append("Saved prompt debug render to: ")
                .append(artifact.renderPath().toAbsolutePath().normalize()).append('\n');
        artifact.providerBodyPath().ifPresent(json ->
            result.append("Saved provider body JSON to: ")
                    .append(json.toAbsolutePath().normalize()).append('\n'));
        return new Result.TrustedInfo(result.toString());
    }

    private static Result saveAll(String explicitDir) throws Exception {
        List<PromptDebugSnapshot> snapshots = PromptDebugCapture.history();
        if (snapshots.isEmpty()) {
            return missingCaptureInfo();
        }
        var dir = PromptDebugDestinationResolver.resolve(explicitDir);
        PromptDebugArtifactWriter.HistoryArtifact artifact =
                PromptDebugArtifactWriter.writeHistory(dir, snapshots);

        StringBuilder result = new StringBuilder();
        result.append("Saved ").append(snapshots.size()).append(" prompt debug capture(s).\n");
        for (PromptDebugArtifactWriter.CaptureArtifact capture : artifact.captures()) {
            result.append("Saved prompt debug render to: ")
                    .append(capture.renderPath().toAbsolutePath().normalize()).append('\n');
            capture.providerBodyPath().ifPresent(json ->
                result.append("Saved provider body JSON to: ")
                        .append(json.toAbsolutePath().normalize()).append('\n'));
        }
        result.append("Saved prompt debug history index to: ")
                .append(artifact.indexPath().toAbsolutePath().normalize()).append('\n');
        return new Result.TrustedInfo(result.toString());
    }

    private static boolean matchesCommand(String raw, String command) {
        if (raw == null) return false;
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.equals(command) || lower.startsWith(command + " ");
    }

    private static String commandArgument(String raw, String command) {
        if (raw == null || raw.length() <= command.length()) return "";
        return raw.substring(command.length()).trim();
    }

    private static Result.Info missingCaptureInfo() {
        if (PromptDebugCapture.lastTurnHadNoProviderRequest()) {
            return new Result.Info(
                    "No provider prompt was sent for the last turn. Talos answered from deterministic runtime policy, "
                            + "so there is no provider request body to show or save for that turn.\n");
        }
        return new Result.Info("No prompt debug capture has been recorded in this process yet.\n");
    }

    private static String help() {
        return """
                /prompt-debug is an internal Talos maintainer command.

                /prompt-debug last
                  Show the latest structured chat request or provider-shaped HTTP body captured by this process.

                /prompt-debug save [directory]
                  Save the same render outside the active workspace by default, plus provider-body JSON when available.
                  Destination precedence: explicit directory, talos.promptDebugDir, TALOS_PROMPT_DEBUG_DIR, then ~/.talos/prompt-debug.

                /prompt-debug save-all [directory]
                  Save every non-background provider request captured since the latest turn started.
                """;
    }
}
