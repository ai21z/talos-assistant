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
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.SamplingControls;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolSpec;
import dev.talos.spi.types.ToolChoiceMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
     * P2 - wall-clock budget for a single LLM call (one full
     * {@link #chatStreamFull} or {@link #chatFull} invocation, including all
     * internal retries).
     *
     * <p><b>Why this exists:</b> the JDK {@code HttpRequest.timeout(...)} only
     * fires while waiting for the <em>next</em> chunk; once chunks trickle in
     * slowly the request never times out, so a wedged or runaway local model
     * can hang the UI for tens of minutes (observed: 23 minutes in a real
     * transcript before the loop hit max-iterations). The non-streaming
     * legacy path in {@code AssistantTurnExecutor} already wraps its call in
     * a {@code CompletableFuture.get(timeout)}, but the streaming path and
     * the tool-call-loop re-prompts had no equivalent. This field, plus
     * {@link LlmCallBudget#run}, closes that gap.
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
     * well before the 5-min wall-clock fires. The user-visible UX is "Talos
     * is frozen". An idle watchdog catches that case in tens of seconds, not
     * minutes, while the wall-clock still backstops genuinely-slow-but-alive
     * generations on big local models.
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
        LlmClient c = new LlmClient(new Config());
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
        LlmClient c = new LlmClient(new Config());
        c.scriptedFailure = failure == null
                ? new RuntimeException("scripted LLM failure")
                : failure;
        return c;
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

    public boolean supportsRequiredToolChoice() {
        if (mode != TransportMode.ENGINE || engineResolver == null) return false;
        if ("ollama".equalsIgnoreCase(backend)) return false;
        try {
            return engineResolver.capabilities().requiredToolChoice();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean supportsNamedToolChoice() {
        if (mode != TransportMode.ENGINE || engineResolver == null) return false;
        if ("ollama".equalsIgnoreCase(backend)) return false;
        try {
            return engineResolver.capabilities().namedToolChoice();
        } catch (Exception e) {
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
            return placeholderAnswer(system, user, snippets);
        }
        // ENGINE: assemble from the streaming path to keep parity exact
        return engineAssembled(system, user, snippets, null, Duration.ofSeconds(90), () -> false);
    }

    /** Optional timeout overload (kept for Mode code that uses it). */
    public String chat(String system, String user, List<Map<String, String>> snippets, Duration timeout) throws TimeoutException {
        if (mode == TransportMode.PLACEHOLDER) return placeholderAnswer(system, user, snippets);
        return engineAssembled(system, user, snippets, null, (timeout == null ? Duration.ofSeconds(90) : timeout), () -> false);
    }

    /** Streaming chat. Parity with non-stream is guaranteed by sharing the same assembly logic. */
    public String chatStream(String system,
                             String user,
                             List<Map<String, String>> snippets,
                             Consumer<String> onChunk) {
        if (mode == TransportMode.PLACEHOLDER) {
            // emit single sanitized chunk to satisfy stream lifecycle, keep parity
            String full = placeholderAnswer(system, user, snippets);
            if (onChunk != null && !full.isEmpty()) onChunk.accept(full);
            return full;
        }
        return engineAssembled(system, user, snippets, onChunk, Duration.ofSeconds(90), () -> false);
    }

    public String chatStream(String system,
                             String user,
                             List<Map<String, String>> snippets,
                             Consumer<String> onChunk,
                             Duration timeout,
                             Supplier<Boolean> cancelled) throws TimeoutException {
        if (mode == TransportMode.PLACEHOLDER) {
            if (cancelled != null && Boolean.TRUE.equals(cancelled.get())) return "";
            String full = placeholderAnswer(system, user, snippets);
            if (cancelled != null && Boolean.TRUE.equals(cancelled.get())) return "";
            if (onChunk != null && !full.isEmpty()) onChunk.accept(full);
            return full;
        }
        return engineAssembled(system, user, snippets, onChunk,
                (timeout == null ? Duration.ofSeconds(90) : timeout),
                (cancelled == null ? () -> false : cancelled));
    }

    /* -------- Multi-turn conversation (structured messages) -------- */

    /**
     * Chat using structured conversation messages (system/user/assistant turns).
     * <p>In ENGINE mode, this triggers the /api/chat endpoint with proper role tags.
     * In PLACEHOLDER mode, falls back to extracting system/user for deterministic output.
     */
    public String chat(List<ChatMessage> messages) {
        if (mode == TransportMode.PLACEHOLDER) {
            return placeholderFromMessages(messages);
        }
        return engineAssembledWithMessages(messages, null, Duration.ofSeconds(90), () -> false);
    }

    /** Multi-turn chat with timeout. */
    public String chat(List<ChatMessage> messages, Duration timeout) throws TimeoutException {
        if (mode == TransportMode.PLACEHOLDER) {
            return placeholderFromMessages(messages);
        }
        return engineAssembledWithMessages(messages, null,
                (timeout == null ? Duration.ofSeconds(90) : timeout), () -> false);
    }

    /**
     * Streaming chat using structured conversation messages.
     * Each token chunk is delivered via the {@code onChunk} callback as it arrives.
     * Returns the fully assembled response.
     */
    public String chatStream(List<ChatMessage> messages, Consumer<String> onChunk) {
        if (mode == TransportMode.PLACEHOLDER) {
            String full = placeholderFromMessages(messages);
            if (onChunk != null && !full.isEmpty()) onChunk.accept(full);
            return full;
        }
        return engineAssembledWithMessages(messages, onChunk, Duration.ofSeconds(90), () -> false);
    }

    /**
     * Streaming chat with timeout and cancellation support.
     */
    public String chatStream(List<ChatMessage> messages,
                             Consumer<String> onChunk,
                             Duration timeout,
                             Supplier<Boolean> cancelled) throws TimeoutException {
        if (mode == TransportMode.PLACEHOLDER) {
            if (cancelled != null && Boolean.TRUE.equals(cancelled.get())) return "";
            String full = placeholderFromMessages(messages);
            if (cancelled != null && Boolean.TRUE.equals(cancelled.get())) return "";
            if (onChunk != null && !full.isEmpty()) onChunk.accept(full);
            return full;
        }
        return engineAssembledWithMessages(messages, onChunk,
                (timeout == null ? Duration.ofSeconds(90) : timeout),
                (cancelled == null ? () -> false : cancelled));
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
    private String engineAssembled(String system,
                                   String user,
                                   List<Map<String, String>> snippets,
                                   Consumer<String> onChunk,
                                   Duration timeout,
                                   Supplier<Boolean> cancelled) {
        // sanitize prompt parts for model consumption
        final String sys = Sanitize.sanitizeForPrompt(Objects.toString(system, ""));
        final String usr = Sanitize.sanitizeForPrompt(Objects.toString(user, ""));
        List<Map<String,String>> sn = sanitizeSnippets(snippets);

        return LlmRetryExecutor.execute(MAX_RETRIES, () -> {
            ChatRequest req = new ChatRequest(
                    backend, model, sys, usr, sn, timeout, List.of(), toolSpecs,
                    withConfigSampling(promptDebugControlsForPlainCall(sys)));
            PromptDebugCapture.record(PromptDebugSnapshot.fromChatRequest(req, onChunk != null));
            return assembleFromStream(engineResolver.chatStream(req), onChunk, cancelled);
        });
    }

    private static ChatRequestControls promptDebugControlsForPlainCall(String systemPrompt) {
        if (isConversationSummarizerPrompt(systemPrompt)) {
            return new ChatRequestControls(
                    ToolChoiceMode.AUTO,
                    "",
                    ResponseFormatMode.TEXT,
                    "",
                    List.of(PromptDebugCapture.BACKGROUND_MAINTENANCE_TAG));
        }
        return ChatRequestControls.defaults();
    }

    private static boolean isConversationSummarizerPrompt(String systemPrompt) {
        return systemPrompt != null
                && systemPrompt.contains("conversation summarizer for a developer CLI tool");
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
    private String engineAssembledWithMessages(List<ChatMessage> messages,
                                               Consumer<String> onChunk,
                                               Duration timeout,
                                               Supplier<Boolean> cancelled) {
        // Sanitize message content while preserving tool-call structure
        List<ChatMessage> sanitized = messages.stream()
                .map(m -> new ChatMessage(
                        m.role(),
                        Sanitize.sanitizeMessageContent(Objects.toString(m.content(), "")),
                        m.toolCalls(),
                        m.toolCallId()))
                .toList();

        return LlmRetryExecutor.execute(MAX_RETRIES, () -> {
            ChatRequest req = new ChatRequest(backend, model, "", "", List.of(), timeout, sanitized, toolSpecs);
            PromptDebugCapture.record(PromptDebugSnapshot.fromChatRequest(req, onChunk != null));
            return assembleFromStream(engineResolver.chatStream(req), onChunk, cancelled);
        });
    }

    /**
     * Result of a structured streaming chat, carrying both assembled text
     * and any native tool calls returned by the model.
     *
     * @param text      assembled prose text (sanitized, think-tags stripped)
     * @param toolCalls native tool calls from the model (empty if none)
     */
    public record StreamResult(String text, List<ChatMessage.NativeToolCall> toolCalls) {
        /** Returns true if the model returned native tool calls. */
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
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
                requestToolSpecs, ChatRequestControls.defaults());
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
        return chatStreamFull(messages, onChunk, wallClockMs, null, ChatRequestControls.defaults());
    }

    public StreamResult chatStreamFull(List<ChatMessage> messages,
                                       Consumer<String> onChunk,
                                       long wallClockMs,
                                       List<ToolSpec> requestToolSpecs) {
        return chatStreamFull(messages, onChunk, wallClockMs, requestToolSpecs, ChatRequestControls.defaults());
    }

    public StreamResult chatStreamFull(List<ChatMessage> messages,
                                       Consumer<String> onChunk,
                                       long wallClockMs,
                                       List<ToolSpec> requestToolSpecs,
                                       ChatRequestControls controls) {
        // P2 - clear any Ctrl-C from the previous turn so stale cancels
        // don't immediately short-circuit this call.
        externalCancelReset.run();
        if (scriptedFailure != null) {
            throw scriptedFailure;
        }
        if (scriptedResponses != null) {
            String r = nextScriptedResponse();
            if (onChunk != null && !r.isEmpty()) onChunk.accept(r);
            return new StreamResult(r, List.of());
        }
        if (mode == TransportMode.PLACEHOLDER) {
            String full = placeholderFromMessages(messages);
            if (onChunk != null && !full.isEmpty()) onChunk.accept(full);
            return new StreamResult(full, List.of());
        }
        // P2 - track the time of the last visible chunk; the watchdog (set up
        // inside withWallClockBudget) abort()s the worker if no chunk arrives
        // for {@link #defaultIdleMs} ms. The cancel supplier OR-combines the
        // engine-level cancel and the externally-set cancel hook so a Ctrl-C
        // future patch can plug in without touching this method.
        AtomicLong lastChunkAt = new AtomicLong(System.currentTimeMillis());
        // Repetition breaker - observes the streamed chunks alongside the
        // idle watchdog. The watchdog polls breaker.tripped() on every tick
        // and aborts the worker via RepetitionException when the model
        // enters a degenerate-output loop. See RepetitionBreaker for the
        // rationale (gemma4:26b April 2026 incident: 200+ lines of "The
        // user's prompt is '..." before the 387s wall-clock fired).
        RepetitionBreaker breaker = new RepetitionBreaker();
        Consumer<String> trackingSink = chunk -> {
            lastChunkAt.set(System.currentTimeMillis());
            breaker.onChunk(chunk);
            if (onChunk != null) onChunk.accept(chunk);
        };
        Supplier<Boolean> cancel = this.externalCancel;
        Duration engineRequestTimeout = engineRequestTimeout(wallClockMs);
        return callBudget.run(
                activeStream -> engineAssembledWithMessagesFullTracked(
                        messages, trackingSink, engineRequestTimeout, cancel,
                        lastChunkAt, activeStream, requestToolSpecs, controls, true),
                wallClockMs,
                lastChunkAt,
                "streaming chat",
                breaker);
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
                requestToolSpecs, ChatRequestControls.defaults());
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
        return chatFull(messages, wallClockMs, null, ChatRequestControls.defaults());
    }

    public StreamResult chatFull(List<ChatMessage> messages,
                                 long wallClockMs,
                                 List<ToolSpec> requestToolSpecs) {
        return chatFull(messages, wallClockMs, requestToolSpecs, ChatRequestControls.defaults());
    }

    public StreamResult chatFull(List<ChatMessage> messages,
                                 long wallClockMs,
                                 List<ToolSpec> requestToolSpecs,
                                 ChatRequestControls controls) {
        // P2 - see chatStreamFull: clear stale cancel flag per call.
        externalCancelReset.run();
        if (scriptedFailure != null) {
            throw scriptedFailure;
        }
        if (scriptedResponses != null) {
            return new StreamResult(nextScriptedResponse(), List.of());
        }
        if (mode == TransportMode.PLACEHOLDER) {
            return new StreamResult(placeholderFromMessages(messages), List.of());
        }
        // P2 - same idle-watchdog + cancel-hook plumbing as chatStreamFull.
        // The non-streaming path still uses an internal stream loop, so
        // chunk arrivals are observable; idle detection is meaningful.
        // Repetition detection applies here too - a non-streaming chat is
        // still driven by the same engine-side token stream, and the same
        // degenerate attractors trip just as easily.
        AtomicLong lastChunkAt = new AtomicLong(System.currentTimeMillis());
        RepetitionBreaker breaker = new RepetitionBreaker();
        Consumer<String> trackingSink = chunk -> {
            lastChunkAt.set(System.currentTimeMillis());
            breaker.onChunk(chunk);
        };
        Supplier<Boolean> cancel = this.externalCancel;
        Duration engineRequestTimeout = engineRequestTimeout(wallClockMs);
        return callBudget.run(
                activeStream -> engineAssembledWithMessagesFullTracked(
                        messages, trackingSink, engineRequestTimeout, cancel,
                        lastChunkAt, activeStream, requestToolSpecs, controls, false),
                wallClockMs,
                lastChunkAt,
                "non-streaming chat",
                breaker);
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
     * P2 - variant of {@link #engineAssembledWithMessagesFull} that calls
     * the tracking sink on every text chunk (so the idle watchdog sees
     * activity). Behavior is otherwise identical.
     */
    private StreamResult engineAssembledWithMessagesFullTracked(List<ChatMessage> messages,
                                                                Consumer<String> trackingSink,
                                                                Duration timeout,
                                                                Supplier<Boolean> cancelled,
                                                                AtomicLong lastChunkAt,
                                                                AtomicReference<AutoCloseable> activeStream,
                                                                List<ToolSpec> requestToolSpecs,
                                                                ChatRequestControls controls,
                                                                boolean streamRequest) {
        // Wrap the cancel supplier so the engine loop also bails when the
        // watchdog completes the future exceptionally (the worker thread
        // is then on borrowed time; we want it to drop out quickly).
        Supplier<Boolean> wrapped = () -> {
            if (cancelled != null && Boolean.TRUE.equals(cancelled.get())) return true;
            return Thread.currentThread().isInterrupted();
        };
        // Bump the heartbeat once before we start blocking on the engine -
        // protects against an engine that takes >idleMs to produce its
        // first chunk on a cold model.
        if (lastChunkAt != null) lastChunkAt.set(System.currentTimeMillis());
        return engineAssembledWithMessagesFull(
                messages, trackingSink, timeout, wrapped, activeStream,
                requestToolSpecs, controls, streamRequest);
    }

    /**
     * ENGINE mode: assemble from token stream using structured messages via /api/chat.
     * Returns a {@link StreamResult} carrying both the assembled text and any
     * native tool calls.
     */
    private StreamResult engineAssembledWithMessagesFull(List<ChatMessage> messages,
                                                         Consumer<String> onChunk,
                                                         Duration timeout,
                                                         Supplier<Boolean> cancelled,
                                                         AtomicReference<AutoCloseable> activeStream,
                                                         List<ToolSpec> requestToolSpecs,
                                                         ChatRequestControls controls,
                                                         boolean streamRequest) {
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
        List<ToolSpec> tools = effectiveToolSpecs(requestToolSpecs);
        ChatRequestControls requestControls = withConfigSampling(controls);
        List<ChatMessage> requestMessages = fitMessagesToContextBudget(sanitized, tools, requestControls);
        if (requestMessages.size() < sanitized.size()) {
            requestControls = withDebugTag(requestControls, "context-budget-trimmed");
            requestMessages = fitMessagesToContextBudget(requestMessages, tools, requestControls);
        }
        final ChatRequestControls finalRequestControls = requestControls;
        final List<ChatMessage> finalRequestMessages = requestMessages;

        return LlmRetryExecutor.execute(MAX_RETRIES, () -> {
            ChatRequest req = new ChatRequest(
                    backend, model, "", "", List.of(), timeout, finalRequestMessages,
                    tools, finalRequestControls);
            PromptDebugCapture.record(PromptDebugSnapshot.fromChatRequest(req, streamRequest));
            try {
                return consumeEngineStream(
                        engineResolver.chatStream(req), activeStream, cancelled, onChunk);
            } catch (EngineException.MalformedResponse malformed) {
                if (!shouldRetryCompatToolArgumentsNonStreaming(malformed, req)) {
                    throw malformed;
                }
                ChatRequest retryReq = new ChatRequest(
                        req.backend, req.model, req.systemPrompt, req.userPrompt,
                        req.snippets, req.timeout, req.messages, req.tools,
                        withDebugTag(req.controls, "compat-tool-arguments-nonstream-retry"));
                PromptDebugCapture.record(PromptDebugSnapshot.fromChatRequest(retryReq, false));
                return consumeEngineStream(
                        engineResolver.chatStreamNonStreaming(retryReq), activeStream, cancelled, onChunk);
            }
        });
    }

    private Duration engineRequestTimeout(long wallClockMs) {
        long timeoutMs = wallClockMs > 0 ? wallClockMs : defaultWallClockBudgetMs;
        return Duration.ofMillis(Math.max(1000L, timeoutMs));
    }

    private StreamResult consumeEngineStream(java.util.stream.Stream<TokenChunk> stream,
                                             AtomicReference<AutoCloseable> activeStream,
                                             Supplier<Boolean> cancelled,
                                             Consumer<String> onChunk) {
        // Try-with-resources ensures the token stream's onClose hook fires on
        // every exit path (break, exception, normal return). Registering the
        // stream before iteration gives the watchdog a handle it can close if
        // the worker blocks in a synchronous socket read.
        try (stream) {
            if (activeStream != null) activeStream.set(stream);
            StringBuilder acc = new StringBuilder();
            List<ChatMessage.NativeToolCall> toolCalls = new ArrayList<>();
            int alreadyEmittedLen = 0;
            boolean visibleOutputEmitted = false;
            try {
                for (TokenChunk ch : (Iterable<TokenChunk>) stream::iterator) {
                    if (cancelled != null && Boolean.TRUE.equals(cancelled.get())) break;
                    if (ch == null || Boolean.TRUE.equals(ch.done())) break;

                    if (ch.hasToolCalls()) {
                        toolCalls.addAll(ch.toolCalls());
                        continue;
                    }

                    String deltaRaw = Objects.toString(ch.text(), "");
                    acc.append(deltaRaw);
                    String noThink = Sanitize.stripThinkTags(acc.toString());
                    String cleaned = Sanitize.sanitizeForOutputPreservingToolCalls(noThink);
                    cleaned = Sanitize.hardTruncate(cleaned, safeCap());

                    int already = Math.min(alreadyEmittedLen, cleaned.length());
                    String emit = cleaned.substring(already);

                    acc.setLength(0);
                    acc.append(cleaned);
                    alreadyEmittedLen = cleaned.length();

                    if (onChunk != null && !emit.isEmpty()) {
                        onChunk.accept(emit);
                        visibleOutputEmitted = true;
                    }
                    if (ToolProtocolText.containsToolCalls(acc.toString())) {
                        break;
                    }
                    if (acc.length() >= safeCap()) break;
                }
                return new StreamResult(acc.toString(), toolCalls);
            } catch (EngineException.Transient transientFailure) {
                if (streamWasLocallyClosed(activeStream, stream)) {
                    return abortedStreamResult(
                            acc.toString(),
                            "local stream closed before generation completed");
                }
                if (visibleOutputEmitted) {
                    return abortedStreamResult(
                            acc.toString(),
                            "stream transport failed after partial output, retry skipped to avoid duplicate output");
                }
                throw transientFailure;
            } finally {
                if (activeStream != null) activeStream.compareAndSet(stream, null);
            }
        }
    }

    private static StreamResult abortedStreamResult(String partialText, String reason) {
        String marker = UiChrome.TURN_ABORTED_PREFIX + ": " + Objects.toString(reason, "generation aborted") + "]";
        String partial = Objects.toString(partialText, "");
        if (partial.isBlank()) {
            return new StreamResult(marker, List.of());
        }
        return new StreamResult(partial + System.lineSeparator() + marker, List.of());
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
        return mode == ToolChoiceMode.REQUIRED || mode == ToolChoiceMode.NAMED;
    }

    private List<ToolSpec> effectiveToolSpecs(List<ToolSpec> requestToolSpecs) {
        return requestToolSpecs == null ? toolSpecs : List.copyOf(requestToolSpecs);
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
                safe.maxOutputTokens());
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
        } catch (Exception ignored) {
            // engineWindow already 0; capability probe is best-effort
        }
        return engineWindow;
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
    private String assembleFromStream(java.util.stream.Stream<TokenChunk> stream,
                                      Consumer<String> onChunk,
                                      Supplier<Boolean> cancelled) {
        // Try-with-resources: closes the engine's token stream on every exit
        // path (cancel break, cap-reached break, exception, normal return).
        // For the Ollama transport this propagates to the HTTP body/socket
        // close via Stream.onClose - preventing the "Ollama keeps generating
        // into a dead consumer" leak that kept a hung repetition-loop stream
        // alive after the tool-call loop had moved on.
        try (stream) {
            StringBuilder acc = new StringBuilder();
            int alreadyEmittedLen = 0;

            for (TokenChunk ch : (Iterable<TokenChunk>) stream::iterator) {
                if (cancelled != null && Boolean.TRUE.equals(cancelled.get())) break;
                if (ch == null || Boolean.TRUE.equals(ch.done())) break;

                String deltaRaw = Objects.toString(ch.text(), "");
                acc.append(deltaRaw);
                String noThink = Sanitize.stripThinkTags(acc.toString());
                String cleaned = Sanitize.sanitizeForOutputPreservingToolCalls(noThink);
                cleaned = Sanitize.hardTruncate(cleaned, safeCap());

                int already = Math.min(alreadyEmittedLen, cleaned.length());
                String emit = cleaned.substring(already);

                acc.setLength(0);
                acc.append(cleaned);
                alreadyEmittedLen = cleaned.length();

                if (onChunk != null && !emit.isEmpty()) onChunk.accept(emit);
                if (acc.length() >= safeCap()) break;
            }
            return acc.toString();
        }
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
