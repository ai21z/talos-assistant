package dev.loqj.core.llm;

import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;
import dev.loqj.core.util.Sanitize;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A minimal, local-first LLM client.
 * The transport to actual model backends is intentionally absent here.
 * A deterministic placeholder is produced so higher layers can be exercised safely.
 * <p>
 * Stream and non-stream outputs are sanitized consistently and capped by limits.
 */
public final class LlmClient {

    private final Config cfg;
    private volatile String model;
    private final long responseMaxChars;

    public LlmClient(Config cfg) {
        this.cfg = (cfg == null ? new Config() : cfg);
        // model default is read from config if present; otherwise a safe default is used
        Map<String, Object> ollama = CfgUtil.map(this.cfg.data.get("ollama"));
        String cfgModel = String.valueOf(ollama.getOrDefault("model", "qwen3:8b"));
        this.model = sanitizeModelName(cfgModel);

        Map<String, Object> limits = CfgUtil.map(this.cfg.data.get("limits"));
        long cfgMax = limits.containsKey("response_max_chars")
                ? CfgUtil.longAt(limits, "response_max_chars", 10 * 1024 * 1024L)
                : 10 * 1024 * 1024L;
        // defensive ceiling is clamped to a minimum to avoid zeros/negatives
        this.responseMaxChars = Math.max(1_000, cfgMax);
    }

    public String getModel() { return model; }

    public void setModel(String name) {
        String sanitized = sanitizeModelName(name);
        if (sanitized.isBlank()) return;
        this.model = sanitized;
    }

    /**
     * Non-streaming chat. A sanitized, capped response is returned.
     * Transport to a real model can be implemented later behind the same interface.
     */
    public String chat(String system, String user, List<Map<String, String>> snippets) {
        // system prompt is always treated as authoritative and sanitized distinctly
        final String sys = Sanitize.sanitizeForPrompt(Objects.toString(system, ""));
        final String usr = Sanitize.sanitizeForPrompt(Objects.toString(user, ""));

        // snippets are flattened deterministically and sanitized for prompts
        StringBuilder ctx = new StringBuilder();
        if (snippets != null) {
            for (Map<String, String> s : snippets) {
                if (s == null) continue;
                String path = Sanitize.sanitizeForPrompt(Objects.toString(s.get("path"), ""));
                String text = Sanitize.sanitizeForPrompt(Objects.toString(s.get("text"), ""));
                if (!path.isBlank()) {
                    ctx.append("\n\n[citation] ").append(path);
                }
                if (!text.isBlank()) {
                    ctx.append("\n").append(text);
                }
            }
        }

        // A deterministic placeholder response is produced.
        // When a backend gets attached, the same sanitation/capping steps should remain.
        String raw = synthesizeLocalAnswer(sys, usr, ctx.toString());

        // Output sanitation mirrors the RenderEngine rules:
        //  - control/ANSI stripping
        //  - suspicious HTML trimming
        //  - think-block removal
        //  - length capping
        String cleaned = Sanitize.sanitizeForOutput(raw);
        cleaned = Sanitize.stripThinkTags(cleaned); // compatibility alias
        cleaned = Sanitize.hardTruncate(cleaned, (int) Math.min(Integer.MAX_VALUE, responseMaxChars));
        return cleaned;
    }

    /**
     * Streaming chat. The same sanitation steps are applied per-chunk and to the final aggregate.
     * The aggregated string is returned for parity with non-streaming calls.
     */
    public String chatStream(String system,
                             String user,
                             List<Map<String, String>> snippets,
                             Consumer<String> onChunk) {
        // Produce the full sanitized answer first to guarantee consistency.
        String full = chat(system, user, snippets);

        // If a transport is attached later, chunking must keep the same sanitation order.
        // For now, a simple one-chunk callback is used to reduce surface area.
        if (onChunk != null && !full.isEmpty()) {
            // cap again defensively at the stream boundary
            String chunk = Sanitize.hardTruncate(full, (int) Math.min(Integer.MAX_VALUE, responseMaxChars));
            onChunk.accept(chunk);
        }
        return full;
    }

    /* -------- Optional timeout/cancellation overloads (no transport yet) -------- */

    public String chat(String system,
                       String user,
                       List<Map<String, String>> snippets,
                       Duration timeout) throws TimeoutException {
        // No blocking transport exists in this class; thus, only the cap path is executed.
        // If a future backend call blocks beyond timeout, a TimeoutException should be thrown there.
        return chat(system, user, snippets);
    }

    public String chatStream(String system,
                             String user,
                             List<Map<String, String>> snippets,
                             Consumer<String> onChunk,
                             Duration timeout,
                             Supplier<Boolean> cancelled) throws TimeoutException {
        // Cancellation is honored optimistically before emitting the chunk.
        if (cancelled != null && Boolean.TRUE.equals(cancelled.get())) return "";
        String full = chat(system, user, snippets);
        if (cancelled != null && Boolean.TRUE.equals(cancelled.get())) return "";
        if (onChunk != null && !full.isEmpty()) {
            String chunk = Sanitize.hardTruncate(full, (int) Math.min(Integer.MAX_VALUE, responseMaxChars));
            onChunk.accept(chunk);
        }
        return full;
    }

    /* -------- Convenience (non-RAG) wrappers -------- */

    public String chatPlain(String prompt) {
        String p = Sanitize.sanitizeForPrompt(Objects.toString(prompt, ""));
        return chat("(system) You are LOQ-J, a local-first assistant.", p, List.of());
    }

    public String chatPlain(String system, String user) {
        String sys = Sanitize.sanitizeForPrompt(Objects.toString(system, ""));
        String usr = Sanitize.sanitizeForPrompt(Objects.toString(user, ""));
        return chat(sys, usr, List.of());
    }

    /* -------- Internals -------- */

    private static String synthesizeLocalAnswer(String system, String user, String ctx) {
        // A short, predictable text is produced to keep tests and CLI behavior stable without network.
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
        // surrounding quotes/brackets are removed
        if ((s.startsWith("<") && s.endsWith(">")) ||
                (s.startsWith("\"") && s.endsWith("\"")) ||
                (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1);
        }
        // only a conservative set of characters is allowed
        s = s.replaceAll("[^A-Za-z0-9._:-]", "");
        if (s.contains("..") || s.contains("//") || s.contains("\\\\")) return "";
        if (s.length() > 64) s = s.substring(0, 64);
        if (s.isEmpty() || !Character.isLetterOrDigit(s.charAt(0))) return "";
        return s;
    }
}
