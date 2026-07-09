package dev.talos.engine.llamacpp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedContextSelectorTest {

    @Test
    void cudaLaneWithSixteenGbBudgetSelectsHigherContextForQwen14b() {
        ManagedContextSelector.Decision decision = ManagedContextSelector.select(
                new ManagedContextSelector.Request(
                        "qwen2.5-coder-14b",
                        ManagedContextSelector.Lane.CUDA,
                        16_303,
                        -1));

        assertEquals(16_384, decision.context());
        assertTrue(decision.reason().contains("estimated"), decision.reason());
        assertTrue(decision.reason().contains("1536 MiB at 8192"), decision.reason());
        assertTrue(decision.reason().contains("CUDA"), decision.reason());
    }

    @Test
    void cudaLaneWithEightGbBudgetKeepsCurrentContext() {
        ManagedContextSelector.Decision decision = ManagedContextSelector.select(
                new ManagedContextSelector.Request(
                        "qwen2.5-coder-14b",
                        ManagedContextSelector.Lane.CUDA,
                        8_192,
                        -1));

        assertEquals(8_192, decision.context());
        assertTrue(decision.reason().contains("8192"), decision.reason());
        assertTrue(decision.reason().contains("VRAM"), decision.reason());
    }

    @Test
    void cpuLaneWithLowSystemRamKeepsCurrentContext() {
        ManagedContextSelector.Decision decision = ManagedContextSelector.select(
                new ManagedContextSelector.Request(
                        "qwen2.5-coder-14b",
                        ManagedContextSelector.Lane.CPU,
                        -1,
                        16_384));

        assertEquals(8_192, decision.context());
        assertTrue(decision.reason().contains("RAM"), decision.reason());
    }

    @Test
    void cpuLaneWithLargeSystemRamSelectsHigherContext() {
        ManagedContextSelector.Decision decision = ManagedContextSelector.select(
                new ManagedContextSelector.Request(
                        "qwen2.5-coder-14b",
                        ManagedContextSelector.Lane.CPU,
                        -1,
                        65_536));

        assertEquals(16_384, decision.context());
        assertTrue(decision.reason().contains("system RAM"), decision.reason());
        assertTrue(decision.reason().contains("estimated"), decision.reason());
    }

    @Test
    void unverifiedProfileKeepsCurrentContext() {
        ManagedContextSelector.Decision decision = ManagedContextSelector.select(
                new ManagedContextSelector.Request(
                        "custom-agent",
                        ManagedContextSelector.Lane.CUDA,
                        24_576,
                        65_536));

        assertEquals(8_192, decision.context());
        assertTrue(decision.reason().contains("unverified"), decision.reason());
    }

    @org.junit.jupiter.api.Test
    void laneFromServerPathRequiresCudaAsAPathToken() {
        org.junit.jupiter.api.Assertions.assertEquals(
                ManagedContextSelector.Lane.CPU,
                ManagedContextSelector.laneFromServerPath(
                        java.nio.file.Path.of("C:/Users/barracuda/llama.cpp/llama-server.exe")),
                "a username containing the letters cuda must not classify the lane as CUDA");
        org.junit.jupiter.api.Assertions.assertEquals(
                ManagedContextSelector.Lane.CUDA,
                ManagedContextSelector.laneFromServerPath(
                        java.nio.file.Path.of("C:/x/.talos/engines/llama.cpp/b9918/win-x64-cuda-13.3/llama-server.exe")));
        org.junit.jupiter.api.Assertions.assertEquals(
                ManagedContextSelector.Lane.CUDA,
                ManagedContextSelector.laneFromServerPath(
                        java.nio.file.Path.of("C:/tools/cuda13/llama-server.exe")));
    }
}
