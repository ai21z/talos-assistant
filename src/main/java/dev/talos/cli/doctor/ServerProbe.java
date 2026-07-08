package dev.talos.cli.doctor;

import dev.talos.core.EngineRuntimeConfig;
import dev.talos.core.CfgUtil;
import dev.talos.core.engine.EngineRegistry;
import dev.talos.engine.llamacpp.LlamaCppLogEvidence;
import dev.talos.engine.llamacpp.LlamaCppPreflight;
import dev.talos.spi.ChatModelEngine;
import dev.talos.spi.types.ChatRequest;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Probes the llama.cpp server. Default mode never starts anything: a
 * managed server that is simply not running is a WARN (Talos starts it
 * automatically on first prompt - that is the product contract), while a
 * connect-only server that is unreachable is a FAIL (nothing will start it).
 *
 * <p>{@code --start} mode is opt-in end-to-end verification: it selects the
 * engine inside try-with-resources and runs a one-word chat, so the managed
 * server (and the model on the GPU) is always released again - if the probe
 * started it, the probe stops it; an externally owned server is never
 * touched.
 */
public final class ServerProbe implements DoctorProbe {
    static final String MODEL_SMOKE_TOKEN = "TALOS_MODEL_SMOKE_OK";
    private static final Duration DEFAULT_SLOW_SMOKE_WARNING_THRESHOLD = Duration.ofSeconds(60);
    private static final long DEFAULT_LLM_TIMEOUT_MS = 300_000L;
    private static final int REFERENCE_PROMPT_TOKENS = 4_000;
    private static final int REFERENCE_GENERATED_TOKENS = 700;
    private static final int MIN_GENERATION_RATE_SAMPLE_TOKENS = 64;
    private static final double SLOW_PROJECTION_TIMEOUT_FRACTION = 0.50d;
    private static final String RATE_SAMPLE_PROMPT = "Reply with the word talos repeated exactly 80 times, separated by spaces. No numbering, no punctuation.";

    private final boolean startServer;
    private final Duration slowSmokeWarningThreshold;
    private final TimingEvidenceSource timingEvidenceSource;

    public ServerProbe(boolean startServer) {
        this(startServer, DEFAULT_SLOW_SMOKE_WARNING_THRESHOLD);
    }

    ServerProbe(boolean startServer, Duration slowSmokeWarningThreshold) {
        this(startServer, slowSmokeWarningThreshold, ServerProbe::readManagedLogEvidence);
    }

    ServerProbe(boolean startServer,
                Duration slowSmokeWarningThreshold,
                TimingEvidenceSource timingEvidenceSource) {
        this.startServer = startServer;
        this.slowSmokeWarningThreshold = slowSmokeWarningThreshold == null
                ? DEFAULT_SLOW_SMOKE_WARNING_THRESHOLD
                : slowSmokeWarningThreshold;
        this.timingEvidenceSource = timingEvidenceSource == null
                ? ServerProbe::readManagedLogEvidence
                : timingEvidenceSource;
    }

    @Override
    public String id() {
        return "server";
    }

    @Override
    public ProbeResult run(DoctorContext ctx) {
        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(ctx.cfg());
        if (!"llama_cpp".equals(runtime.backend())) {
            return ProbeResult.skip(id(),
                    "backend '" + runtime.backend() + "' (server probe supports llama_cpp only)");
        }
        LlamaCppPreflight.Report preflight = LlamaCppPreflight.check(ctx.cfg());
        if (startServer) {
            return startAndChat(ctx, runtime, preflight);
        }
        return probeHealth(preflight);
    }

    private ProbeResult probeHealth(LlamaCppPreflight.Report preflight) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(preflight.baseUrl() + "/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                return ProbeResult.pass(id(), "server responding at " + preflight.baseUrl());
            }
            return ProbeResult.fail(id(),
                    "server at " + preflight.baseUrl() + " returned HTTP " + response.statusCode(),
                    "check the llama-server log under ~/.talos/logs/llama_cpp-" + preflight.port() + ".log");
        } catch (ConnectException e) {
            return unreachable(preflight, "connection refused");
        } catch (Exception e) {
            return unreachable(preflight, String.valueOf(e.getMessage()));
        }
    }

    private ProbeResult unreachable(LlamaCppPreflight.Report preflight, String cause) {
        if (preflight.managed()) {
            return ProbeResult.warn(id(),
                    "managed server not running (" + cause + ") - Talos starts it automatically"
                            + " on first prompt; use 'talos doctor --start' to verify end to end");
        }
        return ProbeResult.fail(id(),
                "server unreachable at " + preflight.baseUrl() + " (" + cause + ") in connect-only mode",
                "start your llama-server or fix engines.llama_cpp.host/port");
    }

    private ProbeResult startAndChat(DoctorContext ctx,
                                     EngineRuntimeConfig runtime,
                                     LlamaCppPreflight.Report preflight) {
        if (preflight.managed() && !preflight.filesOk()) {
            return ProbeResult.skip(id(),
                    "managed files invalid - fix the engine-files failure first");
        }
        try (EngineRegistry registry = new EngineRegistry(ctx.cfg())) {
            registry.select(runtime.backend(), runtime.model());
            if (!(registry.engine() instanceof ChatModelEngine chatEngine)) {
                return ProbeResult.skip(id(), "engine does not expose a chat interface");
            }
            long startNanos = System.nanoTime();
            String reply = chatEngine.chat(new ChatRequest(
                    runtime.backend(), runtime.model(), "",
                    "Reply exactly " + MODEL_SMOKE_TOKEN + " and no other text.", List.of(), null));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            String normalized = reply == null ? "" : reply.strip();
            int replyChars = normalized.length();
            if (!normalized.toUpperCase(Locale.ROOT).contains(MODEL_SMOKE_TOKEN)) {
                return ProbeResult.fail(id(),
                        "model smoke reply did not contain " + MODEL_SMOKE_TOKEN
                                + " (" + replyChars + " reply chars)",
                        "check the model profile, chat template, tool mode, and llama.cpp log under ~/.talos/logs/llama_cpp-"
                                + preflight.port() + ".log");
            }
            RateAssessment rates = assessRates(ctx, preflight);
            if (!rates.measured() && timingEvidenceSource.canImproveAfterSample(ctx, preflight)) {
                runBoundedRateSample(chatEngine, runtime);
                rates = assessRates(ctx, preflight);
            }
            if (rates.warn()) {
                return ProbeResult.warn(id(),
                        "model smoke verified (" + replyChars + " reply chars) but measured rates are slow: "
                                + rates.summary()
                                + "; managed server released again. This profile may be too slow for practical edit work on this machine;"
                                + " use GPU acceleration, a smaller profile, or raise limits.llm_timeout_ms before relying on it.");
            }
            if (elapsed.compareTo(slowSmokeWarningThreshold) > 0) {
                return ProbeResult.warn(id(),
                        "model smoke verified (" + replyChars + " reply chars) but slow: "
                                + elapsedSeconds(elapsed)
                                + " for startup/smoke on "
                                + runtime.model()
                                + "; managed server released again. This profile may be too slow for practical edit work on this machine;"
                                + " use GPU acceleration or stronger hardware before relying on it.");
            }
            return ProbeResult.pass(id(),
                    "end-to-end model smoke verified (" + replyChars + " reply chars);"
                            + " " + rates.summary()
                            + "; managed server released again");
        } catch (Exception e) {
            return ProbeResult.fail(id(),
                    "end-to-end server start failed: " + e.getMessage(),
                    "check ~/.talos/logs/llama_cpp-" + preflight.port() + ".log");
        }
    }

    private static void runBoundedRateSample(ChatModelEngine chatEngine, EngineRuntimeConfig runtime) {
        try {
            chatEngine.chat(new ChatRequest(
                    runtime.backend(), runtime.model(), "",
                    RATE_SAMPLE_PROMPT, List.of(), Duration.ofSeconds(60)));
        } catch (Exception ignored) {
            // Rate sampling is diagnostic. A successful smoke must not become a failure
            // only because the optional rate sample could not be measured.
        }
    }

    private RateAssessment assessRates(DoctorContext ctx, LlamaCppPreflight.Report preflight) {
        LlamaCppLogEvidence evidence = timingEvidenceSource.read(ctx, preflight);
        if (evidence == null) {
            return RateAssessment.unmeasured();
        }
        Optional<LlamaCppLogEvidence.Timing> prompt = latestTiming(evidence, "prompt_eval", 1);
        Optional<LlamaCppLogEvidence.Timing> generation =
                latestTiming(evidence, "eval", MIN_GENERATION_RATE_SAMPLE_TOKENS);
        if (prompt.isEmpty() || generation.isEmpty()) {
            return RateAssessment.unmeasured();
        }

        double promptRate = prompt.orElseThrow().tokensPerSecond();
        double generationRate = generation.orElseThrow().tokensPerSecond();
        if (promptRate <= 0.0d || generationRate <= 0.0d) {
            return RateAssessment.unmeasured();
        }

        double projectedSeconds = REFERENCE_PROMPT_TOKENS / promptRate
                + REFERENCE_GENERATED_TOKENS / generationRate;
        double timeoutSeconds = llmTimeoutMs(ctx) / 1000.0d;
        double warningThreshold = timeoutSeconds * SLOW_PROJECTION_TIMEOUT_FRACTION;
        boolean warn = projectedSeconds > warningThreshold;
        String summary = "prompt eval " + oneDecimal(promptRate)
                + " tok/s, generation " + oneDecimal(generationRate)
                + " tok/s; projected reference turn ("
                + formatInt(REFERENCE_PROMPT_TOKENS)
                + " prompt + "
                + formatInt(REFERENCE_GENERATED_TOKENS)
                + " generated tokens) about "
                + oneDecimal(projectedSeconds)
                + "s"
                + (warn
                ? ", above " + percent(SLOW_PROJECTION_TIMEOUT_FRACTION)
                + " of limits.llm_timeout_ms (" + oneDecimal(timeoutSeconds) + "s)"
                : ", within " + percent(SLOW_PROJECTION_TIMEOUT_FRACTION)
                + " of limits.llm_timeout_ms (" + oneDecimal(timeoutSeconds) + "s)");
        return new RateAssessment(true, warn, summary);
    }

    private static Optional<LlamaCppLogEvidence.Timing> latestTiming(LlamaCppLogEvidence evidence,
                                                                     String kind,
                                                                     int minimumTokens) {
        return evidence.timings().stream()
                .filter(timing -> kind.equals(timing.kind()))
                .filter(timing -> timing.tokens() >= minimumTokens)
                .max(Comparator.comparingInt(LlamaCppLogEvidence.Timing::taskId));
    }

    private static long llmTimeoutMs(DoctorContext ctx) {
        Map<String, Object> limits = CfgUtil.map(ctx.cfg().data.get("limits"));
        return Math.max(1L, CfgUtil.longAt(limits, "llm_timeout_ms", DEFAULT_LLM_TIMEOUT_MS));
    }

    private static LlamaCppLogEvidence readManagedLogEvidence(DoctorContext ctx,
                                                              LlamaCppPreflight.Report preflight) {
        if (preflight == null || !preflight.managed()) {
            return LlamaCppLogEvidence.parse("");
        }
        Path logPath = ctx.talosHome().resolve("logs").resolve("llama_cpp-" + preflight.port() + ".log");
        if (!Files.isRegularFile(logPath)) {
            return LlamaCppLogEvidence.parse("");
        }
        try {
            return LlamaCppLogEvidence.parse(Files.readString(logPath, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return LlamaCppLogEvidence.parse("");
        }
    }

    private static String elapsedSeconds(Duration elapsed) {
        return String.format(Locale.ROOT, "%.1fs", elapsed.toMillis() / 1000.0);
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String formatInt(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.0f%%", fraction * 100.0d);
    }

    @FunctionalInterface
    interface TimingEvidenceSource {
        LlamaCppLogEvidence read(DoctorContext ctx, LlamaCppPreflight.Report preflight);

        default boolean canImproveAfterSample(DoctorContext ctx, LlamaCppPreflight.Report preflight) {
            return preflight != null && preflight.managed();
        }
    }

    private record RateAssessment(boolean measured, boolean warn, String summary) {
        private static RateAssessment unmeasured() {
            return new RateAssessment(false, false, "rates unmeasured");
        }
    }
}
