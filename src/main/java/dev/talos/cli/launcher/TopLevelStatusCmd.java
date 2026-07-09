package dev.talos.cli.launcher;

import dev.talos.core.Config;
import dev.talos.core.CfgUtil;
import dev.talos.core.EngineRuntimeConfig;
import dev.talos.cli.ui.CliStatusDashboard;
import dev.talos.core.engine.EngineRegistry;
import dev.talos.engine.llamacpp.LlamaCppContextArgs;
import dev.talos.engine.llamacpp.LlamaCppContextLimits;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.Health;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalInt;

@CommandLine.Command(name = "status", description = "Show current configuration and workspace status")
public class TopLevelStatusCmd implements Runnable {
    @CommandLine.Option(names="--root", description="Workspace root (default: current dir or TALOS_WORKSPACE env)")
    String root;

    @CommandLine.Option(names={"--verbose", "-v"}, description="Show detailed configuration")
    boolean verbose;

    @Override
    public void run() {
        try {
            // Resolve workspace root with fallback chain: --root > TALOS_WORKSPACE > current dir
            Path workspace = resolveWorkspace();

            if (!Files.isDirectory(workspace)) {
                System.err.println("Error: Not a directory: " + workspace);
                return;
            }

            Config cfg = new Config();
            printStatus(workspace, cfg);

        } catch (Exception e) {
            System.err.println("Status command failed: " + e.getMessage());
            if (Boolean.getBoolean("talos.debug")) {
                e.printStackTrace();
            }
        }
    }

    private Path resolveWorkspace() {
        if (root != null && !root.isBlank()) {
            return Path.of(root).toAbsolutePath().normalize();
        }

        String envRoot = System.getenv("TALOS_WORKSPACE");
        if (envRoot != null && !envRoot.isBlank()) {
            return Path.of(envRoot).toAbsolutePath().normalize();
        }

        return Path.of(".").toAbsolutePath().normalize();
    }

    private void printStatus(Path workspace, Config cfg) {
        if (!verbose) {
            var snapshot = CliStatusDashboard.snapshot(
                    workspace,
                    cfg,
                    "auto",
                    CliStatusDashboard.resolveModel(cfg),
                    "off",
                    "Use talos run, or talos status --verbose");
            System.out.print(CliStatusDashboard.render(snapshot));
            return;
        }

        System.out.println("Talos Status:");

        // Workspace and index directory
        Path indexDir = dev.talos.core.IndexPathResolver.getIndexDirectory(workspace);
        boolean indexExists = Files.exists(indexDir);
        int docCount = indexExists ? getDocCount(indexDir) : 0;

        System.out.println("  Workspace   : " + workspace);
        System.out.println("  Index dir   : " + indexDir);
        System.out.println("  Index exists: " + (indexExists ? ("YES (docs=" + docCount + ")") : "NO"));

        // Check if we're in the installer directory and show hint
        if (dev.talos.cli.CliUtil.isInstallerDirectory(workspace)) {
            System.out.println("  Hint: You are in Talos' install directory. Use --root <project> or set TALOS_WORKSPACE.");
        }

        // Vector mode configuration
        boolean vectors = true;
        var rag = CfgUtil.map(cfg.data.get("rag"));
        if (rag != null) {
            var vectorsObj = rag.get("vectors");
            if (vectorsObj instanceof Map<?,?> vm) {
                Object enabled = vm.get("enabled");
                if (enabled instanceof Boolean b) {
                    vectors = b;
                }
            }
        }
        System.out.println("  Vectors     : " + (vectors ? "ON" : "OFF"));

        System.out.print(renderEngineStatus(cfg));

        if (verbose) {
            System.out.println("\nConfiguration:");
            System.out.println("  Config loaded from: " + cfg.getReport().loadedFrom);
            System.out.println("  User config path:   " + cfg.getReport().userConfigPath);
            if (cfg.getReport().userConfigPresent) {
                if (cfg.getReport().userConfigLoaded) {
                    System.out.println("  User config:        loaded");
                } else {
                    System.out.println("  User config:        parse failed - " + cfg.getReport().userConfigError);
                }
            } else {
                System.out.println("  User config:        not found");
            }
            System.out.println("  Strict mode:        " + cfg.getReport().strictMode);
            System.out.println("  Defaulted keys:     " + cfg.getReport().defaultedKeys.size());
        }
    }

    static String renderEngineStatus(Config cfg) {
        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(cfg);
        StringBuilder out = new StringBuilder();
        out.append("  Backend     : ").append(runtime.backend()).append("\n");
        if ("ollama".equals(runtime.backend())) {
            out.append("  Ollama host : ").append(runtime.hostLabel()).append("\n");
        } else {
            out.append("  Engine host : ").append(runtime.hostLabel()).append("\n");
        }
        out.append("  Chat model  : ").append(runtime.model()).append("\n");
        if (!runtime.modelSourceLabel().isBlank()) {
            out.append("  Model file  : ").append(runtime.modelSourceLabel()).append("\n");
        }
        appendLlamaCppContext(out, cfg, runtime.backend());
        out.append("  Embeddings  : ").append(runtime.embeddingLabel()).append("\n");

        try (EngineRegistry registry = new EngineRegistry(cfg)) {
            registry.select(runtime.backend(), runtime.model());
            Health health = registry.engine().health();
            Capabilities caps = registry.engine().caps();
            out.append("  Health      : ")
                    .append(health.ok() ? "OK" : "DOWN")
                    .append(health.message().isBlank() ? "" : " - " + health.message())
                    .append("\n");
            out.append("  Capabilities: chat=")
                    .append(caps.chat())
                    .append(", stream=").append(caps.stream())
                    .append(", tools=").append(caps.nativeTools())
                    .append(", required_tool=").append(caps.requiredToolChoice())
                    .append("\n");
        } catch (Exception e) {
            out.append("  Health      : DOWN - ").append(e.getMessage()).append("\n");
        }
        return out.toString();
    }

    private static void appendLlamaCppContext(StringBuilder out, Config cfg, String backend) {
        if (!"llama_cpp".equals(backend)) return;
        Map<String, Object> engines = CfgUtil.map(cfg == null ? null : cfg.data.get("engines"));
        Map<String, Object> llama = CfgUtil.map(engines.get("llama_cpp"));
        int configured = CfgUtil.intAt(llama, "context",
                dev.talos.engine.llamacpp.ManagedContextSelector.DEFAULT_CONTEXT);
        String mode = String.valueOf(llama.getOrDefault("mode", "managed"))
                .trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        // Report the context the server actually launches with: managed mode
        // floors at the selector default, connect-only floors at 256
        // (mirrors LlamaCppConfig.from).
        int floored = clampContext("connect_only".equals(mode)
                ? Math.max(256, configured)
                : Math.max(configured, dev.talos.engine.llamacpp.ManagedContextSelector.DEFAULT_CONTEXT));
        OptionalInt serverArgsOverride = "connect_only".equals(mode)
                ? OptionalInt.empty()
                : LlamaCppContextArgs.contextOverride(CfgUtil.strList(llama.get("server_args")));
        int effective = serverArgsOverride.orElse(floored);
        String reason = safeReason(llama.get("context_reason"));
        out.append("  Context     : ").append(effective);
        if (serverArgsOverride.isPresent()) {
            out.append(" (server_args override; configured ").append(configured);
            if (floored != configured) {
                out.append(", engine floor would use ").append(floored);
            }
            out.append(")");
        } else if (effective != configured) {
            out.append(" (configured ").append(configured).append(", raised by the engine floor)");
        }
        if (!reason.isBlank()) {
            out.append(" - ").append(reason);
        }
        out.append("\n");
    }

    private static int clampContext(int context) {
        return Math.min(context, LlamaCppContextLimits.MAX_CONTEXT);
    }

    private static String safeReason(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw).trim();
        if (value.isBlank()) return "";
        value = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        while (value.contains("  ")) {
            value = value.replace("  ", " ");
        }
        return value.length() <= 200 ? value : value.substring(0, 197) + "...";
    }

    private int getDocCount(Path indexDir) {
        try (Directory dir = FSDirectory.open(indexDir);
             DirectoryReader reader = DirectoryReader.open(dir)) {
            return reader.numDocs();
        } catch (Exception e) {
            return 0; // If we can't read the index, assume 0 docs
        }
    }
}
