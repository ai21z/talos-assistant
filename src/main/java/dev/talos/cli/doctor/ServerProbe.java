package dev.talos.cli.doctor;

import dev.talos.core.EngineRuntimeConfig;
import dev.talos.core.engine.EngineRegistry;
import dev.talos.engine.llamacpp.LlamaCppPreflight;
import dev.talos.spi.ChatModelEngine;
import dev.talos.spi.types.ChatRequest;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

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

    private final boolean startServer;

    public ServerProbe(boolean startServer) {
        this.startServer = startServer;
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
            String reply = chatEngine.chat(new ChatRequest(
                    runtime.backend(), runtime.model(), "",
                    "Reply exactly " + MODEL_SMOKE_TOKEN + " and no other text.", List.of(), null));
            String normalized = reply == null ? "" : reply.strip();
            int replyChars = normalized.length();
            if (!normalized.toUpperCase(Locale.ROOT).contains(MODEL_SMOKE_TOKEN)) {
                return ProbeResult.fail(id(),
                        "model smoke reply did not contain " + MODEL_SMOKE_TOKEN
                                + " (" + replyChars + " reply chars)",
                        "check the model profile, chat template, tool mode, and llama.cpp log under ~/.talos/logs/llama_cpp-"
                                + preflight.port() + ".log");
            }
            return ProbeResult.pass(id(),
                    "end-to-end model smoke verified (" + replyChars + " reply chars);"
                            + " managed server released again");
        } catch (Exception e) {
            return ProbeResult.fail(id(),
                    "end-to-end server start failed: " + e.getMessage(),
                    "check ~/.talos/logs/llama_cpp-" + preflight.port() + ".log");
        }
    }
}
