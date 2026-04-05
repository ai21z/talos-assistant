package dev.loqj.core.llm;

import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;
import dev.loqj.core.engine.EngineRegistry;
import dev.loqj.core.util.Sanitize;
import dev.loqj.spi.types.ChatRequest;
import dev.loqj.spi.types.TokenChunk;

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
        // Respect LOQJ_OLLAMA_MODEL env var (same precedence as OllamaEngineProvider)
        String envModel = System.getenv("LOQJ_OLLAMA_MODEL");
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

    /* -------- Convenience (non-RAG) wrappers -------- */

    public String chatPlain(String prompt) {
        String p = Sanitize.sanitizeForPrompt(Objects.toString(prompt, ""));
        return chat("(system) You are Loqs, a local-first knowledge engine.", p, List.of());
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
     */
    private String engineAssembled(String system,
                                   String user,
                                   List<Map<String, String>> snippets,
                                   Consumer<String> onChunk,
                                   Duration timeout,
                                   Supplier<Boolean> cancelled) {
        try {
            // sanitize prompt parts for model consumption
            final String sys = Sanitize.sanitizeForPrompt(Objects.toString(system, ""));
            final String usr = Sanitize.sanitizeForPrompt(Objects.toString(user, ""));

            // pre-sanitize snippets for prompt and also keep a flattened context (deterministic)
            List<Map<String,String>> sn = sanitizeSnippets(snippets);

            ChatRequest req = new ChatRequest(backend, model, sys, usr, sn, timeout);
            StringBuilder acc = new StringBuilder();

            int alreadyEmittedLen = 0;

            for (TokenChunk ch : (Iterable<TokenChunk>) registry.engine().chatStream(req)::iterator) {
                if (cancelled != null && Boolean.TRUE.equals(cancelled.get())) break;
                if (ch == null || Boolean.TRUE.equals(ch.done())) break;

                String deltaRaw = Objects.toString(ch.text(), "");
                // 1) Append raw delta to the aggregate
                acc.append(deltaRaw);

                // 2) Strip think on the WHOLE aggregate (handles tags split across chunks)
                String noThink = Sanitize.stripThinkTags(acc.toString());

                // 3) Now do output sanitization on the WHOLE thing
                String cleaned = Sanitize.sanitizeForOutput(noThink);

                // 4) Enforce the hard cap
                cleaned = Sanitize.hardTruncate(cleaned, safeCap());

                // 5) Figure out just the new suffix to emit
                int already = Math.min(alreadyEmittedLen, cleaned.length()); // keep a local int alreadyEmittedLen = 0; outside loop
                String emit = cleaned.substring(already);

                // 6) Update acc and counters
                acc.setLength(0);
                acc.append(cleaned);
                alreadyEmittedLen = cleaned.length();

                if (onChunk != null && !emit.isEmpty()) onChunk.accept(emit);
                if (acc.length() >= safeCap()) break;
            }

            // final aggregate is already sanitized and capped; return as-is
            return acc.toString();

        } catch (Exception e) {
            // Keep behavior predictable and safe
            String msg = "(error calling backend: " + e.getMessage() + ")";
            msg = Sanitize.sanitizeForOutput(msg);
            msg = Sanitize.stripThinkTags(msg);
            return Sanitize.hardTruncate(msg, safeCap());
        }
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
