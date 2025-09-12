package dev.loqj.core.llm;

import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Minimal, API-compatible LlmClient with added overloads.
 * NOTE: This keeps signatures used across the project:
 *  - getModel()/setModel()
 *  - chat(system, user, snippets)
 *  - chatStream(system, user, snippets, onChunk)
 *  - chatPlain(String)  // existing
 *  - chatPlain(String system, String user)  // new for MemoryPrompts
 *  - chat(..., Duration timeout)  // new
 *  - chatStream(..., onChunk, Duration timeout, Supplier<Boolean> cancelled)  // new
 *
 * If you already had richer implementations, port them back into the bodies
 * of chat/chatStream below; the overloads will still work unchanged.
 */
public class LlmClient {
    private final Config cfg;
    private volatile String model;

    public LlmClient(Config cfg) {
        this.cfg = Objects.requireNonNull(cfg);
        Map<String, Object> ollama = CfgUtil.map(cfg.data.get("ollama"));
        Object m = (ollama == null ? null : ollama.get("model"));
        this.model = (m == null ? "qwen3:8b" : String.valueOf(m));
    }

    public String getModel() { return model; }
    public void setModel(String name) { this.model = (name == null ? "" : name.trim()); }

    /* ===================== Existing APIs (provide your implementations here) ===================== */

    /** Non-streaming chat. Replace body with your existing logic if you had one. */
    public String chat(String system, String user, List<Map<String, String>> snippets) {
        // TODO: integrate your actual Ollama call here.
        // Returning empty string keeps callers safe; replace at your convenience.
        return "";
    }

    /** Streaming chat. Replace body with your existing streaming logic if you had one. */
    public String chatStream(String system, String user, List<Map<String, String>> snippets, Consumer<String> onChunk) {
        // Default fallback: call non-stream and emit once
        String out = chat(system, user, snippets);
        if (out != null && !out.isBlank() && onChunk != null) onChunk.accept(out);
        return out;
    }

    /** Plain chat with a single prompt (kept for backward-compat). */
    public String chatPlain(String prompt) {
        return chat(null, prompt, List.of());
    }

    /** NEW: two-argument plain call, used by MemoryPrompts. */
    public String chatPlain(String system, String user) {
        return chat(system, user, List.of());
    }

    /* ===================== New overloads with timeout / cancel ===================== */

    public String chat(String system, String user, List<Map<String, String>> snippets, Duration timeout) throws TimeoutException {
        ExecutorService es = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "llm-chat"); t.setDaemon(true); return t;
        });
        Future<String> f = es.submit(() -> chat(system, user, snippets));
        try {
            return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            f.cancel(true);
            throw te;
        } catch (ExecutionException | InterruptedException e) {
            f.cancel(true);
            return null;
        } finally {
            es.shutdownNow();
        }
    }

    public String chatStream(String system, String user, List<Map<String, String>> snippets,
                             Consumer<String> onChunk, Duration timeout, Supplier<Boolean> cancelled) throws TimeoutException {
        ExecutorService es = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "llm-chat-stream"); t.setDaemon(true); return t;
        });
        Future<String> f = es.submit(() -> {
            return chatStream(system, user, snippets, chunk -> {
                if (cancelled != null && Boolean.TRUE.equals(cancelled.get())) return;
                if (onChunk != null) onChunk.accept(chunk);
            });
        });
        try {
            return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            f.cancel(true);
            throw te;
        } catch (ExecutionException | InterruptedException e) {
            f.cancel(true);
            return null;
        } finally {
            es.shutdownNow();
        }
    }
}
