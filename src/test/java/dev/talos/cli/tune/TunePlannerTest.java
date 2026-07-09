package dev.talos.cli.tune;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunePlannerTest {

    private static final Path HOME = Path.of("C:/Users/user");

    @Test
    void nvidiaDriverAboveCuda133FloorProposesCuda133Lane() {
        TunePlanner.Proposal proposal = TunePlanner.plan(facts("610.62", 16_303), HOME, path -> true).orElseThrow();

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
        TunePlanner.Proposal proposal = TunePlanner.plan(facts("540.00", 16_303), HOME, path -> true).orElseThrow();

        assertEquals("win-x64-cpu", proposal.lane().variant());
        assertTrue(proposal.expectedSpeedLine().contains("estimate"), proposal.expectedSpeedLine());
    }

    @Test
    void absentGpuEvidenceProposesCpuLaneWithConfidenceNone() {
        TunePlanner.Proposal proposal = TunePlanner.plan(facts("", -1), HOME, path -> true).orElseThrow();

        assertEquals("win-x64-cpu", proposal.lane().variant());
        assertTrue(proposal.detectionSummary().contains("no NVIDIA driver evidence"),
                proposal.detectionSummary());
    }

    @Test
    void cpuLaneSpeedEstimateIsLabeledAndPinsItsBasis() {
        TunePlanner.Proposal proposal = TunePlanner.plan(facts("", -1), HOME, path -> true).orElseThrow();

        String line = proposal.expectedSpeedLine();
        assertTrue(line.contains("estimate"), line);
        assertTrue(line.contains("60 GB/s"), "asserted bandwidth basis must be printed: " + line);
        assertTrue(line.contains("tok/s"), line);
    }

    @Test
    void cpuLaneWithoutModelFileSizeSkipsNumericEstimateHonestly() {
        TunePlanner.Facts noSize = new TunePlanner.Facts(
                "Windows 11", "amd64", false,
                "", "", -1,
                65_536, 12,
                "qwen2.5-coder-14b", -1);
        TunePlanner.Proposal proposal = TunePlanner.plan(noSize, HOME, path -> true).orElseThrow();

        assertTrue(proposal.expectedSpeedLine().contains("unknown"),
                "no numeric estimate without a model size basis: " + proposal.expectedSpeedLine());
        assertFalse(proposal.expectedSpeedLine().contains("tok/s"), proposal.expectedSpeedLine());
    }

    @Test
    void missingLaneBinaryIsReportedAsNotInstalled() {
        TunePlanner.Proposal proposal = TunePlanner.plan(facts("610.62", 16_303), HOME, path -> false).orElseThrow();

        assertFalse(proposal.laneInstalled());
    }

    private static TunePlanner.Facts facts(String driver, long vramMb) {
        return new TunePlanner.Facts(
                "Windows 11", "amd64", false,
                driver.isBlank() ? "" : "NVIDIA GeForce RTX 5070 Ti",
                driver,
                vramMb,
                65_536, 12,
                "qwen2.5-coder-14b",
                8_990_000_000L);
    }
}
