package dev.talos.cli.launcher;

import dev.talos.cli.setup.LlamaCppEngineInstaller;
import dev.talos.cli.setup.LlamaCppEngineManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuneCmdTest {

    @TempDir Path home;

    private static final String CUDA_LOG = """
            load_tensors: offloaded 49/49 layers to GPU
            slot print_timing: id  0 | task 0 | eval time =     834.32 ms /    64 tokens (   13.04 ms per token,    76.71 tokens per second)
            """;

    @Test
    void missingConfigExitsWithWizardGuidanceAndWritesNothing() throws Exception {
        Run run = run("y\ny\n", 0, CUDA_LOG, "610.62", false);

        assertEquals(2, run.exit);
        assertTrue(run.output.contains("talos setup wizard"), run.output);
    }

    @Test
    void declineAtApprovalLeavesConfigUntouched() throws Exception {
        Path config = writeConfig();
        installLaneExe("win-x64-cuda-13.3");
        String before = Files.readString(config, StandardCharsets.UTF_8);

        Run run = run("n\n", 0, CUDA_LOG, "610.62", true);

        assertEquals(0, run.exit);
        assertEquals(before, Files.readString(config, StandardCharsets.UTF_8));
        assertTrue(run.output.contains("No changes made"), run.output);
        assertTrue(run.output.contains("+ "), "the exact diff must be shown before asking: " + run.output);
    }

    @Test
    void approvedTuneWritesConfigBacksUpAndVerifiesFromArtifacts() throws Exception {
        Path config = writeConfig();
        installLaneExe("win-x64-cuda-13.3");
        String before = Files.readString(config, StandardCharsets.UTF_8);

        Run run = run("y\n", 0, CUDA_LOG, "610.62", true);

        assertEquals(0, run.exit, run.output);
        String after = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(after.contains("win-x64-cuda-13.3"), after);
        assertTrue(after.contains("server_args: []"), after);
        Path backup = findBackup(config);
        assertEquals(before, Files.readString(backup, StandardCharsets.UTF_8),
                "backup must preserve the pre-tune config byte for byte");
        assertTrue(run.output.contains("Restore with"), run.output);
        assertTrue(run.output.contains("offloaded 49/49 layers"), run.output);
    }

    @Test
    void failedDoctorVerifyRestoresBackupExactly() throws Exception {
        Path config = writeConfig();
        installLaneExe("win-x64-cuda-13.3");
        String before = Files.readString(config, StandardCharsets.UTF_8);

        Run run = run("y\n", 1, CUDA_LOG, "610.62", true);

        assertEquals(1, run.exit, run.output);
        assertEquals(before, Files.readString(config, StandardCharsets.UTF_8),
                "verify failure must restore the previously approved config");
        assertTrue(run.output.contains("restored"), run.output);
    }

    @Test
    void missingOffloadEvidenceOnCudaLaneRestoresBackup() throws Exception {
        Path config = writeConfig();
        installLaneExe("win-x64-cuda-13.3");
        String before = Files.readString(config, StandardCharsets.UTF_8);

        Run run = run("y\n", 0, "srv log: no offload here\n", "610.62", true);

        assertEquals(1, run.exit, run.output);
        assertEquals(before, Files.readString(config, StandardCharsets.UTF_8));
        assertTrue(run.output.contains("offload"), run.output);
        assertTrue(run.output.contains("restored"), run.output);
    }

    @Test
    void missingLaneBinaryOffersInstallAndDeclineAbortsWithoutChanges() throws Exception {
        Path config = writeConfig();
        String before = Files.readString(config, StandardCharsets.UTF_8);

        Run run = run("n\n", 0, CUDA_LOG, "610.62", true);

        assertEquals(0, run.exit, run.output);
        assertEquals(before, Files.readString(config, StandardCharsets.UTF_8));
        assertTrue(run.output.contains("Install this pinned llama.cpp engine now?"), run.output);
        assertTrue(run.output.contains("No changes made"), run.output);
    }

    @Test
    void connectOnlyConfigIsRejectedBeforeTheInstallOffer() throws Exception {
        Path config = writeConfig();
        Files.writeString(config,
                Files.readString(config, StandardCharsets.UTF_8)
                        .replace("mode: \"managed\"", "mode: \"connect-only\""),
                StandardCharsets.UTF_8);
        String before = Files.readString(config, StandardCharsets.UTF_8);
        CountingInstaller installer = new CountingInstaller();

        Run run = run("y\ny\n", 0, CUDA_LOG, "610.62", installer);

        assertEquals(2, run.exit, run.output);
        assertEquals(0, installer.calls, "an uneditable config must be rejected before any install offer");
        assertEquals(before, Files.readString(config, StandardCharsets.UTF_8));
        assertTrue(run.output.contains("No changes made"), run.output);
    }

    @Test
    void legacyConfigWithoutContextGetsTheFullProposalApplied() throws Exception {
        Path config = writeConfig();
        Files.writeString(config,
                Files.readString(config, StandardCharsets.UTF_8)
                        .replace("    context: 8192\n", ""),
                StandardCharsets.UTF_8);
        installLaneExe("win-x64-cuda-13.3");

        Run run = run("y\n", 0, CUDA_LOG, "610.62", true);

        assertEquals(0, run.exit, run.output);
        String after = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(after.contains("context: 16384"),
                "the proposed context must actually land in a legacy config:\n" + after);
        assertTrue(after.contains("context_reason:"), after);
        assertTrue(after.contains("server_args: []"), after);
    }

    @Test
    void alreadyMatchesIsOnlyClaimedWhenTheConfigActuallyMatches() throws Exception {
        Path config = writeConfig();
        installLaneExe("win-x64-cuda-13.3");

        Run first = run("y\n", 0, CUDA_LOG, "610.62", true);
        assertEquals(0, first.exit, first.output);
        assertFalse(first.output.contains("already matches"), first.output);

        Run second = run("y\n", 0, CUDA_LOG, "610.62", true);
        assertEquals(0, second.exit, second.output);
        assertTrue(second.output.contains("already matches"),
                "a second run on the tuned config must report an honest match: " + second.output);
    }

    @Test
    void helpTextPromisesOnlyDetectProposeApproveVerifyAndCpuFallback() {
        assertTrue(TuneCmd.DESCRIPTION.contains("detect"), TuneCmd.DESCRIPTION);
        assertTrue(TuneCmd.DESCRIPTION.contains("propose"), TuneCmd.DESCRIPTION);
        assertTrue(TuneCmd.DESCRIPTION.contains("approv"), TuneCmd.DESCRIPTION);
        assertTrue(TuneCmd.DESCRIPTION.contains("verif"), TuneCmd.DESCRIPTION);
        assertTrue(TuneCmd.DESCRIPTION.contains("CPU"), TuneCmd.DESCRIPTION);
        assertFalse(TuneCmd.DESCRIPTION.toLowerCase().contains("automatic"), TuneCmd.DESCRIPTION);
    }

    private record Run(int exit, String output) {}

    private Run run(
            String input,
            int doctorExit,
            String serverLog,
            String driverVersion,
            boolean expectConfig) throws Exception {
        return run(input, doctorExit, serverLog, driverVersion, new CountingInstaller());
    }

    private Run run(
            String input,
            int doctorExit,
            String serverLog,
            String driverVersion,
            TuneCmd.EngineInstaller installer) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
        int exit = TuneCmd.run(
                home.resolve(".talos").resolve("config.yaml"),
                home,
                "Windows 11",
                "amd64",
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                out,
                () -> driverVersion.isBlank()
                        ? Optional.empty()
                        : Optional.of(new dev.talos.cli.doctor.NvidiaGpuQuery.GpuFacts(
                                "NVIDIA GeForce RTX 5070 Ti", 16_303, 15_268, driverVersion)),
                installer,
                (configPath, doctorOut) -> doctorExit,
                (talosHome, port) -> serverLog);
        return new Run(exit, stdout.toString(StandardCharsets.UTF_8));
    }

    private static final class CountingInstaller implements TuneCmd.EngineInstaller {
        int calls;

        @Override
        public LlamaCppEngineInstaller.Result install(
                LlamaCppEngineManifest.Entry entry, Path talosHome) throws Exception {
            calls++;
            Path exe = entry.installDir(talosHome).resolve(entry.executableName());
            Files.createDirectories(exe.getParent());
            Files.writeString(exe, "exe", StandardCharsets.UTF_8);
            return new LlamaCppEngineInstaller.Result(
                    LlamaCppEngineInstaller.Status.INSTALLED, exe, "Installed " + exe);
        }
    }

    private Path writeConfig() throws Exception {
        Path config = home.resolve(".talos").resolve("config.yaml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                llm:
                  transport: "engine"
                  default_backend: "llama_cpp"
                  model: "qwen2.5-coder-14b"

                engines:
                  llama_cpp:
                    mode: "managed"
                    server_path: "%s"
                    model_path: ""
                    hf_repo: "Qwen/Qwen2.5-Coder-14B-Instruct-GGUF"
                    hf_file: "qwen2.5-coder-14b-instruct-q4_k_m.gguf"
                    hf_cache_dir: ""
                    model: "qwen2.5-coder-14b"
                    host: "http://127.0.0.1"
                    port: 18115
                    context: 8192
                    jinja: true
                    server_args: []

                tools:
                  native_calling: true
                """.formatted(home.resolve("old-cpu").resolve("llama-server.exe")
                        .toString().replace('\\', '/')),
                StandardCharsets.UTF_8);
        return config;
    }

    private void installLaneExe(String variant) throws Exception {
        LlamaCppEngineManifest.Entry entry = LlamaCppEngineManifest.entries().stream()
                .filter(candidate -> candidate.variant().equals(variant))
                .findFirst()
                .orElseThrow();
        Path exe = entry.installDir(home).resolve(entry.executableName());
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, "exe", StandardCharsets.UTF_8);
    }

    private static Path findBackup(Path config) throws Exception {
        try (var stream = Files.list(config.getParent())) {
            return stream
                    .filter(path -> path.getFileName().toString().startsWith("config.yaml.bak-"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected a timestamped backup"));
        }
    }
}
