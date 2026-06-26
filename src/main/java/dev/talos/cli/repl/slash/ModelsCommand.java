package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.core.engine.EngineRegistry;
import dev.talos.spi.types.ModelRef;

import java.util.ArrayList;
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
                return new Result.Ok(renderInstalledModels(list));
            }
        } catch (Exception e) {
            // Friendly error instead of crashing the REPL
            return new Result.Error("Model catalog not reachable: " + e.getMessage() +
                "\nRun `talos status --verbose` and `talos setup models` to check local model setup.", 500);
        }
    }

    static String renderInstalledModels(List<ModelRef> models) {
        List<ModelRef> managed = new ArrayList<>();
        List<ModelRef> ollama = new ArrayList<>();
        List<ModelRef> other = new ArrayList<>();
        for (ModelRef model : models == null ? List.<ModelRef>of() : models) {
            if ("llama_cpp".equals(model.backend())) {
                managed.add(model);
            } else if ("ollama".equals(model.backend())) {
                ollama.add(model);
            } else {
                other.add(model);
            }
        }

        StringBuilder sb = new StringBuilder("\nInstalled models:\n\n");
        appendGroup(sb, "Recommended managed llama.cpp", managed);
        appendGroup(sb, "Legacy/optional Ollama", ollama);
        appendGroup(sb, "Other configured backends", other);
        sb.append("""

Tip: use /set model <backend/model> to switch among models visible above.
Managed llama.cpp lists the configured/running model only. Downloaded GGUFs are not selectable until configured.
To switch managed GGUF profiles, run `talos setup models --profile <name> --write --force`, then restart Talos.
""");
        return sb.toString();
    }

    private static void appendGroup(StringBuilder sb, String title, List<ModelRef> models) {
        if (models.isEmpty()) {
            return;
        }
        sb.append(title).append(":\n");
        for (ModelRef model : models) {
            sb.append("  ").append(model.backend()).append("/").append(model.name()).append("\n");
        }
        sb.append('\n');
    }
}
