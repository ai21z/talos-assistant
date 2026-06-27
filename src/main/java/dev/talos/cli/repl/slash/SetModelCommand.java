package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.core.engine.EngineRegistry;

import java.util.List;
import java.util.Locale;

public final class SetModelCommand implements Command {

    private static final String USAGE =
            "Usage: /set model <name>\nRun /models to see installed model names.";

    @Override public CommandSpec spec() {
        return new CommandSpec("set", List.of(), "/set model <name>", "Switch active model.",
                CommandGroup.MODELS);
    }

    @Override
    @SuppressWarnings("resource") // ctx.llm() is borrowed from the active REPL context.
    public Result execute(String args, Context ctx) throws Exception {
        String a = args == null ? "" : args.trim();
        String[] parts = a.split("\\s+", 2);
        if (parts.length == 0 || !"model".equals(parts[0].toLowerCase(Locale.ROOT))) {
            return new Result.Error(USAGE, 200);
        }
        String name = parts.length > 1 ? parts[1].trim() : "";
        if (name.isEmpty()) return new Result.Error(USAGE, 200);

        String sanitized = name.replaceAll("[^A-Za-z0-9._:/-]", "");
        if (sanitized.isEmpty()) return new Result.Error("Invalid model name.", 400);

        try (var reg = new EngineRegistry(ctx.cfg())) {
            var cat = reg.compositeCatalog();
            var mref = cat.find(sanitized);
            if (mref.isEmpty()) return new Result.Error(modelNotFoundMessage(sanitized), 404);
            var chosen = mref.get();
            ctx.llm().setModel(chosen.backend() + "/" + chosen.name());
            return new Result.Info("Model: " + ctx.llm().getModel());
        }
    }

    static String modelNotFoundMessage(String sanitized) {
        String base = "Model not found: " + sanitized;
        if (sanitized != null && sanitized.startsWith("llama_cpp/")) {
            return base + "\nManaged llama.cpp can only select the configured/running GGUF."
                    + "\nDownloaded GGUFs must be configured before they appear in /models."
                    + "\nTo switch managed GGUF profiles, run `talos setup models --profile <name> --write --force`,"
                    + " then restart Talos and check /models.";
        }
        return base + "\nTip: /models";
    }
}
