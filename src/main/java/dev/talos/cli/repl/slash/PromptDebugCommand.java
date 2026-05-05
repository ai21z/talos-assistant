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
import java.util.List;
import java.util.Locale;

/** Hidden maintainer command for inspecting the latest assembled/provider prompt. */
public final class PromptDebugCommand implements Command {
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

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
        String q = args == null ? "" : args.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty() || "help".equals(q)) {
            return new Result.TrustedInfo(help());
        }
        if ("last".equals(q) || "show".equals(q)) {
            return PromptDebugCapture.latest()
                    .<Result>map(snapshot -> new Result.TrustedInfo(PromptDebugInspector.format(snapshot)))
                    .orElseGet(() -> new Result.Info("No prompt debug capture has been recorded in this process yet.\n"));
        }
        if ("save".equals(q)) {
            return saveLatest();
        }
        return new Result.Error("Usage: /prompt-debug [help|last|save]", 204);
    }

    private static Result saveLatest() throws Exception {
        var latest = PromptDebugCapture.latest();
        if (latest.isEmpty()) {
            return new Result.Info("No prompt debug capture has been recorded in this process yet.\n");
        }
        PromptDebugSnapshot snapshot = latest.get();
        Path dir = Path.of("local", "prompts").toAbsolutePath().normalize();
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

    private static String help() {
        return """
                /prompt-debug is an internal Talos maintainer command.

                /prompt-debug last
                  Show the latest structured chat request or provider-shaped HTTP body captured by this process.

                /prompt-debug save
                  Save the same render under local/prompts, plus provider-body JSON when available.
                """;
    }
}
