package dev.loqj.cli.commands;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;
import dev.loqj.core.llm.OllamaModels;

import java.util.List;

public final class ModelsCommand implements Command {
    @Override public CommandSpec spec() {
        return new CommandSpec("models", List.of(), ":models", "List installed models.");
    }

    @Override public Result execute(String args, Context ctx) throws Exception {
        var m = OllamaModels.list(ctx.cfg());
        if (m == null || m.isEmpty()) {
            return new Result.Info("Installed models: (none found)\n");
        }
        int shown = Math.min(m.size(), 200);
        StringBuilder sb = new StringBuilder();
        sb.append("Installed models (").append(shown).append(m.size() > shown ? " of " + m.size() : "").append("):\n");
        sb.append(String.join(", ", m.subList(0, shown))).append('\n');
        if (m.size() > shown) sb.append("… truncated; run `ollama list` to see all.\n");
        sb.append('\n');
        return new Result.Ok(sb.toString());
    }
}
