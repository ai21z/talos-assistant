package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.spi.EngineRegistry;

import java.util.List;

public final class SetModelCommand implements Command {
    @Override public CommandSpec spec() {
        return new CommandSpec("set", List.of(), "/set model <name>", "Switch active model.",
                CommandGroup.MODELS);
    }

    @Override public Result execute(String args, Context ctx) throws Exception {
        String a = args == null ? "" : args.trim();
        if (!a.toLowerCase().startsWith("model")) return new Result.Error("Usage: /set model <name>", 200);
        String name = a.substring("model".length()).trim();
        if (name.isEmpty()) return new Result.Error("Usage: /set model <name>", 200);

        String sanitized = name.replaceAll("[^A-Za-z0-9._:/-]", "");
        if (sanitized.isEmpty()) return new Result.Error("Invalid model name.", 400);

        try (var reg = new EngineRegistry(ctx.cfg())) {
            var cat = reg.compositeCatalog();
            var mref = cat.find(sanitized.contains("/") ? sanitized : sanitized); // search either way
            if (mref.isEmpty()) return new Result.Error("Model not found: " + sanitized + "\nTip: /models", 404);
            var chosen = mref.get();
            ctx.llm().setModel(chosen.backend() + "/" + chosen.name());
            return new Result.Info("Model: " + ctx.llm().getModel());
        }
    }
}
