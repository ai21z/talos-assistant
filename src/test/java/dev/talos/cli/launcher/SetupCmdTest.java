package dev.talos.cli.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupCmdTest {

    @TempDir Path tempDir;

    @Test
    void setupCommandDescriptionIsBackendNeutral() {
        CommandLine.Command command = SetupCmd.class.getAnnotation(CommandLine.Command.class);

        assertTrue(command.description()[0].contains("local model"));
        assertFalse(command.description()[0].contains("Install Ollama"));
    }

    @Test
    void setupSummaryDoesNotSayTalosRequiresOllama() {
        String summary = SetupCmd.setupSummary();

        assertTrue(summary.contains("llama.cpp"));
        assertFalse(summary.contains("requires Ollama"));
    }

    @Test
    void modelsHelpMentionsTestedManagedLlamaCppProfiles() {
        String help = SetupCmd.modelsHelp();

        assertTrue(help.contains("qwen2.5-coder-14b"));
        assertTrue(help.contains("gpt-oss-20b"));
        assertTrue(help.contains("qwen36vf-q6k"));
        assertTrue(help.contains("deepseek-v2lite-q4km"));
        assertTrue(help.contains("native/default"));
        assertTrue(help.contains("text/tool-prompt"));
        assertTrue(help.contains("talos setup models --profile"));
        assertTrue(help.contains(".talos/models"));
    }

    @Test
    void generatedProfileConfigUsesYamlSafeForwardSlashPathsAndTalosModelCache() {
        Path server = tempDir.resolve("engines").resolve("llama-cpp").resolve("llama-server.exe");
        Path cache = tempDir.resolve(".talos").resolve("models").resolve("huggingface");

        String yaml = SetupCmd.renderManagedLlamaCppProfileConfig(
                "qwen2.5-coder-14b",
                server,
                null,
                cache,
                18115);

        assertTrue(yaml.contains("default_backend: \"llama_cpp\""));
        assertTrue(yaml.contains("model: \"qwen2.5-coder-14b\""));
        assertTrue(yaml.contains("server_path: \"" + server.toString().replace('\\', '/') + "\""));
        assertTrue(yaml.contains("hf_repo: \"Qwen/Qwen2.5-Coder-14B-Instruct-GGUF\""));
        assertTrue(yaml.contains("hf_file: \"qwen2.5-coder-14b-instruct-q4_k_m.gguf\""));
        assertTrue(yaml.contains("hf_cache_dir: \"" + cache.toString().replace('\\', '/') + "\""));
        assertTrue(yaml.contains("tools:"));
        assertTrue(yaml.contains("native_calling: true"));
        assertFalse(yaml.contains("C:\\"));
    }

    @Test
    void qwenVibeForgedProfileUsesNativeToolCalling() {
        Path server = tempDir.resolve("llama-server.exe");

        String yaml = SetupCmd.renderManagedLlamaCppProfileConfig(
                "qwen36vf-q6k",
                server,
                null,
                tempDir.resolve(".talos").resolve("models").resolve("huggingface"),
                18115);

        assertTrue(yaml.contains("model: \"qwen36vf-q6k\""));
        assertTrue(yaml.contains("hf_repo: \"tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF\""));
        assertTrue(yaml.contains("hf_file: \"Qwen3.6-14B-A3B-VibeForged-v2-Q6_K.gguf\""));
        assertTrue(yaml.contains("native_calling: true"));
    }

    @Test
    void deepSeekProfileUsesTextToolPromptMode() {
        Path server = tempDir.resolve("llama-server.exe");

        String yaml = SetupCmd.renderManagedLlamaCppProfileConfig(
                "deepseek-v2lite-q4km",
                server,
                null,
                tempDir.resolve(".talos").resolve("models").resolve("huggingface"),
                18115);

        assertTrue(yaml.contains("model: \"deepseek-v2lite-q4km\""));
        assertTrue(yaml.contains("hf_repo: \"bartowski/DeepSeek-Coder-V2-Lite-Instruct-GGUF\""));
        assertTrue(yaml.contains("hf_file: \"DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf\""));
        assertTrue(yaml.contains("native_calling: false"));
    }

    @Test
    void generatedUserOwnedModelConfigUsesModelPathAndDoesNotSetHuggingFaceSource() {
        Path server = tempDir.resolve("llama-server.exe");
        Path model = tempDir.resolve("models").resolve("agent.gguf");

        String yaml = SetupCmd.renderManagedLlamaCppProfileConfig(
                "custom-agent",
                server,
                model,
                tempDir.resolve(".talos").resolve("models").resolve("huggingface"),
                18115);

        assertTrue(yaml.contains("model_path: \"" + model.toString().replace('\\', '/') + "\""));
        assertTrue(yaml.contains("hf_repo: \"\""));
        assertTrue(yaml.contains("hf_file: \"\""));
        assertTrue(yaml.contains("native_calling: true"));
    }

    @Test
    void generatedUserOwnedModelConfigRejectsProfileThatBecomesBlankAfterSanitizing() {
        Path server = tempDir.resolve("llama-server.exe");
        Path model = tempDir.resolve("models").resolve("agent.gguf");

        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> SetupCmd.renderManagedLlamaCppProfileConfig(
                        "!!!",
                        server,
                        model,
                        tempDir.resolve(".talos").resolve("models").resolve("huggingface"),
                        18115));

        assertTrue(error.getMessage().contains("model profile"));
    }

    @Test
    void setupModelsWriteSupportsBareConfigPath() throws Exception {
        Path server = tempDir.resolve("llama-server.exe");
        Files.writeString(server, "fake", StandardCharsets.UTF_8);
        Path config = Path.of("talos-setup-test-" + UUID.randomUUID() + ".yaml");

        try {
            SetupCmd cmd = new SetupCmd();
            cmd.topic = "models";
            cmd.profile = "gpt-oss-20b";
            cmd.serverPath = server;
            cmd.write = true;
            cmd.configPath = config;

            int exit = cmd.call();

            assertEquals(0, exit);
            assertTrue(Files.readString(config, StandardCharsets.UTF_8).contains("model: \"gpt-oss-20b\""));
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void setupModelsWriteCreatesConfigFile() throws Exception {
        Path server = tempDir.resolve("llama-server.exe");
        Files.writeString(server, "fake", StandardCharsets.UTF_8);
        Path config = tempDir.resolve(".talos").resolve("config.yaml");

        int exit = new CommandLine(new SetupCmd()).execute(
                "models",
                "--profile", "gpt-oss-20b",
                "--server-path", server.toString(),
                "--write",
                "--config", config.toString());

        assertEquals(0, exit);
        String yaml = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("model: \"gpt-oss-20b\""));
        assertTrue(yaml.contains("hf_repo: \"ggml-org/gpt-oss-20b-GGUF\""));
        assertTrue(yaml.contains("hf_cache_dir:"));
    }

    @Test
    void setupModelsWriteRefusesExistingConfigWithoutForce() throws Exception {
        Path server = tempDir.resolve("llama-server.exe");
        Files.writeString(server, "fake", StandardCharsets.UTF_8);
        Path config = tempDir.resolve(".talos").resolve("config.yaml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "existing: true\n", StandardCharsets.UTF_8);

        int exit = new CommandLine(new SetupCmd()).execute(
                "models",
                "--profile", "gpt-oss-20b",
                "--server-path", server.toString(),
                "--write",
                "--config", config.toString());

        assertEquals(2, exit);
        assertEquals("existing: true\n", Files.readString(config, StandardCharsets.UTF_8));
    }

    @Test
    void setupModelsForceWritesBackupBeforeReplacingConfig() throws Exception {
        Path server = tempDir.resolve("llama-server.exe");
        Files.writeString(server, "fake", StandardCharsets.UTF_8);
        Path config = tempDir.resolve(".talos").resolve("config.yaml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "existing: true\n", StandardCharsets.UTF_8);

        int exit = new CommandLine(new SetupCmd()).execute(
                "models",
                "--profile", "qwen36vf-q6k",
                "--server-path", server.toString(),
                "--write",
                "--force",
                "--config", config.toString());

        assertEquals(0, exit);
        assertTrue(Files.readString(config, StandardCharsets.UTF_8).contains("model: \"qwen36vf-q6k\""));
        try (var stream = Files.list(config.getParent())) {
            Path backup = stream
                    .filter(path -> path.getFileName().toString().startsWith("config.yaml.bak-"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("existing: true\n", Files.readString(backup, StandardCharsets.UTF_8));
        }
    }
}
