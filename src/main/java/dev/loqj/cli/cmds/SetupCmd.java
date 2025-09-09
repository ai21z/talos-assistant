package dev.loqj.cli.cmds;
 
import picocli.CommandLine;
 
@CommandLine.Command(name = "setup", description = "Install Ollama and pull models")
public class SetupCmd implements Runnable {
    @CommandLine.Option(names="--install-ollama", description="Install Ollama via winget")
    boolean install;
 
    @CommandLine.Option(names="--models", description="Comma-separated list to pull (e.g. qwen2.5:7b-instruct,llama3.1:8b-instruct)")
    String models;
 
    @Override public void run() {
        try {
            if (install) {
                new ProcessBuilder(
                        "winget", "install", "--exact", "Ollama.Ollama",
                        "--silent", "--accept-package-agreements", "--accept-source-agreements")
                        .inheritIO().start().waitFor();
            }
            if (models != null && !models.isBlank()) {
                for (String m : models.split(",")) {
                    String id = m.trim();
                    if (!id.isEmpty()) {
                        System.out.println("Pulling model: " + id);
                        new ProcessBuilder("ollama", "pull", id).inheritIO().start().waitFor();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("setup failed: " + e.getMessage());
        }
    }
}
