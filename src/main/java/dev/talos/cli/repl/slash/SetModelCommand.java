package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.core.engine.EngineRegistry;
import dev.talos.engine.llamacpp.GgufCacheScanner;
import dev.talos.spi.types.ModelRef;

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
            if (mref.isEmpty()) {
                // T899: if the requested name is a GGUF on disk that just is not
                // configured yet, say so and how to switch, instead of a bare 404.
                var downloaded = GgufCacheScanner.downloadedNotConfigured(ctx.cfg());
                return new Result.Error(modelNotFoundMessage(sanitized, downloaded), 404);
            }
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

    /**
     * T899: when the requested name is a GGUF that exists on disk but is not
     * configured (so it is not in the running catalog), return actionable switch
     * guidance instead of a bare 404. Managed llama.cpp binds one GGUF at launch,
     * so switching genuinely requires reconfigure + restart - we state that
     * honestly rather than imply an in-REPL hot-swap that does not exist.
     */
    static String modelNotFoundMessage(String sanitized, List<ModelRef> downloaded) {
        String matched = matchDownloaded(sanitized, downloaded);
        if (matched != null) {
            return "\"" + matched + "\" is downloaded but not configured, so it is not selectable here yet."
                    + "\nManaged llama.cpp binds one GGUF at launch (no hot-swap). Switching means reconfiguring and restarting."
                    + "\nTo switch to it, run in your terminal (not at this prompt):"
                    + "\n  talos setup models --profile <name> --server-path <llama-server> --write --force"
                    + "\n(or edit engines.llama_cpp.hf_repo / hf_file in ~/.talos/config.yaml to point at this GGUF)."
                    + "\nThen restart Talos and confirm with /models.";
        }
        return modelNotFoundMessage(sanitized);
    }

    /** Returns the on-disk GGUF name matching {@code sanitized} (ignoring case, a
     *  .gguf suffix, and any backend/ prefix), or null if none. */
    private static String matchDownloaded(String sanitized, List<ModelRef> downloaded) {
        if (sanitized == null || downloaded == null) return null;
        String want = stripGgufSuffix(sanitized);
        int slash = want.lastIndexOf('/');
        if (slash >= 0) want = want.substring(slash + 1);
        for (ModelRef m : downloaded) {
            if (m == null || m.name() == null) continue;
            if (stripGgufSuffix(m.name()).equalsIgnoreCase(want)) return m.name();
        }
        return null;
    }

    private static String stripGgufSuffix(String s) {
        String t = s.trim();
        return t.toLowerCase(Locale.ROOT).endsWith(".gguf") ? t.substring(0, t.length() - 5) : t;
    }
}
