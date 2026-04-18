package dev.talos.core.llm;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.core.engine.EngineRegistry;
import dev.talos.core.util.Sanitize;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolSpec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
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
    private EngineRegistry registry;          // lazy; only if ENGINE
    private volatile String backend;          // ENGINE mode: current backend id (e.g., "ollama")
    private volatile String model;            // model name (or backend-qualified accepted via setModel)
    private final long responseMaxChars;

    /**
     * P2 — wall-clock budget for a single LLM call (one full
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
     * {@link #withWallClockBudget}, closes that gap.
     *
     * <p>Default 300_000 ms (5 min), overridable via
     * {@code limits.llm_timeout_ms} in config or per-call via the
     * {@code wallClockMs} parameter on the new public overloads.
     */
    private final long defaultWallClockBudgetMs;

    /**
     * P2 — idle-stream timeout (ms). If no chunk (text or tool-call) arrives
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
     * P2 — externally-settable cancel hook. The REPL (or future Ctrl-C
     * handler) calls {@link #setCancelSupplier} once at bootstrap to install
     * a {@link Supplier} that flips to {@code true} when the user requests
     * abort. The streaming loop polls it on every chunk; the watchdog polls
     * it once per tick. Default no-op preserves test behavior.
     */
    private volatile Supplier<Boolean> externalCancel = () -> false;

    /**
     * P2 — companion reset callback for {@link #externalCancel}. Invoked at
     * the top of each public streaming/non-streaming call so a Ctrl-C
     * pressed during turn N cannot leak into turn N+1. Default no-op
     * preserves test behavior (tests never set a cancel supplier).
     */
    private volatile Runnable externalCancelReset = () -> {};

    /**
     * Single-thread executor used solely to host the worker that executes
     * {@code engineAssembledWithMessagesFull} when wrapped by
     * {@link #withWallClockBudget}. We use a dedicated executor (rather than
     * the common pool) so we can issue {@code cancel(true)} on timeout
     * without disturbing other CompletableFutures in the JVM.
     */
    private final ExecutorService llmCallExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "talos-llm-call");
                t.setDaemon(true);
                return t;
            });

    /**
     * Single-thread scheduler for the idle-stream watchdog. Daemon so it
     * never holds the JVM open. One scheduler is shared across all calls;
     * each call schedules its own {@code ScheduledFuture} and cancels it on
     * normal completion.
     */
    private final java.util.concurrent.ScheduledExecutorService watchdogExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "talos-llm-watchdog");
                t.setDaemon(true);
                return t;
            });

    /** Tool definitions to include in engine chat requests (native tool calling). */
    private volatile List<ToolSpec> toolSpecs = List.of();

    // Telemetry: track truncation events
    private volatile int truncationCount = 0;

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
    // speculative abstraction. See docs/new-architecture/
    // talos-harness-main-plan.md §8 N4 and §10 discussion item 2 for
    // the design decision (option (a): minimal factory).
    private volatile java.util.List<String> scriptedResponses = null;
    private final java.util.concurrent.atomic.AtomicInteger scriptedCursor =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public LlmClient(Config cfg) {
        this.cfg = (cfg == null ? new Config() : cfg);

        // ---- transport mode (default: PLACEHOLDER for tests/local safety) ----
        // When a Config is provided, ignore env here to keep tests deterministic.
        // If you want ENGINE in the app, set it in config under llm.transport.
        Map<String, Object> llmBlock = CfgUtil.map(this.cfg.data.get("llm"));
        String transport = String.valueOf(llmBlock.getOrDefault("transport", "placeholder"));
        this.mode = "engine".equalsIgnoreCase(transport) ? TransportMode.ENGINE : TransportMode.PLACEHOLDER;

        // ---- defaults compatible with existing tests ----
        Map<String, Object> ollama = CfgUtil.map(this.cfg.data.get("ollama"));
        // Respect TALOS_OLLAMA_MODEL env var (same precedence as OllamaEngineProvider)
        String envModel = System.getenv("TALOS_OLLAMA_MODEL");
        String cfgModel;
        if (envModel != null && !envModel.isBlank()) {
            cfgModel = envModel.trim();
        } else {
            cfgModel = String.valueOf(ollama.getOrDefault("model", "qwen3:8b"));
        }
        this.model = sanitizeModelName(cfgModel);
        this.backend = Objects.toString(CfgUtil.map(this.cfg.data.get("llm")).getOrDefault("default_backend", "ollama"));

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

        // Lazy init registry only when ENGINE mode is actually used.
        if (this.mode == TransportMode.ENGINE) {
            this.registry = new EngineRegistry(this.cfg);
            // if config already contains a qualified model, keep it
            if (this.model.contains("/")) {
                String[] parts = this.model.split("/", 2);
                this.backend = parts[0];
                this.model = parts[1];
            }
            try { this.registry.select(this.backend, this.model); } catch (Exception ignore) {}
        }
    }

    /** Get number of truncation events that occurred (for telemetry/status reporting). */
    public int getTruncationCount() {
        return truncationCount;
    }

    /** Reset telemetry counters. */
    public void resetTelemetry() {
        truncationCount = 0;
    }

    // ── N4 scripted-LLM test seam (factories + helper) ────────────────

    /**
     * Test-only factory: returns an LlmClient whose
     * {@link #chatFull(List)} and {@link #chatStreamFull(List, Consumer)}
     * emit {@code responses} in order, one per call. After the list is
     * exhausted the last response is repeated (so a scripted run cannot
     * accidentally fall through to a real backend).
     *
     * <p>Ignores engine / Ollama configuration entirely — no backend
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

    /** Accepts "backend/model" or just "model" (in PLACEHOLDER, backend is ignored). */
    public void setModel(String name) {
        String sanitized = sanitizeModelName(Objects.toString(name, ""));
        if (sanitized.isBlank()) return;

        if (mode == TransportMode.ENGINE && sanitized.contains("/")) {
            String[] parts = sanitized.split("/", 2);
            this.backend = parts[0];
            this.model = parts[1];
            if (registry != null) try { registry.select(this.backend, this.model); } catch (Exception ignore) {}
        } else {
            this.model = sanitized;
            if (mode == TransportMode.ENGINE && registry != null) try { registry.select(this.backend, this.model); } catch (Exception ignore) {}
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

    /**
     * P2 — install an external cancel supplier (e.g., a Ctrl-C handler that
     * flips an {@link java.util.concurrent.atomic.AtomicBoolean}). Polled on
     * every stream chunk and once per watchdog tick. Pass {@code null} or a
     * {@code () -> false} supplier to disable.
     */
    public void setCancelSupplier(Supplier<Boolean> cancel) {
        this.externalCancel = (cancel == null) ? () -> false : cancel;
    }

    /**
     * P2 — install an external "reset the cancel flag" callback. Invoked
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
        return chat("(system) You are Talos, a local-first knowledge engine.", p, List.of());
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
        cleaned = Sanitize.hardTruncate(cleaned, safeCap(), () -> truncationCount++);
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

        EngineException lastTransient = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) backoff(attempt);
            try {
                ChatRequest req = new ChatRequest(backend, model, sys, usr, sn, timeout, List.of(), toolSpecs);
                return assembleFromStream(registry.engine().chatStream(req), onChunk, cancelled);
            } catch (EngineException.Transient t) {
                lastTransient = t;
                // retry on next iteration
            } catch (EngineException ee) {
                throw ee; // connection, model-not-found, response error — no retry
            } catch (Exception e) {
                throw new EngineException.ResponseError(0, e.getMessage(), e);
            }
        }
        throw lastTransient; // retries exhausted
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

        EngineException lastTransient = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) backoff(attempt);
            try {
                ChatRequest req = new ChatRequest(backend, model, "", "", List.of(), timeout, sanitized, toolSpecs);
                return assembleFromStream(registry.engine().chatStream(req), onChunk, cancelled);
            } catch (EngineException.Transient t) {
                lastTransient = t;
            } catch (EngineException ee) {
                throw ee;
            } catch (Exception e) {
                throw new EngineException.ResponseError(0, e.getMessage(), e);
            }
        }
        throw lastTransient;
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
        // P2 — clear any Ctrl-C from the previous turn so stale cancels
        // don't immediately short-circuit this call.
        externalCancelReset.run();
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
        // P2 — track the time of the last visible chunk; the watchdog (set up
        // inside withWallClockBudget) abort()s the worker if no chunk arrives
        // for {@link #defaultIdleMs} ms. The cancel supplier OR-combines the
        // engine-level cancel and the externally-set cancel hook so a Ctrl-C
        // future patch can plug in without touching this method.
        AtomicLong lastChunkAt = new AtomicLong(System.currentTimeMillis());
        Consumer<String> trackingSink = chunk -> {
            lastChunkAt.set(System.currentTimeMillis());
            if (onChunk != null) onChunk.accept(chunk);
        };
        Supplier<Boolean> cancel = this.externalCancel;
        return withWallClockBudget(
                () -> engineAssembledWithMessagesFullTracked(
                        messages, trackingSink, Duration.ofSeconds(90), cancel, lastChunkAt),
                wallClockMs,
                lastChunkAt,
                "streaming chat");
    }

    /**
     * Non-streaming chat that returns both text and native tool calls.
     * Used by the tool-call loop for re-prompting after tool execution.
     */
    public StreamResult chatFull(List<ChatMessage> messages) {
        return chatFull(messages, defaultWallClockBudgetMs);
    }

    /**
     * Non-streaming chat with an explicit wall-clock budget.
     * See {@link #chatStreamFull(List, Consumer, long)}.
     */
    public StreamResult chatFull(List<ChatMessage> messages, long wallClockMs) {
        // P2 — see chatStreamFull: clear stale cancel flag per call.
        externalCancelReset.run();
        if (scriptedResponses != null) {
            return new StreamResult(nextScriptedResponse(), List.of());
        }
        if (mode == TransportMode.PLACEHOLDER) {
            return new StreamResult(placeholderFromMessages(messages), List.of());
        }
        // P2 — same idle-watchdog + cancel-hook plumbing as chatStreamFull.
        // The non-streaming path still uses an internal stream loop, so
        // chunk arrivals are observable; idle detection is meaningful.
        AtomicLong lastChunkAt = new AtomicLong(System.currentTimeMillis());
        Consumer<String> trackingSink = chunk -> lastChunkAt.set(System.currentTimeMillis());
        Supplier<Boolean> cancel = this.externalCancel;
        return withWallClockBudget(
                () -> engineAssembledWithMessagesFullTracked(
                        messages, trackingSink, Duration.ofSeconds(90), cancel, lastChunkAt),
                wallClockMs,
                lastChunkAt,
                "non-streaming chat");
    }

    /**
     * Wrap an engine call in a wall-clock budget. On timeout, the worker is
     * interrupted (best-effort: JDK HttpClient body reads typically wake on
     * interrupt + close) and we synthesize a {@link StreamResult} containing
     * a single user-visible error line. We deliberately return rather than
     * throw: the calling tool-call loop is structured around StreamResults,
     * and an exception there causes the whole REPL turn to abort with an
     * unhelpful stack-trace flash. This keeps the UX coherent.
     */
    private StreamResult withWallClockBudget(java.util.concurrent.Callable<StreamResult> work,
                                             long wallClockMs,
                                             AtomicLong lastChunkAt,
                                             String label) {
        // Per-call idle watchdog: if no chunk arrives within defaultIdleMs,
        // cancel the worker. The watchdog tick interval is min(idle/4, 5s)
        // to keep the abort latency bounded without busy-spinning.
        java.util.concurrent.ScheduledFuture<?> watchdog = null;
        CompletableFuture<StreamResult> fut;
        if (wallClockMs <= 0) {
            try { return work.call(); }
            catch (RuntimeException re) { throw re; }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        fut = CompletableFuture.supplyAsync(() -> {
            try { return work.call(); }
            catch (RuntimeException re) { throw re; }
            catch (Exception e) { throw new RuntimeException(e); }
        }, llmCallExecutor);

        final long idleMs = defaultIdleMs;
        if (idleMs > 0 && lastChunkAt != null) {
            long tickMs = Math.max(500L, Math.min(idleMs / 4L, 5_000L));
            final CompletableFuture<StreamResult> futRef = fut;
            watchdog = watchdogExecutor.scheduleAtFixedRate(() -> {
                if (futRef.isDone()) return;
                long since = System.currentTimeMillis() - lastChunkAt.get();
                if (since > idleMs) {
                    futRef.completeExceptionally(new IdleStreamException(idleMs));
                }
            }, tickMs, tickMs, TimeUnit.MILLISECONDS);
        }

        try {
            return fut.get(wallClockMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            fut.cancel(true);
            String msg = "[turn aborted: " + label + " exceeded "
                    + (wallClockMs / 1000) + "s wall-clock budget — model is hung "
                    + "or producing tokens too slowly. Try a smaller model, a shorter prompt, "
                    + "or raise limits.llm_timeout_ms in config.]";
            return new StreamResult(msg, List.of());
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof IdleStreamException ise) {
                fut.cancel(true);
                String msg = "[turn aborted: " + label + " produced no tokens for "
                        + (ise.idleMs / 1000) + "s — model appears wedged. "
                        + "Try a smaller model or raise limits.llm_idle_ms in config.]";
                return new StreamResult(msg, List.of());
            }
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        } catch (InterruptedException ie) {
            fut.cancel(true);
            Thread.currentThread().interrupt();
            return new StreamResult("[turn aborted: interrupted]", List.of());
        } finally {
            if (watchdog != null) watchdog.cancel(false);
        }
    }

    /**
     * P2 — internal sentinel used by the idle watchdog to abort a hung
     * stream. Carries the configured idle threshold so the user-visible
     * abort message can quote the actual number.
     */
    private static final class IdleStreamException extends RuntimeException {
        final long idleMs;
        IdleStreamException(long idleMs) {
            super("idle stream > " + idleMs + " ms");
            this.idleMs = idleMs;
        }
    }

    /**
     * P2 — variant of {@link #engineAssembledWithMessagesFull} that calls
     * the tracking sink on every text chunk (so the idle watchdog sees
     * activity). Behavior is otherwise identical.
     */
    private StreamResult engineAssembledWithMessagesFullTracked(List<ChatMessage> messages,
                                                                Consumer<String> trackingSink,
                                                                Duration timeout,
                                                                Supplier<Boolean> cancelled,
                                                                AtomicLong lastChunkAt) {
        // Wrap the cancel supplier so the engine loop also bails when the
        // watchdog completes the future exceptionally (the worker thread
        // is then on borrowed time; we want it to drop out quickly).
        Supplier<Boolean> wrapped = () -> {
            if (cancelled != null && Boolean.TRUE.equals(cancelled.get())) return true;
            return Thread.currentThread().isInterrupted();
        };
        // Bump the heartbeat once before we start blocking on the engine —
        // protects against an engine that takes >idleMs to produce its
        // first chunk on a cold model.
        if (lastChunkAt != null) lastChunkAt.set(System.currentTimeMillis());
        return engineAssembledWithMessagesFull(messages, trackingSink, timeout, wrapped);
    }

    /**
     * ENGINE mode: assemble from token stream using structured messages via /api/chat.
     * Returns a {@link StreamResult} carrying both the assembled text and any
     * native tool calls.
     */
    private StreamResult engineAssembledWithMessagesFull(List<ChatMessage> messages,
                                                         Consumer<String> onChunk,
                                                         Duration timeout,
                                                         Supplier<Boolean> cancelled) {
        // Sanitize message content while preserving tool-call structure
        // (toolCalls, toolCallId) — these carry native tool-call context that
        // OllamaEngine.serializeChatMessage needs for proper /api/chat formatting.
        List<ChatMessage> sanitized = messages.stream()
                .map(m -> new ChatMessage(
                        m.role(),
                        Sanitize.sanitizeMessageContent(Objects.toString(m.content(), "")),
                        m.toolCalls(),
                        m.toolCallId()))
                .toList();

        EngineException lastTransient = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) backoff(attempt);
            try {
                ChatRequest req = new ChatRequest(backend, model, "", "", List.of(), timeout, sanitized, toolSpecs);
                java.util.stream.Stream<TokenChunk> stream = registry.engine().chatStream(req);
                StringBuilder acc = new StringBuilder();
                List<ChatMessage.NativeToolCall> toolCalls = new ArrayList<>();
                int alreadyEmittedLen = 0;

                for (TokenChunk ch : (Iterable<TokenChunk>) stream::iterator) {
                    if (cancelled != null && Boolean.TRUE.equals(cancelled.get())) break;
                    if (ch == null || Boolean.TRUE.equals(ch.done())) break;

                    // Native tool-call chunk: collect structured calls, skip text processing
                    if (ch.hasToolCalls()) {
                        toolCalls.addAll(ch.toolCalls());
                        continue;
                    }

                    // Text chunk: sanitize and emit as before
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
                return new StreamResult(acc.toString(), toolCalls);
            } catch (EngineException.Transient t) {
                lastTransient = t;
            } catch (EngineException ee) {
                throw ee;
            } catch (Exception e) {
                throw new EngineException.ResponseError(0, e.getMessage(), e);
            }
        }
        throw lastTransient;
    }

    // ── Retry / back-off constants ────────────────────────────────────────

    /** Max retries for transient engine errors (per call, not per session). */
    static final int MAX_RETRIES = 2;

    private static void backoff(int attempt) {
        try { Thread.sleep(attempt * 400L); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Shared streaming assembly loop used by both engine methods.
     * Sanitizes, strips think-tags, enforces hard cap, and emits chunks.
     */
    private String assembleFromStream(java.util.stream.Stream<TokenChunk> stream,
                                      Consumer<String> onChunk,
                                      Supplier<Boolean> cancelled) {
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

    @Override public void close() {
        if (registry != null) try { registry.close(); } catch (Exception ignored) {}
        try { llmCallExecutor.shutdownNow(); } catch (Exception ignored) {}
        try { watchdogExecutor.shutdownNow(); } catch (Exception ignored) {}
    }
}
