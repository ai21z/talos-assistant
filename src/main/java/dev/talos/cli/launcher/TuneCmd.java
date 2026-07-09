package dev.talos.cli.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.talos.cli.doctor.NvidiaGpuQuery;
import dev.talos.cli.setup.LlamaCppEngineInstaller;
import dev.talos.cli.setup.LlamaCppEngineManifest;
import dev.talos.cli.tune.TuneConfigEditor;
import dev.talos.cli.tune.TunePlanner;
import dev.talos.cli.tune.TuneVerifier;
import dev.talos.core.Config;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code talos tune} (T987): detect, propose, approve, verify.
 *
 * <p>Fail-safe posture is restore-backup-only: tune never writes a config
 * shape the user did not preview, and a failed verify restores the
 * timestamped backup byte for byte. Talos does no layer-placement math
 * anywhere here; llama.cpp's fit owns placement.
 */
@CommandLine.Command(
        name = "tune",
        mixinStandardHelpOptions = true,
        description = TuneCmd.DESCRIPTION)
public final class TuneCmd implements java.util.concurrent.Callable<Integer> {

    public static final String DESCRIPTION =
            "Hardware config tune: detects the machine read-only, proposes an exact config diff, "
                    + "applies it only after approval with a timestamped backup, then verifies GPU offload "
                    + "and rates from server-log evidence; falls back to the CPU lane when evidence is missing.";

    @CommandLine.Option(names = "--config", description = "Path to config.yaml (default: ~/.talos/config.yaml)")
    Path configPath;

    @FunctionalInterface
    public interface GpuFactsSupplier {
        Optional<NvidiaGpuQuery.GpuFacts> get();
    }

    @FunctionalInterface
    public interface EngineInstaller {
        LlamaCppEngineInstaller.Result install(LlamaCppEngineManifest.Entry entry, Path userHome) throws Exception;
    }

    @FunctionalInterface
    public interface DoctorRunner {
        int run(Path configPath, PrintStream out);
    }

    @FunctionalInterface
    public interface ServerLogReader {
        String read(Path talosHome, int port);
    }

    @Override
    public Integer call() {
        Path userHome = Path.of(System.getProperty("user.home", "."));
        Path config = configPath != null
                ? configPath
                : userHome.resolve(".talos").resolve("config.yaml");
        return run(
                config,
                userHome,
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown"),
                System.in,
                System.out,
                NvidiaGpuQuery::read,
                new LlamaCppEngineInstaller()::install,
                (writtenConfig, out) -> DoctorCmd.run(
                        new Config(writtenConfig),
                        DoctorCmd.resolveWorkspace(null),
                        userHome.resolve(".talos").toAbsolutePath().normalize(),
                        true,
                        out),
                TuneCmd::readManagedServerLog);
    }

    static int run(
            Path configPath,
            Path userHome,
            String osName,
            String osArch,
            InputStream input,
            PrintStream out,
            GpuFactsSupplier gpuFacts,
            EngineInstaller installer,
            DoctorRunner doctorRunner,
            ServerLogReader logReader) {
        try {
            out.println("Talos tune: detect, propose, approve, verify. Detection is read-only.");
            if (configPath == null || !Files.isRegularFile(configPath)) {
                out.println("No config found at " + configPath + ".");
                out.println("Run `talos setup wizard` first; tune adjusts an existing approved config.");
                return 2;
            }
            String yaml = Files.readString(configPath, StandardCharsets.UTF_8);

            Optional<NvidiaGpuQuery.GpuFacts> gpu = gpuFacts.get();
            TunePlanner.Facts facts = new TunePlanner.Facts(
                    osName,
                    osArch,
                    false,
                    gpu.map(NvidiaGpuQuery.GpuFacts::name).orElse(""),
                    gpu.map(NvidiaGpuQuery.GpuFacts::driverVersion).orElse(""),
                    gpu.map(NvidiaGpuQuery.GpuFacts::vramTotalMb).orElse(-1L),
                    systemMemoryMb(),
                    Runtime.getRuntime().availableProcessors(),
                    configuredProfile(yaml),
                    configuredModelFileSize(yaml));

            Optional<TunePlanner.Proposal> planned = TunePlanner.plan(
                    facts, userHome, Files::isRegularFile);
            if (planned.isEmpty()) {
                out.println("No pinned engine lane exists for this platform yet. No changes made.");
                return 2;
            }
            TunePlanner.Proposal proposal = planned.get();

            out.println();
            out.println("Detected (read-only):");
            proposal.detectionSummary().lines().forEach(line -> out.println("  " + line));
            out.println();
            out.println("Proposal:");
            out.println("  engine lane: " + proposal.lane().variant()
                    + " (llama.cpp " + proposal.lane().upstreamTag() + ")");
            out.println("  server path: " + proposal.serverPath());
            out.println("  context: " + proposal.context().context()
                    + " (" + proposal.context().reason() + ")");
            out.println("  server_args: [] (llama.cpp auto-fit owns GPU placement; Talos passes no layer flags)");
            out.println("  " + proposal.expectedSpeedLine());

            Path serverPath = proposal.serverPath();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            if (!proposal.laneInstalled()) {
                LlamaCppEngineManifest.Entry lane = proposal.lane();
                out.println();
                out.println("The selected lane's binary is not installed yet:");
                out.println("  tag: " + lane.upstreamTag());
                out.println("  asset: " + lane.assetName());
                out.println("  SHA-256: " + lane.sha256());
                if (lane.companion() != null) {
                    out.println("  driver runtime asset: " + lane.companion().assetName());
                    out.println("  driver runtime SHA-256: " + lane.companion().sha256());
                }
                if (!askYes(reader, out, "Install this pinned llama.cpp engine now? [y/N] ")) {
                    out.println("No changes made.");
                    return 0;
                }
                LlamaCppEngineInstaller.Result install = installer.install(lane, userHome);
                out.println(install.message());
                if (install.status() == LlamaCppEngineInstaller.Status.FAILED) {
                    out.println("Engine install failed; no changes made.");
                    return 1;
                }
                serverPath = install.serverPath();
            }

            TuneConfigEditor.Edit edit = TuneConfigEditor.propose(
                    yaml, serverPath, proposal.context().context(), proposal.context().reason());
            if (edit.diff().isBlank()) {
                out.println();
                out.println("Config already matches the proposal. No changes needed.");
                return 0;
            }
            out.println();
            out.println("Proposed config change (exact diff):");
            edit.diff().lines().forEach(out::println);
            if (!askYes(reader, out, "Apply this config change? [y/N] ")) {
                out.println("No changes made.");
                return 0;
            }

            Path backup = configPath.resolveSibling(
                    configPath.getFileName() + ".bak-" + safeTimestamp());
            Files.copy(configPath, backup);
            atomicWrite(configPath, edit.updatedYaml());
            out.println("Backed up previous config to " + backup);
            out.println("Restore with: copy the backup over " + configPath
                    + " (byte-identical to the pre-tune config).");

            out.println();
            out.println("Verifying: running talos doctor --start against the new config.");
            int doctorExit = doctorRunner.run(configPath, out);
            int port = TuneConfigEditor.configuredPort(edit.updatedYaml(), 18115);
            String serverLog = logReader.read(userHome.resolve(".talos"), port);
            boolean cudaLane = proposal.lane().backend().startsWith("cuda");
            TuneVerifier.Result verify = TuneVerifier.verify(serverLog, cudaLane, doctorExit);

            if (!verify.passed()) {
                Files.copy(backup, configPath, StandardCopyOption.REPLACE_EXISTING);
                out.println("Verify failed: " + verify.summary() + ".");
                out.println("The previous config was restored from " + backup + ".");
                return 1;
            }
            out.println("Verified: " + verify.summary() + ".");
            if (verify.spillSuspected()) {
                out.println("Warning: generation speed is far below the lane's expected class. "
                        + "The config was kept because offload evidence is real, but investigate "
                        + "VRAM pressure before relying on this lane.");
            }
            return 0;
        } catch (Exception error) {
            out.println("tune failed: " + safeMessage(error));
            return 2;
        }
    }

    private static String readManagedServerLog(Path talosHome, int port) {
        try {
            Path logPath = talosHome.resolve("logs").resolve("llama_cpp-" + port + ".log");
            return Files.isRegularFile(logPath)
                    ? Files.readString(logPath, StandardCharsets.UTF_8)
                    : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void atomicWrite(Path target, String content) throws Exception {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + safeTimestamp());
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception atomicRefused) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String configuredProfile(String yaml) {
        try {
            Object parsed = new ObjectMapper(new YAMLFactory()).readValue(yaml, Object.class);
            if (parsed instanceof Map<?, ?> root) {
                Object llm = root.get("llm");
                if (llm instanceof Map<?, ?> llmMap) {
                    String model = Objects.toString(llmMap.get("model"), "");
                    if (!model.isBlank()) return model;
                }
            }
        } catch (Exception ignored) {
            // fall through to empty profile; the context selector stays at 8192
        }
        return "";
    }

    private static long configuredModelFileSize(String yaml) {
        try {
            Object parsed = new ObjectMapper(new YAMLFactory()).readValue(yaml, Object.class);
            if (parsed instanceof Map<?, ?> root
                    && root.get("engines") instanceof Map<?, ?> engines
                    && engines.get("llama_cpp") instanceof Map<?, ?> llama) {
                String modelPath = Objects.toString(llama.get("model_path"), "");
                if (!modelPath.isBlank()) {
                    Path path = Path.of(modelPath);
                    if (Files.isRegularFile(path)) {
                        return Files.size(path);
                    }
                }
            }
        } catch (Exception ignored) {
            // unknown size keeps the estimate line honest
        }
        return -1;
    }

    private static long systemMemoryMb() {
        try {
            OperatingSystemMXBean mx = ManagementFactory.getOperatingSystemMXBean();
            if (mx instanceof com.sun.management.OperatingSystemMXBean sun) {
                long bytes = sun.getTotalMemorySize();
                return bytes > 0 ? bytes / (1024 * 1024) : -1;
            }
        } catch (RuntimeException ignored) {
            return -1;
        }
        return -1;
    }

    private static boolean askYes(BufferedReader reader, PrintStream out, String prompt) throws Exception {
        out.print(prompt);
        String line = reader.readLine();
        String answer = line == null ? "" : line.trim();
        out.println(answer);
        return answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes");
    }

    private static String safeTimestamp() {
        return Instant.now().toString().replace(":", "").replace(".", "");
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message.replace('\n', ' ');
    }
}
