package dev.talos.cli.setup;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppEngineManifestTest {

    @Test
    void selectsPinnedUbuntuWslX64CpuArtifact() {
        SetupWizardSnapshot snapshot = snapshot("Linux", "amd64", true, "Ubuntu 26.04 LTS");

        var entry = LlamaCppEngineManifest.select(snapshot).orElseThrow();

        assertEquals("ubuntu-x64-cpu", entry.variant());
        assertEquals("b9860", entry.upstreamTag());
        assertEquals("llama-b9860-bin-ubuntu-x64.tar.gz", entry.assetName());
        assertEquals("https://github.com/ggml-org/llama.cpp/releases/download/b9860/llama-b9860-bin-ubuntu-x64.tar.gz",
                entry.url().toString());
        assertEquals("b68e8072eb88d1cc8b8e9d6ea8237aae87b34c6d8bbffda958c870e4dc949714", entry.sha256());
        assertEquals(15_851_454L, entry.sizeBytes());
        assertEquals("llama-server", entry.executableName());
        assertEquals(Path.of(".talos", "engines", "llama.cpp", "b9860", "ubuntu-x64-cpu"),
                entry.installSubdir());
        assertEquals("", entry.minNvidiaDriver());
        assertNull(entry.companion());
    }

    @Test
    void rejectsNonUbuntuLinuxForFirstMilestone() {
        assertFalse(LlamaCppEngineManifest.select(snapshot("Linux", "amd64", false, "Fedora Linux 42")).isPresent());
    }

    @Test
    void rejectsArmForFirstMilestone() {
        assertFalse(LlamaCppEngineManifest.select(snapshot("Linux", "aarch64", true, "Ubuntu 26.04 LTS")).isPresent());
        assertFalse(LlamaCppEngineManifest.select(
                windowsSnapshot("aarch64", "610.62")).isPresent());
    }

    @Test
    void windowsWithoutDriverEvidenceSelectsPinnedCpuLane() {
        var entry = LlamaCppEngineManifest.select(snapshot("Windows 11", "amd64", false, "")).orElseThrow();

        assertEquals("win-x64-cpu", entry.variant());
        assertEquals("b9918", entry.upstreamTag());
        assertEquals("llama-b9918-bin-win-cpu-x64.zip", entry.assetName());
        assertEquals("https://github.com/ggml-org/llama.cpp/releases/download/b9918/llama-b9918-bin-win-cpu-x64.zip",
                entry.url().toString());
        assertEquals("3324814ec61b79b218d580f00501c7a575a46f160a555aea23b44d549f395412", entry.sha256());
        assertEquals(17_498_395L, entry.sizeBytes());
        assertEquals("llama-server.exe", entry.executableName());
        assertEquals(Path.of(".talos", "engines", "llama.cpp", "b9918", "win-x64-cpu"),
                entry.installSubdir());
        assertEquals("", entry.minNvidiaDriver());
        assertNull(entry.companion());
    }

    @Test
    void windowsDriverAtOrAboveCuda133FloorSelectsCuda133LaneWithCudartCompanion() {
        for (String driver : new String[] {"580.00", "580.88", "610.62"}) {
            var entry = LlamaCppEngineManifest.select(windowsSnapshot("amd64", driver)).orElseThrow();

            assertEquals("win-x64-cuda-13.3", entry.variant(), driver);
            assertEquals("b9918", entry.upstreamTag());
            assertEquals("cuda-13.3", entry.backend());
            assertEquals("llama-b9918-bin-win-cuda-13.3-x64.zip", entry.assetName());
            assertEquals(
                    "https://github.com/ggml-org/llama.cpp/releases/download/b9918/llama-b9918-bin-win-cuda-13.3-x64.zip",
                    entry.url().toString());
            assertEquals("d5d786b93b683da2a902b1e62c43ee251ce15837a6d0ea19393be3e84a8dcb87", entry.sha256());
            assertEquals(161_407_586L, entry.sizeBytes());
            assertEquals("580.00", entry.minNvidiaDriver());
            assertEquals("llama-server.exe", entry.executableName());
            assertEquals(Path.of(".talos", "engines", "llama.cpp", "b9918", "win-x64-cuda-13.3"),
                    entry.installSubdir());

            var companion = entry.companion();
            assertNotNull(companion, "CUDA lane must model the cudart companion archive");
            assertEquals("cudart-llama-bin-win-cuda-13.3-x64.zip", companion.assetName());
            assertEquals(
                    "https://github.com/ggml-org/llama.cpp/releases/download/b9918/cudart-llama-bin-win-cuda-13.3-x64.zip",
                    companion.url().toString());
            assertEquals("1462a050eb4c684921ba51dcc4cc488a036674c3e73e9945ee705b854808d03e", companion.sha256());
            assertEquals(390_970_417L, companion.sizeBytes());
        }
    }

    @Test
    void windowsDriverBetweenFloorsSelectsCuda124LaneWithCudartCompanion() {
        for (String driver : new String[] {"551.61", "555.85", "579.99"}) {
            var entry = LlamaCppEngineManifest.select(windowsSnapshot("amd64", driver)).orElseThrow();

            assertEquals("win-x64-cuda-12.4", entry.variant(), driver);
            assertEquals("b9918", entry.upstreamTag());
            assertEquals("cuda-12.4", entry.backend());
            assertEquals("llama-b9918-bin-win-cuda-12.4-x64.zip", entry.assetName());
            assertEquals("ff695bee95d374221357089e852f74fa38645247cdfd292f13e133e1ddfb6987", entry.sha256());
            assertEquals(266_285_781L, entry.sizeBytes());
            assertEquals("551.61", entry.minNvidiaDriver());

            var companion = entry.companion();
            assertNotNull(companion, "CUDA lane must model the cudart companion archive");
            assertEquals("cudart-llama-bin-win-cuda-12.4-x64.zip", companion.assetName());
            assertEquals("8c79a9b226de4b3cacfd1f83d24f962d0773be79f1e7b75c6af4ded7e32ae1d6", companion.sha256());
            assertEquals(391_443_627L, companion.sizeBytes());
        }
    }

    @Test
    void windowsDriverBelowCuda124FloorFailsSafeToCpuLane() {
        for (String driver : new String[] {"551.60", "537.13", "451.48"}) {
            var entry = LlamaCppEngineManifest.select(windowsSnapshot("amd64", driver)).orElseThrow();
            assertEquals("win-x64-cpu", entry.variant(), driver);
        }
    }

    @Test
    void windowsUnparseableDriverEvidenceFailsSafeToCpuLane() {
        for (String driver : new String[] {"", "   ", "unknown", "not.a.driver"}) {
            var entry = LlamaCppEngineManifest.select(windowsSnapshot("amd64", driver)).orElseThrow();
            assertEquals("win-x64-cpu", entry.variant(), "driver evidence: '" + driver + "'");
        }
    }

    @Test
    void cudaDriverFloorsAreSingleSourcedFromManifestMetadata() {
        assertEquals("580.00", LlamaCppEngineManifest.minNvidiaDriverForBackend("cuda-13.3"));
        assertEquals("551.61", LlamaCppEngineManifest.minNvidiaDriverForBackend("cuda-12.4"));
        assertEquals("", LlamaCppEngineManifest.minNvidiaDriverForBackend("cpu"));
        assertEquals("", LlamaCppEngineManifest.minNvidiaDriverForBackend("rocm-6.4"));
        assertEquals("580.00", LlamaCppEngineManifest.strictestCudaDriverFloor(),
                "unknown cuda variants must gate on the strictest pinned cuda floor");
    }

    @Test
    void entriesExposeEveryPinnedLaneForDocsHonestyChecks() {
        var variants = LlamaCppEngineManifest.entries().stream()
                .map(LlamaCppEngineManifest.Entry::variant)
                .toList();
        assertTrue(variants.contains("ubuntu-x64-cpu"), variants.toString());
        assertTrue(variants.contains("win-x64-cpu"), variants.toString());
        assertTrue(variants.contains("win-x64-cuda-12.4"), variants.toString());
        assertTrue(variants.contains("win-x64-cuda-13.3"), variants.toString());
    }

    private static SetupWizardSnapshot windowsSnapshot(String arch, String driverVersion) {
        return new SetupWizardSnapshot(
                "Windows 11",
                arch,
                false,
                "",
                21,
                Path.of("C:/Users/user/.talos/config.yaml"),
                false,
                null,
                false,
                512_000,
                16_384,
                32_768,
                driverVersion.isBlank() ? "" : "NVIDIA GeForce RTX 5070 Ti",
                driverVersion.isBlank() ? -1 : 16_303,
                driverVersion);
    }

    private static SetupWizardSnapshot snapshot(String os, String arch, boolean wsl, String distro) {
        return new SetupWizardSnapshot(
                os,
                arch,
                wsl,
                distro,
                21,
                Path.of("/home/user/.talos/config.yaml"),
                false,
                null,
                false,
                512_000,
                16_384,
                32_768);
    }
}
