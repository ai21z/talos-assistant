package dev.talos.cli.repl.slash;

import dev.talos.cli.prompt.PromptDebugInspector;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Hidden maintainer command for inspecting the latest assembled/provider prompt. */
public final class PromptDebugCommand implements Command {
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String PROMPT_DEBUG_DIR_PROPERTY = "talos.promptDebugDir";
    private static final String PROMPT_DEBUG_DIR_ENV = "TALOS_PROMPT_DEBUG_DIR";

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
        Path dir = promptDebugDirectory(explicitDir);
        Files.createDirectories(dir);

        String ts = FILE_TS.format(LocalDateTime.now());
        Path md = dir.resolve("prompt-debug-" + ts + ".md");
        Files.writeString(md, PromptDebugInspector.format(snapshot), StandardCharsets.UTF_8);

        StringBuilder result = new StringBuilder();
        result.append("Saved prompt debug render to: ")
                .append(md.toAbsolutePath().normalize()).append('\n');
        if (!snapshot.providerBodyJson().isBlank()) {
            Path json = dir.resolve("prompt-debug-" + ts + ".provider-body.json");
            Files.writeString(json, PromptDebugInspector.redactedProviderBodyJson(snapshot), StandardCharsets.UTF_8);
            result.append("Saved provider body JSON to: ")
                    .append(json.toAbsolutePath().normalize()).append('\n');
        }
        return new Result.TrustedInfo(result.toString());
    }

    private static Result saveAll(String explicitDir) throws Exception {
        List<PromptDebugSnapshot> snapshots = PromptDebugCapture.history();
        if (snapshots.isEmpty()) {
            return missingCaptureInfo();
        }
        Path dir = promptDebugDirectory(explicitDir);
        Files.createDirectories(dir);

        String ts = FILE_TS.format(LocalDateTime.now());
        List<String> indexLines = new ArrayList<>();
        StringBuilder result = new StringBuilder();
        result.append("Saved ").append(snapshots.size()).append(" prompt debug capture(s).\n");
        for (int i = 0; i < snapshots.size(); i++) {
            PromptDebugSnapshot snapshot = snapshots.get(i);
            String prefix = "prompt-debug-" + ts + "-" + String.format("%02d", i + 1);
            Path md = dir.resolve(prefix + ".md");
            Files.writeString(md, PromptDebugInspector.format(snapshot), StandardCharsets.UTF_8);
            result.append("Saved prompt debug render to: ")
                    .append(md.toAbsolutePath().normalize()).append('\n');
            indexLines.add((i + 1) + ". " + md.toAbsolutePath().normalize());
            if (!snapshot.providerBodyJson().isBlank()) {
                Path json = dir.resolve(prefix + ".provider-body.json");
                Files.writeString(json, PromptDebugInspector.redactedProviderBodyJson(snapshot), StandardCharsets.UTF_8);
                result.append("Saved provider body JSON to: ")
                        .append(json.toAbsolutePath().normalize()).append('\n');
                indexLines.add("   provider: " + json.toAbsolutePath().normalize());
            }
        }
        Path index = dir.resolve("prompt-debug-" + ts + "-index.md");
        Files.writeString(index,
                "# Talos Prompt Debug History\n\n" + String.join("\n", indexLines) + "\n",
                StandardCharsets.UTF_8);
        result.append("Saved prompt debug history index to: ")
                .append(index.toAbsolutePath().normalize()).append('\n');
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

    private static Path promptDebugDirectory(String explicitDir) {
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
