package dev.talos.cli.tune;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuneVerifierTest {

    private static final String CUDA_LOG_FULL_OFFLOAD_FAST = """
            load_tensors: offloaded 49/49 layers to GPU
            common_params_fit_impl: projected to use 10035 MiB of device memory vs. 15268 MiB of free device memory
            slot print_timing: id  0 | task 0 | prompt eval time =     346.61 ms /   638 tokens (    0.54 ms per token,  1840.68 tokens per second)
            slot print_timing: id  0 | task 0 | eval time =     834.32 ms /    64 tokens (   13.04 ms per token,    76.71 tokens per second)
            """;

    private static final String CUDA_LOG_FULL_OFFLOAD_SLOW = """
            load_tensors: offloaded 49/49 layers to GPU
            slot print_timing: id  0 | task 0 | eval time =   10312.11 ms /    64 tokens (  161.13 ms per token,     6.21 tokens per second)
            """;

    private static final String CUDA_LOG_ZERO_OFFLOAD = """
            load_tensors: offloaded 0/49 layers to GPU
            slot print_timing: id  0 | task 0 | eval time =   10312.11 ms /    64 tokens (  161.13 ms per token,     6.21 tokens per second)
            """;

    private static final String CUDA_LOG_PARTIAL_OFFLOAD_SLOW = """
            load_tensors: offloaded 24/49 layers to GPU
            slot print_timing: id  0 | task 0 | eval time =   10312.11 ms /    64 tokens (  161.13 ms per token,     6.21 tokens per second)
            """;

    private static final String CUDA_LOG_FULL_OFFLOAD_TINY_SMOKE_ONLY = """
            load_tensors: offloaded 49/49 layers to GPU
            slot print_timing: id  0 | task 0 | eval time =      17.10 ms /     2 tokens (    8.55 ms per token,   116.99 tokens per second)
            """;

    private static final String LOG_WITHOUT_OFFLOAD = """
            srv  log_server_r: request: GET /health 127.0.0.1 200
            slot print_timing: id  0 | task 0 | eval time =     834.32 ms /    64 tokens (   13.04 ms per token,    76.71 tokens per second)
            """;

    @Test
    void cudaLaneWithOffloadLineAndHealthyRateVerifies() {
        TuneVerifier.Result result = TuneVerifier.verify(CUDA_LOG_FULL_OFFLOAD_FAST, true, 0);

        assertTrue(result.passed(), result.summary());
        assertTrue(result.summary().contains("offloaded 49/49 layers"), result.summary());
        assertTrue(result.summary().contains("76.7"), result.summary());
        assertFalse(result.spillSuspected(), result.summary());
    }

    @Test
    void cudaLaneWithoutOffloadEvidenceFailsVerification() {
        TuneVerifier.Result result = TuneVerifier.verify(LOG_WITHOUT_OFFLOAD, true, 0);

        assertFalse(result.passed(), "no offload line means no GPU claim");
        assertTrue(result.summary().contains("offload"), result.summary());
    }

    @Test
    void cudaLaneWithZeroOffloadedLayersFailsVerification() {
        TuneVerifier.Result result = TuneVerifier.verify(CUDA_LOG_ZERO_OFFLOAD, true, 0);

        assertFalse(result.passed(), "0/N offload is CPU execution, not verified GPU acceleration");
        assertTrue(result.summary().contains("0/49"), result.summary());
        assertTrue(result.summary().contains("refusing"), result.summary());
    }

    @Test
    void cudaLaneWithPartialOffloadAndCpuClassSpeedWarnsSpill() {
        TuneVerifier.Result result = TuneVerifier.verify(CUDA_LOG_PARTIAL_OFFLOAD_SLOW, true, 0);

        assertTrue(result.passed(), result.summary());
        assertTrue(result.spillSuspected(), result.summary());
        assertTrue(result.summary().contains("partial"), result.summary());
        assertTrue(result.summary().contains("estimate"), result.summary());
    }

    @Test
    void cudaLaneIgnoresTinySmokeEvalInsteadOfCallingItMeasuredGenerationRate() {
        TuneVerifier.Result result = TuneVerifier.verify(CUDA_LOG_FULL_OFFLOAD_TINY_SMOKE_ONLY, true, 0);

        assertTrue(result.passed(), result.summary());
        assertFalse(result.summary().contains("116.99"), result.summary());
        assertFalse(result.summary().contains("measured"), result.summary());
        assertTrue(result.summary().contains("generation unavailable"), result.summary());
    }

    @Test
    void cudaLaneWithFullOffloadButCpuClassSpeedWarnsSpillWithoutFailing() {
        TuneVerifier.Result result = TuneVerifier.verify(CUDA_LOG_FULL_OFFLOAD_SLOW, true, 0);

        assertTrue(result.passed(), result.summary());
        assertTrue(result.spillSuspected(), result.summary());
        assertTrue(result.summary().contains("estimate"),
                "the spill floor is an estimate and must say so: " + result.summary());
    }

    @Test
    void cpuLaneDoesNotRequireOffloadEvidence() {
        TuneVerifier.Result result = TuneVerifier.verify(LOG_WITHOUT_OFFLOAD, false, 0);

        assertTrue(result.passed(), result.summary());
        assertFalse(result.spillSuspected());
    }

    @Test
    void doctorFailureFailsVerificationRegardlessOfLog() {
        TuneVerifier.Result result = TuneVerifier.verify(CUDA_LOG_FULL_OFFLOAD_FAST, true, 1);

        assertFalse(result.passed());
        assertTrue(result.summary().contains("doctor"), result.summary());
    }
}
