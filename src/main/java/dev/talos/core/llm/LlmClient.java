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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
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

    /** Tool definitions to include in engine chat requests (native tool calling). */
    private volatile List<ToolSpec> toolSpecs = List.of();

    // Telemetry: track truncation events
    private volatile int truncationCount = 0;

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
        List<ChatMessage> sanitized = messages.stream()
                .map(m -> new ChatMessage(m.role(), Sanitize.sanitizeMessageContent(Objects.toString(m.content(), ""))))
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
    }
}
