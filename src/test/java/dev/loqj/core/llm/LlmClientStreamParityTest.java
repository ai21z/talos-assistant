package dev.loqj.core.llm;

import dev.loqj.core.Config;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

final class LlmClientStreamParityTest {

    private static Config cappedConfig(int maxChars) {
        Config cfg = new Config();
        // Ensure limits exist and include response_max_chars
        @SuppressWarnings("unchecked")
        var limits = (java.util.Map<String,Object>) cfg.data.computeIfAbsent("limits", k -> new java.util.LinkedHashMap<>());
        limits.put("response_max_chars", maxChars);
        // Ensure ollama block exists to avoid NPE in some client constructors
        @SuppressWarnings("unchecked")
        var ollama = (java.util.Map<String,Object>) cfg.data.computeIfAbsent("ollama", k -> new java.util.LinkedHashMap<>());
        ollama.put("model", "qwen3:8b");
        return cfg;
    }

    private static void assertNoAnsiOrThink(String s) {
        assertFalse(s.contains("\u001B"), "ANSI escape codes should be stripped");
        assertFalse(s.matches(".*[\\x00-\\x08\\x0E-\\x1F\\x7F].*"), "Control characters should be stripped");
        assertFalse(s.contains("<think>") || s.contains("</think>"), "Think blocks should be removed");
    }

    @Test
    void stream_matches_nonStream_and_is_sanitized() {
        Config cfg = cappedConfig(8_000);
        LlmClient llm = new LlmClient(cfg);

        String system = "You are \u001B[31mLOQ-J\u001B[0m <think>sys</think>";
        String user   = "Hello <think>user</think> \u0007";
        List<Map<String,String>> ctx = List.of(
                Map.of("path", "README.md", "text", "line1 <think>c</think>\u001B[0m line2"),
                Map.of("path", "src/Main.java", "text", "public class X {}\u0007")
        );

        // Non-stream
        String oneShot = llm.chat(system, user, ctx);
        assertNotNull(oneShot);
        assertFalse(oneShot.isBlank());
        assertNoAnsiOrThink(oneShot);

        // Stream and aggregate
        AtomicReference<StringBuilder> buf = new AtomicReference<>(new StringBuilder());
        Consumer<String> onChunk = chunk -> buf.get().append(chunk);
        String streamed = llm.chatStream(system, user, ctx, onChunk);

        String agg = buf.get().toString();
        assertNoAnsiOrThink(streamed);
        assertNoAnsiOrThink(agg);

        // Parity: streamed aggregate equals returned streamed and equals non-stream
        assertEquals(streamed, agg, "Aggregated chunks should equal streamed return");
        assertEquals(oneShot, streamed, "Streamed and non-streamed outputs should be identical after sanitation");
    }

    @Test
    void response_is_capped_by_limits() {
        int cap = 512; // small cap to make capping observable
        Config cfg = cappedConfig(cap);
        LlmClient llm = new LlmClient(cfg);

        String bigUser = "X".repeat(10_000) + "<think>hidden</think>"; // large content with think blocks
        List<Map<String,String>> ctx = List.of(Map.of("path", "foo.txt", "text", "Y".repeat(10_000)));

        String oneShot = llm.chat("sys", bigUser, ctx);
        assertTrue(oneShot.length() <= cap, "Non-stream answer should be capped by response_max_chars");
        assertNoAnsiOrThink(oneShot);

        StringBuilder sb = new StringBuilder();
        String streamed = llm.chatStream("sys", bigUser, ctx, sb::append);
        assertTrue(streamed.length() <= cap, "Streamed answer should be capped by response_max_chars");
        assertEquals(streamed, sb.toString(), "Aggregated chunks should match streamed return");
        assertNoAnsiOrThink(streamed);
    }

    @Test
    void determinism_across_calls_is_preserved() {
        Config cfg = cappedConfig(4096);
        LlmClient llm = new LlmClient(cfg);

        String sys = "s";
        String usr = "u";
        List<Map<String,String>> ctx = List.of(Map.of("path", "p", "text", "t"));

        String a = llm.chat(sys, usr, ctx);
        String b = llm.chat(sys, usr, ctx);
        assertEquals(a, b, "Chat outputs should be deterministic for identical inputs in local placeholder mode");

        StringBuilder abuf = new StringBuilder();
        String sa = llm.chatStream(sys, usr, ctx, abuf::append);

        StringBuilder bbuf = new StringBuilder();
        String sb = llm.chatStream(sys, usr, ctx, bbuf::append);

        assertEquals(sa, sb, "Streamed outputs should be deterministic");
        assertEquals(abuf.toString(), bbuf.toString(), "Aggregated streamed chunks should be deterministic");
    }
}
