package dev.loqj.cli.cmds;

import dev.loqj.core.Config;
import dev.loqj.core.rag.RagService;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

@CommandLine.Command(name="rag-ask", description="Ask with RAG")
public class RagAskCmd implements Runnable {
    @CommandLine.Option(names="--root") String root;
    @CommandLine.Option(names="--k") Integer k;
    @CommandLine.Parameters(index="0") String question;

    @Override public void run() {
        try {
            Path r = resolveWorkspaceRoot();
            if (!Files.isDirectory(r)) {
                System.err.println("rag-ask failed: not a directory: " + r);
                return;
            }
            var ans = new RagService(new Config()).ask(r, question, k);
            System.out.println(ans.text());
            if (!ans.citations().isEmpty()) {
                System.out.println("\n[Citations]");
                for (var c : ans.citations()) System.out.println(" - " + c);
            }
        } catch (Exception e) {
            System.err.println("rag-ask failed: " + e.getMessage());
        }
    }

    private Path resolveWorkspaceRoot() {
        if (root != null && !root.isBlank()) {
            return Path.of(root).toAbsolutePath().normalize();
        }

        String envRoot = System.getenv("LOQJ_WORKSPACE");
        if (envRoot != null && !envRoot.isBlank()) {
            return Path.of(envRoot).toAbsolutePath().normalize();
        }

        return Path.of(".").toAbsolutePath().normalize();
    }
}