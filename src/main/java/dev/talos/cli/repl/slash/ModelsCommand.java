package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.spi.EngineRegistry;

import java.util.List;

public final class ModelsCommand implements Command {
    @Override public CommandSpec spec() {
        return new CommandSpec("models", List.of("model"), "/models", "List installed models.", CommandGroup.MODELS);
    }

    @Override public Result execute(String args, Context ctx) throws Exception {
        try {
            // Safe model listing that won't spawn interactive processes on Windows
            try (var reg = new EngineRegistry(ctx.cfg())) {
                var cat = reg.compositeCatalog();
                var list = cat.installed(); // Use installed(), not all() to avoid subprocess calls
                if (list.isEmpty()) {
                    return new Result.Info("No models found. Run `talos setup models` to configure managed llama.cpp, or select a configured legacy backend.");
                }

                StringBuilder sb = new StringBuilder("\nInstalled models:\n\n");
                for (var m : list) {
                    sb.append("  ").append(m.backend()).append("/").append(m.name()).append("\n");
                }
                sb.append("\nTip: use /set model <backend/model> to switch.\n");
                return new Result.Ok(sb.toString());
            }
        } catch (Exception e) {
            // Friendly error instead of crashing the REPL
            return new Result.Error("Model catalog not reachable: " + e.getMessage() +
                "\nRun `talos status --verbose` and `talos setup models` to check local model setup.", 500);
        }
    }
}
