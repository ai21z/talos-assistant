package dev.talos.core.llm;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.core.EngineRuntimeConfig;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.tool.ToolProtocolText;
import dev.talos.core.util.Sanitize;
import dev.talos.core.util.UiChrome;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ControlFlowFailure;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportAttempt;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.SamplingControls;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolSpec;
import dev.talos.spi.types.ToolChoiceMode;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Local-first LLM client with dual transport:
 *  - PLACEHOLDER (default): deterministic, sanitized, capped output; no backend calls.
 *  - ENGINE (opt-in): uses SPI engines discovered via ServiceLoader; still sanitized/capped,
 *    and stream/non-stream parity is preserved by assembling the same token sequence.
 * <p>
 * Tests depend on PLACEHOLDER behavior (sanitized, capped, deterministic, stream==non-stream parity).
 */
public final class LlmClient implements AutoCloseable {

    private enum TransportMode { PLACEHOLDER, ENGINE }

    private final Config cfg;
    private final TransportMode mode;
    private final LlmEngineResolver engineResolver;
    private final LlmCallBudget callBudget;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile String backend;          // ENGINE mode: current backend id (e.g., "ollama")
    private volatile String model;            // model name (or backend-qualified accepted via setModel)
    private final long responseMaxChars;

    /**
     * P2 - no-progress wall-clock budget for a single LLM call (one full
     * {@link #chatStreamFull} or {@link #chatFull} invocation, including all
     * internal retries). Once token chunks are arriving, the idle watchdog below
     * becomes the live-progress guard so slow local models are not killed solely
     * because they crossed the first wall-clock threshold.
     *
     * <p><b>Why this exists:</b> the JDK {@code HttpRequest.timeout(...)} only
     * fires while waiting for the <em>next</em> chunk; once chunks trickle in
     * slowly the request never times out, so a wedged or runaway local model
     * can hang the UI for tens of minutes (observed: 23 minutes in a real
     * transcript before the loop hit max-iterations). The non-streaming
     * legacy path in {@code AssistantTurnExecutor} already wraps its call in
     * a {@code CompletableFuture.get(timeout)}, but the streaming path and
     * the tool-call-loop re-prompts had no equivalent. This field, plus
     * {@link LlmCallBudget#run}, closes that gap. Calls that never produce a
     * first chunk still fail at this budget; calls that keep producing chunks
     * can continue past it until they finish or go idle.
     *
     * <p>Default 300_000 ms (5 min), overridable via
     * {@code limits.llm_timeout_ms} in config or per-call via the
     * {@code wallClockMs} parameter on the new public overloads.
     */
    private final long defaultWallClockBudgetMs;

    /**
     * P2 - idle-stream timeout (ms). If no chunk (text or tool-call) arrives
     * from the engine within this window, the worker is interrupted and the
     * call returns a synthesized abort marker (same shape as the wall-clock
     * trip).
     *
     * <p><b>Why this exists in addition to the wall-clock budget:</b> a short
     * prompt that wedges the model produces a long stretch of zero tokens
     * well before a long wall-clock budget would fire. The user-visible UX is "Talos
     * is frozen". An idle watchdog catches that case in tens of seconds, not
     * minutes, while genuine chunk progress keeps big local-model generations alive.
     *
     * <p>Configurable via {@code limits.llm_idle_ms}; default 60_000 ms.
     * Set ≤0 to disable.
     */
    private final long defaultIdleMs;

    /**
     * P2 - externally-settable cancel hook. The REPL (or future Ctrl-C
     * handler) calls {@link #setCancelSupplier} once at bootstrap to install
     * a {@link Supplier} that flips to {@code true} when the user requests
     * abort. The streaming loop polls it on every chunk; the watchdog polls
     * it once per tick. Default no-op preserves test behavior.
     */
    private volatile Supplier<Boolean> externalCancel = () -> false;

    /**
     * P2 - companion reset callback for {@link #externalCancel}. Invoked at
     * the top of each public streaming/non-streaming call so a Ctrl-C
     * pressed during turn N cannot leak into turn N+1. Default no-op
     * preserves test behavior (tests never set a cancel supplier).
     */
    private volatile Runnable externalCancelReset = () -> {};

    /** Tool definitions to include in engine chat requests (native tool calling). */
    private volatile List<ToolSpec> toolSpecs = List.of();
    private final boolean configuredNativeTools;
    private volatile Capabilities preparedCapabilities;

    // Telemetry: track truncation events
    private final AtomicInteger truncationCount = new AtomicInteger();

    // ── N4 scripted-LLM test seam ────────────────────────────────────
    //
    // When set, chatFull / chatStreamFull bypass the real transport and
    // emit these responses in order. The cursor advances per call and
    // clamps to the final response after exhaustion. Null means normal
    // transport behavior is preserved (tests that don't use the
    // scripted path are unaffected).
    //
    // Rationale: the harness (ExecutorScenarioRunner) needs to drive
    // AssistantTurnExecutor.execute() deterministically with a known
    // model-output sequence, without an interface extraction or a
    // speculative abstraction. See docs/architecture/
    // talos-harness-main-plan.md §8 N4 and §10 discussion item 2 for
    // the design decision (option (a): minimal factory).
    private volatile java.util.List<String> scriptedResponses = null;
    private volatile RuntimeException scriptedFailure = null;
    private final java.util.concurrent.atomic.AtomicInteger scriptedCursor =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** Config-level sampling overrides from llm.sampling (T740); unset fields leave server defaults. */
    private final SamplingControls configSampling;

    public LlmClient(Config cfg) {
        this(cfg, null);
    }

    LlmClient(Config cfg, LlmEngineResolver engineResolver) {
        this.cfg = (cfg == null ? new Config() : cfg);

        // ---- transport mode (default: PLACEHOLDER for tests/local safety) ----
        // When a Config is provided, ignore env here to keep tests deterministic.
        // If you want ENGINE in the app, set it in config under llm.transport.
        Map<String, Object> llmBlock = CfgUtil.map(this.cfg.data.get("llm"));
        String transport = String.valueOf(llmBlock.getOrDefault("transport", "placeholder"));
        this.mode = "engine".equalsIgnoreCase(transport) ? TransportMode.ENGINE : TransportMode.PLACEHOLDER;
        this.configSampling = parseConfigSampling(CfgUtil.map(llmBlock.get("sampling")));
        this.configuredNativeTools = CfgUtil.boolAt(
                CfgUtil.map(this.cfg.data.get("tools")), "native_calling", true);

        // ---- defaults compatible with existing tests ----
        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(this.cfg);
        this.model = sanitizeModelName(runtime.model());
        this.backend = runtime.backend();

        // ---- limits.response_max_chars (honor exactly, min=1) ----
        Map<String, Object> limits = CfgUtil.map(this.cfg.data.get("limits"));
        long cfgMax = 10 * 1024 * 1024L; // fallback: 10 MiB
        if (limits != null) {
            Object v = limits.get("response_max_chars");
            if (v instanceof Number n)      cfgMax = n.longValue();
            else if (v != null) try {       cfgMax = Long.parseLong(String.valueOf(v)); } catch (Exception ignore) {}
        }
        this.responseMaxChars = Math.max(1, cfgMax);

        // ---- limits.llm_timeout_ms (P2 wall-clock budget; min=1000) ----
        long cfgBudget = 300_000L; // fallback: 5 minutes
        if (limits != null) {
            Object v = limits.get("llm_timeout_ms");
            if (v instanceof Number n)      cfgBudget = n.longValue();
            else if (v != null) try {       cfgBudget = Long.parseLong(String.valueOf(v)); } catch (Exception ignore) {}
        }
        this.defaultWallClockBudgetMs = Math.max(1000L, cfgBudget);

        // ---- limits.llm_idle_ms (P2 idle-stream watchdog; min=1000, ≤0 disables) ----
        long cfgIdle = 60_000L; // fallback: 60s between chunks
        if (limits != null) {
            Object v = limits.get("llm_idle_ms");
            if (v instanceof Number n)      cfgIdle = n.longValue();
            else if (v != null) try {       cfgIdle = Long.parseLong(String.valueOf(v)); } catch (Exception ignore) {}
        }
        // 0 or negative ⇒ disabled (preserved verbatim); otherwise floor at 1s.
        this.defaultIdleMs = cfgIdle <= 0 ? cfgIdle : Math.max(1000L, cfgIdle);
        this.callBudget = new LlmCallBudget(defaultIdleMs);

        // Create the engine seam only when ENGINE mode is actually used.
        if (this.mode == TransportMode.ENGINE) {
            this.engineResolver = engineResolver == null
                    ? new RegistryLlmEngineResolver(this.cfg)
                    : engineResolver;
            // if config already contains a qualified model, keep it
            if (this.model.contains("/")) {
                String[] parts = this.model.split("/", 2);
                this.backend = parts[0];
                this.model = parts[1];
            }
            try { this.engineResolver.select(this.backend, this.model); } catch (Exception ignore) {}
        } else {
            this.engineResolver = null;
        }
    }

    /** Get number of truncation events that occurred (for telemetry/status reporting). */
    public int getTruncationCount() {
        return truncationCount.get();
    }

    /** Reset telemetry counters. */
    public void resetTelemetry() {
        truncationCount.set(0);
    }

    // ── N4 scripted-LLM test seam (factories + helper) ────────────────

    /**
     * Test-only factory: returns an LlmClient whose
     * {@link #chatFull(List)} and {@link #chatStreamFull(List, Consumer)}
     * emit {@code responses} in order, one per call. After the list is
     * exhausted the last response is repeated (so a scripted run cannot
     * accidentally fall through to a real backend).
     *
     * <p>Ignores engine / Ollama configuration entirely - no backend
     * connection is attempted.
     *
     * @param responses ordered list of model outputs, one per turn
     *                  (initial response + follow-ups after tool calls)
     */
    public static LlmClient scripted(java.util.List<String> responses) {
        java.util.List<String> safe = (responses == null || responses.isEmpty())
                ? java.util.List.of("") : java.util.List.copyOf(responses);
        LlmClient c = new LlmClient(placeholderOnlyConfig());
        c.scriptedResponses = safe;
        return c;
    }

    /** Single-response variant of {@link #scripted(java.util.List)}. */
    public static LlmClient scripted(String response) {
        return scripted(java.util.List.of(response == null ? "" : response));
    }

    /**
     * Test-only factory: returns an LlmClient that throws {@code failure}
     * from structured full/stream chat entrypoints. This lets executor tests
     * exercise backend exception handling without opening a real engine.
     */
    public static LlmClient scriptedFailure(RuntimeException failure) {
        LlmClient c = new LlmClient(placeholderOnlyConfig());
        c.scriptedFailure = failure == null
                ? new RuntimeException("scripted LLM failure")
                : failure;
        return c;
    }

    private static Config placeholderOnlyConfig() {
        Config config = new Config();
        Map<String, Object> llm = new java.util.LinkedHashMap<>(CfgUtil.map(config.data.get("llm")));
        llm.put("transport", "placeholder");
        config.data.put("llm", llm);
        return config;
    }

    /**
     * Advance the scripted cursor and return the next scripted response.
     * Clamps to the last entry after exhaustion. Called from
     * {@link #chatFull} / {@link #chatStreamFull} when
     * {@link #scriptedResponses} is set.
     */
    private String nextScriptedResponse() {
        int next = scriptedCursor.getAndIncrement();
        int idx = Math.min(next, scriptedResponses.size() - 1);
        return scriptedResponses.get(idx);
    }

    public String getModel() {
        return (mode == TransportMode.ENGINE ? backend + "/" + model : model);
    }

    /**
     * Diagnostic view of the context window that this client actually enforces.
     * The values come from the same calculation used before sending an engine
     * request, so REPL diagnostics cannot drift from runtime budgeting.
     */
    public ContextWindowDiagnostics contextWindowDiagnostics() {
        int configured = TokenBudget.fromConfig(cfg).contextMaxTokens();
        int engineWindow = engineContextWindowTokens();
        int effective = effectiveContextWindowTokens(configured, engineWindow);
        return new ContextWindowDiagnostics(getModel(), backend, configured, engineWindow, effective);
    }

    public record ContextWindowDiagnostics(
            String model,
            String backend,
            int configuredWindowTokens,
            int engineWindowTokens,
            int effectiveWindowTokens
    ) {}

    /** Accepts "backend/model" or just "model" (in PLACEHOLDER, backend is ignored). */
    public void setModel(String name) {
        String sanitized = sanitizeModelName(Objects.toString(name, ""));
        if (sanitized.isBlank()) return;

        if (mode == TransportMode.ENGINE && sanitized.contains("/")) {
            String[] parts = sanitized.split("/", 2);
            this.backend = parts[0];
            this.model = parts[1];
            if (engineResolver != null) try { engineResolver.select(this.backend, this.model); } catch (Exception ignore) {}
        } else {
            this.model = sanitized;
            if (mode == TransportMode.ENGINE && engineResolver != null) try { engineResolver.select(this.backend, this.model); } catch (Exception ignore) {}
        }
        this.preparedCapabilities = null;
    }

    /**
     * Set the tool specifications that will be included in engine chat requests.
     * Called during bootstrap after tools are registered.
     */
    public void setToolSpecs(List<ToolSpec> specs) {
        this.toolSpecs = (specs == null || specs.isEmpty()) ? List.of() : List.copyOf(specs);
    }

    /** Get the current tool specifications (for testing). */
    public List<ToolSpec> getToolSpecs() {
        return toolSpecs;
    }

    /** Prepares the selected engine and returns whether native tools are permitted for this turn. */
    public boolean prepareNativeTools() throws Exception {
        if (!configuredNativeTools) return false;
        if (mode != TransportMode.ENGINE || engineResolver == null) return true;
        if (!engineResolver.capabilitiesAuthoritative()) return true;
        Capabilities prepared = engineResolver.prepareCapabilities();
        preparedCapabilities = prepared;
        return prepared != null && prepared.nativeTools();
    }

    /** Current no-start view used by prompt inspection and turn-policy recording. */
    public boolean nativeToolsCurrentlyEnabled() {
        if (!configuredNativeTools) return false;
        if (mode != TransportMode.ENGINE || engineResolver == null) return true;
        if (!engineResolver.capabilitiesAuthoritative()) return true;
        Capabilities current = preparedCapabilities;
        if (current == null) {
            try {
                current = engineResolver.capabilities();
            } catch (Exception failure) {
                rethrowControlFlow(failure);
                return false;
            }
        }
        return current != null && current.nativeTools();
    }

    /** Returns the selected engine's bounded identity evidence without starting it. */
    public ModelIdentitySnapshot modelIdentity() {
        if (mode != TransportMode.ENGINE || engineResolver == null) {
            return ModelIdentitySnapshot.empty();
        }
        try {
            ModelIdentitySnapshot identity = engineResolver.modelIdentity();
            return identity == null ? ModelIdentitySnapshot.empty() : identity;
        } catch (RuntimeException failure) {
            rethrowControlFlow(failure);
            return ModelIdentitySnapshot.empty();
        }
    }

    public boolean supportsRequiredToolChoice() {
        if (mode != TransportMode.ENGINE || engineResolver == null) return false;
        if ("ollama".equalsIgnoreCase(backend)) return false;
        try {
            Capabilities current = effectiveCapabilities();
            return current != null && current.requiredToolChoice();
        } catch (Exception failure) {
            rethrowControlFlow(failure);
            return false;
        }
    }

    public boolean supportsNamedToolChoice() {
        if (mode != TransportMode.ENGINE || engineResolver == null) return false;
        if ("ollama".equalsIgnoreCase(backend)) return false;
        try {
            Capabilities current = effectiveCapabilities();
            return current != null && current.namedToolChoice();
        } catch (Exception failure) {
            rethrowControlFlow(failure);
            return false;
        }
    }

    /**
     * P2 - install an external cancel supplier (e.g., a Ctrl-C handler that
     * flips an {@link java.util.concurrent.atomic.AtomicBoolean}). Polled on
     * every stream chunk and once per watchdog tick. Pass {@code null} or a
     * {@code () -> false} supplier to disable.
     */
    public void setCancelSupplier(Supplier<Boolean> cancel) {
        this.externalCancel = (cancel == null) ? () -> false : cancel;
    }

    /**
     * P2 - install an external "reset the cancel flag" callback. Invoked
     * automatically at the top of {@link #chatStreamFull} and
     * {@link #chatFull} so a Ctrl-C pressed during turn N cannot leak into
     * turn N+1. The REPL owns the {@link java.util.concurrent.atomic.AtomicBoolean}
     * and supplies {@code flag::set} bound to {@code false} here.
     */
    public void setCancelResetHook(Runnable reset) {
        this.externalCancelReset = (reset == null) ? () -> {} : reset;
    }

    /** Non-streaming chat: sanitized, capped; in ENGINE mode uses the same streaming path for parity. */
    public String chat(String system, String user, List<Map<String, String>> snippets) {
        if (mode == TransportMode.PLACEHOLDER) {
            return captureTextGeneration(
                    ProviderResponseCapture.Source.PLACEHOLDER_RUNTIME,
                    ignored -> TextGeneration.synthetic(placeholderAnswer(system, user, snippets)));
        }
        // ENGINE: assemble from the streaming path to keep parity exact
        return captureTextGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                generation -> engineAssembled(
                        system, user, snippets, null, Duration.ofSeconds(90), () -> false,
                        ChatRequestControls.defaults(), generation));
    }

    /** Optional timeout overload (kept for Mode code that uses it). */
    public String chat(String system, String user, List<Map<String, String>> snippets, Duration timeout) throws TimeoutException {
        if (mode == TransportMode.PLACEHOLDER) {
            return captureTextGeneration(
                    ProviderResponseCapture.Source.PLACEHOLDER_RUNTIME,
                    ignored -> TextGeneration.synthetic(placeholderAnswer(system, user, snippets)));
        }
        return captureTextGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                generation -> engineAssembled(system, user, snippets, null,
                        (timeout == null ? Duration.ofSeconds(90) : timeout), () -> false,
                        ChatRequestControls.defaults(), generation));
    }

    /** Streaming chat. Parity with non-stream is guaranteed by sharing the same assembly logic. */
    public String chatStream(String system,
                             String user,
                             List<Map<String, String>> snippets,
                             Consumer<String> onChunk) {
        if (mode == TransportMode.PLACEHOLDER) {
            // emit single sanitized chunk to satisfy stream lifecycle, keep parity
            return captureTextGeneration(
                    ProviderResponseCapture.Source.PLACEHOLDER_RUNTIME,
                    ignored -> {
                        String full = placeholderAnswer(system, user, snippets);
                        if (onChunk != null && !full.isEmpty()) onChunk.accept(full);
                        return TextGeneration.synthetic(full);
                    });
        }
        return captureTextGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                generation -> engineAssembled(
                        system, user, snippets, onChunk, Duration.ofSeconds(90), () -> false,
                        ChatRequestControls.defaults(), generation));
    }

    public String chatStream(String system,
                             String user,
                             List<Map<String, String>> snippets,
                             Consumer<String> onChunk,
                             Duration timeout,
                             Supplier<Boolean> cancelled) throws TimeoutException {
        if (mode == TransportMode.PLACEHOLDER) {
            return captureTextGeneration(
                    ProviderResponseCapture.Source.PLACEHOLDER_RUNTIME,
                    ignored -> {
                        if (isCancelled(cancelled)) return TextGeneration.aborted("");
                        String full = placeholderAnswer(system, user, snippets);
                        if (isCancelled(cancelled)) return TextGeneration.aborted("");
                        if (onChunk != null && !full.isEmpty()) onChunk.accept(full);
                        return TextGeneration.synthetic(full);
                    });
        }
        return captureTextGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                generation -> engineAssembled(system, user, snippets, onChunk,
                        (timeout == null ? Duration.ofSeconds(90) : timeout),
                        (cancelled == null ? () -> false : cancelled),
                        ChatRequestControls.defaults(), generation));
    }

    /* -------- Multi-turn conversation (structured messages) -------- */

    /**
     * Chat using structured conversation messages (system/user/assistant turns).
     * <p>In ENGINE mode, this triggers the /api/chat endpoint with proper role tags.
     * In PLACEHOLDER mode, falls back to extracting system/user for deterministic output.
     */
    public String chat(List<ChatMessage> messages) {
        if (mode == TransportMode.PLACEHOLDER) {
            return captureTextGeneration(
                    ProviderResponseCapture.Source.PLACEHOLDER_RUNTIME,
                    ignored -> TextGeneration.synthetic(placeholderFromMessages(messages)));
        }
        return captureTextGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                generation -> engineAssembledWithMessages(
                        messages, null, Duration.ofSeconds(90), () -> false, generation));
    }

    /** Multi-turn chat with timeout. */
    public String chat(List<ChatMessage> messages, Duration timeout) throws TimeoutException {
        if (mode == TransportMode.PLACEHOLDER) {
            return captureTextGeneration(
                    ProviderResponseCapture.Source.PLACEHOLDER_RUNTIME,
                    ignored -> TextGeneration.synthetic(placeholderFromMessages(messages)));
        }
        return captureTextGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                generation -> engineAssembledWithMessages(messages, null,
                        (timeout == null ? Duration.ofSeconds(90) : timeout), () -> false, generation));
    }

    /**
     * Streaming chat using structured conversation messages.
     * Each token chunk is delivered via the {@code onChunk} callback as it arrives.
     * Returns the fully assembled response.
     */
    public String chatStream(List<ChatMessage> messages, Consumer<String> onChunk) {
        if (mode == TransportMode.PLACEHOLDER) {
            return captureTextGeneration(
                    ProviderResponseCapture.Source.PLACEHOLDER_RUNTIME,
                    ignored -> {
                        String full = placeholderFromMessages(messages);
                        if (onChunk != null && !full.isEmpty()) onChunk.accept(full);
                        return TextGeneration.synthetic(full);
                    });
        }
        return captureTextGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                generation -> engineAssembledWithMessages(
                        messages, onChunk, Duration.ofSeconds(90), () -> false, generation));
    }

    /**
     * Streaming chat with timeout and cancellation support.
     */
    public String chatStream(List<ChatMessage> messages,
                             Consumer<String> onChunk,
                             Duration timeout,
                             Supplier<Boolean> cancelled) throws TimeoutException {
        if (mode == TransportMode.PLACEHOLDER) {
            return captureTextGeneration(
                    ProviderResponseCapture.Source.PLACEHOLDER_RUNTIME,
                    ignored -> {
                        if (isCancelled(cancelled)) return TextGeneration.aborted("");
                        String full = placeholderFromMessages(messages);
                        if (isCancelled(cancelled)) return TextGeneration.aborted("");
                        if (onChunk != null && !full.isEmpty()) onChunk.accept(full);
                        return TextGeneration.synthetic(full);
                    });
        }
        return captureTextGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                generation -> engineAssembledWithMessages(messages, onChunk,
                        (timeout == null ? Duration.ofSeconds(90) : timeout),
                        (cancelled == null ? () -> false : cancelled), generation));
    }

    /* -------- Convenience (non-RAG) wrappers -------- */

    public String chatPlain(String prompt) {
        String p = Sanitize.sanitizeForPrompt(Objects.toString(prompt, ""));
        return chat("(system) You are Talos, a local-first workspace assistant.", p, List.of());
    }

    public String chatPlain(String system, String user) {
        String sys = Sanitize.sanitizeForPrompt(Objects.toString(system, ""));
        String usr = Sanitize.sanitizeForPrompt(Objects.toString(user, ""));
        return chat(sys, usr, List.of());
    }

    /**
     * Explicit background-maintenance lane for conversation compaction.
     * Source ownership is selected by this typed entrypoint, never by prompt prose.
     */
    public String chatPlainBackgroundMaintenance(String system, String user) {
        String sys = Sanitize.sanitizeForPrompt(Objects.toString(system, ""));
        String usr = Sanitize.sanitizeForPrompt(Objects.toString(user, ""));
        if (mode == TransportMode.PLACEHOLDER) {
            return captureTextGeneration(
                    ProviderResponseCapture.Source.PLACEHOLDER_RUNTIME,
                    ignored -> TextGeneration.synthetic(placeholderAnswer(sys, usr, List.of())));
        }
        return captureTextGeneration(
                ProviderResponseCapture.Source.BACKGROUND_PROVIDER,
                generation -> engineAssembled(
                        sys,
                        usr,
                        List.of(),
                        null,
                        Duration.ofSeconds(90),
                        () -> false,
                        backgroundMaintenanceControls(),
                        generation));
    }

    /* ======================= Internals ======================= */

    private String placeholderAnswer(String system, String user, List<Map<String, String>> snippets) {
        // sanitize inputs for prompt
        final String sys = Sanitize.sanitizeForPrompt(Objects.toString(system, ""));
        final String usr = Sanitize.sanitizeForPrompt(Objects.toString(user, ""));
        // deterministic context flattening (also sanitized for prompt)
        StringBuilder ctx = new StringBuilder();
        if (snippets != null) {
            for (Map<String, String> s : snippets) {
                if (s == null) continue;
                String path = Sanitize.sanitizeForPrompt(Objects.toString(s.get("path"), ""));
                String text = Sanitize.sanitizeForPrompt(Objects.toString(s.get("text"), ""));
                if (!path.isBlank()) ctx.append("\n\n[citation] ").append(path);
                if (!text.isBlank()) ctx.append("\n").append(text);
            }
        }
        // produce deterministic local text
        String raw = synthesizeLocalAnswer(sys, usr, ctx.toString());
        // output sanitation mirrors RenderEngine (strip ANSI/control + think tags) + hard cap
        String cleaned = Sanitize.stripThinkTags(raw);
        cleaned = Sanitize.sanitizeForOutput(cleaned);
        cleaned = Sanitize.hardTruncate(cleaned, safeCap(), truncationCount::incrementAndGet);
        return cleaned;
    }

    /**
     * ENGINE mode: assemble from token stream, sanitizing per-chunk and obeying the same hard cap.
     * This guarantees:
     *  - stream vs non-stream parity (both use this path)
     *  - no ANSI/control or <think> survives
     *
     * <p>Transient engine errors are retried up to {@link #MAX_RETRIES} times with
     * exponential back-off. Non-transient {@link EngineException} subtypes (connection
     * refused, model not found) propagate immediately for structured handling upstream.
     */
    private TextGeneration engineAssembled(String system,
                                           String user,
                                           List<Map<String, String>> snippets,
                                           Consumer<String> onChunk,
                                           Duration timeout,
                                           Supplier<Boolean> cancelled,
                                           ChatRequestControls controls,
                                           ProviderResponseCapture.Generation generation) {
        // sanitize prompt parts for model consumption
        final String sys = Sanitize.sanitizeForPrompt(Objects.toString(system, ""));
        final String usr = Sanitize.sanitizeForPrompt(Objects.toString(user, ""));
        List<Map<String,String>> sn = sanitizeSnippets(snippets);

        return LlmRetryExecutor.executeIndexed(MAX_RETRIES, tryNumber -> {
            ChatRequest req = new ChatRequest(
                    backend, model, sys, usr, sn, timeout, List.of(), List.of(),
                    withConfigSampling(controls));
            return invokePhysicalTextAttempt(
                    generation,
                    tryNumber == 0
                            ? ProviderResponseCapture.AttemptKind.PRIMARY
                            : ProviderResponseCapture.AttemptKind.TRANSIENT_RETRY,
                    tryNumber == 0
                            ? ProviderResponseCapture.RetryReason.NONE
                            : ProviderResponseCapture.RetryReason.TRANSIENT_ENGINE_FAILURE,
                    req,
                    onChunk != null,
                    onChunk,
                    cancelled);
        });
    }

    private static ChatRequestControls backgroundMaintenanceControls() {
        return new ChatRequestControls(
                ToolChoiceMode.AUTO,
                "",
                ResponseFormatMode.TEXT,
                "",
                List.of(PromptDebugCapture.BACKGROUND_MAINTENANCE_TAG));
    }

    private static List<Map<String,String>> sanitizeSnippets(List<Map<String,String>> xs) {
        if (xs == null) return List.of();
        java.util.ArrayList<Map<String,String>> out = new java.util.ArrayList<>(xs.size());
        for (Map<String,String> s : xs) {
            if (s == null) continue;
            String path = Sanitize.sanitizeForPrompt(Objects.toString(s.get("path"), ""));
            String text = Sanitize.sanitizeForPrompt(Objects.toString(s.get("text"), ""));
            out.add(Map.of("path", path, "text", text));
        }
        return java.util.Collections.unmodifiableList(out);
    }

    private int safeCap() {
        long cap = responseMaxChars;
        if (cap > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (cap < 1) return 1;
        return (int) cap;
    }

    /**
     * PLACEHOLDER mode: extract system/user from structured messages and delegate
     * to the existing deterministic answer generation (keeps tests working).
     */
    private String placeholderFromMessages(List<ChatMessage> messages) {
        String sys = messages.stream()
                .filter(m -> "system".equals(m.role()))
                .map(ChatMessage::content)
                .findFirst().orElse("");
        String usr = messages.stream()
                .filter(m -> "user".equals(m.role()))
                .reduce((a, b) -> b)   // last user message
                .map(ChatMessage::content)
                .orElse("");
        return placeholderAnswer(sys, usr, List.of());
    }

    /**
     * ENGINE mode: assemble from token stream using structured messages via /api/chat.
     * Sanitization, hard cap, and retry logic are applied identically to the legacy path.
     */
    private TextGeneration engineAssembledWithMessages(List<ChatMessage> messages,
                                                       Consumer<String> onChunk,
                                                       Duration timeout,
                                                       Supplier<Boolean> cancelled,
                                                       ProviderResponseCapture.Generation generation) {
        // Sanitize message content while preserving tool-call structure
        List<ChatMessage> sanitized = messages.stream()
                .map(m -> new ChatMessage(
                        m.role(),
                        Sanitize.sanitizeMessageContent(Objects.toString(m.content(), "")),
                        m.toolCalls(),
                        m.toolCallId()))
                .toList();

        return LlmRetryExecutor.executeIndexed(MAX_RETRIES, tryNumber -> {
            ChatRequest req = new ChatRequest(
                    backend, model, "", "", List.of(), timeout, sanitized, List.of());
            return invokePhysicalTextAttempt(
                    generation,
                    tryNumber == 0
                            ? ProviderResponseCapture.AttemptKind.PRIMARY
                            : ProviderResponseCapture.AttemptKind.TRANSIENT_RETRY,
                    tryNumber == 0
                            ? ProviderResponseCapture.RetryReason.NONE
                            : ProviderResponseCapture.RetryReason.TRANSIENT_ENGINE_FAILURE,
                    req,
                    onChunk != null,
                    onChunk,
                    cancelled);
        });
    }

    /**
     * Result of a structured streaming chat, carrying both assembled text
     * and any native tool calls returned by the model.
     *
     * @param text        assembled prose text (sanitized, think-tags stripped)
     * @param toolCalls   native tool calls from the model (empty if none)
     * @param finishReason provider finish reason ("" when not reported)
     * @param abortReason why Talos aborted the generation ("" when it
     *                    completed normally) - explicit metadata so
     *                    consumers never have to re-derive the abort from
     *                    the marker text
     */
    public record StreamResult(
            String text,
            List<ChatMessage.NativeToolCall> toolCalls,
            String finishReason,
            String abortReason
    ) {
        private static final MetadataRegistry METADATA = new MetadataRegistry();

        public StreamResult(String text, List<ChatMessage.NativeToolCall> toolCalls) {
            this(text, toolCalls, "", "");
        }

        public StreamResult(String text, List<ChatMessage.NativeToolCall> toolCalls, String finishReason) {
            this(text, toolCalls, finishReason, "");
        }

        public StreamResult(
                String text,
                List<ChatMessage.NativeToolCall> toolCalls,
                String finishReason,
                String abortReason) {
            this.text = text == null ? "" : text;
            this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            this.finishReason = finishReason == null ? "" : finishReason.strip();
            this.abortReason = abortReason == null ? "" : abortReason.strip();
        }

        public StreamResult(
                String text,
                List<ChatMessage.NativeToolCall> toolCalls,
                String finishReason,
                String abortReason,
                ProviderResponseCapture.AbortOrigin abortOrigin) {
            this(text, toolCalls, finishReason, abortReason);
            METADATA.put(this, new Metadata(
                    normalizedAbortOrigin(abortOrigin, this.abortReason),
                    completionFor(this.finishReason, this.abortReason),
                    false,
                    false,
                    ProviderResponseCapture.Receipt.none()));
        }

        StreamResult(
                String text,
                List<ChatMessage.NativeToolCall> toolCalls,
                String finishReason,
                String abortReason,
                ProviderResponseCapture.Completion completion,
                boolean normalized,
                boolean truncated) {
            this(text, toolCalls, finishReason, abortReason);
            METADATA.put(this, new Metadata(
                    normalizedAbortOrigin(null, this.abortReason),
                    completion == null
                            ? completionFor(this.finishReason, this.abortReason)
                            : completion,
                    normalized,
                    truncated,
                    ProviderResponseCapture.Receipt.none()));
        }

        public StreamResult(
                String text,
                List<ChatMessage.NativeToolCall> toolCalls,
                String finishReason,
                String abortReason,
                ProviderResponseCapture.AbortOrigin abortOrigin,
                ProviderResponseCapture.Completion completion,
                boolean normalized,
                boolean truncated,
                ProviderResponseCapture.Receipt provenance) {
            this(text, toolCalls, finishReason, abortReason);
            METADATA.put(this, new Metadata(
                    normalizedAbortOrigin(abortOrigin, this.abortReason),
                    completion == null
                            ? completionFor(this.finishReason, this.abortReason)
                            : completion,
                    normalized,
                    truncated,
                    provenance == null
                            ? ProviderResponseCapture.Receipt.none()
                            : provenance));
        }

        StreamResult withProvenance(ProviderResponseCapture.Receipt receipt) {
            return new StreamResult(text, toolCalls, finishReason, abortReason,
                    abortOrigin(), completion(), normalized(), truncated(), receipt);
        }

        public ProviderResponseCapture.AbortOrigin abortOrigin() {
            Metadata metadata = METADATA.get(this);
            return metadata == null
                    ? normalizedAbortOrigin(null, abortReason)
                    : metadata.abortOrigin();
        }

        public ProviderResponseCapture.Completion completion() {
            Metadata metadata = METADATA.get(this);
            return metadata == null
                    ? completionFor(finishReason, abortReason)
                    : metadata.completion();
        }

        public boolean normalized() {
            Metadata metadata = METADATA.get(this);
            return metadata != null && metadata.normalized();
        }

        public boolean truncated() {
            Metadata metadata = METADATA.get(this);
            return metadata != null && metadata.truncated();
        }

        public ProviderResponseCapture.Receipt provenance() {
            Metadata metadata = METADATA.get(this);
            return metadata == null
                    ? ProviderResponseCapture.Receipt.none()
                    : metadata.provenance();
        }

        /** Returns true if the model returned native tool calls. */
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }

        /** Returns true when the provider stopped because the requested output cap was reached. */
        public boolean outputLimitReached() {
            return "length".equalsIgnoreCase(finishReason);
        }

        /**
         * True when Talos aborted the generation (wall-clock, idle
         * watchdog, repetition breaker, interrupt, or transport loss after
         * partial output) instead of the model completing it. Any text is
         * at best a partial answer and must not be treated as authoritative.
         */
        public boolean aborted() {
            return !abortReason.isEmpty();
        }

        private static ProviderResponseCapture.Completion completionFor(
                String finishReason,
                String abortReason) {
            if (abortReason != null && !abortReason.isBlank()) {
                return ProviderResponseCapture.Completion.RUNTIME_ABORT;
            }
            return "length".equalsIgnoreCase(finishReason)
                    ? ProviderResponseCapture.Completion.PROVIDER_LENGTH
                    : recognizedNormalFinishReason(finishReason)
                            ? ProviderResponseCapture.Completion.PROVIDER_EOS
                            : ProviderResponseCapture.Completion.UNRECOGNIZED_PROVIDER_STOP;
        }

        private static ProviderResponseCapture.AbortOrigin normalizedAbortOrigin(
                ProviderResponseCapture.AbortOrigin requested,
                String abortReason) {
            if (abortReason == null || abortReason.isBlank()) {
                return ProviderResponseCapture.AbortOrigin.NONE;
            }
            return requested == null
                    ? ProviderResponseCapture.AbortOrigin.RUNTIME_THREAD_INTERRUPT
                    : requested;
        }

        private record Metadata(
                ProviderResponseCapture.AbortOrigin abortOrigin,
                ProviderResponseCapture.Completion completion,
                boolean normalized,
                boolean truncated,
                ProviderResponseCapture.Receipt provenance) {}

        /**
         * Weak identity sidecar keeps the established four-component public
         * record contract while carrying owner-bound evidence capabilities.
         */
        private static final class MetadataRegistry {
            private final ReferenceQueue<StreamResult> queue = new ReferenceQueue<>();
            private final ConcurrentHashMap<IdentityReference, Metadata> entries =
                    new ConcurrentHashMap<>();

            private void put(StreamResult result, Metadata metadata) {
                drain();
                entries.put(new IdentityReference(result, queue), metadata);
            }

            private Metadata get(StreamResult result) {
                drain();
                return entries.get(new IdentityReference(result, null));
            }

            private void drain() {
                IdentityReference reference;
                while ((reference = (IdentityReference) queue.poll()) != null) {
                    entries.remove(reference);
                }
            }
        }

        private static final class IdentityReference extends WeakReference<StreamResult> {
            private final int identityHash;

            private IdentityReference(
                    StreamResult referent,
                    ReferenceQueue<StreamResult> queue) {
                super(Objects.requireNonNull(referent, "stream result"), queue);
                identityHash = System.identityHashCode(referent);
            }

            @Override
            public int hashCode() {
                return identityHash;
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) return true;
                if (!(other instanceof IdentityReference that)) return false;
                StreamResult mine = get();
                return mine != null && mine == that.get();
            }
        }
    }

    /**
     * Streaming chat that returns both text and native tool calls.
     *
     * <p>When the engine supports native tool calling and the model returns
     * structured {@code tool_calls}, they are captured separately from the
     * text stream. This enables the tool-call loop to process them without
     * regex parsing.
     *
     * @param messages structured conversation messages
     * @param onChunk  callback for text display chunks (may be null)
     * @return stream result with text and tool calls
     */
    public StreamResult chatStreamFull(List<ChatMessage> messages, Consumer<String> onChunk) {
        return chatStreamFull(messages, onChunk, defaultWallClockBudgetMs);
    }

    public StreamResult chatStreamFull(
            List<ChatMessage> messages,
            Consumer<String> onChunk,
            List<ToolSpec> requestToolSpecs) {
        return chatStreamFull(messages, onChunk, defaultWallClockBudgetMs,
                requestToolSpecs, legacyToolControls(requestToolSpecs));
    }

    public StreamResult chatStreamFull(
            List<ChatMessage> messages,
            Consumer<String> onChunk,
            List<ToolSpec> requestToolSpecs,
            ChatRequestControls controls) {
        return chatStreamFull(messages, onChunk, defaultWallClockBudgetMs, requestToolSpecs, controls);
    }

    /**
     * Streaming chat with an explicit wall-clock budget for the whole call.
     *
     * <p>If the engine does not produce a complete response within
     * {@code wallClockMs}, the worker thread is interrupted and a
     * {@link StreamResult} carrying a partial-text + budget-exceeded marker
     * is returned. Any chunks already delivered to {@code onChunk} are
     * preserved (the user has already seen them).
     *
     * <p>Set {@code wallClockMs <= 0} to disable the budget (legacy behavior).
     *
     * @param messages    structured conversation messages
     * @param onChunk     callback for text display chunks (may be null)
     * @param wallClockMs hard deadline in ms; ≤0 disables
     */
    public StreamResult chatStreamFull(List<ChatMessage> messages,
                                       Consumer<String> onChunk,
                                       long wallClockMs) {
        return chatStreamFull(
                messages, onChunk, wallClockMs, null, legacyToolControls(null));
    }

    public StreamResult chatStreamFull(List<ChatMessage> messages,
                                       Consumer<String> onChunk,
                                       long wallClockMs,
                                       List<ToolSpec> requestToolSpecs) {
        return chatStreamFull(
                messages,
                onChunk,
                wallClockMs,
                requestToolSpecs,
                legacyToolControls(requestToolSpecs));
    }

    public StreamResult chatStreamFull(List<ChatMessage> messages,
                                       Consumer<String> onChunk,
                                       long wallClockMs,
                                       List<ToolSpec> requestToolSpecs,
                                       ChatRequestControls controls) {
        // P2 - clear any Ctrl-C from the previous turn so stale cancels
        // don't immediately short-circuit this call.
        externalCancelReset.run();
        ProviderResponseCapture.Source source = responseSource(controls);
        ProviderResponseCapture.ToolAuthorityMode toolAuthorityMode =
                toolAuthorityMode(source, controls);
        List<ToolSpec> selectedToolSpecs = selectedToolSpecs(requestToolSpecs);
        ProviderResponseCapture.ToolAuthorityContract toolAuthorityContract =
                toolAuthorityContract(
                        source, toolAuthorityMode, selectedToolSpecs, controls);
        return captureStreamGeneration(source, toolAuthorityContract, generation -> {
            if (scriptedFailure != null) {
                throw scriptedFailure;
            }
            if (scriptedResponses != null) {
                String r = nextScriptedResponse();
                if (onChunk != null && !r.isEmpty()) onChunk.accept(r);
                return recordSyntheticStreamResult(syntheticStreamResult(r), source, generation);
            }
            if (mode == TransportMode.PLACEHOLDER) {
                String full = placeholderFromMessages(messages);
                if (onChunk != null && !full.isEmpty()) onChunk.accept(full);
                return recordSyntheticStreamResult(syntheticStreamResult(full), source, generation);
            }
            // P2 - track the time of the last decoded-provider progress callback;
            // the watchdog (set up inside withWallClockBudget) abort()s the worker
            // if no decoded chunk arrives for {@link #defaultIdleMs} ms. This is
            // not evidence about transport settlement. The cancel supplier
            // OR-combines the engine-level cancel and the externally-set cancel
            // hook so a Ctrl-C future patch can plug in without touching this
            // method.
            AtomicLong lastChunkAt = new AtomicLong(System.currentTimeMillis());
            RepetitionBreaker breaker = new RepetitionBreaker();
            Consumer<String> decodedProgressSink = chunk -> {
                lastChunkAt.set(System.currentTimeMillis());
                breaker.onChunk(chunk);
            };
            Supplier<Boolean> cancel = this.externalCancel;
            Duration engineRequestTimeout = engineRequestTimeout(wallClockMs);
            return callBudget.run(
                    activeStream -> engineAssembledWithMessagesFullTracked(
                            messages, onChunk, decodedProgressSink, engineRequestTimeout, cancel,
                             lastChunkAt, activeStream, selectedToolSpecs, controls, true, generation),
                    wallClockMs,
                    lastChunkAt,
                    "streaming chat",
                    breaker,
                    cancel,
                    generation);
        });
    }

    /**
     * Non-streaming chat that returns both text and native tool calls.
     * Used by the tool-call loop for re-prompting after tool execution.
     */
    public StreamResult chatFull(List<ChatMessage> messages) {
        return chatFull(messages, defaultWallClockBudgetMs);
    }

    public StreamResult chatFull(List<ChatMessage> messages, List<ToolSpec> requestToolSpecs) {
        return chatFull(messages, defaultWallClockBudgetMs,
                requestToolSpecs, legacyToolControls(requestToolSpecs));
    }

    public StreamResult chatFull(
            List<ChatMessage> messages,
            List<ToolSpec> requestToolSpecs,
            ChatRequestControls controls) {
        return chatFull(messages, defaultWallClockBudgetMs, requestToolSpecs, controls);
    }

    /**
     * Non-streaming chat with an explicit wall-clock budget.
     * See {@link #chatStreamFull(List, Consumer, long)}.
     */
    public StreamResult chatFull(List<ChatMessage> messages, long wallClockMs) {
        return chatFull(messages, wallClockMs, null, legacyToolControls(null));
    }

    public StreamResult chatFull(List<ChatMessage> messages,
                                 long wallClockMs,
                                 List<ToolSpec> requestToolSpecs) {
        return chatFull(
                messages,
                wallClockMs,
                requestToolSpecs,
                legacyToolControls(requestToolSpecs));
    }

    public StreamResult chatFull(List<ChatMessage> messages,
                                 long wallClockMs,
                                 List<ToolSpec> requestToolSpecs,
                                 ChatRequestControls controls) {
        // P2 - see chatStreamFull: clear stale cancel flag per call.
        externalCancelReset.run();
        ProviderResponseCapture.Source source = responseSource(controls);
        ProviderResponseCapture.ToolAuthorityMode toolAuthorityMode =
                toolAuthorityMode(source, controls);
        List<ToolSpec> selectedToolSpecs = selectedToolSpecs(requestToolSpecs);
        ProviderResponseCapture.ToolAuthorityContract toolAuthorityContract =
                toolAuthorityContract(
                        source, toolAuthorityMode, selectedToolSpecs, controls);
        return captureStreamGeneration(source, toolAuthorityContract, generation -> {
            if (scriptedFailure != null) {
                throw scriptedFailure;
            }
            if (scriptedResponses != null) {
                return recordSyntheticStreamResult(
                        syntheticStreamResult(nextScriptedResponse()), source, generation);
            }
            if (mode == TransportMode.PLACEHOLDER) {
                return recordSyntheticStreamResult(
                        syntheticStreamResult(placeholderFromMessages(messages)), source, generation);
            }
            // P2 - same idle-watchdog + cancel-hook plumbing as chatStreamFull.
            AtomicLong lastChunkAt = new AtomicLong(System.currentTimeMillis());
            RepetitionBreaker breaker = new RepetitionBreaker();
            Consumer<String> decodedProgressSink = chunk -> {
                lastChunkAt.set(System.currentTimeMillis());
                breaker.onChunk(chunk);
            };
            Supplier<Boolean> cancel = this.externalCancel;
            Duration engineRequestTimeout = engineRequestTimeout(wallClockMs);
            return callBudget.run(
                    activeStream -> engineAssembledWithMessagesFullTracked(
                            messages, null, decodedProgressSink, engineRequestTimeout, cancel,
                             lastChunkAt, activeStream, selectedToolSpecs, controls, false, generation),
                    wallClockMs,
                    lastChunkAt,
                    "non-streaming chat",
                    breaker,
                    cancel,
                    generation);
        });
    }

    private String captureTextGeneration(
            ProviderResponseCapture.Source source,
            Function<ProviderResponseCapture.Generation, TextGeneration> generationWork) {
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                source, ProviderResponseCapture.purposeFor(source));
        try {
            TextGeneration result = generationWork.apply(generation);
            if (result == null) return null;
            if (result.completion() == ProviderResponseCapture.Completion.RUNTIME_ABORT) {
                abandonGenerationForAbort(generation, result.abortOrigin());
                return result.text();
            }
            ProviderResponseCapture.Receipt receipt = result.provenance();
            if (receipt == null || !generation.generationId().equals(receipt.generationId())) {
                ProviderResponseCapture.Attempt synthetic = generation.startAttempt(
                        ProviderResponseCapture.AttemptKind.PRIMARY,
                        ProviderResponseCapture.RetryReason.NONE);
                receipt = synthetic.respond(new ProviderResponseCapture.Response(
                        result.text(), 0, source, result.completion(),
                        result.normalized(), result.truncated()));
            }
            generation.complete(receipt);
            return result.text();
        } catch (RuntimeException | Error failure) {
            generation.fail(generationFailure(source, failure, generation));
            throw failure;
        }
    }

    private StreamResult captureStreamGeneration(
            ProviderResponseCapture.Source source,
            ProviderResponseCapture.ToolAuthorityContract toolAuthorityContract,
            Function<ProviderResponseCapture.Generation, StreamResult> generationWork) {
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                source, ProviderResponseCapture.purposeFor(source), toolAuthorityContract);
        try {
            StreamResult result = generationWork.apply(generation);
            if (result != null
                    && result.provenance() != null
                    && generation.generationId().equals(result.provenance().generationId())) {
                generation.complete(result.provenance());
            }
            return result;
        } catch (RuntimeException | Error failure) {
            generation.fail(generationFailure(source, failure, generation));
            throw failure;
        }
    }

    private static ProviderResponseCapture.Failure generationFailure(
            ProviderResponseCapture.Source source,
            Throwable failure,
            ProviderResponseCapture.Generation generation) {
        if (source == ProviderResponseCapture.Source.SCRIPTED_HARNESS) {
            return ProviderResponseCapture.Failure.HARNESS_EXCEPTION;
        }
        if (source == ProviderResponseCapture.Source.PLACEHOLDER_RUNTIME) {
            return ProviderResponseCapture.Failure.RUNTIME_EXCEPTION;
        }
        ProviderResponseCapture.Failure latestAttemptFailure = generation == null
                ? null : generation.latestAttemptFailure();
        if (latestAttemptFailure != null) {
            return latestAttemptFailure;
        }
        return failureAtTransportBoundary(
                generation != null && generation.latestAttemptRequestDispatched(), failure);
    }

    private ProviderResponseCapture.Source responseSource(ChatRequestControls controls) {
        if (scriptedFailure != null || scriptedResponses != null) {
            return ProviderResponseCapture.Source.SCRIPTED_HARNESS;
        }
        if (mode == TransportMode.PLACEHOLDER) {
            return ProviderResponseCapture.Source.PLACEHOLDER_RUNTIME;
        }
        boolean background = controls != null
                && controls.debugTags().stream()
                        .anyMatch(PromptDebugCapture.BACKGROUND_MAINTENANCE_TAG::equals);
        return providerSource(!background);
    }

    private static ProviderResponseCapture.ToolAuthorityMode toolAuthorityMode(
            ProviderResponseCapture.Source source,
            ChatRequestControls controls
    ) {
        if (source != ProviderResponseCapture.Source.FOREGROUND_PROVIDER) {
            return ProviderResponseCapture.ToolAuthorityMode.NONE;
        }
        ChatRequestControls safe = controls == null
                ? ChatRequestControls.defaults()
                : controls;
        return safe.toolAuthorityMode();
    }

    private List<ToolSpec> selectedToolSpecs(List<ToolSpec> requestToolSpecs) {
        List<ToolSpec> selected = requestToolSpecs == null
                ? toolSpecs
                : requestToolSpecs;
        return selected == null ? List.of() : List.copyOf(selected);
    }

    private static ProviderResponseCapture.ToolAuthorityContract toolAuthorityContract(
            ProviderResponseCapture.Source source,
            ProviderResponseCapture.ToolAuthorityMode mode,
            List<ToolSpec> selectedToolSpecs,
            ChatRequestControls controls
    ) {
        if (source != ProviderResponseCapture.Source.FOREGROUND_PROVIDER
                || mode == null
                || mode == ProviderResponseCapture.ToolAuthorityMode.NONE) {
            return ProviderResponseCapture.ToolAuthorityContract.none();
        }
        ChatRequestControls safe = controls == null
                ? ChatRequestControls.defaults()
                : controls;
        List<String> selectedNames = selectedToolSpecs == null
                ? List.of()
                : selectedToolSpecs.stream()
                        .map(spec -> Objects.requireNonNull(
                                spec, "selected provider tool spec").name())
                        .toList();
        return ProviderResponseCapture.ToolAuthorityContract.bound(
                mode,
                selectedNames,
                safe.toolChoice(),
                safe.namedTool());
    }

    /**
     * Classifies one explicit runtime-selected tool surface without consulting
     * provider output. Callers retain ownership of whether that surface is
     * permitted for the request.
     */
    public ProviderResponseCapture.ToolAuthorityMode toolAuthorityModeForToolSurface(
            List<ToolSpec> requestToolSpecs
    ) {
        List<ToolSpec> requested = requestToolSpecs == null
                ? toolSpecs
                : List.copyOf(requestToolSpecs);
        if (requested.isEmpty()) {
            return ProviderResponseCapture.ToolAuthorityMode.NONE;
        }
        return nativeToolsCurrentlyEnabled()
                ? ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE
                : ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON;
    }

    private ChatRequestControls legacyToolControls(List<ToolSpec> requestToolSpecs) {
        return ChatRequestControls.defaults().withToolAuthorityMode(
                toolAuthorityModeForToolSurface(requestToolSpecs));
    }

    private static ProviderResponseCapture.Source providerSource(boolean foreground) {
        return foreground
                ? ProviderResponseCapture.Source.FOREGROUND_PROVIDER
                : ProviderResponseCapture.Source.BACKGROUND_PROVIDER;
    }

    private static ProviderResponseCapture.Failure failureType(
            ProviderResponseCapture.Source source) {
        return switch (source) {
            case SCRIPTED_HARNESS -> ProviderResponseCapture.Failure.HARNESS_EXCEPTION;
            case PLACEHOLDER_RUNTIME -> ProviderResponseCapture.Failure.RUNTIME_EXCEPTION;
            case FOREGROUND_PROVIDER, BACKGROUND_PROVIDER ->
                    ProviderResponseCapture.Failure.PROVIDER_EXCEPTION;
        };
    }

    private static StreamResult syntheticStreamResult(String text) {
        return new StreamResult(
                text,
                List.of(),
                "",
                "",
                ProviderResponseCapture.Completion.SYNTHETIC_RETURN,
                false,
                false);
    }

    private record TextGeneration(
            String text,
            ProviderResponseCapture.Completion completion,
            boolean normalized,
            boolean truncated,
            ProviderResponseCapture.AbortOrigin abortOrigin,
            ProviderResponseCapture.Receipt provenance) {
        private TextGeneration(
                String text,
                ProviderResponseCapture.Completion completion,
                boolean normalized,
                boolean truncated) {
            this(text, completion, normalized, truncated,
                    ProviderResponseCapture.AbortOrigin.NONE,
                    ProviderResponseCapture.Receipt.none());
        }

        private TextGeneration {
            text = Objects.toString(text, "");
            completion = Objects.requireNonNull(completion, "completion");
            abortOrigin = abortOrigin == null
                    ? ProviderResponseCapture.AbortOrigin.NONE
                    : abortOrigin;
            provenance = provenance == null
                    ? ProviderResponseCapture.Receipt.none()
                    : provenance;
        }

        static TextGeneration synthetic(String text) {
            return new TextGeneration(
                    text,
                    ProviderResponseCapture.Completion.SYNTHETIC_RETURN,
                    false,
                    false);
        }

        static TextGeneration aborted(String text) {
            return new TextGeneration(
                    text,
                    ProviderResponseCapture.Completion.RUNTIME_ABORT,
                    false,
                    false,
                    ProviderResponseCapture.AbortOrigin.OPERATOR_EXTERNAL,
                    ProviderResponseCapture.Receipt.none());
        }

        TextGeneration withProvenance(ProviderResponseCapture.Receipt receipt) {
            return new TextGeneration(
                    text, completion, normalized, truncated, abortOrigin, receipt);
        }
    }

    private TextGeneration invokePhysicalTextAttempt(
            ProviderResponseCapture.Generation generation,
            ProviderResponseCapture.AttemptKind attemptKind,
            ProviderResponseCapture.RetryReason retryReason,
            ChatRequest request,
            boolean streamRequest,
            Consumer<String> onChunk,
            Supplier<Boolean> cancelled) throws Exception {
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(attemptKind, retryReason);
        AtomicBoolean transportDispatched = new AtomicBoolean();
        AtomicBoolean transportUnobserved = new AtomicBoolean();
        ProviderTransportHandoff handoff = new ProviderTransportHandoff() {
            @Override
            public void requestDispatched() {
                attempt.requestDispatched();
                transportDispatched.set(true);
            }

            @Override
            public void requestDispatched(ProviderTransportHandoff.DispatchEvidence evidence) {
                attempt.requestDispatched(evidence);
                transportDispatched.set(true);
            }

            @Override
            public ProviderTransportAttempt admitTransport(
                    ProviderTransportHandoff.DispatchEvidence evidence) {
                ProviderTransportAttempt transport = attempt.admitTransport(evidence);
                transportDispatched.set(true);
                return transport;
            }

            @Override
            public void transportUnobserved() {
                attempt.transportUnobserved();
                transportUnobserved.set(true);
            }
        };
        try {
            PromptDebugCapture.record(PromptDebugSnapshot.fromChatRequest(request, streamRequest));
        } catch (RuntimeException | Error preDispatch) {
            attempt.fail(ProviderResponseCapture.Failure.RUNTIME_PRE_DISPATCH);
            generation.fail(ProviderResponseCapture.Failure.RUNTIME_PRE_DISPATCH);
            throw preDispatch;
        }
        try {
            TextGeneration result = assembleFromStream(
                    engineResolver.chatStream(request, handoff),
                    onChunk,
                    cancelled,
                    origin -> abandonGenerationForAbort(generation, origin));
            if (result.completion() == ProviderResponseCapture.Completion.RUNTIME_ABORT) {
                return result;
            }
            ProviderResponseCapture.Receipt receipt = attempt.respond(
                    new ProviderResponseCapture.Response(
                            result.text(),
                            0,
                            generation.source(),
                            result.completion(),
                            result.normalized(),
                            result.truncated()));
            return result.withProvenance(receipt);
        } catch (EngineException.ContextBudgetExceeded contextFailure) {
            ProviderResponseCapture.Failure classified = failureAtTransportBoundary(
                    transportDispatched.get(), transportUnobserved.get(), contextFailure);
            attempt.fail(classified);
            generation.fail(classified);
            throw contextFailure;
        } catch (EngineException.Transient transientFailure) {
            ProviderResponseCapture.Failure classified = failureAtTransportBoundary(
                    transportDispatched.get(), transportUnobserved.get(), transientFailure);
            attempt.fail(classified);
            throw transientFailure;
        } catch (EngineException.MalformedResponse malformed) {
            ProviderResponseCapture.Failure classified = failureAtTransportBoundary(
                    transportDispatched.get(), transportUnobserved.get(), malformed);
            attempt.fail(classified);
            if (!transportDispatched.get() && !transportUnobserved.get()) {
                generation.fail(classified);
            }
            throw malformed;
        } catch (EngineException engineFailure) {
            ProviderResponseCapture.Failure classified = failureAtTransportBoundary(
                    transportDispatched.get(), transportUnobserved.get(), engineFailure);
            attempt.fail(classified);
            generation.fail(classified);
            throw engineFailure;
        } catch (RuntimeException | Error runtimeFailure) {
            ProviderResponseCapture.Failure classified = failureAtTransportBoundary(
                    transportDispatched.get(), transportUnobserved.get(), runtimeFailure);
            attempt.fail(classified);
            generation.fail(classified);
            throw runtimeFailure;
        } catch (Exception checkedProviderFailure) {
            ProviderResponseCapture.Failure classified = failureAtTransportBoundary(
                    transportDispatched.get(), transportUnobserved.get(), checkedProviderFailure);
            attempt.fail(classified);
            generation.fail(classified);
            throw checkedProviderFailure;
        }
    }

    private static ProviderResponseCapture.Failure failureAtTransportBoundary(
            boolean transportDispatched,
            Throwable failure) {
        return failureAtTransportBoundary(transportDispatched, false, failure);
    }

    private static ProviderResponseCapture.Failure failureAtTransportBoundary(
            boolean transportDispatched,
            boolean transportUnobserved,
            Throwable failure) {
        if (ControlFlowFailure.isInterruption(failure)) {
            return ProviderResponseCapture.Failure.INTERRUPTION_CAUSE_UNPROVEN;
        }
        if (transportUnobserved) {
            return ProviderResponseCapture.Failure.TRANSPORT_UNOBSERVED;
        }
        if (!transportDispatched) {
            return ProviderResponseCapture.Failure.RUNTIME_PRE_DISPATCH;
        }
        if (failure instanceof EngineException.Transient) {
            return ProviderResponseCapture.Failure.PROVIDER_TRANSIENT;
        }
        if (failure instanceof EngineException.MalformedResponse) {
            return ProviderResponseCapture.Failure.PROVIDER_MALFORMED_RESPONSE;
        }
        if (!(failure instanceof EngineException)
                && (failure instanceof RuntimeException || failure instanceof Error)) {
            return ProviderResponseCapture.Failure.RUNTIME_EXCEPTION;
        }
        return ProviderResponseCapture.Failure.PROVIDER_EXCEPTION;
    }

    private static StreamResult recordAttemptResponse(
            StreamResult result,
            ProviderResponseCapture.Source source,
            ProviderResponseCapture.Attempt attempt) {
        if (result == null) return null;
        ProviderResponseCapture.Receipt receipt = attempt.respond(
                new ProviderResponseCapture.Response(
                        result.text(),
                        result.toolCalls(),
                        source,
                        result.completion(),
                        result.normalized(),
                        result.truncated(),
                        ToolProtocolText.containsToolCalls(result.text())));
        return result.withProvenance(receipt);
    }

    private static StreamResult recordSyntheticStreamResult(
            StreamResult result,
            ProviderResponseCapture.Source source,
            ProviderResponseCapture.Generation generation) {
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        return recordAttemptResponse(result, source, attempt);
    }

    /**
     * Best-effort close of the currently-active engine stream handle, as
     * installed by the worker inside {@link #engineAssembledWithMessagesFull}.
     * Called from the watchdog thread (or the abort {@code catch} blocks in
     * {@link LlmCallBudget#run}) to force the worker's blocked socket
     * read to throw and unwind - no interrupt alone can do that.
     *
     * <p>Uses {@code getAndSet(null)} so repeated callers (e.g. watchdog then
     * the {@code ExecutionException} catch) don't double-close. All exceptions
     * are swallowed: the stream may already be closed by the worker's
     * try-with-resources on a concurrent normal exit.
     *
     * <p>Package-private for unit testing (see {@code LlmClientAsyncCloseTest}).
     */
    static void closeActiveStream(AtomicReference<AutoCloseable> ref) {
        LlmCallBudget.closeActiveStream(ref);
    }

    /**
     * P2 - variant of {@link #engineAssembledWithMessagesFull} that keeps
     * visible output separate from decoded-provider progress. The latter
     * owns idle-watchdog and repetition accounting even while protocol text
     * remains buffered and invisible. Behavior is otherwise identical.
     */
    private StreamResult engineAssembledWithMessagesFullTracked(List<ChatMessage> messages,
                                                                Consumer<String> displaySink,
                                                                Consumer<String> decodedProgressSink,
                                                                Duration timeout,
                                                                Supplier<Boolean> cancelled,
                                                                AtomicLong lastChunkAt,
                                                                AtomicReference<AutoCloseable> activeStream,
                                                                List<ToolSpec> requestToolSpecs,
                                                                ChatRequestControls controls,
                                                                boolean streamRequest,
                                                                ProviderResponseCapture.Generation generation) {
        // The structured stream consumer classifies both the explicit cancel
        // supplier and an interrupt of its budget-owned worker thread. Keep
        // those signals distinct so an internal shutdownNow cannot be
        // reported as an operator cancellation.
        // The pre-engine heartbeat prime lives in LlmCallBudget.run, which
        // sets and captures the same value so thread-start latency can never
        // read as first-token progress (T988). Priming here on the worker
        // thread would race that capture.
        return engineAssembledWithMessagesFull(
                messages, displaySink, decodedProgressSink, timeout, cancelled, activeStream,
                requestToolSpecs, controls, streamRequest, generation);
    }

    /**
     * ENGINE mode: assemble from token stream using structured messages via /api/chat.
     * Returns a {@link StreamResult} carrying both the assembled text and any
     * native tool calls.
     */
    private StreamResult engineAssembledWithMessagesFull(List<ChatMessage> messages,
                                                         Consumer<String> onChunk,
                                                         Consumer<String> progressOnlySink,
                                                         Duration timeout,
                                                         Supplier<Boolean> cancelled,
                                                         AtomicReference<AutoCloseable> activeStream,
                                                         List<ToolSpec> requestToolSpecs,
                                                         ChatRequestControls controls,
                                                         boolean streamRequest,
                                                         ProviderResponseCapture.Generation generation) {
        // Sanitize message content while preserving tool-call structure
        // (toolCalls, toolCallId) - these carry native tool-call context that
        // OllamaEngine.serializeChatMessage needs for proper /api/chat formatting.
        List<ChatMessage> sanitized = messages.stream()
                .map(m -> new ChatMessage(
                        m.role(),
                        Sanitize.sanitizeMessageContent(Objects.toString(m.content(), "")),
                        m.toolCalls(),
                        m.toolCallId()))
                .toList();
        List<ToolSpec> tools = effectiveToolSpecs(
                requestToolSpecs, generation.toolAuthorityMode());
        ChatRequestControls requestControls = withConfigSampling(controls);
        List<ChatMessage> requestMessages = fitMessagesToContextBudget(sanitized, tools, requestControls);
        if (requestMessages.size() < sanitized.size()) {
            requestControls = withDebugTag(requestControls, "context-budget-trimmed");
            requestMessages = fitMessagesToContextBudget(requestMessages, tools, requestControls);
        }
        final ChatRequestControls finalRequestControls = requestControls;
        final List<ChatMessage> finalRequestMessages = requestMessages;

        return LlmRetryExecutor.executeIndexed(MAX_RETRIES, tryNumber -> {
            ChatRequest req = new ChatRequest(
                    backend, model, "", "", List.of(), timeout, finalRequestMessages,
                    tools, finalRequestControls);
            try {
                boolean deferVisibleChunks = shouldDeferVisibleChunksForCapRetry(req);
                Consumer<String> firstAttemptSink = deferVisibleChunks
                        ? null
                        : onChunk;
                StreamResult first = invokePhysicalStreamAttempt(
                        generation,
                        tryNumber == 0
                                ? ProviderResponseCapture.AttemptKind.PRIMARY
                                : ProviderResponseCapture.AttemptKind.TRANSIENT_RETRY,
                        tryNumber == 0
                                ? ProviderResponseCapture.RetryReason.NONE
                                : ProviderResponseCapture.RetryReason.TRANSIENT_ENGINE_FAILURE,
                        req,
                        streamRequest,
                        activeStream,
                        cancelled,
                        firstAttemptSink,
                        progressOnlySink,
                        !deferVisibleChunks,
                        false);
                if (!shouldRetryCapTruncatedToolResult(req, first)) {
                    if (deferVisibleChunks && onChunk != null && first != null && !first.text().isEmpty()) {
                        onChunk.accept(first.text());
                    }
                    return first;
                }
                // T989 recovery: a per-request output cap cut a tool-required
                // generation before any usable tool call was produced. One
                // retry with the cap lifted; the managed server --predict
                // backstop still bounds it.
                ChatRequest liftedReq = new ChatRequest(
                        req.backend, req.model, req.systemPrompt, req.userPrompt,
                        req.snippets, req.timeout, req.messages, req.tools,
                        withDebugTag(req.controls, "output-cap-lifted-retry").withMaxOutputTokens(0));
                return invokePhysicalStreamAttempt(
                        generation,
                        ProviderResponseCapture.AttemptKind.OUTPUT_CAP_LIFT_RETRY,
                        ProviderResponseCapture.RetryReason.OUTPUT_CAP_TRUNCATION,
                        liftedReq,
                        streamRequest,
                        activeStream,
                        cancelled,
                        onChunk,
                        progressOnlySink,
                        true,
                        false);
            } catch (EngineException.MalformedResponse malformed) {
                if ((generation.attached() && generation.terminal())
                        || !shouldRetryCompatToolArgumentsNonStreaming(malformed, req)) {
                    generation.fail(ProviderResponseCapture.Failure.PROVIDER_MALFORMED_RESPONSE);
                    throw malformed;
                }
                // A capped request that truncated mid-arguments fails the same
                // way non-streamed, so the retry also lifts the cap (T989).
                boolean liftCap = req.controls != null && req.controls.maxOutputTokens() > 0;
                ChatRequestControls retryControls = withDebugTag(
                        req.controls,
                        liftCap ? "compat-tool-arguments-uncapped-retry" : "compat-tool-arguments-nonstream-retry");
                if (liftCap) {
                    retryControls = retryControls.withMaxOutputTokens(0);
                }
                ChatRequest retryReq = new ChatRequest(
                        req.backend, req.model, req.systemPrompt, req.userPrompt,
                        req.snippets, req.timeout, req.messages, req.tools,
                        retryControls);
                return invokePhysicalStreamAttempt(
                        generation,
                        ProviderResponseCapture.AttemptKind.COMPAT_NON_STREAM_RETRY,
                        ProviderResponseCapture.RetryReason.MALFORMED_TOOL_ARGUMENTS,
                        retryReq,
                        false,
                        activeStream,
                        cancelled,
                        onChunk,
                        progressOnlySink,
                        true,
                        true);
            }
        });
    }

    private StreamResult invokePhysicalStreamAttempt(
            ProviderResponseCapture.Generation generation,
            ProviderResponseCapture.AttemptKind attemptKind,
            ProviderResponseCapture.RetryReason retryReason,
            ChatRequest request,
            boolean streamRequest,
            AtomicReference<AutoCloseable> activeStream,
            Supplier<Boolean> cancelled,
            Consumer<String> onChunk,
            Consumer<String> progressOnlySink,
            boolean partialOutputCommitsAttempt,
            boolean nonStreamingCompat) {
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(attemptKind, retryReason);
        AtomicBoolean transportDispatched = new AtomicBoolean();
        AtomicBoolean transportUnobserved = new AtomicBoolean();
        ProviderTransportHandoff handoff = new ProviderTransportHandoff() {
            @Override
            public void requestDispatched() {
                attempt.requestDispatched();
                transportDispatched.set(true);
            }

            @Override
            public void requestDispatched(ProviderTransportHandoff.DispatchEvidence evidence) {
                attempt.requestDispatched(evidence);
                transportDispatched.set(true);
            }

            @Override
            public ProviderTransportAttempt admitTransport(
                    ProviderTransportHandoff.DispatchEvidence evidence) {
                ProviderTransportAttempt transport = attempt.admitTransport(evidence);
                transportDispatched.set(true);
                return transport;
            }

            @Override
            public void transportUnobserved() {
                attempt.transportUnobserved();
                transportUnobserved.set(true);
            }
        };
        try {
            PromptDebugCapture.record(PromptDebugSnapshot.fromChatRequest(request, streamRequest));
        } catch (RuntimeException | Error preDispatch) {
            attempt.fail(ProviderResponseCapture.Failure.RUNTIME_PRE_DISPATCH);
            generation.fail(ProviderResponseCapture.Failure.RUNTIME_PRE_DISPATCH);
            throw preDispatch;
        }
        try {
            java.util.stream.Stream<TokenChunk> stream = nonStreamingCompat
                    ? engineResolver.chatStreamNonStreaming(request, handoff)
                    : engineResolver.chatStream(request, handoff);
            StreamResult result = consumeEngineStream(
                    stream,
                    activeStream,
                    cancelled,
                    onChunk,
                    progressOnlySink,
                    partialOutputCommitsAttempt,
                    origin -> {
                        if (origin != ProviderResponseCapture.AbortOrigin.PROVIDER_TRANSPORT) {
                            abandonGenerationForAbort(generation, origin);
                        }
                    });
            if (result != null && result.aborted()) {
                if (result.abortOrigin() == ProviderResponseCapture.AbortOrigin.PROVIDER_TRANSPORT) {
                    ProviderResponseCapture.Failure classified = transportUnobserved.get()
                            ? ProviderResponseCapture.Failure.TRANSPORT_UNOBSERVED
                            : transportDispatched.get()
                                    ? ProviderResponseCapture.Failure.PROVIDER_TRANSIENT
                                    : ProviderResponseCapture.Failure.RUNTIME_PRE_DISPATCH;
                    attempt.fail(classified);
                    generation.fail(classified);
                }
                return result;
            }
            return recordAttemptResponse(result, generation.source(), attempt);
        } catch (EngineException.ContextBudgetExceeded contextFailure) {
            ProviderResponseCapture.Failure classified = failureAtTransportBoundary(
                    transportDispatched.get(), transportUnobserved.get(), contextFailure);
            attempt.fail(classified);
            generation.fail(classified);
            throw contextFailure;
        } catch (EngineException.Transient transientFailure) {
            ProviderResponseCapture.Failure classified = failureAtTransportBoundary(
                    transportDispatched.get(), transportUnobserved.get(), transientFailure);
            attempt.fail(classified);
            throw transientFailure;
        } catch (EngineException.MalformedResponse malformed) {
            ProviderResponseCapture.Failure classified = failureAtTransportBoundary(
                    transportDispatched.get(), transportUnobserved.get(), malformed);
            attempt.fail(classified);
            if (!transportDispatched.get() && !transportUnobserved.get()) {
                generation.fail(classified);
            }
            throw malformed;
        } catch (EngineException engineFailure) {
            ProviderResponseCapture.Failure classified = failureAtTransportBoundary(
                    transportDispatched.get(), transportUnobserved.get(), engineFailure);
            attempt.fail(classified);
            generation.fail(classified);
            throw engineFailure;
        } catch (RuntimeException | Error runtimeFailure) {
            ProviderResponseCapture.Failure classified = failureAtTransportBoundary(
                    transportDispatched.get(), transportUnobserved.get(), runtimeFailure);
            attempt.fail(classified);
            generation.fail(classified);
            throw runtimeFailure;
        } catch (Exception checkedProviderFailure) {
            ProviderResponseCapture.Failure classified = failureAtTransportBoundary(
                    transportDispatched.get(), transportUnobserved.get(), checkedProviderFailure);
            attempt.fail(classified);
            generation.fail(classified);
            rethrowControlFlow(checkedProviderFailure);
            throw new EngineException.ResponseError(
                    0, checkedProviderFailure.getMessage(), checkedProviderFailure);
        }
    }

    private static void abandonGenerationForAbort(
            ProviderResponseCapture.Generation generation,
            ProviderResponseCapture.AbortOrigin origin) {
        ProviderResponseCapture.Abandonment abandonment = switch (origin) {
            case OPERATOR_EXTERNAL -> ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED;
            case RUNTIME_STREAM_CLOSE, PROVIDER_TRANSPORT ->
                    ProviderResponseCapture.Abandonment.RUNTIME_STREAM_CLOSED;
            case RUNTIME_WATCHDOG -> ProviderResponseCapture.Abandonment.WALL_CLOCK_TIMEOUT;
            case RUNTIME_THREAD_INTERRUPT, NONE ->
                    ProviderResponseCapture.Abandonment.INTERRUPTION_CAUSE_UNPROVEN;
        };
        generation.abandon(abandonment);
    }

    private static boolean shouldDeferVisibleChunksForCapRetry(ChatRequest request) {
        if (request == null || request.controls == null) return false;
        if (request.controls.maxOutputTokens() <= 0) return false;
        if (request.tools == null || request.tools.isEmpty()) return false;
        ToolChoiceMode mode = request.controls.toolChoice();
        return mode == ToolChoiceMode.REQUIRED || mode == ToolChoiceMode.NAMED;
    }

    private Duration engineRequestTimeout(long wallClockMs) {
        long timeoutMs = wallClockMs > 0 ? wallClockMs : defaultWallClockBudgetMs;
        return Duration.ofMillis(Math.max(1000L, timeoutMs));
    }

    private StreamResult consumeEngineStream(java.util.stream.Stream<TokenChunk> stream,
                                             AtomicReference<AutoCloseable> activeStream,
                                             Supplier<Boolean> cancelled,
                                             Consumer<String> onChunk,
                                             Consumer<String> progressOnlySink,
                                             boolean partialOutputCommitsAttempt,
                                             Consumer<ProviderResponseCapture.AbortOrigin>
                                                     abortBeforeStreamClose) {
        // Try-with-resources ensures the token stream's onClose hook fires on
        // every exit path (break, exception, normal return). Registering the
        // stream before iteration gives the watchdog a handle it can close if
        // the worker blocks in a synchronous socket read.
        try (stream) {
            if (activeStream != null) activeStream.set(stream);
            ProviderTextAssembly assembly = new ProviderTextAssembly(safeCap());
            List<ChatMessage.NativeToolCall> toolCalls = new ArrayList<>();
            boolean displayOutputDelivered = false;
            String finishReason = "";
            ProviderResponseCapture.Completion completion =
                    ProviderResponseCapture.Completion.STREAM_WITHOUT_EOS;
            try {
                ProviderResponseCapture.AbortOrigin initialAbort = abortOrigin(cancelled);
                if (initialAbort != ProviderResponseCapture.AbortOrigin.NONE) {
                    recordAbortBeforeStreamClose(initialAbort, abortBeforeStreamClose);
                    return abortedStreamResult(
                            assembly, onChunk, "interrupted", initialAbort);
                }
                for (TokenChunk ch : (Iterable<TokenChunk>) stream::iterator) {
                    ProviderResponseCapture.AbortOrigin loopAbort = abortOrigin(cancelled);
                    if (loopAbort != ProviderResponseCapture.AbortOrigin.NONE) {
                        recordAbortBeforeStreamClose(loopAbort, abortBeforeStreamClose);
                        return abortedStreamResult(
                                assembly,
                                onChunk,
                                "interrupted",
                                loopAbort);
                    }
                    if (ch == null) {
                        completion = ProviderResponseCapture.Completion.STREAM_WITHOUT_EOS;
                        break;
                    }
                    if (Boolean.TRUE.equals(ch.done())) {
                        finishReason = Objects.toString(ch.finishReason(), "").strip();
                        completion = completionForTerminalChunk(ch, !toolCalls.isEmpty());
                        break;
                    }

                    if (ch.hasToolCalls()) {
                        if (progressOnlySink != null) {
                            progressOnlySink.accept("");
                        }
                        toolCalls.addAll(ch.toolCalls());
                        continue;
                    }

                    String deltaRaw = Objects.toString(ch.text(), "");
                    reportDecodedProviderProgress(deltaRaw, progressOnlySink);
                    boolean capReached = assembly.append(deltaRaw);
                    if (assembly.emitStable(onChunk) > 0) {
                        displayOutputDelivered = true;
                    }
                    if (toolCalls.isEmpty()
                            && assembly.containsSettledLegacyXmlToolCall()) {
                        completion = ProviderResponseCapture.Completion.RUNTIME_LEGACY_TEXT_STOP;
                        break;
                    }
                    if (capReached) {
                        completion = ProviderResponseCapture.Completion.RUNTIME_SAFE_CAP;
                        break;
                    }
                }
                String terminalText = providerTextCanRetainProtocol(completion)
                        ? assembly.finalText()
                        : assembly.nonAuthoritativeText();
                assembly.emitTerminal(terminalText, onChunk);
                return new StreamResult(
                        terminalText,
                        toolCalls,
                        finishReason,
                        "",
                        completion,
                        assembly.normalized(terminalText),
                        assembly.truncated());
            } catch (EngineException.Transient transientFailure) {
                if (streamWasLocallyClosed(activeStream, stream)) {
                    recordAbortBeforeStreamClose(
                            ProviderResponseCapture.AbortOrigin.RUNTIME_STREAM_CLOSE,
                            abortBeforeStreamClose);
                    return abortedStreamResult(
                            assembly,
                            onChunk,
                            "local stream closed before generation completed",
                            ProviderResponseCapture.AbortOrigin.RUNTIME_STREAM_CLOSE);
                }
                String retainedPartial = assembly.nonAuthoritativeText();
                // Decoded progress alone is not a retry barrier. A retry is
                // unsafe only after display received bytes, or when this
                // attempt was not deliberately deferred and its fail-closed
                // projection retains meaningful partial model output.
                if (displayOutputDelivered
                        || (partialOutputCommitsAttempt && !retainedPartial.isBlank())) {
                    return abortedStreamResult(
                            assembly,
                            onChunk,
                            retainedPartial,
                            "stream transport failed after partial output, retry skipped to avoid duplicate output",
                            ProviderResponseCapture.AbortOrigin.PROVIDER_TRANSPORT);
                }
                throw transientFailure;
            } finally {
                if (activeStream != null) activeStream.compareAndSet(stream, null);
            }
        }
    }

    private static boolean providerTextCanRetainProtocol(
            ProviderResponseCapture.Completion completion) {
        return completion == ProviderResponseCapture.Completion.PROVIDER_EOS
                || completion
                        == ProviderResponseCapture.Completion.RUNTIME_LEGACY_TEXT_STOP;
    }

    static void reportDecodedProviderProgress(
            String decodedChunk,
            Consumer<String> progressOnlySink) {
        if (progressOnlySink == null
                || Objects.toString(decodedChunk, "").isEmpty()) {
            return;
        }
        // Decoded-provider progress is distinct from visible sanitized output
        // and does not establish transport settlement. An unresolved
        // JSON/think/HTML envelope may buffer display through provider EOS,
        // but every decoded byte must still feed both idle and repetition
        // accounting exactly once. This sink is internal and never renders
        // provider protocol to the user.
        progressOnlySink.accept(decodedChunk);
    }

    static StreamResult abortedStreamResult(String partialText, String reason) {
        return abortedStreamResult(
                partialText, reason, ProviderResponseCapture.AbortOrigin.RUNTIME_THREAD_INTERRUPT);
    }

    static StreamResult abortedStreamResult(
            String partialText,
            String reason,
            ProviderResponseCapture.AbortOrigin abortOrigin) {
        return abortedStreamResult(
                partialText, reason, abortOrigin, false, false);
    }

    private static StreamResult abortedStreamResult(
            String partialText,
            String reason,
            ProviderResponseCapture.AbortOrigin abortOrigin,
            boolean normalized,
            boolean truncated) {
        String abortReason = Objects.toString(reason, "generation aborted");
        String marker = UiChrome.TURN_ABORTED_PREFIX + ": " + abortReason + "]";
        String partial = Objects.toString(partialText, "");
        String text = partial.isBlank() ? marker : partial + System.lineSeparator() + marker;
        return new StreamResult(
                text,
                List.of(),
                "",
                abortReason,
                abortOrigin,
                ProviderResponseCapture.Completion.RUNTIME_ABORT,
                normalized,
                truncated,
                ProviderResponseCapture.Receipt.none());
    }

    private static StreamResult abortedStreamResult(
            ProviderTextAssembly assembly,
            Consumer<String> onChunk,
            String reason,
            ProviderResponseCapture.AbortOrigin abortOrigin) {
        String partial = assembly == null ? "" : assembly.nonAuthoritativeText();
        return abortedStreamResult(assembly, onChunk, partial, reason, abortOrigin);
    }

    private static StreamResult abortedStreamResult(
            ProviderTextAssembly assembly,
            Consumer<String> onChunk,
            String partial,
            String reason,
            ProviderResponseCapture.AbortOrigin abortOrigin) {
        if (assembly != null) {
            assembly.emitTerminal(partial, onChunk);
        }
        return abortedStreamResult(
                partial,
                reason,
                abortOrigin,
                assembly != null && assembly.normalized(partial),
                assembly != null && assembly.truncated());
    }

    private static boolean isCancelled(Supplier<Boolean> cancelled) {
        return cancelled != null && Boolean.TRUE.equals(cancelled.get());
    }

    private static ProviderResponseCapture.AbortOrigin abortOrigin(Supplier<Boolean> cancelled) {
        if (isCancelled(cancelled)) return ProviderResponseCapture.AbortOrigin.OPERATOR_EXTERNAL;
        if (Thread.currentThread().isInterrupted()) {
            return ProviderResponseCapture.AbortOrigin.RUNTIME_THREAD_INTERRUPT;
        }
        return ProviderResponseCapture.AbortOrigin.NONE;
    }

    private static boolean streamWasLocallyClosed(
            AtomicReference<AutoCloseable> activeStream,
            AutoCloseable stream) {
        return activeStream != null && activeStream.get() != stream;
    }

    private static boolean shouldRetryCompatToolArgumentsNonStreaming(
            EngineException.MalformedResponse malformed,
            ChatRequest request) {
        if (malformed == null || request == null) return false;
        if (!"compat chat stream tool arguments".equals(malformed.context())) return false;
        if (request.tools == null || request.tools.isEmpty()) return false;
        ToolChoiceMode mode = request.controls == null
                ? ToolChoiceMode.AUTO
                : request.controls.toolChoice();
        // Capped requests also qualify under AUTO: backends without required
        // tool choice still receive caps (T989), and a cap can truncate the
        // arguments of a voluntarily emitted tool call.
        boolean capped = request.controls != null && request.controls.maxOutputTokens() > 0;
        return mode == ToolChoiceMode.REQUIRED || mode == ToolChoiceMode.NAMED || capped;
    }

    /**
     * True when a per-request output cap stopped a tool-required generation
     * before any usable tool call (native or complete text-form) arrived.
     * Such a result can never satisfy the turn's tool obligation, so one
     * cap-lifted retry is strictly better than surfacing the truncation.
     */
    private static boolean shouldRetryCapTruncatedToolResult(ChatRequest request, StreamResult result) {
        if (request == null || result == null) return false;
        if (request.controls == null || request.controls.maxOutputTokens() <= 0) return false;
        if (request.tools == null || request.tools.isEmpty()) return false;
        ToolChoiceMode mode = request.controls.toolChoice();
        if (mode != ToolChoiceMode.REQUIRED && mode != ToolChoiceMode.NAMED) return false;
        if (!result.outputLimitReached()) return false;
        if (result.hasToolCalls()) return false;
        return !ToolProtocolText.containsToolCalls(result.text());
    }

    private List<ToolSpec> effectiveToolSpecs(
            List<ToolSpec> requestToolSpecs,
            ProviderResponseCapture.ToolAuthorityMode toolAuthorityMode
    ) {
        ProviderResponseCapture.ToolAuthorityMode mode = toolAuthorityMode == null
                ? ProviderResponseCapture.ToolAuthorityMode.NONE
                : toolAuthorityMode;
        List<ToolSpec> requested = requestToolSpecs == null
                ? toolSpecs
                : List.copyOf(requestToolSpecs);
        if (mode == ProviderResponseCapture.ToolAuthorityMode.NONE) {
            return List.of();
        }
        if (requested.isEmpty()) {
            throw new IllegalStateException(
                    "provider tool authority requires an explicit nonempty tool surface");
        }
        if (mode == ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON) {
            return List.of();
        }
        if (!nativeToolsCurrentlyEnabled()) {
            throw new IllegalStateException(
                    "native provider tool authority requires native engine capability");
        }
        return requested;
    }

    private Capabilities effectiveCapabilities() {
        if (engineResolver == null) return null;
        if (preparedCapabilities != null) return preparedCapabilities;
        return engineResolver.capabilities();
    }

    private static ChatRequestControls withDebugTag(ChatRequestControls controls, String tag) {
        ChatRequestControls safe = controls == null ? ChatRequestControls.defaults() : controls;
        if (tag == null || tag.isBlank() || safe.debugTags().contains(tag)) {
            return safe;
        }
        List<String> tags = new ArrayList<>(safe.debugTags());
        tags.add(tag);
        return new ChatRequestControls(
                safe.toolChoice(),
                safe.namedTool(),
                safe.responseFormat(),
                safe.jsonSchema(),
                tags,
                safe.sampling(),
                safe.maxOutputTokens(),
                safe.toolAuthorityMode());
    }

    /** Layers llm.sampling config values under turn-level sampling decisions (set fields win). */
    private ChatRequestControls withConfigSampling(ChatRequestControls controls) {
        ChatRequestControls safe = controls == null ? ChatRequestControls.defaults() : controls;
        if (configSampling == null || !configSampling.anySet()) return safe;
        SamplingControls merged = safe.sampling().mergedWithFallback(configSampling);
        if (merged.equals(safe.sampling())) return safe;
        return safe.withSampling(merged);
    }

    private static SamplingControls parseConfigSampling(Map<String, Object> sampling) {
        if (sampling == null || sampling.isEmpty()) return SamplingControls.none();
        return new SamplingControls(
                asDoubleOrNull(sampling.get("temperature")),
                asDoubleOrNull(sampling.get("top_p")),
                asIntOrNull(sampling.get("top_k")),
                asLongOrNull(sampling.get("seed")));
    }

    private static Double asDoubleOrNull(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return null;
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private static Integer asIntOrNull(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return null;
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private static Long asLongOrNull(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v == null) return null;
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    /** Test seam: the parsed llm.sampling config overrides. */
    SamplingControls configSampling() {
        return configSampling;
    }

    private List<ChatMessage> fitMessagesToContextBudget(List<ChatMessage> messages,
                                                         List<ToolSpec> tools,
                                                         ChatRequestControls controls) {
        int contextWindowTokens = effectiveContextWindowTokens();
        int inputBudgetTokens = inputBudgetTokens(contextWindowTokens);
        int estimatedTokens = estimateChatRequestTokens(messages, tools, controls);
        if (estimatedTokens <= inputBudgetTokens) {
            return messages;
        }

        List<ChatMessage> trimmed = new ArrayList<>(messages);
        int removedMessages = 0;
        while (estimatedTokens > inputBudgetTokens) {
            int removed = removeOldestRemovableHistoryGroup(trimmed);
            if (removed == 0) break;
            removedMessages += removed;
            estimatedTokens = estimateChatRequestTokens(trimmed, tools, controls);
        }

        if (estimatedTokens > inputBudgetTokens) {
            throw new EngineException.ContextBudgetExceeded(
                    estimatedTokens, inputBudgetTokens, contextWindowTokens, removedMessages);
        }
        return List.copyOf(trimmed);
    }

    private int engineContextWindowTokens() {
        int engineWindow = 0;
        try {
            if (engineResolver != null && engineResolver.capabilities() != null) {
                engineWindow = engineResolver.capabilities().contextWindow();
            }
        } catch (Exception failure) {
            rethrowControlFlow(failure);
            // engineWindow already 0; capability probe is best-effort
        }
        return engineWindow;
    }

    private static void rethrowControlFlow(Throwable failure) {
        if (!ControlFlowFailure.isCancellation(failure)
                && !ControlFlowFailure.isInterruption(failure)) return;
        throw ControlFlowFailure.propagate(failure);
    }

    private int effectiveContextWindowTokens() {
        int configured = TokenBudget.fromConfig(cfg).contextMaxTokens();
        int engineWindow = engineContextWindowTokens();
        return effectiveContextWindowTokens(configured, engineWindow);
    }

    private static int effectiveContextWindowTokens(int configured, int engineWindow) {
        if (engineWindow > 0) {
            return Math.max(256, Math.min(configured, engineWindow));
        }
        return Math.max(256, configured);
    }

    private static int inputBudgetTokens(int contextWindowTokens) {
        TokenBudget budget = new TokenBudget(contextWindowTokens);
        int responseReserve = (int) (budget.contextMaxTokens() * budget.responseReserveFraction());
        return Math.max(64, budget.contextMaxTokens() - responseReserve - budget.overheadTokens());
    }

    private static int estimateChatRequestTokens(List<ChatMessage> messages,
                                                 List<ToolSpec> tools,
                                                 ChatRequestControls controls) {
        TokenBudget estimator = new TokenBudget();
        int total = 64;
        for (ChatMessage message : messages == null ? List.<ChatMessage>of() : messages) {
            total += 8;
            total += estimator.estimateTokens(Objects.toString(message.role(), ""));
            total += estimator.estimateTokens(Objects.toString(message.content(), ""));
            if (message.toolCallId() != null && !message.toolCallId().isBlank()) {
                total += 4 + estimator.estimateTokens(message.toolCallId());
            }
            if (message.hasNativeToolCalls()) {
                for (ChatMessage.NativeToolCall call : message.toolCalls()) {
                    if (call == null) continue;
                    total += 12;
                    total += estimator.estimateTokens(Objects.toString(call.id(), ""));
                    total += estimator.estimateTokens(Objects.toString(call.name(), ""));
                    total += estimator.estimateTokens(Objects.toString(call.arguments(), ""));
                }
            }
        }
        for (ToolSpec tool : tools == null ? List.<ToolSpec>of() : tools) {
            total += 24;
            total += estimator.estimateTokens(tool.name());
            total += estimator.estimateTokens(tool.description());
            total += estimator.estimateTokens(Objects.toString(tool.parametersSchemaJson(), ""));
        }
        if (controls != null) {
            total += 8;
            total += estimator.estimateTokens(controls.toolChoice().name());
            total += estimator.estimateTokens(controls.namedTool());
            total += estimator.estimateTokens(controls.responseFormat().name());
            total += estimator.estimateTokens(controls.jsonSchema());
            total += estimator.estimateTokens(String.join(",", controls.debugTags()));
        }
        return total;
    }

    private static int removeOldestRemovableHistoryGroup(List<ChatMessage> messages) {
        int anchor = currentTurnAnchorIndex(messages);
        for (int i = 0; i < anchor; i++) {
            ChatMessage message = messages.get(i);
            if (isSystemRole(message)) continue;

            int start = i;
            int end = i + 1;
            if (isToolRole(message)) {
                int assistantIndex = precedingAssistantToolCallIndex(messages, i);
                if (assistantIndex >= 0 && assistantIndex < anchor) {
                    start = assistantIndex;
                    end = consecutiveToolResultsEnd(messages, assistantIndex + 1, anchor);
                }
            } else if (message != null && message.hasNativeToolCalls()) {
                end = consecutiveToolResultsEnd(messages, i + 1, anchor);
            }

            messages.subList(start, end).clear();
            return end - start;
        }
        return 0;
    }

    private static int currentTurnAnchorIndex(List<ChatMessage> messages) {
        int lastUser = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (isUserRole(messages.get(i))) {
                lastUser = i;
            }
        }
        int searchFrom = lastUser >= 0 ? lastUser : messages.size() - 1;
        for (int i = searchFrom; i >= 0; i--) {
            if (isCurrentTurnFrame(messages.get(i))) {
                return i;
            }
        }
        return lastUser >= 0 ? lastUser : messages.size();
    }

    private static boolean isCurrentTurnFrame(ChatMessage message) {
        if (!isSystemRole(message)) return false;
        String content = Objects.toString(message.content(), "");
        return content.contains("[CurrentTurnCapability]");
    }

    private static int precedingAssistantToolCallIndex(List<ChatMessage> messages, int toolIndex) {
        int i = toolIndex - 1;
        while (i >= 0 && isToolRole(messages.get(i))) {
            i--;
        }
        if (i >= 0) {
            ChatMessage previous = messages.get(i);
            if (previous != null && "assistant".equals(previous.role()) && previous.hasNativeToolCalls()) {
                return i;
            }
        }
        return -1;
    }

    private static int consecutiveToolResultsEnd(List<ChatMessage> messages, int start, int limitExclusive) {
        int end = start;
        while (end < limitExclusive && isToolRole(messages.get(end))) {
            end++;
        }
        return end;
    }

    private static boolean isSystemRole(ChatMessage message) {
        return message != null && "system".equals(message.role());
    }

    private static boolean isUserRole(ChatMessage message) {
        return message != null && "user".equals(message.role());
    }

    private static boolean isToolRole(ChatMessage message) {
        return message != null && "tool".equals(message.role());
    }

    // ── Retry / back-off constants ────────────────────────────────────────

    /** Max retries for transient engine errors (per call, not per session). */
    static final int MAX_RETRIES = 2;

    /**
     * Shared streaming assembly loop used by both engine methods.
     * Sanitizes, strips think-tags, enforces hard cap, and emits chunks.
     */
    private TextGeneration assembleFromStream(java.util.stream.Stream<TokenChunk> stream,
                                              Consumer<String> onChunk,
                                              Supplier<Boolean> cancelled,
                                              Consumer<ProviderResponseCapture.AbortOrigin>
                                                      abortBeforeStreamClose) {
        // Try-with-resources: closes the engine's token stream on every exit
        // path (cancel break, cap-reached break, exception, normal return).
        // For the Ollama transport this propagates to the HTTP body/socket
        // close via Stream.onClose - preventing the "Ollama keeps generating
        // into a dead consumer" leak that kept a hung repetition-loop stream
        // alive after the tool-call loop had moved on.
        try (stream) {
            ProviderTextAssembly assembly = new ProviderTextAssembly(safeCap());
            ProviderResponseCapture.Completion completion =
                    ProviderResponseCapture.Completion.STREAM_WITHOUT_EOS;
            ProviderResponseCapture.AbortOrigin abortOrigin =
                    ProviderResponseCapture.AbortOrigin.NONE;

            if (isCancelled(cancelled)) {
                completion = ProviderResponseCapture.Completion.RUNTIME_ABORT;
                abortOrigin = ProviderResponseCapture.AbortOrigin.OPERATOR_EXTERNAL;
                recordAbortBeforeStreamClose(abortOrigin, abortBeforeStreamClose);
            } else {
                for (TokenChunk ch : (Iterable<TokenChunk>) stream::iterator) {
                    ProviderResponseCapture.AbortOrigin observedAbort = isCancelled(cancelled)
                            ? ProviderResponseCapture.AbortOrigin.OPERATOR_EXTERNAL
                            : ProviderResponseCapture.AbortOrigin.NONE;
                    if (observedAbort != ProviderResponseCapture.AbortOrigin.NONE) {
                        completion = ProviderResponseCapture.Completion.RUNTIME_ABORT;
                        abortOrigin = observedAbort;
                        recordAbortBeforeStreamClose(observedAbort, abortBeforeStreamClose);
                        break;
                    }
                    if (ch == null) {
                        completion = ProviderResponseCapture.Completion.STREAM_WITHOUT_EOS;
                        break;
                    }
                    if (Boolean.TRUE.equals(ch.done())) {
                        String finishReason = Objects.toString(ch.finishReason(), "").strip();
                        completion = completionForTerminalChunk(ch);
                        break;
                    }

                    String deltaRaw = Objects.toString(ch.text(), "");
                    boolean capReached = assembly.append(deltaRaw);
                    assembly.emitStable(onChunk);
                    if (capReached) {
                        completion = ProviderResponseCapture.Completion.RUNTIME_SAFE_CAP;
                        break;
                    }
                }
            }
            String terminalText = providerTextCanRetainProtocol(completion)
                    ? assembly.finalText()
                    : assembly.nonAuthoritativeText();
            assembly.emitTerminal(terminalText, onChunk);
            return new TextGeneration(
                    terminalText,
                    completion,
                    assembly.normalized(terminalText),
                    assembly.truncated(),
                    abortOrigin, ProviderResponseCapture.Receipt.none());
        }
    }

    private static void recordAbortBeforeStreamClose(
            ProviderResponseCapture.AbortOrigin origin,
            Consumer<ProviderResponseCapture.AbortOrigin> recorder) {
        if (recorder == null
                || origin == null
                || origin == ProviderResponseCapture.AbortOrigin.NONE
                || origin == ProviderResponseCapture.AbortOrigin.PROVIDER_TRANSPORT) {
            return;
        }
        recorder.accept(origin);
    }

    private static ProviderResponseCapture.Completion completionForTerminalChunk(
            TokenChunk chunk) {
        return completionForTerminalChunk(chunk, chunk != null && chunk.hasToolCalls());
    }

    private static ProviderResponseCapture.Completion completionForTerminalChunk(
            TokenChunk chunk,
            boolean toolCallObserved) {
        if (chunk == null
                || chunk.terminalSignal() != TokenChunk.TerminalSignal.PROVIDER_SENTINEL) {
            return ProviderResponseCapture.Completion.STREAM_WITHOUT_EOS;
        }
        String finishReason = Objects.toString(chunk.finishReason(), "").strip();
        if ("length".equalsIgnoreCase(finishReason)) {
            return ProviderResponseCapture.Completion.PROVIDER_LENGTH;
        }
        if ("tool_calls".equalsIgnoreCase(finishReason) && !toolCallObserved) {
            return ProviderResponseCapture.Completion.UNRECOGNIZED_PROVIDER_STOP;
        }
        return recognizedNormalFinishReason(finishReason)
                ? ProviderResponseCapture.Completion.PROVIDER_EOS
                : ProviderResponseCapture.Completion.UNRECOGNIZED_PROVIDER_STOP;
    }

    private static boolean recognizedNormalFinishReason(String finishReason) {
        String normalized = Objects.toString(finishReason, "").strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.isEmpty()
                || "stop".equals(normalized)
                || "tool_calls".equals(normalized);
    }

    private static String synthesizeLocalAnswer(String system, String user, String ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Model: ").append("(local:").append("sandbox").append(")\n");
        sb.append("System: ").append(system).append("\n");
        if (!user.isBlank()) sb.append("\nUser: ").append(user);
        if (!ctx.isBlank())  sb.append("\n\n[Context received]").append(ctx);
        sb.append("\n\n(Response generation is disabled in this build; this is a sanitized placeholder.)");
        return sb.toString();
    }

    private static String sanitizeModelName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if ((s.startsWith("<") && s.endsWith(">")) ||
                (s.startsWith("\"") && s.endsWith("\"")) ||
                (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1);
        }
        // allow backend/model, dots, underscores, colons, hyphens
        s = s.replaceAll("[^A-Za-z0-9._:/-]", "");
        if (s.contains("..") || s.contains("\\\\") || s.contains("//")) return "";
        if (s.length() > 64) s = s.substring(0, 64);
        if (s.isEmpty() || !Character.isLetterOrDigit(s.charAt(0))) return "";
        return s;
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (engineResolver != null) try { engineResolver.close(); } catch (Exception ignored) {}
        try { callBudget.close(); } catch (Exception ignored) {}
    }
}
