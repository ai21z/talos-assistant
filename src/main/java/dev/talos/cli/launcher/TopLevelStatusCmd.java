package dev.talos.cli.launcher;

import dev.talos.core.Config;
import dev.talos.core.CfgUtil;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

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

        // Ollama configuration
        var ollama = CfgUtil.map(cfg.data.get("ollama"));
        if (ollama != null) {
            String host = Objects.toString(ollama.getOrDefault("host", System.getenv("TALOS_OLLAMA_HOST")));
            if (host == null || host.isBlank()) {
                host = "http://127.0.0.1:11434";
            }

            String model = System.getenv("TALOS_OLLAMA_MODEL");
            if (model == null) model = Objects.toString(ollama.getOrDefault("model", "qwen2.5-coder:14b"));

            System.out.println("  Ollama host : " + host);
            System.out.println("  Chat model  : " + model);

            if (verbose) {
                // Embeddings: check availability
                String embedModel = Objects.toString(ollama.getOrDefault("embed", "bge-m3"));
                System.out.println("  Embed model : " + embedModel);
            }
        }

        if (verbose) {
            System.out.println("\nConfiguration:");
            System.out.println("  Config loaded from: " + cfg.getReport().loadedFrom);
            System.out.println("  Strict mode:        " + cfg.getReport().strictMode);
            System.out.println("  Defaulted keys:     " + cfg.getReport().defaultedKeys.size());
        }
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
