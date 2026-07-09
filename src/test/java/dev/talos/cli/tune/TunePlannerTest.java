package dev.talos.cli.tune;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunePlannerTest {

    private static final Path HOME = Path.of("C:/Users/user");
    private static final TunePlanner.InstalledExecutableLocator FLAT_INSTALLED =
            (dir, name) -> Optional.of(dir.resolve(name));
    private static final TunePlanner.InstalledExecutableLocator NOT_INSTALLED =
            (dir, name) -> Optional.empty();

    @Test
    void nvidiaDriverAboveCuda133FloorProposesCuda133Lane() {
        TunePlanner.Proposal proposal =
                TunePlanner.plan(facts("610.62", 16_303), HOME, FLAT_INSTALLED).orElseThrow();

        assertEquals("win-x64-cuda-13.3", proposal.lane().variant());
        assertTrue(proposal.laneInstalled());
        assertTrue(proposal.serverPath().toString().replace('\\', '/')
                .endsWith(".talos/engines/llama.cpp/b9918/win-x64-cuda-13.3/llama-server.exe"),
                proposal.serverPath().toString());
        assertEquals(16_384, proposal.context().context());
        assertTrue(proposal.detectionSummary().contains("610.62"), proposal.detectionSummary());
        assertTrue(proposal.detectionSummary().contains("nvidia-smi"), proposal.detectionSummary());
        assertTrue(proposal.expectedSpeedLine().contains("measured"),
                "CUDA lane speed must be measured at verify, not asserted: " + proposal.expectedSpeedLine());
    }

    @Test
    void driverBelowCuda124FloorProposesCpuLane() {
        TunePlanner.Proposal proposal =
                TunePlanner.plan(facts("540.00", 16_303), HOME, FLAT_INSTALLED).orElseThrow();

        assertEquals("win-x64-cpu", proposal.lane().variant());
        assertTrue(proposal.expectedSpeedLine().contains("estimate"), proposal.expectedSpeedLine());
    }

    @Test
    void absentGpuEvidenceProposesCpuLaneWithConfidenceNone() {
        TunePlanner.Proposal proposal =
                TunePlanner.plan(facts("", -1), HOME, FLAT_INSTALLED).orElseThrow();

        assertEquals("win-x64-cpu", proposal.lane().variant());
        assertTrue(proposal.detectionSummary().contains("no NVIDIA driver evidence"),
                proposal.detectionSummary());
    }

    @Test
    void cpuLaneSpeedEstimateIsLabeledAndPinsItsBasis() {
        TunePlanner.Proposal proposal =
                TunePlanner.plan(facts("", -1), HOME, FLAT_INSTALLED).orElseThrow();

        String line = proposal.expectedSpeedLine();
        assertTrue(line.contains("estimate"), line);
        assertTrue(line.contains("60 GB/s"), "asserted bandwidth basis must be printed: " + line);
        assertTrue(line.contains("tok/s"), line);
    }

    @Test
    void cpuLaneWithoutModelFileSizeSkipsNumericEstimateHonestly() {
        TunePlanner.Facts noSize = new TunePlanner.Facts(
                "Windows 11", "amd64", false, "",
                "", "", -1,
                65_536, 12,
                "qwen2.5-coder-14b", -1);
        TunePlanner.Proposal proposal = TunePlanner.plan(noSize, HOME, FLAT_INSTALLED).orElseThrow();

        assertTrue(proposal.expectedSpeedLine().contains("unknown"),
                "no numeric estimate without a model size basis: " + proposal.expectedSpeedLine());
        assertFalse(proposal.expectedSpeedLine().contains("tok/s"), proposal.expectedSpeedLine());
    }

    @Test
    void missingLaneBinaryIsReportedAsNotInstalled() {
        TunePlanner.Proposal proposal =
                TunePlanner.plan(facts("610.62", 16_303), HOME, NOT_INSTALLED).orElseThrow();

        assertFalse(proposal.laneInstalled());
    }

    @Test
    void ubuntuFactsSelectTheShippedUbuntuLane() {
        TunePlanner.Proposal proposal =
                TunePlanner.plan(linuxFacts(false, "Ubuntu 22.04.4 LTS"), Path.of("/home/user"), NOT_INSTALLED)
                        .orElseThrow(() -> new AssertionError(
                                "Ubuntu x64 must select the shipped ubuntu-x64-cpu lane"));

        assertEquals("ubuntu-x64-cpu", proposal.lane().variant());
        assertFalse(proposal.laneInstalled());
        assertTrue(proposal.serverPath().toString().replace('\\', '/')
                        .endsWith(".talos/engines/llama.cpp/b9860/ubuntu-x64-cpu/llama-server"),
                proposal.serverPath().toString());
        assertTrue(proposal.expectedSpeedLine().contains("estimate"), proposal.expectedSpeedLine());
    }

    @Test
    void wslUbuntuFactsSelectTheShippedUbuntuLane() {
        TunePlanner.Proposal proposal =
                TunePlanner.plan(linuxFacts(true, "Ubuntu 24.04.1 LTS"), Path.of("/home/user"), NOT_INSTALLED)
                        .orElseThrow(() -> new AssertionError(
                                "WSL Ubuntu x64 must select the shipped ubuntu-x64-cpu lane"));

        assertEquals("ubuntu-x64-cpu", proposal.lane().variant());
    }

    @Test
    void nestedInstalledExecutableIsRecognizedAndUsedAsServerPath() {
        // The Ubuntu tar nests the binary (build/bin/llama-server); an
        // installed lane must be recognized and the proposal must carry the
        // path that actually exists, not a flat guess.
        TunePlanner.InstalledExecutableLocator nested =
                (dir, name) -> Optional.of(dir.resolve("build").resolve("bin").resolve(name));

        TunePlanner.Proposal proposal =
                TunePlanner.plan(linuxFacts(false, "Ubuntu 22.04.4 LTS"), Path.of("/home/user"), nested)
                        .orElseThrow();

        assertTrue(proposal.laneInstalled(),
                "a nested installed layout must be recognized as installed");
        assertTrue(proposal.serverPath().toString().replace('\\', '/')
                        .endsWith("ubuntu-x64-cpu/build/bin/llama-server"),
                proposal.serverPath().toString());
    }

    @Test
    void unsupportedLinuxDistroProducesNoProposal() {
        assertTrue(TunePlanner.plan(linuxFacts(false, "Fedora Linux 40"), Path.of("/home/user"), FLAT_INSTALLED)
                        .isEmpty(),
                "no pinned lane exists for non-Ubuntu distros; tune must stay honest");
        assertTrue(TunePlanner.plan(linuxFacts(false, ""), Path.of("/home/user"), FLAT_INSTALLED)
                        .isEmpty(),
                "no distro evidence must not select the Ubuntu lane");
    }

    private static TunePlanner.Facts linuxFacts(boolean wsl, String distroName) {
        return new TunePlanner.Facts(
                "Linux", "amd64", wsl, distroName,
                "", "", -1,
                65_536, 12,
                "qwen2.5-coder-14b",
                8_990_000_000L);
    }

    private static TunePlanner.Facts facts(String driver, long vramMb) {
        return new TunePlanner.Facts(
                "Windows 11", "amd64", false, "",
                driver.isBlank() ? "" : "NVIDIA GeForce RTX 5070 Ti",
                driver,
                vramMb,
                65_536, 12,
                "qwen2.5-coder-14b",
                8_990_000_000L);
    }
}
