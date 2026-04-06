package dev.talos.cli.cmds;

import dev.talos.core.Config;
import dev.talos.core.index.Indexer;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

@CommandLine.Command(name = "rag-index", description = "Index repository (Lucene + embeddings via Ollama)")
public class RagIndexCmd implements Runnable {
    @CommandLine.Option(names="--root", description="Path to project root (default: current dir)")
    String root;

    @CommandLine.Option(names="--full", description="Force full reindex (ignore file hashes)")
    boolean forceFull;

    @CommandLine.Option(names="--json", description="Output statistics in JSON format")
    boolean asJson;

    @CommandLine.Option(names="--stats", description="Show last indexing statistics without running")
    boolean statsOnly;

    @Override public void run() {
        Path r = resolveWorkspaceRoot();
        try {
            if (!Files.isDirectory(r)) {
                System.err.println("Index failed: not a directory: " + r);
                return;
            }

            var cfg = new Config();
            var indexer = new Indexer(cfg);

            if (statsOnly) {
                renderStats(indexer.getLastRunStats(), asJson);
                return;
            }

            System.out.println("Indexing root: " + r);
            indexer.index(r, forceFull);
            renderStats(indexer.getLastRunStats(), asJson);
        } catch (Exception e) {
            System.err.println("Index failed: " + e.getMessage());
        }
    }

    private Path resolveWorkspaceRoot() {
        if (root != null && !root.isBlank()) {
            return Path.of(root).toAbsolutePath().normalize();
        }

        String envRoot = System.getenv("TALOS_WORKSPACE");
        if (envRoot != null && !envRoot.isBlank()) {
            return Path.of(envRoot).toAbsolutePath().normalize();
        }

        return Path.of(".").toAbsolutePath().normalize();
    }

    private void renderStats(Object stats, boolean asJson) {
        if (stats == null) {
            System.out.println(asJson ? "{\"error\":\"No statistics available\"}" : "No statistics available.");
            return;
        }

        if (asJson && stats instanceof dev.talos.core.index.IndexingStats indexStats) {
            System.out.println(indexStats.toJson());
        } else {
            System.out.println("Index complete.");
        }
    }
}
