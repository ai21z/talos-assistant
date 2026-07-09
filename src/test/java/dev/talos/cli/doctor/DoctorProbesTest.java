package dev.talos.cli.doctor;

import com.sun.net.httpserver.HttpServer;
import dev.talos.core.Config;
import dev.talos.engine.llamacpp.LlamaCppLogEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T784: per-probe decision coverage for the doctor preflight. */
class DoctorProbesTest {

    @TempDir Path tempDir;

    // ── config ───────────────────────────────────────────────────────────

    @Test
    void configParseFailureFailsWithTheUserConfigPathInTheHint() {
        ProbeResult result = ConfigProbe.decide(
                "classpath:config/default-config.yaml", "C:/u/.talos/config.yaml",
                true, false, "mapping values are not allowed here", false, 0);

        assertEquals(ProbeResult.Status.FAIL, result.status());
        assertTrue(result.detail().contains("mapping values are not allowed here"));
        assertTrue(result.hint().contains("C:/u/.talos/config.yaml"));
    }

    @Test
    void configLoadedPasses() {
        ProbeResult result = ConfigProbe.decide(
                "classpath:config/default-config.yaml", "(none)",
                false, false, "", false, 3);

        assertEquals(ProbeResult.Status.PASS, result.status());
        assertTrue(result.detail().contains("no user config (built-in defaults)"));
        assertTrue(result.detail().contains("3 defaulted key(s)"));
    }

    @Test
    void strictModeWithDefaultedKeysWarns() {
        ProbeResult result = ConfigProbe.decide(
                "classpath:config/default-config.yaml", "(none)",
                true, true, "", true, 2);

        assertEquals(ProbeResult.Status.WARN, result.status());
        assertTrue(result.detail().contains("under strict mode"));
    }

    // ── runtime environment ─────────────────────────────────────────────

    @Test
    void runtimeEnvironmentReportsBoundedHardwareFactsAndExplicitGpuProbeFailure() {
        Config cfg = llamaCppConfig(Map.of(
                "mode", "managed",
                "server_path", "C:/talos/engines/llama-cpp/cpu/llama-server.exe"));

        ProbeResult result = new RuntimeEnvironmentProbe(failingGpuQuery()).run(ctx(cfg));

        assertEquals(ProbeResult.Status.PASS, result.status());
        assertTrue(result.detail().contains("os="), result.detail());
        assertTrue(result.detail().contains("arch="), result.detail());
        assertTrue(result.detail().contains("java="), result.detail());
        assertTrue(result.detail().contains("cpu="), result.detail());
        assertTrue(result.detail().contains("jvmMaxMemoryMb="), result.detail());
        assertTrue(result.detail().contains("talosHomeFreeMb="), result.detail());
        assertTrue(result.detail().contains("gpuProbe=nvidia-smi failed; assuming no GPU"), result.detail());
        assertFalse(result.detail().contains("GPU/VRAM not probed by Talos"), result.detail());
    }

    @Test
    void runtimeEnvironmentReportsNvidiaSmiGpuFactsAndCpuLanePointer() {
        Config cfg = llamaCppConfig(Map.of(
                "mode", "managed",
                "server_path", "C:/Users/AI21Z/AppData/Local/Programs/talos/engines/llama-cpp/cpu/llama-server.exe"));

        ProbeResult result = new RuntimeEnvironmentProbe(successfulGpuQuery(
                "NVIDIA GeForce RTX 5070 Ti, 16303 MiB, 15037 MiB, 576.88")).run(ctx(cfg));

        assertEquals(ProbeResult.Status.PASS, result.status());
        assertTrue(result.detail().contains("gpu=nvidia-smi:NVIDIA GeForce RTX 5070 Ti"), result.detail());
        assertTrue(result.detail().contains("vramTotalMb=16303"), result.detail());
        assertTrue(result.detail().contains("vramFreeMb=15037"), result.detail());
        assertTrue(result.detail().contains("driver=576.88"), result.detail());
        assertTrue(result.detail().contains("serverLane=cpu (configured path)"), result.detail());
        assertTrue(result.detail().contains("GPU present but configured llama.cpp server appears CPU-only"), result.detail());
        assertTrue(result.detail().contains("talos tune"), result.detail());
    }

    @Test
    void runtimeEnvironmentWarnsBeforeServerStartWhenCudaLaneDriverIsBelowFloor() {
        Config cfg = llamaCppConfig(Map.of(
                "mode", "managed",
                "server_path", "C:/talos/engines/llama-cpp/cuda-13.3/bin/llama-server.exe"));

        ProbeResult result = new RuntimeEnvironmentProbe(successfulGpuQuery(
                "NVIDIA GeForce RTX 4090, 24564 MiB, 22000 MiB, 555.85")).run(ctx(cfg));

        assertEquals(ProbeResult.Status.WARN, result.status());
        assertTrue(result.detail().contains("serverLane=cuda-13.3 (configured path)"), result.detail());
        assertTrue(result.detail().contains("driver 555.85 below required 580.00"), result.detail());
        assertTrue(result.detail().contains("source=nvidia-smi"), result.detail());
    }

    // ── engine-files ─────────────────────────────────────────────────────

    @Test
    void engineFilesPassWhenManagedFilesExist() throws IOException {
        Config cfg = llamaCppConfig(Map.of(
                "mode", "managed",
                "server_path", touch("llama-server.exe").toString(),
                "model_path", touch("agent.gguf").toString()));

        ProbeResult result = new EngineFilesProbe().run(ctx(cfg));

        assertEquals(ProbeResult.Status.PASS, result.status());
    }

    @Test
    void engineFilesFailWhenModelMissing() throws IOException {
        Config cfg = llamaCppConfig(Map.of(
                "mode", "managed",
                "server_path", touch("llama-server.exe").toString(),
                "model_path", tempDir.resolve("missing.gguf").toString()));

        ProbeResult result = new EngineFilesProbe().run(ctx(cfg));

        assertEquals(ProbeResult.Status.FAIL, result.status());
        assertTrue(result.detail().contains("model_path or hf_repo is missing"));
        assertTrue(result.hint().contains("talos setup models"));
    }

    @Test
    void engineFilesSkipInConnectOnlyMode() {
        Config cfg = llamaCppConfig(Map.of("mode", "connect_only"));

        ProbeResult result = new EngineFilesProbe().run(ctx(cfg));

        assertEquals(ProbeResult.Status.SKIP, result.status());
        assertTrue(result.detail().contains("connect-only"));
    }

    // ── server ───────────────────────────────────────────────────────────

    @Test
    void serverRespondingPasses() throws IOException {
        HttpServer server = startHealthServer(200);
        try {
            Config cfg = llamaCppConfig(Map.of(
                    "mode", "managed",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort()));

            ProbeResult result = new ServerProbe(false).run(ctx(cfg));

            assertEquals(ProbeResult.Status.PASS, result.status());
            assertTrue(result.detail().contains("server responding"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serverHttpErrorFails() throws IOException {
        HttpServer server = startHealthServer(503);
        try {
            Config cfg = llamaCppConfig(Map.of(
                    "mode", "managed",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort()));

            ProbeResult result = new ServerProbe(false).run(ctx(cfg));

            assertEquals(ProbeResult.Status.FAIL, result.status());
            assertTrue(result.detail().contains("HTTP 503"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void managedServerNotRunningIsAWarnNotAFailure() throws IOException {
        Config cfg = llamaCppConfig(Map.of(
                "mode", "managed",
                "host", "http://127.0.0.1",
                "port", freePort()));

        ProbeResult result = new ServerProbe(false).run(ctx(cfg));

        assertEquals(ProbeResult.Status.WARN, result.status());
        assertTrue(result.detail().contains("Talos starts it automatically"));
    }

    @Test
    void connectOnlyServerUnreachableFails() throws IOException {
        Config cfg = llamaCppConfig(Map.of(
                "mode", "connect_only",
                "host", "http://127.0.0.1",
                "port", freePort()));

        ProbeResult result = new ServerProbe(false).run(ctx(cfg));

        assertEquals(ProbeResult.Status.FAIL, result.status());
        assertTrue(result.detail().contains("connect-only"));
        assertTrue(result.hint().contains("start your llama-server"));
    }

    @Test
    void startModeSkipsWhenManagedFilesAreInvalid() {
        Config cfg = llamaCppConfig(Map.of(
                "mode", "managed",
                "server_path", tempDir.resolve("missing-server.exe").toString(),
                "model_path", tempDir.resolve("missing.gguf").toString()));

        ProbeResult result = new ServerProbe(true).run(ctx(cfg));

        assertEquals(ProbeResult.Status.SKIP, result.status());
        assertTrue(result.detail().contains("engine-files"));
    }

    @Test
    void startModeFailsWhenSmokeReplyDoesNotContainTheExpectedToken() throws IOException {
        HttpServer server = startChatServer("wrong-token");
        try {
            Config cfg = llamaCppConfig(Map.of(
                    "mode", "connect_only",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "model", "smoke-model"));

            ProbeResult result = new ServerProbe(true).run(ctx(cfg));

            assertEquals(ProbeResult.Status.FAIL, result.status());
            assertTrue(result.detail().contains("model smoke reply did not contain"), result.detail());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startModeFailsWhenModelOnlyEchoesTheSmokePrompt() throws IOException {
        HttpServer server = startEchoChatServer();
        try {
            Config cfg = llamaCppConfig(Map.of(
                    "mode", "connect_only",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "model", "echo-model"));

            ProbeResult result = new ServerProbe(true).run(ctx(cfg));

            assertEquals(ProbeResult.Status.FAIL, result.status());
            assertTrue(result.detail().contains("model smoke reply did not contain"), result.detail());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startModeFailsWhenModelWrapsSmokeTokenInExtraText() throws IOException {
        HttpServer server = startChatServer("The token is " + MODEL_SMOKE_REPLY + ".");
        try {
            Config cfg = llamaCppConfig(Map.of(
                    "mode", "connect_only",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "model", "verbose-model"));

            ProbeResult result = new ServerProbe(true).run(ctx(cfg));

            assertEquals(ProbeResult.Status.FAIL, result.status());
            assertTrue(result.detail().contains("model smoke reply did not exactly match"),
                    result.detail());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startModePassesWhenSuccessfulModelSmokeIsFast() throws IOException {
        AtomicInteger chatCalls = new AtomicInteger();
        HttpServer server = startChatServer(MODEL_SMOKE_REPLY, Duration.ZERO, chatCalls);
        try {
            Config cfg = llamaCppConfig(Map.of(
                    "mode", "connect_only",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "model", "qwen2.5-coder-14b"));

            ProbeResult result = new ServerProbe(true).run(ctx(cfg));

            assertEquals(ProbeResult.Status.PASS, result.status());
            assertTrue(result.detail().contains("end-to-end model smoke verified"), result.detail());
            assertTrue(result.detail().contains("rates unmeasured"), result.detail());
            assertFalse(result.detail().contains("slow"), result.detail());
            assertTrue(result.detail().contains("external server left running"), result.detail());
            assertFalse(result.detail().contains("managed server released again"), result.detail());
            assertEquals(1, chatCalls.get(), "connect-only doctor must not run a managed-log rate sample");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startModeWarnsWhenMeasuredRatesProjectSlowAgentTurnEvenIfSmokeIsFast() throws IOException {
        HttpServer server = startChatServer(MODEL_SMOKE_REPLY);
        try {
            Config cfg = llamaCppConfig(Map.of(
                    "mode", "connect_only",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "model", "qwen2.5-coder-14b"));
            cfg.data.put("limits", Map.of("llm_timeout_ms", 300_000L));
            LlamaCppLogEvidence rates = LlamaCppLogEvidence.parse("""
                    0.40.000.000 I slot print_timing: id  0 | task 7 | prompt eval time =   50000.00 ms /  4000 tokens (   12.50 ms per token,    80.00 tokens per second)
                    3.17.254.000 I slot print_timing: id  0 | task 7 |        eval time =  137254.90 ms /   700 tokens (  196.08 ms per token,     5.10 tokens per second)
                    """);

            ProbeResult result = new ServerProbe(
                    true,
                    Duration.ofSeconds(60),
                    (ctx, preflight) -> rates).run(ctx(cfg));

            assertEquals(ProbeResult.Status.WARN, result.status());
            assertTrue(result.detail().contains("prompt eval 80.0 tok/s"), result.detail());
            assertTrue(result.detail().contains("generation 5.1 tok/s"), result.detail());
            assertTrue(result.detail().contains("reference turn"), result.detail());
            assertTrue(result.detail().contains("4,000 prompt"), result.detail());
            assertTrue(result.detail().contains("700 generated"), result.detail());
            assertTrue(result.detail().contains("50% of limits.llm_timeout_ms"), result.detail());
            assertTrue(result.detail().contains("GPU acceleration"), result.detail());
            assertTrue(result.detail().contains("smaller profile"), result.detail());
            assertTrue(result.detail().contains("external server left running"), result.detail());
            assertFalse(result.detail().contains("managed server released again"), result.detail());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startModeWarnsWhenMeasuredRatesProjectSlowAgentTurnEvenWithRaisedTimeout() throws IOException {
        HttpServer server = startChatServer(MODEL_SMOKE_REPLY);
        try {
            Config cfg = llamaCppConfig(Map.of(
                    "mode", "connect_only",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "model", "qwen2.5-coder-14b"));
            cfg.data.put("limits", Map.of("llm_timeout_ms", 1_200_000L));
            LlamaCppLogEvidence rates = LlamaCppLogEvidence.parse("""
                    0.40.000.000 I slot print_timing: id  0 | task 7 | prompt eval time =   50000.00 ms /  4000 tokens (   12.50 ms per token,    80.00 tokens per second)
                    3.17.254.000 I slot print_timing: id  0 | task 7 |        eval time =  137254.90 ms /   700 tokens (  196.08 ms per token,     5.10 tokens per second)
                    """);

            ProbeResult result = new ServerProbe(
                    true,
                    Duration.ofSeconds(60),
                    (ctx, preflight) -> rates).run(ctx(cfg));

            assertEquals(ProbeResult.Status.WARN, result.status());
            assertTrue(result.detail().contains("practical edit work"), result.detail());
            assertTrue(result.detail().contains("reference turn"), result.detail());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startModeIgnoresTinyPromptTimingSamplesInsteadOfProjectingFromThem() throws IOException {
        HttpServer server = startChatServer(MODEL_SMOKE_REPLY);
        LlamaCppLogEvidence tinyPromptOnly = LlamaCppLogEvidence.parse("""
                0.01.000.000 I slot print_timing: id  0 | task 7 | prompt eval time =    4000.00 ms /    40 tokens (  100.00 ms per token,    10.00 tokens per second)
                0.08.000.000 I slot print_timing: id  0 | task 7 |        eval time =    7000.00 ms /   700 tokens (   10.00 ms per token,   100.00 tokens per second)
                """);
        try {
            Config cfg = llamaCppConfig(Map.of(
                    "mode", "connect_only",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "model", "qwen2.5-coder-14b"));

            ProbeResult result = new ServerProbe(
                    true,
                    Duration.ofSeconds(60),
                    (ctx, preflight) -> tinyPromptOnly).run(ctx(cfg));

            assertEquals(ProbeResult.Status.PASS, result.status());
            assertTrue(result.detail().contains("rates unmeasured"), result.detail());
            assertFalse(result.detail().contains("prompt eval 10.0 tok/s"), result.detail());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startModeWarnsWhenManagedRateSampleStillCannotMeasureRates() throws IOException {
        AtomicInteger chatCalls = new AtomicInteger();
        HttpServer server = startChatServer(MODEL_SMOKE_REPLY, Duration.ZERO, chatCalls);
        try {
            Config cfg = llamaCppConfig(Map.of(
                    "mode", "connect_only",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "model", "qwen2.5-coder-14b"));

            ProbeResult result = new ServerProbe(
                    true,
                    Duration.ofSeconds(60),
                    new ServerProbe.TimingEvidenceSource() {
                        @Override
                        public LlamaCppLogEvidence read(DoctorContext ctx, dev.talos.engine.llamacpp.LlamaCppPreflight.Report preflight) {
                            return LlamaCppLogEvidence.parse("");
                        }

                        @Override
                        public boolean canImproveAfterSample(DoctorContext ctx, dev.talos.engine.llamacpp.LlamaCppPreflight.Report preflight) {
                            return true;
                        }
                    }).run(ctx(cfg));

            assertEquals(ProbeResult.Status.WARN, result.status());
            assertTrue(result.detail().contains("rate sample could not be measured within 60s"), result.detail());
            assertTrue(result.detail().contains("smaller profile"), result.detail());
            assertTrue(chatCalls.get() >= 2, "probe must attempt the bounded rate sample before warning");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startModeRunsBoundedRateSampleWhenInitialSmokeLogHasNoUsableGenerationRate() throws IOException {
        AtomicInteger chatCalls = new AtomicInteger();
        HttpServer server = startChatServer(MODEL_SMOKE_REPLY, Duration.ZERO, chatCalls);
        AtomicInteger evidenceReads = new AtomicInteger();
        LlamaCppLogEvidence rates = LlamaCppLogEvidence.parse("""
                0.40.000.000 I slot print_timing: id  0 | task 7 | prompt eval time =   50000.00 ms /  4000 tokens (   12.50 ms per token,    80.00 tokens per second)
                3.17.254.000 I slot print_timing: id  0 | task 7 |        eval time =  137254.90 ms /   700 tokens (  196.08 ms per token,     5.10 tokens per second)
                """);
        try {
            Config cfg = llamaCppConfig(Map.of(
                    "mode", "connect_only",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "model", "qwen2.5-coder-14b"));

            ProbeResult result = new ServerProbe(
                    true,
                    Duration.ofSeconds(60),
                    new ServerProbe.TimingEvidenceSource() {
                        @Override
                        public LlamaCppLogEvidence read(DoctorContext ctx, dev.talos.engine.llamacpp.LlamaCppPreflight.Report preflight) {
                            return evidenceReads.incrementAndGet() == 1
                                    ? LlamaCppLogEvidence.parse("")
                                    : rates;
                        }

                        @Override
                        public boolean canImproveAfterSample(DoctorContext ctx, dev.talos.engine.llamacpp.LlamaCppPreflight.Report preflight) {
                            return true;
                        }
                    }).run(ctx(cfg));

            assertEquals(ProbeResult.Status.WARN, result.status());
            assertTrue(result.detail().contains("prompt eval 80.0 tok/s"), result.detail());
            assertTrue(chatCalls.get() >= 2, "doctor --start should run a second bounded sample when rates are absent");
            assertTrue(evidenceReads.get() >= 2, "doctor should re-read timing evidence after the bounded sample");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void boundedRateSamplePromptHasWideNeutralContextMargin() throws IOException {
        AtomicInteger chatCalls = new AtomicInteger();
        List<String> requestBodies = new CopyOnWriteArrayList<>();
        HttpServer server = startChatServer(MODEL_SMOKE_REPLY, Duration.ZERO, chatCalls, requestBodies);
        try {
            Config cfg = llamaCppConfig(Map.of(
                    "mode", "connect_only",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "model", "qwen2.5-coder-14b"));

            new ServerProbe(
                    true,
                    Duration.ofSeconds(60),
                    new ServerProbe.TimingEvidenceSource() {
                        @Override
                        public LlamaCppLogEvidence read(DoctorContext ctx, dev.talos.engine.llamacpp.LlamaCppPreflight.Report preflight) {
                            return LlamaCppLogEvidence.parse("");
                        }

                        @Override
                        public boolean canImproveAfterSample(DoctorContext ctx, dev.talos.engine.llamacpp.LlamaCppPreflight.Report preflight) {
                            return true;
                        }
                    }).run(ctx(cfg));

            assertTrue(requestBodies.size() >= 2, "doctor --start should send smoke plus bounded rate sample");
            long neutralContextRepeats = countOccurrences(
                    requestBodies.get(1),
                    "Local workspace assistant measurement context.");
            assertTrue(neutralContextRepeats >= 80,
                    "rate sample prompt needs broad tokenization margin, repeats=" + neutralContextRepeats);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startModeWarnsWhenSuccessfulModelSmokeIsSlow() throws IOException {
        HttpServer server = startChatServer(MODEL_SMOKE_REPLY, Duration.ofMillis(60));
        try {
            Config cfg = llamaCppConfig(Map.of(
                    "mode", "connect_only",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "model", "qwen2.5-coder-14b"));

            ProbeResult result = new ServerProbe(true, Duration.ofMillis(10)).run(ctx(cfg));

            assertEquals(ProbeResult.Status.WARN, result.status());
            assertTrue(result.detail().contains("model smoke verified"), result.detail());
            assertTrue(result.detail().contains("slow"), result.detail());
            assertTrue(result.detail().contains("qwen2.5-coder-14b"), result.detail());
            assertTrue(result.detail().contains("GPU acceleration or stronger hardware"), result.detail());
        } finally {
            server.stop(0);
        }
    }

    // ── index-writable / home-writable ───────────────────────────────────

    @Test
    void indexDirectoryWritablePassesAndLeavesNoProbeFile() {
        DoctorContext ctx = ctx(new Config());

        ProbeResult result = new IndexWritableProbe().run(ctx);

        assertEquals(ProbeResult.Status.PASS, result.status());
        Path indices = ctx.talosHome().resolve("indices");
        assertTrue(Files.isDirectory(indices));
        try (var files = Files.walk(indices)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("doctor-")),
                    "probe temp file must be deleted");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void indexDirectoryUnwritableFails() throws IOException {
        // A talosHome path that is a FILE makes createDirectories throw on
        // every platform - more reliable than read-only directory bits on
        // Windows.
        Path fileAsHome = touch("not-a-directory");
        DoctorContext ctx = new DoctorContext(new Config(), tempDir, fileAsHome);

        ProbeResult result = new IndexWritableProbe().run(ctx);

        assertEquals(ProbeResult.Status.FAIL, result.status());
        assertTrue(result.detail().contains("not writable"));
    }

    @Test
    void homeWritablePassesAndCreatesLogsDir() {
        DoctorContext ctx = ctx(new Config());

        ProbeResult result = new HomeWritableProbe().run(ctx);

        assertEquals(ProbeResult.Status.PASS, result.status());
        assertTrue(Files.isDirectory(ctx.talosHome().resolve("logs")));
    }

    @Test
    void homeUnwritableFails() throws IOException {
        Path fileAsHome = touch("home-as-file");
        DoctorContext ctx = new DoctorContext(new Config(), tempDir, fileAsHome);

        ProbeResult result = new HomeWritableProbe().run(ctx);

        assertEquals(ProbeResult.Status.FAIL, result.status());
    }

    // ── retrieval / vectors ─────────────────────────────────────────────

    @Test
    void retrievalStateShowsBm25OnlyWhenVectorsDisabled() {
        Config cfg = new Config();
        Map<String, Object> rag = new LinkedHashMap<>();
        rag.put("vectors", Map.of("enabled", false));
        cfg.data.put("rag", rag);
        cfg.data.put("embed", Map.of("provider", "disabled", "model", "none"));

        ProbeResult result = new RetrievalStateProbe().run(ctx(cfg));

        assertEquals(ProbeResult.Status.PASS, result.status());
        assertTrue(result.detail().contains("vectors=OFF"), result.detail());
        assertTrue(result.detail().contains("BM25-only"), result.detail());
        assertTrue(result.detail().contains("embedding=disabled/none"), result.detail());
        assertTrue(result.detail().contains("embedding dimension not probed by doctor"), result.detail());
        assertTrue(result.detail().contains("GPU facts reported by runtime-env probe"), result.detail());
        assertFalse(result.detail().contains("GPU/VRAM not probed by Talos"), result.detail());
    }

    @Test
    void retrievalStateShowsRemoteEmbeddingHostRejectedWithoutAllowRemote() {
        Config cfg = new Config();
        cfg.data.put("embed", Map.of(
                "provider", "compat",
                "model", "embedder",
                "host", "http://127.0.0.1.evil.com:8000",
                "allow_remote", false));
        cfg.data.put("rag", Map.of("vectors", Map.of("enabled", true)));

        ProbeResult result = new RetrievalStateProbe().run(ctx(cfg));

        assertEquals(ProbeResult.Status.WARN, result.status());
        assertTrue(result.detail().contains("locality=remote-rejected"), result.detail());
        assertTrue(result.detail().contains("BM25-only fallback likely"), result.detail());
    }

    @Test
    void retrievalStateSanitizesSecretShapedEmbeddingHost() {
        Config cfg = new Config();
        String secretHost = "https://user:sk-test-secretsecretsecretsecret@example.com";
        cfg.data.put("embed", Map.of(
                "provider", "compat",
                "model", "embedder",
                "host", secretHost,
                "allow_remote", true));
        cfg.data.put("rag", Map.of("vectors", Map.of("enabled", true)));

        ProbeResult result = new RetrievalStateProbe().run(ctx(cfg));

        assertEquals(ProbeResult.Status.PASS, result.status());
        assertTrue(result.detail().contains("host=[redacted]"), result.detail());
        assertTrue(result.detail().contains("locality=remote-allowed"), result.detail());
        assertTrue(result.detail().contains("mode=hybrid if embedding probe succeeds"), result.detail());
        assertTrue(result.detail().contains("model=embedder"), result.detail());
        assertTrue(!result.detail().contains("sk-test-secretsecretsecretsecret"), result.detail());
    }

    @Test
    void retrievalStateUsesManagedLlamaCppEmbeddingHostWhenEmbedHostIsBlank() {
        Config cfg = new Config();
        cfg.data.put("embed", Map.of(
                "provider", "llama_cpp",
                "model", "bge-m3",
                "host", "",
                "managed", Map.of(
                        "enabled", true,
                        "host", "http://127.0.0.1",
                        "port", 18116)));
        cfg.data.put("rag", Map.of("vectors", Map.of("enabled", true)));

        ProbeResult result = new RetrievalStateProbe().run(ctx(cfg));

        assertEquals(ProbeResult.Status.PASS, result.status());
        assertTrue(result.detail().contains("embedding=llama_cpp/bge-m3"), result.detail());
        assertTrue(result.detail().contains("host=http://127.0.0.1:18116"), result.detail());
        assertTrue(result.detail().contains("locality=local"), result.detail());
        assertTrue(result.detail().contains("mode=hybrid if embedding probe succeeds"), result.detail());
        assertFalse(result.detail().contains("11434"), result.detail());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private DoctorContext ctx(Config cfg) {
        return new DoctorContext(cfg, tempDir.resolve("workspace"), tempDir.resolve("talos-home"));
    }

    private Path touch(String filename) throws IOException {
        Path path = tempDir.resolve(filename);
        Files.writeString(path, "fake", StandardCharsets.UTF_8);
        return path;
    }

    private static Config llamaCppConfig(Map<String, Object> llamaCpp) {
        Config cfg = new Config();
        Map<String, Object> engines = new LinkedHashMap<>();
        engines.put("llama_cpp", new LinkedHashMap<>(llamaCpp));
        cfg.data.put("engines", engines);
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("default_backend", "llama_cpp");
        cfg.data.put("llm", llm);
        return cfg;
    }

    @org.junit.jupiter.api.Test
    void cuda12MinorVariantsUseTheCuda124FloorNotTheStrictestOne() {
        Config cfg = llamaCppConfig(Map.of(
                "mode", "managed",
                "server_path", "C:/talos/engines/llama-cpp/cuda-12.6/llama-server.exe"));

        ProbeResult result = new RuntimeEnvironmentProbe(successfulGpuQuery(
                "NVIDIA GeForce RTX 4070, 12282, 11000, 555.85")).run(ctx(cfg));

        assertTrue(result.detail().contains("serverLane=cuda-12"), result.detail());
        assertFalse(result.detail().contains("below required"),
                "driver 555.85 satisfies the 12.4 floor; the 13.x floor must not apply: " + result.detail());
    }

    @org.junit.jupiter.api.Test
    void cudaLettersInsideAnotherWordDoNotClassifyTheLaneAsCuda() {
        Config cfg = llamaCppConfig(Map.of(
                "mode", "managed",
                "server_path", "C:/Users/barracuda/llama.cpp/llama-server.exe"));

        ProbeResult result = new RuntimeEnvironmentProbe(successfulGpuQuery(
                "NVIDIA GeForce RTX 4070, 12282, 11000, 555.85")).run(ctx(cfg));

        assertTrue(result.detail().contains("serverLane=cpu"), result.detail());
        assertFalse(result.detail().contains("below required"), result.detail());
    }

    private static RuntimeEnvironmentProbe.GpuQueryRunner successfulGpuQuery(String output) {
        return () -> new RuntimeEnvironmentProbe.GpuQueryResult(0, output, "");
    }

    private static RuntimeEnvironmentProbe.GpuQueryRunner failingGpuQuery() {
        return () -> new RuntimeEnvironmentProbe.GpuQueryResult(9, "", "driver not loaded");
    }

    private static HttpServer startHealthServer(int status) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] bytes = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static final String MODEL_SMOKE_REPLY = "TALOS_MODEL_SMOKE_OK";

    private static HttpServer startChatServer(String reply) throws IOException {
        return startChatServer(reply, Duration.ZERO);
    }

    private static HttpServer startChatServer(String reply, Duration responseDelay) throws IOException {
        return startChatServer(reply, responseDelay, null);
    }

    private static HttpServer startChatServer(String reply, Duration responseDelay, AtomicInteger calls) throws IOException {
        return startChatServer(reply, responseDelay, calls, null);
    }

    private static HttpServer startChatServer(
            String reply,
            Duration responseDelay,
            AtomicInteger calls,
            List<String> requestBodies) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            if (calls != null) {
                calls.incrementAndGet();
            }
            if (requestBodies != null) {
                requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            if (responseDelay != null && !responseDelay.isZero() && !responseDelay.isNegative()) {
                try {
                    Thread.sleep(responseDelay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = ("""
                    {"choices":[{"message":{"role":"assistant","content":"%s"}}]}
                    """.formatted(reply)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static HttpServer startEchoChatServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String escaped = body.replace("\\", "\\\\").replace("\"", "\\\"");
            byte[] bytes = ("""
                    {"choices":[{"message":{"role":"assistant","content":"%s"}}]}
                    """.formatted(escaped)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static long countOccurrences(String text, String needle) {
        long count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
