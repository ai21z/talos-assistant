package dev.talos.cli.repl.slash;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.prompt.PromptInspector;
import dev.talos.cli.prompt.PromptRender;
import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class PromptCommand implements Command {
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ModeController modes;
    private final Path workspace;

    public PromptCommand(ModeController modes, Path workspace) {
        this.modes = modes;
        this.workspace = workspace;
    }

    @Override
    public CommandSpec spec() {
        return new CommandSpec(
                "prompt",
                List.of(),
                "/prompt [last|save] [optional input]",
                "Inspect the prompt Talos would send.",
                CommandGroup.DEBUG);
    }

    @Override
    public Result execute(String args, Context ctx) throws Exception {
        String trimmed = args == null ? "" : args.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        if ("last".equals(lower)) {
            return LastPromptCapture.latest()
                    .<Result>map(render -> new Result.TrustedInfo(PromptInspector.format(render)))
                    .orElseGet(() -> new Result.Info("No prompt has been captured in this process yet."));
        }

        if (lower.equals("save") || lower.startsWith("save ")) {
            String input = trimmed.length() <= 4 ? "" : trimmed.substring(4).trim();
            PromptRender render = renderNext(input, ctx);
            String body = PromptInspector.format(render);
            Path out = save(body);
            return new Result.TrustedInfo("Saved prompt render to: " + out.toAbsolutePath().normalize() + "\n");
        }

        return new Result.TrustedInfo(PromptInspector.format(renderNext(trimmed, ctx)));
    }

    private PromptRender renderNext(String input, Context ctx) {
        return PromptInspector.renderNext(
                modes == null ? "auto" : modes.getActiveName(),
                input,
                workspace,
                ctx);
    }

    private static Path save(String body) throws Exception {
        Path dir = Path.of("local", "prompts").toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path out = dir.resolve("prompt-" + FILE_TS.format(LocalDateTime.now()) + ".md");
        Files.writeString(out, body == null ? "" : body, StandardCharsets.UTF_8);
        return out;
    }
}
