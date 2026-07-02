package dev.talos.cli.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupWizardPlannerTest {

    @TempDir Path tempDir;

    @Test
    void freshUbuntuWslPlanIsDryRunOnlyAndQueuesManualChoices() {
        SetupWizardSnapshot snapshot = new SetupWizardSnapshot(
                "Linux",
                "amd64",
                true,
                "Ubuntu 26.04 LTS",
                21,
                Path.of("/home/ai21z/.talos/config.yaml"),
                false,
                null,
                false,
                512_000,
                16_384);

        SetupWizardPlan plan = SetupWizardPlanner.plan(snapshot);

        assertFalse(plan.hasSideEffects(), "dry-run planner must not select side effects");
        assertEquals(SetupWizardStep.Action.SKIP, plan.requiredStep("java").action());
        assertEquals(SetupWizardStep.Action.ASK, plan.requiredStep("config").action());
        assertEquals(SetupWizardStep.Action.ASK, plan.requiredStep("llama-server").action());
        assertEquals(SetupWizardStep.Action.ASK, plan.requiredStep("model-profile").action());
        assertTrue(plan.requiredStep("llama-server").detail().contains("pinned"));
        assertTrue(plan.requiredStep("llama-server").detail().contains("b9860"));
        assertTrue(plan.requiredStep("llama-server").detail().contains("ubuntu-x64-cpu"));
        assertTrue(plan.requiredStep("llama-server").detail().contains("SHA-256"));
        assertTrue(plan.requiredStep("model-profile").detail().contains("qwen2.5-coder-14b"));
        assertTrue(plan.requiredStep("model-profile").detail().contains("gpt-oss-20b"));
        assertTrue(plan.requiredStep("verification").detail().contains("talos doctor --start"));

        String dryRun = SetupWizardRenderer.render(plan);
        assertTrue(dryRun.contains("b9860"), dryRun);
        assertTrue(dryRun.contains("llama-b9860-bin-ubuntu-x64.tar.gz"), dryRun);
        assertTrue(dryRun.contains("SHA-256"), dryRun);
        assertFalse(dryRun.contains("latest"), dryRun);
    }

    @Test
    void existingCompatibleRuntimeStateIsDetectedAsReuseOrSkip() {
        SetupWizardSnapshot snapshot = new SetupWizardSnapshot(
                "Linux",
                "amd64",
                true,
                "Ubuntu 26.04 LTS",
                21,
                Path.of("/home/ai21z/.talos/config.yaml"),
                true,
                Path.of("/home/ai21z/.local/bin/llama-server"),
                true,
                512_000,
                16_384);

        SetupWizardPlan plan = SetupWizardPlanner.plan(snapshot);

        assertEquals(SetupWizardStep.Action.SKIP, plan.requiredStep("java").action());
        assertEquals(SetupWizardStep.Action.REUSE_OR_ASK, plan.requiredStep("config").action());
        assertEquals(SetupWizardStep.Action.REUSE_OR_ASK, plan.requiredStep("llama-server").action());
        assertTrue(plan.requiredStep("config").detail().contains("Existing config detected"));
        assertTrue(plan.requiredStep("llama-server").detail().contains("Linux-compatible llama-server detected"));
    }

    @Test
    void windowsExecutableVisibleFromWslIsNotAcceptedAsCompatibleServer() {
        SetupWizardSnapshot snapshot = new SetupWizardSnapshot(
                "Linux",
                "amd64",
                true,
                "Ubuntu 26.04 LTS",
                21,
                Path.of("/home/ai21z/.talos/config.yaml"),
                true,
                Path.of("/mnt/c/Tools/llama-server.exe"),
                true,
                512_000,
                16_384);

        SetupWizardPlan plan = SetupWizardPlanner.plan(snapshot);

        assertEquals(SetupWizardStep.Action.BLOCK_OR_ASK, plan.requiredStep("llama-server").action());
        assertTrue(plan.requiredStep("llama-server").detail().contains("Windows .exe"));
        assertTrue(plan.requiredStep("llama-server").detail().contains("Linux-compatible"));
    }

    @Test
    void interactiveWizardDenialAtFinalWriteCreatesNoConfig() throws Exception {
        Path server = touch("llama-server");
        Path config = tempDir.resolve(".talos").resolve("config.yaml");
        SetupWizardPlan plan = SetupWizardPlanner.plan(snapshot(config, false, server, true, true));

        SetupWizardRunner.Result result = run(plan, "y\n1\nn\n");

        assertEquals(0, result.exitCode());
        assertFalse(result.wroteConfig());
        assertFalse(Files.exists(config), "final write denial must leave config absent");
    }

    @Test
    void interactiveWizardWritesSelectedProfileAfterExplicitConfirmations() throws Exception {
        Path server = touch("llama-server");
        Path config = tempDir.resolve(".talos").resolve("config.yaml");
        SetupWizardPlan plan = SetupWizardPlanner.plan(snapshot(config, false, server, true, true));

        SetupWizardRunner.Result result = run(plan, "y\n2\ny\n");

        assertEquals(0, result.exitCode());
        assertTrue(result.wroteConfig());
        String yaml = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("model: \"gpt-oss-20b\""), yaml);
        assertTrue(yaml.contains("server_path: \"" + server.toAbsolutePath().normalize().toString().replace('\\', '/') + "\""), yaml);
        assertTrue(yaml.contains("hf_repo: \"ggml-org/gpt-oss-20b-GGUF\""), yaml);
    }

    @Test
    void interactiveWizardBacksUpExistingConfigBeforeOverwrite() throws Exception {
        Path server = touch("llama-server");
        Path config = tempDir.resolve(".talos").resolve("config.yaml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "existing: true\n", StandardCharsets.UTF_8);
        SetupWizardPlan plan = SetupWizardPlanner.plan(snapshot(config, true, server, true, true));

        SetupWizardRunner.Result result = run(plan, "y\n1\ny\n");

        assertEquals(0, result.exitCode());
        assertTrue(result.wroteConfig());
        assertTrue(Files.readString(config, StandardCharsets.UTF_8).contains("model: \"qwen2.5-coder-14b\""));
        try (var stream = Files.list(config.getParent())) {
            Path backup = stream
                    .filter(path -> path.getFileName().toString().startsWith("config.yaml.bak-"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("existing: true\n", Files.readString(backup, StandardCharsets.UTF_8));
            assertEquals(backup, result.backupPath());
        }
    }

    @Test
    void interactiveWizardRejectsWindowsExeServerInWslAndSkipsWhenNoReplacement() throws Exception {
        Path server = touch("llama-server.exe");
        Path config = tempDir.resolve(".talos").resolve("config.yaml");
        SetupWizardPlan plan = SetupWizardPlanner.plan(snapshot(config, false, server, true, true));

        SetupWizardRunner.Result result = run(plan, "\n");

        assertEquals(0, result.exitCode());
        assertFalse(result.wroteConfig());
        assertFalse(Files.exists(config), "rejected WSL .exe server must not produce config");
        assertTrue(result.output().contains("not Linux-compatible"), result.output());
    }

    @Test
    void interactiveWizardDeniesPinnedEngineInstallWithoutSideEffects() throws Exception {
        Path config = tempDir.resolve(".talos").resolve("config.yaml");
        SetupWizardPlan plan = SetupWizardPlanner.plan(snapshot(config, false, null, false, true));
        FakeEngineInstaller installer = new FakeEngineInstaller();

        SetupWizardRunner.Result result = run(plan, "n\n\n", installer);

        assertEquals(0, result.exitCode());
        assertEquals(0, installer.calls);
        assertFalse(result.wroteConfig());
        assertFalse(Files.exists(config), "engine denial and path skip must leave config absent");
        assertTrue(result.output().contains("Model setup skipped"), result.output());
    }

    @Test
    void defaultRunnerUsesUserHomeAsManifestBaseWithoutDoubleTalosHome() throws Exception {
        Path config = tempDir.resolve(".talos").resolve("config.yaml");
        SetupWizardPlan plan = SetupWizardPlanner.plan(snapshot(config, false, null, false, true));
        String previousHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());

            SetupWizardRunner.Result result = run(plan, "n\n\n");

            String normalized = result.output().replace('\\', '/');
            assertTrue(normalized.contains(tempDir.resolve(".talos").resolve("engines").toString().replace('\\', '/')),
                    result.output());
            assertFalse(normalized.contains(".talos/.talos/engines"), result.output());
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void interactiveWizardInstallsPinnedEngineThenWritesConfigAfterConfirmation() throws Exception {
        Path config = tempDir.resolve(".talos").resolve("config.yaml");
        Path installedServer = tempDir.resolve(".talos")
                .resolve("engines")
                .resolve("llama.cpp")
                .resolve("b9860")
                .resolve("ubuntu-x64-cpu")
                .resolve("bin")
                .resolve("llama-server");
        SetupWizardPlan plan = SetupWizardPlanner.plan(snapshot(config, false, null, false, true));
        FakeEngineInstaller installer = new FakeEngineInstaller(installedServer);

        SetupWizardRunner.Result result = run(plan, "y\n1\ny\n", installer);

        assertEquals(0, result.exitCode());
        assertEquals(1, installer.calls);
        assertTrue(result.wroteConfig());
        String yaml = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("server_path: \"" + installedServer.toAbsolutePath().normalize().toString().replace('\\', '/') + "\""), yaml);
        assertTrue(result.output().contains("Installed pinned llama.cpp engine"), result.output());
        assertTrue(result.output().contains("No model downloads"), result.output());
    }

    private SetupWizardRunner.Result run(SetupWizardPlan plan, String input) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        return SetupWizardRunner.run(
                plan,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                this::renderConfig,
                tempDir.resolve(".talos").resolve("models").resolve("huggingface"),
                18115);
    }

    private SetupWizardRunner.Result run(
            SetupWizardPlan plan,
            String input,
            SetupWizardRunner.EngineInstaller installer) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        return SetupWizardRunner.run(
                plan,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                this::renderConfig,
                tempDir.resolve(".talos").resolve("models").resolve("huggingface"),
                18115,
                tempDir,
                installer);
    }

    private String renderConfig(String profile, Path server, Path cacheDir, int port) {
        return """
                llm:
                  default_backend: "llama_cpp"
                  model: "%s"

                engines:
                  llama_cpp:
                    server_path: "%s"
                    hf_repo: "%s"
                """.formatted(
                profile,
                server.toAbsolutePath().normalize().toString().replace('\\', '/'),
                "gpt-oss-20b".equals(profile) ? "ggml-org/gpt-oss-20b-GGUF" : "Qwen/Qwen2.5-Coder-14B-Instruct-GGUF");
    }

    private Path touch(String name) throws Exception {
        Path path = tempDir.resolve("bin").resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "fake", StandardCharsets.UTF_8);
        return path;
    }

    private SetupWizardSnapshot snapshot(Path config, boolean configExists, Path server, boolean serverExists, boolean wsl) {
        return new SetupWizardSnapshot(
                "Linux",
                "amd64",
                wsl,
                "Ubuntu 26.04 LTS",
                21,
                config,
                configExists,
                server,
                serverExists,
                512_000,
                16_384);
    }

    private static final class FakeEngineInstaller implements SetupWizardRunner.EngineInstaller {
        private final Path serverPath;
        int calls;

        FakeEngineInstaller() {
            this(null);
        }

        FakeEngineInstaller(Path serverPath) {
            this.serverPath = serverPath;
        }

        @Override
        public LlamaCppEngineInstaller.Result install(LlamaCppEngineManifest.Entry entry, Path talosHome) throws Exception {
            calls++;
            if (serverPath == null) {
                return new LlamaCppEngineInstaller.Result(
                        LlamaCppEngineInstaller.Status.FAILED,
                        null,
                        "fake failure");
            }
            Files.createDirectories(serverPath.getParent());
            Files.writeString(serverPath, "server", StandardCharsets.UTF_8);
            return new LlamaCppEngineInstaller.Result(
                    LlamaCppEngineInstaller.Status.INSTALLED,
                    serverPath.toAbsolutePath().normalize(),
                    "Installed pinned llama.cpp engine at " + serverPath.getParent());
        }
    }
}
