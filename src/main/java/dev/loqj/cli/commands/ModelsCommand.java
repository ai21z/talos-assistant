package dev.loqj.cli.commands;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;
import dev.loqj.core.engine.EngineRegistry;

import java.util.List;

public final class ModelsCommand implements Command {
    @Override public CommandSpec spec() {
        return new CommandSpec("models", List.of(), ":models", "List installed models across all backends.");
    }

    @Override public Result execute(String args, Context ctx) throws Exception {
        try (var reg = new EngineRegistry(ctx.cfg())) {
            var cat = reg.compositeCatalog();
            var list = cat.installed(); // <-- use installed(), not all()
            if (list.isEmpty()) return new Result.Info("(no models found)");
            StringBuilder sb = new StringBuilder("\nInstalled models:\n\n");
            for (var m : list) sb.append("  ").append(m.backend()).append("/").append(m.name()).append("\n");
            sb.append("\nTip: use :set model <backend/model> to switch.\n");
            return new Result.Ok(sb.toString());
        }
    }
}
