package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.core.engine.EngineRegistry;
import dev.talos.engine.llamacpp.GgufCacheScanner;
import dev.talos.engine.llamacpp.LlamaCppModelCatalog;
import dev.talos.engine.llamacpp.ManagedModelLibrary;
import dev.talos.cli.ui.ModelIdentityPresentation;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.ModelRef;

import java.util.ArrayList;
import java.util.List;

public final class ModelsCommand implements Command {
    @Override public CommandSpec spec() {
        return new CommandSpec("models", List.of("model"), "/models [--verbose | use <alias> | add <alias> <absolute GGUF path>]", "List or select local models.", CommandGroup.MODELS);
    }

    @Override public Result execute(String args, Context ctx) throws Exception {
        try {
            String request = args == null ? "" : args.trim();
            String[] tokens = request.split("\\s+", 2);
            String operation = tokens[0].toLowerCase(java.util.Locale.ROOT);
            String value = tokens.length == 2 ? tokens[1].trim() : "";
            if (ctx.llm() != null && ctx.llm().supportsManagedModelSelection()
                    && ctx.llm().getModel().startsWith("llama_cpp/")) {
                if (operation.equals("use")) {
                    if (value.isBlank()) return new Result.Error("Usage: /models use <alias>", 400);
                    return new SetModelCommand().execute("model " + value, ctx);
                }
                if (operation.equals("add")) {
                    String[] registration = value.split("\\s+", 2);
                    if (registration.length != 2) return new Result.Error("Usage: /models add <alias> \"<absolute GGUF path>\"", 400);
                    String path = registration[1].trim();
                    if (path.length() >= 2 && ((path.startsWith("\"") && path.endsWith("\""))
                            || (path.startsWith("'") && path.endsWith("'")))) {
                        path = path.substring(1, path.length() - 1);
                    }
                    ctx.llm().managedModelLibrary().register(registration[0], java.nio.file.Path.of(path));
                    return new Result.Info("The local model was registered for this session. No model was started.\n"
                            + "Use /models use " + registration[0] + " to select it.");
                }
                boolean verbose = request.equals("--verbose");
                if (!request.isBlank() && !verbose) return new Result.Error("Usage: /models [--verbose], /models use <alias>, or /models add <alias> \"<absolute GGUF path>\"", 400);
                return new Result.Ok(renderManagedModels(ctx.llm().managedModelLibrary().entries(),
                        ctx.llm().getModel(), ctx.llm().modelIdentity(), ctx.llm().modelSelectionUnavailable(), verbose));
            }
            if (!request.isBlank() && !request.equals("--verbose")) return new Result.Error("Local GGUF registration requires managed llama.cpp. External servers are not restarted.", 400);
            // Safe model listing that won't spawn interactive processes on Windows
            try (var reg = new EngineRegistry(ctx.cfg())) {
                var cat = reg.compositeCatalog();
                var list = cat.installed(); // Use installed(), not all() to avoid subprocess calls
                // T877: surface downloaded-but-not-configured GGUFs via a safe, no-subprocess
                // scan of the HF cache so a user can SEE what they have on disk.
                var downloaded = GgufCacheScanner.downloadedNotConfigured(ctx.cfg());
                if (list.isEmpty() && downloaded.isEmpty()) {
                    return new Result.Info("No configured or downloaded models found. Run `talos setup models` to configure managed llama.cpp.");
                }
                ModelIdentitySnapshot identity = ctx.llm() == null
                        ? ModelIdentitySnapshot.empty()
                        : ctx.llm().modelIdentity();
                return new Result.Ok(renderInstalledModels(list, downloaded, identity));
            }
        } catch (IllegalArgumentException invalid) {
            if (dev.talos.spi.types.ControlFlowFailure.classify(invalid)
                    != dev.talos.spi.types.ControlFlowFailure.Kind.NONE) {
                throw dev.talos.spi.types.ControlFlowFailure.propagate(invalid);
            }
            String detail = java.util.Objects.toString(invalid.getMessage(), "Invalid model command.")
                    .replace('\r', ' ').replace('\n', ' ');
            if (detail.length() > 600) detail = detail.substring(0, 600);
            return new Result.Error(detail + "\nUse /help models for command syntax.", 400);
        } catch (Exception e) {
            if (dev.talos.spi.types.ControlFlowFailure.classify(e)
                    != dev.talos.spi.types.ControlFlowFailure.Kind.NONE) {
                throw dev.talos.spi.types.ControlFlowFailure.propagate(e);
            }
            // Friendly error instead of crashing the REPL
            return new Result.Error("Model catalog not reachable: " + e.getMessage() +
                "\nRun `talos status --verbose` and `talos setup models` to check local model setup.", 500);
        }
    }

    static String renderManagedModels(List<ManagedModelLibrary.Entry> entries, String selected,
                                      ModelIdentitySnapshot identity, boolean unavailable) {
        return renderManagedModels(entries, selected, identity, unavailable, false);
    }

    static String renderManagedModels(List<ManagedModelLibrary.Entry> entries, String selected,
                                      ModelIdentitySnapshot identity, boolean unavailable, boolean verbose) {
        StringBuilder out = new StringBuilder("Local model selection\n\n");
        if (unavailable) out.append("No model is available after an incomplete switch.\n\n");
        java.util.Map<String, List<ManagedModelLibrary.Entry>> groups = new java.util.LinkedHashMap<>();
        for (var entry : entries) {
            boolean current = !unavailable && (entry.alias().equals(selected)
                    || ("llama_cpp/" + entry.alias()).equals(selected));
            boolean ready = current && identity != null && identity.chatAvailable()
                    && "llama_cpp".equals(identity.backend()) && entry.alias().equals(identity.configuredModel())
                    && !"REFUSED".equals(identity.resolution())
                    && "AVAILABLE".equals(identity.propsState()) && "MATCHED".equals(identity.servedIdentityState());
            if (current) {
                out.append("Current selection\n  ").append(entry.alias()).append("  [")
                        .append(ready ? "ACTIVE" : "SELECTED").append("]\n")
                        .append("    Catalog: ").append(entry.disposition()).append('\n')
                        .append("    File: ").append(entry.availability()).append('\n');
                if (verbose && !entry.fileName().isBlank()) {
                    out.append("    Name: ").append(entry.fileName()).append('\n');
                }
                out.append('\n');
            } else {
                String group = "unavailable locally".equals(entry.availability())
                        ? "Not available locally"
                        : "Local candidates, " + entry.disposition();
                groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(entry);
            }
        }
        groups.forEach((group, models) -> {
            out.append(group).append('\n');
            for (var model : models) {
                out.append("  ").append(model.alias()).append('\n');
                if (verbose) {
                    out.append("    Availability: ").append(model.availability()).append('\n')
                            .append("    Catalog: ").append(model.disposition()).append('\n');
                    if (!model.fileName().isBlank()) out.append("    Name: ").append(model.fileName()).append('\n');
                }
            }
            out.append('\n');
        });
        if (entries.isEmpty()) out.append("No local model entries were found.\n\n");
        out.append("""
                Select: /models use <alias>
                Details: /models --verbose
                Import and switching help: /help models

                Local candidates are not verified. Catalog status is not current readiness.
                Selection is session-only. Saved defaults are unchanged. No download is performed.
                """);
        if (verbose) out.append("""

                ACTIVE means the served model identity was matched. SELECTED is configuration only.
                Files are checked before switching. The owned backend is restarted when needed.
                Talos stays open. External servers are not restarted.
                Import: /models add <alias> "<absolute GGUF path>"
                Compatibility form: /set model <alias>. The same switch is performed.
                /profiles manages workspace verification, not model selection.
                """);
        return out.toString();
    }

    static String renderInstalledModels(List<ModelRef> models, List<ModelRef> downloaded) {
        return renderInstalledModels(models, downloaded, ModelIdentitySnapshot.empty());
    }

    static String renderInstalledModels(
            List<ModelRef> models,
            List<ModelRef> downloaded,
            ModelIdentitySnapshot identity) {
        List<ModelRef> managed = new ArrayList<>();
        for (ModelRef model : models == null ? List.<ModelRef>of() : models) {
            if ("llama_cpp".equals(model.backend())) {
                managed.add(model);
            }
        }

        StringBuilder sb = new StringBuilder("\nConfigured/selectable models:\n\n");
        appendGroup(sb, "Recommended managed llama.cpp", managed, identity);
        appendDownloadedGroup(sb, downloaded);
        sb.append("""

Switching models:
  - Entries shown as backend/model are selectable. Switch with: /set model <backend/model>
  - Selectable does not prove the local engine is ready; verify with talos doctor --start.
  - "Downloaded GGUFs (not configured)" are on disk but not selectable yet.
    Configure one with: talos setup models --profile <name> --write --force
    then restart Talos.

Managed llama.cpp runs a single GGUF, fixed at launch. To change which GGUF it runs,
reconfigure as above and restart (no hot-swap).
The /profiles command is unrelated: it manages workspace verification profiles, not models.
""");
        return sb.toString();
    }

    private static void appendGroup(
            StringBuilder sb,
            String title,
            List<ModelRef> models,
            ModelIdentitySnapshot identity) {
        if (models.isEmpty()) {
            return;
        }
        sb.append(title).append(":\n");
        for (ModelRef model : models) {
            sb.append("  ").append(model.backend()).append("/").append(model.name());
            List<String> notes = new ArrayList<>();
            if ("llama_cpp".equals(model.backend())) {
                LlamaCppModelCatalog.byAlias(model.name())
                        .filter(LlamaCppModelCatalog.Profile::selectable)
                        .map(profile -> "catalog " + profile.disposition().label())
                        .ifPresent(notes::add);
            }
            notes.addAll(ModelIdentityPresentation.activeModelNotes(
                    identity, model.backend(), model.name()));
            if (model.note() != null && !model.note().isBlank()) {
                notes.add(model.note());
            }
            if (!notes.isEmpty()) {
                sb.append("  (").append(String.join(", ", notes)).append(")");
            }
            sb.append("\n");
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
