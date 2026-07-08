package dev.talos.cli.doctor;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Reports bounded local runtime and hardware facts with read-only GPU detection. */
public final class RuntimeEnvironmentProbe implements DoctorProbe {
    private static final Duration GPU_QUERY_TIMEOUT = Duration.ofSeconds(3);

    private final GpuQueryRunner gpuQueryRunner;

    public RuntimeEnvironmentProbe() {
        this(new NvidiaSmiQueryRunner());
    }

    RuntimeEnvironmentProbe(GpuQueryRunner gpuQueryRunner) {
        this.gpuQueryRunner = gpuQueryRunner == null
                ? new NvidiaSmiQueryRunner()
                : gpuQueryRunner;
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

    record GpuQueryResult(int exitCode, String stdout, String stderr) {}

    private static final class GpuProbe {
        private final GpuInfo info;

        private GpuProbe(GpuInfo info) {
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
                Optional<GpuInfo> parsed = GpuInfo.parse(result.stdout());
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
                    + " vramTotalMb=" + info.totalMb()
                    + " vramFreeMb=" + info.freeMb()
                    + " driver=" + info.driverVersion()
                    + " source=nvidia-smi";
        }
    }

    private record GpuInfo(String name, long totalMb, long freeMb, String driverVersion) {
        static Optional<GpuInfo> parse(String stdout) {
            String firstLine = Objects.toString(stdout, "").lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .findFirst()
                    .orElse("");
            if (firstLine.isBlank()) return Optional.empty();
            String[] parts = firstLine.split(",", 4);
            if (parts.length < 4) return Optional.empty();
            try {
                return Optional.of(new GpuInfo(
                        parts[0].trim(),
                        parseMb(parts[1]),
                        parseMb(parts[2]),
                        parts[3].trim()));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }

        private static long parseMb(String value) {
            String digits = Objects.toString(value, "")
                    .replace("MiB", "")
                    .replace("MB", "")
                    .trim();
            return Long.parseLong(digits);
        }
    }

    private record ServerLane(String label, boolean cuda, String requiredDriver) {
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
            if (normalized.contains("cuda-13.3") || normalized.contains("cuda13.3")
                    || normalized.contains("cuda_13.3") || normalized.contains("cuda13")) {
                return new ServerLane("cuda-13.3 (configured path)", true, "580.00");
            }
            if (normalized.contains("cuda-12.4") || normalized.contains("cuda12.4")
                    || normalized.contains("cuda_12.4") || normalized.contains("cuda12")) {
                return new ServerLane("cuda-12.4 (configured path)", true, "551.61");
            }
            if (normalized.contains("cuda")) {
                return new ServerLane("cuda (configured path)", true, "580.00");
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

    private static final class NvidiaSmiQueryRunner implements GpuQueryRunner {
        @Override
        public GpuQueryResult query() throws IOException, InterruptedException {
            IOException lastIo = null;
            for (String candidate : nvidiaSmiCandidates()) {
                try {
                    return run(candidate);
                } catch (IOException e) {
                    lastIo = e;
                }
            }
            if (lastIo != null) throw lastIo;
            return new GpuQueryResult(127, "", "nvidia-smi not found");
        }

        private static GpuQueryResult run(String command) throws IOException, InterruptedException {
            Process process = new ProcessBuilder(
                    command,
                    "--query-gpu=name,memory.total,memory.free,driver_version",
                    "--format=csv,noheader,nounits")
                    .redirectErrorStream(false)
                    .start();
            boolean done = process.waitFor(GPU_QUERY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!done) {
                process.destroyForcibly();
                return new GpuQueryResult(124, "", "nvidia-smi timed out");
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return new GpuQueryResult(process.exitValue(), stdout, stderr);
        }

        private static List<String> nvidiaSmiCandidates() {
            List<String> candidates = new ArrayList<>();
            candidates.add(isWindows() ? "nvidia-smi.exe" : "nvidia-smi");
            if (isWindows()) {
                String root = firstNonBlank(System.getenv("SystemRoot"), "C:\\Windows");
                candidates.add(Path.of(root, "System32", "nvidia-smi.exe").toString());
                Path driverStore = Path.of(root, "System32", "DriverStore", "FileRepository");
                candidates.addAll(driverStoreCandidates(driverStore));
            }
            return candidates.stream().distinct().toList();
        }

        private static List<String> driverStoreCandidates(Path root) {
            if (!Files.isDirectory(root)) return List.of();
            try (var stream = Files.find(root, 4,
                    (path, attrs) -> attrs.isRegularFile()
                            && "nvidia-smi.exe".equalsIgnoreCase(path.getFileName().toString())
                            && path.toString().toLowerCase(Locale.ROOT).contains("nvdm"))) {
                return stream
                        .sorted(Comparator.comparing(Path::toString).reversed())
                        .limit(4)
                        .map(Path::toString)
                        .toList();
            } catch (IOException e) {
                return List.of();
            }
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String text = Objects.toString(value, "").trim();
            if (!text.isBlank()) return text;
        }
        return "";
    }
}
