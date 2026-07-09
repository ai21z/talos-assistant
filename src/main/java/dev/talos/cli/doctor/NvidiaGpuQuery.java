package dev.talos.cli.doctor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Shared read-only NVIDIA GPU detection primitive (T992).
 *
 * <p>Single owner of the nvidia-smi query, its Windows resolution order
 * (PATH, System32, then DriverStore), and the assume-no-GPU degradation.
 * The doctor probe, setup wizard, and tune flow all consume this class
 * instead of re-implementing detection. WMI {@code AdapterRAM} is banned
 * as a source: it caps at 4 GB and misreports modern cards.
 */
public final class NvidiaGpuQuery {
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(3);

    private NvidiaGpuQuery() {}

    /** Facts read directly from nvidia-smi. Absent means "assume no GPU". */
    public record GpuFacts(String name, long vramTotalMb, long vramFreeMb, String driverVersion) {}

    /** Runs nvidia-smi with the standard resolution order. Absent on any failure. */
    public static Optional<GpuFacts> read() {
        try {
            QueryResult result = query();
            if (result == null || result.exitCode() != 0) {
                return Optional.empty();
            }
            return parse(result.stdout());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Parses the first GPU line of {@code --query-gpu=name,memory.total,memory.free,driver_version}. */
    public static Optional<GpuFacts> parse(String stdout) {
        String firstLine = Objects.toString(stdout, "").lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("");
        if (firstLine.isBlank()) {
            return Optional.empty();
        }
        String[] parts = firstLine.split(",", 4);
        if (parts.length < 4) {
            return Optional.empty();
        }
        try {
            return Optional.of(new GpuFacts(
                    parts[0].trim(),
                    parseMb(parts[1]),
                    parseMb(parts[2]),
                    parts[3].trim()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    record QueryResult(int exitCode, String stdout, String stderr) {}

    static QueryResult query() throws IOException, InterruptedException {
        IOException lastIo = null;
        for (String candidate : nvidiaSmiCandidates()) {
            try {
                return run(candidate);
            } catch (IOException e) {
                lastIo = e;
            }
        }
        if (lastIo != null) throw lastIo;
        return new QueryResult(127, "", "nvidia-smi not found");
    }

    private static long parseMb(String value) {
        String digits = Objects.toString(value, "")
                .replace("MiB", "")
                .replace("MB", "")
                .trim();
        return Long.parseLong(digits);
    }

    private static QueryResult run(String command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                command,
                "--query-gpu=name,memory.total,memory.free,driver_version",
                "--format=csv,noheader,nounits")
                .redirectErrorStream(false)
                .start();
        boolean done = process.waitFor(QUERY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!done) {
            process.destroyForcibly();
            return new QueryResult(124, "", "nvidia-smi timed out");
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new QueryResult(process.exitValue(), stdout, stderr);
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String text = Objects.toString(value, "").trim();
            if (!text.isBlank()) return text;
        }
        return "";
    }
}
