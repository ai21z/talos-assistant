package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.core.engine.EngineRegistry;
import dev.talos.engine.llamacpp.GgufCacheScanner;
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
                // T877: surface downloaded-but-not-configured GGUFs via a safe, no-subprocess
                // scan of the HF cache so a user can SEE what they have on disk.
                var downloaded = GgufCacheScanner.downloadedNotConfigured(ctx.cfg());
                if (list.isEmpty() && downloaded.isEmpty()) {
                    return new Result.Info("No models found. Run `talos setup models` to configure managed llama.cpp, or select a configured legacy backend.");
                }
                return new Result.Ok(renderInstalledModels(list, downloaded));
            }
        } catch (Exception e) {
            // Friendly error instead of crashing the REPL
            return new Result.Error("Model catalog not reachable: " + e.getMessage() +
                "\nRun `talos status --verbose` and `talos setup models` to check local model setup.", 500);
        }
    }

    static String renderInstalledModels(List<ModelRef> models, List<ModelRef> downloaded) {
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
        appendDownloadedGroup(sb, downloaded);
        sb.append("""

Switching models:
  - Entries shown as backend/model are ready now. Switch with: /set model <backend/model>
  - "Downloaded GGUFs (not configured)" are on disk but not selectable yet.
    Configure one with: talos setup models --profile <name> --write --force
    then restart Talos.

Managed llama.cpp runs a single GGUF, fixed at launch. To change which GGUF it runs,
reconfigure as above and restart (no hot-swap).
The /profiles command is unrelated: it manages workspace verification profiles, not models.
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

    /**
     * T877: downloaded GGUFs present on disk but not the configured model. Rendered
     * by bare name (not backend/name) because they are not selectable via /set until
     * configured -- the tip below explains how to configure one.
     */
    private static void appendDownloadedGroup(StringBuilder sb, List<ModelRef> downloaded) {
        if (downloaded == null || downloaded.isEmpty()) {
            return;
        }
        sb.append("Downloaded GGUFs (not configured):\n");
        for (ModelRef model : downloaded) {
            sb.append("  ").append(model.name()).append("\n");
        }
        sb.append('\n');
    }
}
