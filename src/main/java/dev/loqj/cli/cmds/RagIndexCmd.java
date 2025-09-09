package dev.loqj.cli.cmds;

import dev.loqj.core.Config;
import dev.loqj.core.index.Indexer;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

@CommandLine.Command(name = "rag-index", description = "Index repository (Lucene + embeddings via Ollama)")
public class RagIndexCmd implements Runnable {
    @CommandLine.Option(names="--root", description="Path to project root (default: current dir)")
    String root;

    @Override public void run() {
        Path r = Path.of(root == null || root.isBlank() ? "." : root).toAbsolutePath().normalize();
        try {
            if (!Files.isDirectory(r)) {
                System.err.println("Index failed: not a directory: " + r);
                return;
            }
            System.out.println("Indexing root: " + r);
            var cfg = new Config();
            new Indexer(cfg).index(r);
            System.out.println("Index complete.");
        } catch (Exception e) {
            System.err.println("Index failed: " + e.getMessage());
        }
    }
}
