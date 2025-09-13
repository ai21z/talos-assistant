package dev.loqj.cli.commands;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;
import dev.loqj.core.llm.OllamaModels;

import java.util.List;

public final class SetModelCommand implements Command {
    @Override public CommandSpec spec() {
        return new CommandSpec("set", List.of(), ":set model <name>", "Switch active LLM model.");
    }

    @Override public Result execute(String args, Context ctx) throws Exception {
        String a = args == null ? "" : args.trim();
        if (!a.toLowerCase().startsWith("model")) {
            return new Result.Error("Usage: :set model <name>", 200);
        }
        String name = a.substring("model".length()).trim();
        if (name.isEmpty()) return new Result.Error("Usage: :set model <name>", 200);

        // light sanitize
        String sanitized = name.replaceAll("[^A-Za-z0-9._:-]", "");
        if (sanitized.isEmpty()) return new Result.Error("Invalid model name.", 400);

        var known = OllamaModels.list(ctx.cfg());
        if (!known.isEmpty() && !known.contains(sanitized)) {
            return new Result.Error("Model not found: " + sanitized + "\nTip: :models or `ollama pull " + sanitized + "`", 404);
        }

        ctx.llm().setModel(sanitized);
        return new Result.Info("Model: " + ctx.llm().getModel());
    }
}
