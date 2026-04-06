package dev.talos.cli.commands;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.CfgUtil;
import dev.talos.core.IndexPathResolver;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class WorkspaceCommand implements Command {
    private final Path workspace;

    public WorkspaceCommand(Path workspace) {
        this.workspace = workspace;
    }

    @Override
    public CommandSpec spec() {
        return new CommandSpec("workspace",
                List.of("where"),
                ":workspace",
                "Show active workspace and index paths.",
                CommandGroup.BASICS);
    }

    @Override
    public Result execute(String args, Context ctx) {
        try {
            var sb = new StringBuilder();

            // Absolute workspace path
            Path absWorkspace = workspace.toAbsolutePath().normalize();
            sb.append("Workspace : ").append(absWorkspace).append("\n");

            // Index directory path using centralized resolver
            Path indexDir = IndexPathResolver.getIndexDirectory(absWorkspace);
            sb.append("Index dir : ").append(indexDir).append("\n");

            // Index status and doc count
            boolean indexExists = Files.exists(indexDir) && Files.isDirectory(indexDir);
            if (indexExists) {
                int docCount = getDocCount(indexDir);
                sb.append("Index     : YES, docs=").append(docCount).append("\n");
            } else {
                sb.append("Index     : NO\n");
            }

            // Vector configuration - extract from config properly
            var cfg = ctx.cfg();
            boolean vectors = true;
            String embedModel = "bge-m3";
            int concurrency = 4;

            var rag = CfgUtil.map(cfg.data.get("rag"));
            if (rag != null) {
                var vectorsObj = CfgUtil.map(rag.get("vectors"));
                if (vectorsObj != null) {
                    Object enabled = vectorsObj.get("enabled");
                    if (enabled instanceof Boolean b) {
                        vectors = b;
                    }
                }

                // Get embed concurrency from rag section
                Object concObj = rag.get("embed_concurrency");
                if (concObj instanceof Number n) {
                    concurrency = n.intValue();
                }
            }

            var ollama = CfgUtil.map(cfg.data.get("ollama"));
            if (ollama != null) {
                Object modelObj = ollama.get("embed");
                if (modelObj != null) embedModel = Objects.toString(modelObj);
            }

            sb.append("Vectors   : ").append(vectors ? "ON" : "OFF");
            if (vectors) {
                sb.append(" (embed_model=").append(embedModel)
                  .append(", concurrency=").append(concurrency).append(")");
            }
            sb.append("\n");

            return new Result.TrustedInfo(sb.toString());

        } catch (Exception e) {
            return new Result.Error("Failed to get workspace info: " + e.getMessage(), 500);
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
