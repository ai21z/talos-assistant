package dev.loqj.cli.cmds;

import dev.loqj.core.Config;
import dev.loqj.core.CfgUtil;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

@CommandLine.Command(name = "status", description = "Show current configuration and workspace status")
public class StatusCmd implements Runnable {
    @CommandLine.Option(names="--root", description="Workspace root (default: current dir or LOQJ_WORKSPACE env)")
    String root;

    @CommandLine.Option(names={"--verbose", "-v"}, description="Show detailed configuration")
    boolean verbose;

    @Override
    public void run() {
        try {
            // Resolve workspace root with fallback chain: --root > LOQJ_WORKSPACE > current dir
            Path workspace = resolveWorkspace();

            if (!Files.isDirectory(workspace)) {
                System.err.println("Error: Not a directory: " + workspace);
                return;
            }

            Config cfg = new Config();
            printStatus(workspace, cfg);

        } catch (Exception e) {
            System.err.println("Status command failed: " + e.getMessage());
            if (Boolean.getBoolean("loqj.debug")) {
                e.printStackTrace();
            }
        }
    }

    private Path resolveWorkspace() {
        if (root != null && !root.isBlank()) {
            return Path.of(root).toAbsolutePath().normalize();
        }

        String envRoot = System.getenv("LOQJ_WORKSPACE");
        if (envRoot != null && !envRoot.isBlank()) {
            return Path.of(envRoot).toAbsolutePath().normalize();
        }

        return Path.of(".").toAbsolutePath().normalize();
    }

    private void printStatus(Path workspace, Config cfg) {
        System.out.println("Loqs Status:");
        System.out.println("  Active workspace: " + workspace);

        // Check if we're in the installer directory and show hint
        if (dev.loqj.cli.CliUtil.isInstallerDirectory(workspace)) {
            System.out.println("  Hint: You are in Loqs' install directory. Use --root <project> or set LOQJ_WORKSPACE.");
        }

        // Show index directory location
        Path indexDir = dev.loqj.core.IndexPathResolver.getIndexDirectory(workspace);
        System.out.println("  Index directory:  " + indexDir);
        System.out.println("  Index exists:     " + (Files.exists(indexDir) ? "YES" : "NO"));

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
        System.out.println("  Vectors enabled:  " + (vectors ? "YES" : "NO"));

        // Ollama configuration
        var ollama = CfgUtil.map(cfg.data.get("ollama"));
        if (ollama != null) {
            String host = Objects.toString(ollama.getOrDefault("host", System.getenv("LOQJ_OLLAMA_HOST")));
            if (host == null || host.isBlank()) {
                host = "http://127.0.0.1:11434";
            }

            String model = System.getenv("LOQJ_OLLAMA_MODEL");
            if (model == null) model = Objects.toString(ollama.getOrDefault("chat", "qwen2.5:7b"));

            System.out.println("  Ollama host:      " + host);
            System.out.println("  Chat model:       " + model);

            if (verbose) {
                String embedModel = Objects.toString(ollama.getOrDefault("embed", "bge-m3"));
                System.out.println("  Embed model:      " + embedModel);
            }
        }

        if (verbose) {
            System.out.println("\nConfiguration:");
            System.out.println("  Config loaded from: " + cfg.getReport().loadedFrom);
            System.out.println("  Strict mode:        " + cfg.getReport().strictMode);
            System.out.println("  Defaulted keys:     " + cfg.getReport().defaultedKeys.size());
        }
    }
}
