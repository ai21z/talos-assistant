package dev.talos.cli.doctor;

import dev.talos.cli.setup.LlamaCppEngineManifest;
import dev.talos.core.CfgUtil;
import dev.talos.core.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Reports bounded local runtime and hardware facts with read-only GPU detection. */
public final class RuntimeEnvironmentProbe implements DoctorProbe {

    private final GpuQueryRunner gpuQueryRunner;

    public RuntimeEnvironmentProbe() {
        this(defaultRunner());
    }

    RuntimeEnvironmentProbe(GpuQueryRunner gpuQueryRunner) {
        this.gpuQueryRunner = gpuQueryRunner == null
                ? defaultRunner()
                : gpuQueryRunner;
    }

    private static GpuQueryRunner defaultRunner() {
        return () -> new GpuQueryResult(NvidiaGpuQuery.query());
    }

    @Override
    public String id() {
        return "runtime-env";
    }

    @Override
    public ProbeResult run(DoctorContext ctx) {
        String os = System.getProperty("os.name", "unknown");
        String arch = System.getProperty("os.arch", "unknown");
        String java = System.getProperty("java.version", "unknown");
        int cpus = Runtime.getRuntime().availableProcessors();
        long maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        String freeMb = freeSpaceMb(ctx.talosHome());
        ServerLane lane = ServerLane.from(ctx.cfg());
        GpuProbe gpu = GpuProbe.read(gpuQueryRunner);

        String detail = "os=" + os
                + " arch=" + arch
                + " java=" + java
                + " cpu=" + cpus
                + " jvmMaxMemoryMb=" + maxMemoryMb
                + " talosHomeFreeMb=" + freeMb
                + " " + gpu.detail()
                + " serverLane=" + lane.label();

        String laneWarning = lane.warning(gpu);
        if (!laneWarning.isBlank()) {
            detail += " " + laneWarning;
        }

        return lane.warns(gpu)
                ? ProbeResult.warn(id(), detail)
                : ProbeResult.pass(id(), detail);
    }

    private static String freeSpaceMb(Path path) {
        try {
            Path existing = nearestExisting(path);
            FileStore store = Files.getFileStore(existing);
            return String.valueOf(store.getUsableSpace() / (1024L * 1024L));
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static Path nearestExisting(Path path) throws IOException {
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        Path current = path == null ? cwd : path.toAbsolutePath().normalize();
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        return current == null ? cwd : current;
    }

    @FunctionalInterface
    interface GpuQueryRunner {
        GpuQueryResult query() throws IOException, InterruptedException;
    }

    record GpuQueryResult(int exitCode, String stdout, String stderr) {
        GpuQueryResult(NvidiaGpuQuery.QueryResult result) {
            this(result.exitCode(), result.stdout(), result.stderr());
        }
    }

    private static final class GpuProbe {
        private final NvidiaGpuQuery.GpuFacts info;

        private GpuProbe(NvidiaGpuQuery.GpuFacts info) {
            this.info = info;
        }

        static GpuProbe read(GpuQueryRunner runner) {
            try {
                GpuQueryResult result = runner.query();
                if (result == null) {
                    return failed();
                }
                if (result.exitCode() != 0) {
                    return failed();
                }
                Optional<NvidiaGpuQuery.GpuFacts> parsed = NvidiaGpuQuery.parse(result.stdout());
                return parsed.<GpuProbe>map(GpuProbe::new)
                        .orElseGet(GpuProbe::failed);
            } catch (Exception e) {
                return failed();
            }
        }

        private static GpuProbe failed() {
            return new GpuProbe(null);
        }

        boolean present() {
            return info != null;
        }

        String driverVersion() {
            return info == null ? "" : info.driverVersion();
        }

        String detail() {
            if (info == null) {
                return "gpuProbe=nvidia-smi failed; assuming no GPU source=nvidia-smi";
            }
            return "gpu=nvidia-smi:" + info.name()
                    + " vramTotalMb=" + info.vramTotalMb()
                    + " vramFreeMb=" + info.vramFreeMb()
                    + " driver=" + info.driverVersion()
                    + " source=nvidia-smi";
        }
    }

    private record ServerLane(String label, boolean cuda, String requiredDriver) {
        private static final java.util.regex.Pattern CUDA_13_FAMILY =
                java.util.regex.Pattern.compile("(?<![a-z0-9])cuda[-_.]?13");
        private static final java.util.regex.Pattern CUDA_12_FAMILY =
                java.util.regex.Pattern.compile("(?<![a-z0-9])cuda[-_.]?12");
        private static final java.util.regex.Pattern CUDA_TOKEN =
                java.util.regex.Pattern.compile("(?<![a-z0-9])cuda");

        static ServerLane from(Config cfg) {
            Map<String, Object> engines = CfgUtil.map(cfg == null ? null : cfg.data.get("engines"));
            Map<String, Object> llama = CfgUtil.map(engines.get("llama_cpp"));
            String mode = Objects.toString(llama.getOrDefault("mode", "managed"), "").trim()
                    .toLowerCase(Locale.ROOT).replace('-', '_');
            String path = Objects.toString(llama.get("server_path"), "");
            String normalized = path.toLowerCase(Locale.ROOT).replace('\\', '/');
            if ("connect_only".equals(mode)) {
                return new ServerLane("connect-only (configured)", false, "");
            }
            // Driver floors are manifest metadata (T986); this probe never
            // carries its own copies. Family matching is token-anchored so a
            // path merely containing the letters cuda ("barracuda") stays on
            // the CPU lane, and any cuda-12 minor uses the 12.4 floor rather
            // than the strictest one.
            if (CUDA_13_FAMILY.matcher(normalized).find()) {
                return new ServerLane("cuda-13.3 (configured path)", true,
                        LlamaCppEngineManifest.minNvidiaDriverForBackend("cuda-13.3"));
            }
            if (CUDA_12_FAMILY.matcher(normalized).find()) {
                return new ServerLane("cuda-12.4 (configured path)", true,
                        LlamaCppEngineManifest.minNvidiaDriverForBackend("cuda-12.4"));
            }
            if (CUDA_TOKEN.matcher(normalized).find()) {
                return new ServerLane("cuda (configured path)", true,
                        LlamaCppEngineManifest.strictestCudaDriverFloor());
            }
            return new ServerLane("cpu (configured path)", false, "");
        }

        boolean warns(GpuProbe gpu) {
            if (!cuda) return false;
            if (!gpu.present()) return true;
            return compareDriver(gpu.driverVersion(), requiredDriver) < 0;
        }

        String warning(GpuProbe gpu) {
            if (cuda && !gpu.present()) {
                return "CUDA lane configured but nvidia-smi failed; driver compatibility unverified before server start";
            }
            if (cuda && compareDriver(gpu.driverVersion(), requiredDriver) < 0) {
                return "driver " + gpu.driverVersion() + " below required " + requiredDriver
                        + " for " + label.replace(" (configured path)", "");
            }
            if (!cuda && gpu.present() && label.startsWith("cpu")) {
                return "GPU present but configured llama.cpp server appears CPU-only; run talos tune or configure a CUDA lane.";
            }
            return "";
        }

        private static int compareDriver(String actual, String required) {
            List<Integer> left = parseVersion(actual);
            List<Integer> right = parseVersion(required);
            for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
                int l = i < left.size() ? left.get(i) : 0;
                int r = i < right.size() ? right.get(i) : 0;
                if (l != r) return Integer.compare(l, r);
            }
            return 0;
        }

        private static List<Integer> parseVersion(String value) {
            String[] tokens = Objects.toString(value, "").trim().split("\\.");
            List<Integer> out = new ArrayList<>();
            for (String token : tokens) {
                if (token.isBlank()) continue;
                try {
                    out.add(Integer.parseInt(token.replaceAll("[^0-9].*$", "")));
                } catch (NumberFormatException ignored) {
                    out.add(0);
                }
            }
            return out;
        }
    }
}
