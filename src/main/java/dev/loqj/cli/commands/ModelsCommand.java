package dev.loqj.cli.commands;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;
import dev.loqj.core.engine.EngineRegistry;

import java.util.List;

public final class ModelsCommand implements Command {
    @Override public CommandSpec spec() {
        return new CommandSpec("models", List.of(), ":models", "List installed models across all backends.", CommandGroup.MODELS);
    }

    @Override public Result execute(String args, Context ctx) throws Exception {
        try {
            // Safe model listing that won't spawn interactive processes on Windows
            try (var reg = new EngineRegistry(ctx.cfg())) {
                var cat = reg.compositeCatalog();
                var list = cat.installed(); // Use installed(), not all() to avoid subprocess calls
                if (list.isEmpty()) return new Result.Info("No models found. Make sure Ollama is running and models are installed.");

                StringBuilder sb = new StringBuilder("\nInstalled models:\n\n");
                for (var m : list) {
                    sb.append("  ").append(m.backend()).append("/").append(m.name()).append("\n");
                }
                sb.append("\nTip: use :set model <backend/model> to switch.\n");
                return new Result.Ok(sb.toString());
            }
        } catch (Exception e) {
            // Friendly error instead of crashing the REPL
            return new Result.Error("Ollama not reachable: " + e.getMessage() +
                "\nMake sure Ollama is running (ollama serve) and try again.", 500);
        }
    }
}
