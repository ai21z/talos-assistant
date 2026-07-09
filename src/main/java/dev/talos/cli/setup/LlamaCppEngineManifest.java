package dev.talos.cli.setup;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Pinned, SHA-256-verified llama.cpp engine lanes the wizard may offer.
 *
 * <p>Windows lanes pin llama.cpp {@code b9918}, the release the CUDA lane
 * evidence was measured on (talos-performance-review-2026-07-08). The Ubuntu
 * lane deliberately stays on {@code b9860}: it is the already-validated
 * shipping lane and this arc had no Linux verification rig, so bumping it
 * would be an unverified change (mixed tags, recorded reason, T986).
 *
 * <p>Driver floors are manifest metadata and live only here. Captured at
 * implementation time (2026-07-09) from NVIDIA's documentation: CUDA 12.4 GA
 * requires Windows driver {@code >= 551.61} (CUDA Toolkit release notes,
 * "CUDA Toolkit and Corresponding Driver Versions"), and the CUDA 13.x family
 * requires driver {@code >= 580} (CUDA compatibility guide, minor-version
 * compatibility; the toolkit table carries no Windows rows for 13.1+ because
 * the display driver is no longer bundled). CUDA lanes are gated on detected
 * driver evidence, never on GPU presence alone; no evidence selects CPU.
 */
public final class LlamaCppEngineManifest {
    private LlamaCppEngineManifest() {}

    private static final Entry UBUNTU_X64_CPU = new Entry(
            "ubuntu-x64-cpu",
            "Linux",
            "Ubuntu",
            "x64",
            "cpu",
            "b9860",
            "llama-b9860-bin-ubuntu-x64.tar.gz",
            URI.create("https://github.com/ggml-org/llama.cpp/releases/download/b9860/llama-b9860-bin-ubuntu-x64.tar.gz"),
            "b68e8072eb88d1cc8b8e9d6ea8237aae87b34c6d8bbffda958c870e4dc949714",
            15_851_454L,
            Path.of(".talos", "engines", "llama.cpp", "b9860", "ubuntu-x64-cpu"),
            "llama-server");

    private static final Entry WIN_X64_CPU = new Entry(
            "win-x64-cpu",
            "Windows",
            "",
            "x64",
            "cpu",
            "b9918",
            "llama-b9918-bin-win-cpu-x64.zip",
            URI.create("https://github.com/ggml-org/llama.cpp/releases/download/b9918/llama-b9918-bin-win-cpu-x64.zip"),
            "3324814ec61b79b218d580f00501c7a575a46f160a555aea23b44d549f395412",
            17_498_395L,
            Path.of(".talos", "engines", "llama.cpp", "b9918", "win-x64-cpu"),
            "llama-server.exe");

    private static final Entry WIN_X64_CUDA_12_4 = new Entry(
            "win-x64-cuda-12.4",
            "Windows",
            "",
            "x64",
            "cuda-12.4",
            "b9918",
            "llama-b9918-bin-win-cuda-12.4-x64.zip",
            URI.create("https://github.com/ggml-org/llama.cpp/releases/download/b9918/llama-b9918-bin-win-cuda-12.4-x64.zip"),
            "ff695bee95d374221357089e852f74fa38645247cdfd292f13e133e1ddfb6987",
            266_285_781L,
            Path.of(".talos", "engines", "llama.cpp", "b9918", "win-x64-cuda-12.4"),
            "llama-server.exe",
            "551.61",
            new CompanionAsset(
                    "cudart-llama-bin-win-cuda-12.4-x64.zip",
                    URI.create("https://github.com/ggml-org/llama.cpp/releases/download/b9918/cudart-llama-bin-win-cuda-12.4-x64.zip"),
                    "8c79a9b226de4b3cacfd1f83d24f962d0773be79f1e7b75c6af4ded7e32ae1d6",
                    391_443_627L));

    private static final Entry WIN_X64_CUDA_13_3 = new Entry(
            "win-x64-cuda-13.3",
            "Windows",
            "",
            "x64",
            "cuda-13.3",
            "b9918",
            "llama-b9918-bin-win-cuda-13.3-x64.zip",
            URI.create("https://github.com/ggml-org/llama.cpp/releases/download/b9918/llama-b9918-bin-win-cuda-13.3-x64.zip"),
            "d5d786b93b683da2a902b1e62c43ee251ce15837a6d0ea19393be3e84a8dcb87",
            161_407_586L,
            Path.of(".talos", "engines", "llama.cpp", "b9918", "win-x64-cuda-13.3"),
            "llama-server.exe",
            "580.00",
            new CompanionAsset(
                    "cudart-llama-bin-win-cuda-13.3-x64.zip",
                    URI.create("https://github.com/ggml-org/llama.cpp/releases/download/b9918/cudart-llama-bin-win-cuda-13.3-x64.zip"),
                    "1462a050eb4c684921ba51dcc4cc488a036674c3e73e9945ee705b854808d03e",
                    390_970_417L));

    private static final List<Entry> ENTRIES = List.of(
            UBUNTU_X64_CPU,
            WIN_X64_CPU,
            WIN_X64_CUDA_12_4,
            WIN_X64_CUDA_13_3);

    /** All pinned lanes, for provenance rendering and docs honesty checks. */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static Optional<Entry> select(SetupWizardSnapshot snapshot) {
        if (snapshot == null) {
            return Optional.empty();
        }
        if (!isX64(snapshot.osArch())) {
            return Optional.empty();
        }
        if (contains(snapshot.osName(), "win") && !snapshot.wsl()) {
            return Optional.of(selectWindowsLane(snapshot.gpuDriverVersion()));
        }
        if (!contains(snapshot.osName(), "linux")) {
            return Optional.empty();
        }
        if (!contains(snapshot.distroName(), "ubuntu")) {
            return Optional.empty();
        }
        return Optional.of(UBUNTU_X64_CPU);
    }

    /**
     * Windows lane choice is gated on detected driver evidence, never GPU
     * presence alone. No evidence, unparseable evidence, or a driver below
     * every CUDA floor all fail safe to the CPU lane.
     */
    private static Entry selectWindowsLane(String detectedDriver) {
        if (driverAtLeast(detectedDriver, WIN_X64_CUDA_13_3.minNvidiaDriver())) {
            return WIN_X64_CUDA_13_3;
        }
        if (driverAtLeast(detectedDriver, WIN_X64_CUDA_12_4.minNvidiaDriver())) {
            return WIN_X64_CUDA_12_4;
        }
        return WIN_X64_CPU;
    }

    /** Driver floor for an exact backend id, or empty when the backend has none. */
    public static String minNvidiaDriverForBackend(String backend) {
        String normalized = backend == null ? "" : backend.trim().toLowerCase(Locale.ROOT);
        for (Entry entry : ENTRIES) {
            if (entry.backend().equals(normalized) && !entry.minNvidiaDriver().isBlank()) {
                return entry.minNvidiaDriver();
            }
        }
        return "";
    }

    /** The strictest pinned CUDA floor, for unknown CUDA variants. */
    public static String strictestCudaDriverFloor() {
        String strictest = "";
        for (Entry entry : ENTRIES) {
            if (!entry.backend().startsWith("cuda") || entry.minNvidiaDriver().isBlank()) {
                continue;
            }
            if (strictest.isBlank() || compareDriverVersions(entry.minNvidiaDriver(), strictest) > 0) {
                strictest = entry.minNvidiaDriver();
            }
        }
        return strictest;
    }

    /** True only when {@code detected} parses and is at or above {@code floor}. */
    public static boolean driverAtLeast(String detected, String floor) {
        String actual = detected == null ? "" : detected.trim();
        if (actual.isBlank() || floor == null || floor.isBlank()) {
            return false;
        }
        if (!actual.matches("[0-9]+(\\.[0-9]+)*")) {
            return false;
        }
        return compareDriverVersions(actual, floor) >= 0;
    }

    static int compareDriverVersions(String left, String right) {
        String[] a = safeVersion(left).split("\\.");
        String[] b = safeVersion(right).split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int l = i < a.length ? parseSegment(a[i]) : 0;
            int r = i < b.length ? parseSegment(b[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static String safeVersion(String value) {
        return value == null ? "" : value.trim();
    }

    private static int parseSegment(String token) {
        try {
            return Integer.parseInt(token.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static boolean isX64(String arch) {
        String normalized = arch == null ? "" : arch.toLowerCase(Locale.ROOT);
        return normalized.equals("amd64") || normalized.equals("x86_64") || normalized.equals("x64");
    }

    /** Separate upstream archive that must be verified and installed beside the server. */
    public record CompanionAsset(
            String assetName,
            URI url,
            String sha256,
            long sizeBytes) {
    }

    public record Entry(
            String variant,
            String os,
            String distroFamily,
            String arch,
            String backend,
            String upstreamTag,
            String assetName,
            URI url,
            String sha256,
            long sizeBytes,
            Path installSubdir,
            String executableName,
            String minNvidiaDriver,
            CompanionAsset companion) {

        public Entry {
            minNvidiaDriver = minNvidiaDriver == null ? "" : minNvidiaDriver.trim();
        }

        /** Pre-T986 shape: CPU lane with no driver floor and no companion archive. */
        public Entry(
                String variant,
                String os,
                String distroFamily,
                String arch,
                String backend,
                String upstreamTag,
                String assetName,
                URI url,
                String sha256,
                long sizeBytes,
                Path installSubdir,
                String executableName) {
            this(variant, os, distroFamily, arch, backend, upstreamTag, assetName, url, sha256,
                    sizeBytes, installSubdir, executableName, "", null);
        }

        public Path installDir(Path home) {
            Path base = home == null ? Path.of(System.getProperty("user.home", ".")) : home;
            return base.resolve(installSubdir).toAbsolutePath().normalize();
        }
    }
}
