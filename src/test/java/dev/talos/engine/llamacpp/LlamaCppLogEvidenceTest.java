package dev.talos.engine.llamacpp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppLogEvidenceTest {

    @Test
    void parsesB9918OffloadFitBufferAndTimingEvidence() {
        String log = """
                0.00.378.315 I common_params_fit_impl: projected to use 9803 MiB of device memory vs. 15037 MiB of free device memory
                0.00.378.320 I common_params_fit_impl: will leave 5233 >= 1024 MiB of free device memory, no changes needed
                0.00.901.280 I load_tensors: offloaded 49/49 layers to GPU
                0.00.901.286 I load_tensors:        CUDA0 model buffer size =  8148.38 MiB
                0.03.100.127 I llama_kv_cache:      CUDA0 KV buffer size =  1536.00 MiB
                0.04.169.847 I slot print_timing: id  0 | task 0 | prompt eval time =     561.33 ms /  1908 tokens (    0.29 ms per token,  3399.05 tokens per second)
                0.04.169.853 I slot print_timing: id  0 | task 0 |        eval time =      17.10 ms /     2 tokens (    8.55 ms per token,   116.99 tokens per second)
                """;

        LlamaCppLogEvidence evidence = LlamaCppLogEvidence.parse(log);

        assertTrue(evidence.offload().isPresent());
        assertEquals(49, evidence.offload().orElseThrow().offloadedLayers());
        assertEquals(49, evidence.offload().orElseThrow().totalLayers());
        assertEquals("GPU", evidence.offload().orElseThrow().target());

        assertTrue(evidence.fit().isPresent());
        assertEquals(9803, evidence.fit().orElseThrow().projectedDeviceMemoryMiB());
        assertEquals(15037, evidence.fit().orElseThrow().freeDeviceMemoryMiB());
        assertEquals(5233, evidence.fit().orElseThrow().remainingMiB());
        assertEquals(1024, evidence.fit().orElseThrow().requiredRemainingMiB());
        assertEquals("no changes needed", evidence.fit().orElseThrow().decision());

        assertEquals(2, evidence.buffers().size());
        assertEquals("CUDA0", evidence.buffers().get(0).device());
        assertEquals("model", evidence.buffers().get(0).kind());
        assertEquals(8148.38, evidence.buffers().get(0).sizeMiB(), 0.001);
        assertEquals("KV", evidence.buffers().get(1).kind());
        assertEquals(1536.00, evidence.buffers().get(1).sizeMiB(), 0.001);

        assertEquals(2, evidence.timings().size());
        assertEquals("prompt_eval", evidence.timings().get(0).kind());
        assertEquals(0, evidence.timings().get(0).taskId());
        assertEquals(561.33, evidence.timings().get(0).millis(), 0.001);
        assertEquals(1908, evidence.timings().get(0).tokens());
        assertEquals(3399.05, evidence.timings().get(0).tokensPerSecond(), 0.001);
        assertEquals("eval", evidence.timings().get(1).kind());
    }

    @Test
    void missingOffloadLinesDegradeToUnverified() {
        LlamaCppLogEvidence evidence = LlamaCppLogEvidence.parse("""
                0.04.169.847 I slot print_timing: id  0 | task 0 | prompt eval time =     561.33 ms /  1908 tokens (    0.29 ms per token,  3399.05 tokens per second)
                """);

        assertFalse(evidence.offload().isPresent());
        assertFalse(evidence.fit().isPresent());
        assertEquals(1, evidence.timings().size());
    }
}
