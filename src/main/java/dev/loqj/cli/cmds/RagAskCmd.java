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
            Path r = Path.of(root == null || root.isBlank() ? "." : root).toAbsolutePath().normalize();
            if (!Files.isDirectory(r)) { System.err.println("rag-ask failed: not a directory: " + r); return; }
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
}