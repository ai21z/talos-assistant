package dev.talos.cli.launcher;
 
import picocli.CommandLine;

@CommandLine.Command(name = "setup", description = "Configure Talos local model engines")
public class SetupCmd implements Runnable {
    @CommandLine.Option(names="--install-ollama", description="Legacy: install Ollama via winget")
    boolean install;
 
    @CommandLine.Option(names="--models", description="Legacy Ollama: comma-separated list to pull")
    String models;

    public static String setupSummary() {
        return "Talos uses configurable local model engines. The default path is llama.cpp: "
                + "set engines.llama_cpp.server_path and engines.llama_cpp.model_path in ~/.talos/config.yaml. "
                + "Ollama remains available only when explicitly selected as the backend.";
    }
 
    @Override public void run() {
        try {
            if (!install && (models == null || models.isBlank())) {
                System.out.println(setupSummary());
                return;
            }
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
