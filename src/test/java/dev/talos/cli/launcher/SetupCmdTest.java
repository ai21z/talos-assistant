package dev.talos.cli.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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

        assertTrue(help.contains("Accepted beta stability profiles:"), help);
        assertTrue(help.contains("Experimental selectable profiles:"), help);
        assertTrue(help.contains("qwen2.5-coder-14b"));
        assertTrue(help.contains("gpt-oss-20b"));
        assertTrue(help.contains("qwen36vf-q6k"));
        assertTrue(help.contains("deepseek-v2lite-q4km"));
        assertTrue(help.contains("native/default"));
        assertTrue(help.contains("text/tool-prompt"));
        assertTrue(help.contains("docs/reference/model-profiles.md"), help);
        assertTrue(help.contains("talos doctor --start"), help);
        assertTrue(help.contains("talos setup models --profile"));
        assertTrue(help.contains("Windows setup guide"), help);
        assertTrue(help.contains("docs/getting-started/windows-setup.md"), help);
        assertTrue(help.contains("Template only"), help);
        assertTrue(help.contains("gpt-oss-20b-mxfp4.gguf"), help);
        assertTrue(help.contains("--model-path"), help);
        assertTrue(help.contains("refuses to write a config"), help);
        assertTrue(help.contains(".talos/models"));
        assertFalse(help.contains("Tested profiles:"), help);
    }

    @Test
    void setupWizardDryRunRendersDecisionPlanWithoutSideEffects() throws Exception {
        Path config = tempDir.resolve(".talos").resolve("config.yaml");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream previousOut = System.out;

        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            int exit = new CommandLine(new SetupCmd()).execute(
                    "wizard",
                    "--dry-run",
                    "--config", config.toString());

            assertEquals(0, exit);
        } finally {
            System.setOut(previousOut);
        }

        String text = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Talos setup wizard dry run"), text);
        assertTrue(text.contains("No changes will be made"), text);
        assertTrue(text.contains("no package installs"), text);
        assertTrue(text.contains("no model downloads"), text);
        assertTrue(text.contains("no config writes"), text);
        assertTrue(text.contains("qwen2.5-coder-14b"), text);
        assertTrue(text.contains("gpt-oss-20b"), text);
        assertTrue(text.contains("talos doctor --start"), text);
        assertFalse(text.contains("latest"), text);
        assertFalse(Files.exists(config), "dry-run must not create config");
    }

    @Test
    void setupWizardInteractiveDownloadDenialCreatesNoConfig() throws Exception {
        Path server = tempDir.resolve("llama-server");
        Files.writeString(server, "fake", StandardCharsets.UTF_8);
        Path config = tempDir.resolve(".talos").resolve("config.yaml");
        ByteArrayInputStream stdin = new ByteArrayInputStream("y\n1\nn\n".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        var previousIn = System.in;
        var previousOut = System.out;

        int exit;
        try {
            System.setIn(stdin);
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            exit = new CommandLine(new SetupCmd()).execute(
                    "wizard",
                    "--server-path", server.toString(),
                    "--config", config.toString());
        } finally {
            System.setIn(previousIn);
            System.setOut(previousOut);
        }

        assertEquals(0, exit);
        assertFalse(Files.exists(config), "download denial must not write config");
        String text = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Download this model now? [y/N]"), text);
        assertTrue(text.contains("Model setup skipped. No config written."), text);
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
        assertTrue(yaml.contains("provider: \"disabled\""), yaml);
        assertTrue(yaml.contains("model: \"none\""), yaml);
        assertFalse(yaml.contains("managed:"), yaml);
        assertTrue(yaml.contains("vectors:\n    enabled: false"), yaml);
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
        assertTrue(yaml.contains("native_calling: false"));
    }

    @Test
    void knownProfileRejectsMismatchedExplicitGgufPath() {
        Path server = tempDir.resolve("llama-server.exe");
        Path model = tempDir.resolve("models").resolve("qwen2.5-coder-7b-instruct-q4_k_m.gguf");

        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> SetupCmd.renderManagedLlamaCppProfileConfig(
                        "qwen2.5-coder-14b",
                        server,
                        model,
                        tempDir.resolve(".talos").resolve("models").resolve("huggingface"),
                        18115));

        assertTrue(error.getMessage().contains("does not match profile qwen2.5-coder-14b"), error.getMessage());
        assertTrue(error.getMessage().contains("qwen2.5-coder-14b-instruct-q4_k_m.gguf"), error.getMessage());
        assertTrue(error.getMessage().contains("use a custom profile name"), error.getMessage());
    }

    @Test
    void knownProfileAcceptsMatchingExplicitGgufPathAndKeepsProfileToolMode() {
        Path server = tempDir.resolve("llama-server.exe");
        Path model = tempDir.resolve("models").resolve("qwen2.5-coder-14b-instruct-q4_k_m.gguf");

        String yaml = SetupCmd.renderManagedLlamaCppProfileConfig(
                "qwen2.5-coder-14b",
                server,
                model,
                tempDir.resolve(".talos").resolve("models").resolve("huggingface"),
                18115);

        assertTrue(yaml.contains("model: \"qwen2.5-coder-14b\""), yaml);
        assertTrue(yaml.contains("model_path: \"" + model.toString().replace('\\', '/') + "\""), yaml);
        assertTrue(yaml.contains("hf_repo: \"\""), yaml);
        assertTrue(yaml.contains("hf_file: \"\""), yaml);
        assertTrue(yaml.contains("native_calling: true"), yaml);
    }

    @Test
    void setupModelsWriteUsesStandardHuggingFaceCacheForGptOss() throws Exception {
        Path server = tempDir.resolve("llama-server.exe");
        Files.writeString(server, "fake", StandardCharsets.UTF_8);
        Path config = tempDir.resolve(".talos").resolve("config.yaml");
        Path model = standardHuggingFaceSnapshot(
                "ggml-org/gpt-oss-20b-GGUF",
                "gpt-oss-20b-mxfp4.gguf");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exit = withUserHome(tempDir, () -> {
            PrintStream previousOut = System.out;
            try {
                System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
                return new CommandLine(new SetupCmd()).execute(
                        "models",
                        "--profile", "gpt-oss-20b",
                        "--server-path", server.toString(),
                        "--write",
                        "--config", config.toString());
            } finally {
                System.setOut(previousOut);
            }
        });

        assertEquals(0, exit);
        String yaml = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("model: \"gpt-oss-20b\""), yaml);
        assertTrue(yaml.contains("model_path: \"" + model.toString().replace('\\', '/') + "\""), yaml);
        assertTrue(yaml.contains("hf_repo: \"\""), yaml);
        assertTrue(yaml.contains("hf_file: \"\""), yaml);
        String text = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Model path: " + model), text);
        assertTrue(text.contains("resolved from local Hugging Face cache"), text);
    }

    @Test
    void setupModelsWriteRefusesGptOssWithoutLocalModelSource() throws Exception {
        Path server = tempDir.resolve("llama-server.exe");
        Files.writeString(server, "fake", StandardCharsets.UTF_8);
        Path config = tempDir.resolve(".talos").resolve("config.yaml");
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = withUserHome(tempDir, () -> {
            PrintStream previousErr = System.err;
            try {
                System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
                return new CommandLine(new SetupCmd()).execute(
                        "models",
                        "--profile", "gpt-oss-20b",
                        "--server-path", server.toString(),
                        "--write",
                        "--config", config.toString());
            } finally {
                System.setErr(previousErr);
            }
        });

        assertEquals(2, exit);
        assertFalse(Files.exists(config), "unresolved GPT-OSS setup must not write a non-startable config");
        String text = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("gpt-oss-20b requires a local GGUF"), text);
        assertTrue(text.contains("--model-path"), text);
        assertTrue(text.contains("talos setup wizard"), text);
    }

    @Test
    void setupModelsWriteHonorsExplicitUserOwnedModelPath() throws Exception {
        Path server = tempDir.resolve("llama-server.exe");
        Files.writeString(server, "fake", StandardCharsets.UTF_8);
        Path model = tempDir.resolve("models").resolve("gpt-oss-20b-mxfp4.gguf");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "fake model", StandardCharsets.UTF_8);
        Path config = tempDir.resolve(".talos").resolve("config.yaml");

        int exit = new CommandLine(new SetupCmd()).execute(
                "models",
                "--profile", "gpt-oss-20b",
                "--server-path", server.toString(),
                "--model-path", model.toString(),
                "--write",
                "--config", config.toString());

        assertEquals(0, exit);
        String yaml = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("model_path: \"" + model.toString().replace('\\', '/') + "\""), yaml);
        assertTrue(yaml.contains("hf_repo: \"\""), yaml);
        assertTrue(yaml.contains("hf_file: \"\""), yaml);
    }

    @Test
    void generatedProfileConfigCanOptIntoManagedBgeM3Embeddings() {
        Path server = tempDir.resolve("engines").resolve("llama-cpp").resolve("llama-server.exe");
        Path cache = tempDir.resolve(".talos").resolve("models").resolve("huggingface");

        String yaml = SetupCmd.renderManagedLlamaCppProfileConfig(
                "qwen2.5-coder-14b",
                server,
                null,
                cache,
                18115,
                "bge-m3",
                18116);

        assertTrue(yaml.contains("provider: \"llama_cpp\""), yaml);
        assertTrue(yaml.contains("model: \"bge-m3\""), yaml);
        assertTrue(yaml.contains("host: \"http://127.0.0.1:18116\""), yaml);
        assertTrue(yaml.contains("dimensions: 1024"), yaml);
        assertTrue(yaml.contains("managed:"), yaml);
        assertTrue(yaml.contains("enabled: true"), yaml);
        assertTrue(yaml.contains("hf_repo: \"ggml-org/bge-m3-Q8_0-GGUF\""), yaml);
        assertTrue(yaml.contains("hf_file: \"bge-m3-q8_0.gguf\""), yaml);
        assertTrue(yaml.contains("pooling: \"mean\""), yaml);
        assertTrue(yaml.contains("vectors:\n    enabled: true"), yaml);
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
            cmd.profile = "qwen2.5-coder-14b";
            cmd.serverPath = server;
            cmd.write = true;
            cmd.configPath = config;

            int exit = cmd.call();

            assertEquals(0, exit);
            assertTrue(Files.readString(config, StandardCharsets.UTF_8).contains("model: \"qwen2.5-coder-14b\""));
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
                "--profile", "qwen2.5-coder-14b",
                "--server-path", server.toString(),
                "--write",
                "--config", config.toString());

        assertEquals(0, exit);
        String yaml = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("model: \"qwen2.5-coder-14b\""));
        assertTrue(yaml.contains("model_path: \"") || yaml.contains("hf_repo: \"Qwen/Qwen2.5-Coder-14B-Instruct-GGUF\""),
                yaml);
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

    private Path standardHuggingFaceSnapshot(String repo, String fileName) throws Exception {
        Path snapshot = tempDir.resolve(".cache")
                .resolve("huggingface")
                .resolve("hub")
                .resolve("models--" + repo.replace("/", "--"))
                .resolve("snapshots")
                .resolve("abc123");
        Files.createDirectories(snapshot);
        Path model = snapshot.resolve(fileName);
        Files.writeString(model, "fake model", StandardCharsets.UTF_8);
        return model.toAbsolutePath().normalize();
    }

    private static int withUserHome(Path userHome, ThrowingIntSupplier action) throws Exception {
        String previous = System.getProperty("user.home");
        try {
            System.setProperty("user.home", userHome.toString());
            return action.getAsInt();
        } finally {
            if (previous == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previous);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingIntSupplier {
        int getAsInt() throws Exception;
    }
}
