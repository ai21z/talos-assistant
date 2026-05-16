package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.cli.repl.SessionMemory;
import dev.talos.core.Config;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AskMode}: conversational memory integration.
 *
 * <p>Verifies that AskMode reads from and writes to {@link SessionMemory},
 * ensuring multi-turn conversations maintain continuity.
 *
 * <p>These tests use PLACEHOLDER transport (no real LLM calls) so they are
 * fast and deterministic. The key property being tested is that the prompt
 * sent to the LLM includes prior conversation context.
 */
class AskModeTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    private static Config placeholderConfig() {
        Config cfg = new Config();
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "placeholder");
        llm.put("default_backend", "ollama");
        cfg.data.put("llm", llm);
        return cfg;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  buildMessages (structured /api/chat messages — primary code path)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void buildMessages_no_history_returns_system_and_user() {
        List<ChatMessage> msgs = AskMode.buildMessages("You are helpful.", "hello", List.of());
        assertEquals(2, msgs.size());
        assertEquals("system", msgs.get(0).role());
        assertEquals("You are helpful.", msgs.get(0).content());
        assertEquals("user", msgs.get(1).role());
        assertEquals("hello", msgs.get(1).content());
    }

    @Test
    void buildMessages_includes_prior_turns_between_system_and_current() {
        var memory = new SessionMemory();
        memory.update("make me ascii art", "Sure! What kind?");
        List<ChatMessage> history = memory.getTurns();

        List<ChatMessage> msgs = AskMode.buildMessages("sys", "a cat", history);
        assertEquals(4, msgs.size());
        assertEquals("system", msgs.get(0).role());
        assertEquals("user", msgs.get(1).role());
        assertEquals("make me ascii art", msgs.get(1).content());
        assertEquals("assistant", msgs.get(2).role());
        assertEquals("Sure! What kind?", msgs.get(2).content());
        assertEquals("user", msgs.get(3).role());
        assertEquals("a cat", msgs.get(3).content());
    }

    @Test
    void buildMessages_multi_turn_history_preserves_order() {
        var memory = new SessionMemory();
        memory.update("turn1-q", "turn1-a");
        memory.update("turn2-q", "turn2-a");
        List<ChatMessage> history = memory.getTurns();

        List<ChatMessage> msgs = AskMode.buildMessages("sys", "turn3-q", history);
        assertEquals(6, msgs.size());
        assertEquals("system", msgs.get(0).role());
        assertEquals("turn1-q", msgs.get(1).content());
        assertEquals("turn1-a", msgs.get(2).content());
        assertEquals("turn2-q", msgs.get(3).content());
        assertEquals("turn2-a", msgs.get(4).content());
        assertEquals("turn3-q", msgs.get(5).content());
    }

    @Test
    void buildMessages_empty_history_same_as_no_history() {
        List<ChatMessage> msgs = AskMode.buildMessages("sys", "hello", List.of());
        assertEquals(2, msgs.size(), "Empty history should produce just system + user");
    }

    @Test
    void buildMessages_null_history_same_as_no_history() {
        List<ChatMessage> msgs = AskMode.buildMessages("sys", "hello", (List<ChatMessage>) null);
        assertEquals(2, msgs.size(), "Null history should produce just system + user");
    }

    @Test
    void buildMessages_with_prior_turns_for_second_turn() {
        var memory = new SessionMemory();
        memory.update("make me ascii art", "Here is some ASCII art!");
        List<ChatMessage> history = memory.getTurns();

        List<ChatMessage> msgs = AskMode.buildMessages("sys", "a shield", history);
        assertTrue(msgs.size() >= 4, "Should have system + prior pair + current user");
        assertTrue(msgs.stream().anyMatch(m -> "make me ascii art".equals(m.content())),
                "Prior user turn should be in structured messages");
        assertEquals("a shield", msgs.get(msgs.size() - 1).content(),
                "Current user message should be last");
    }

    @Test
    void handle_does_not_update_memory_directly() throws Exception {
        // Memory updates are now centralized in TurnProcessor via MemoryUpdateListener.
        // AskMode.handle() should NOT call memory.update() — that's the TurnProcessor's job.
        var memory = new SessionMemory();
        var ctx = Context.builder(placeholderConfig()).memory(memory).build();
        var mode = new AskMode();

        mode.handle("first question", WS, ctx);
        // Memory should be empty because AskMode no longer writes to it directly
        assertFalse(memory.hasContent(),
                "AskMode should not update memory directly (centralized in TurnProcessor)");
        assertTrue(memory.getTurns().isEmpty(),
                "No structured turns should be added by AskMode directly");
    }


    // ═══════════════════════════════════════════════════════════════════════
    //  Memory updates are now centralized in TurnProcessor
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void handle_returns_ok_result_for_memory_listener() throws Exception {
        // TurnProcessor's MemoryUpdateListener extracts the answer from Result.Ok
        // Verify AskMode returns a Result.Ok with content that can be recorded
        var ctx = Context.builder(placeholderConfig()).build();
        var mode = new AskMode();

        Optional<Result> result = mode.handle("hello there", WS, ctx);
        assertTrue(result.isPresent());
        assertInstanceOf(Result.Ok.class, result.get());
        assertFalse(result.get().toString().isBlank(),
                "Result should contain content for memory recording");
    }

    @Test
    void handle_does_not_accumulate_memory_directly() throws Exception {
        // Verifies the architectural change: modes don't own memory management
        var memory = new SessionMemory();
        var ctx = Context.builder(placeholderConfig()).memory(memory).build();
        var mode = new AskMode();

        mode.handle("first question", WS, ctx);
        mode.handle("second question", WS, ctx);

        // Memory should remain empty — only TurnProcessor writes to it
        assertFalse(memory.hasContent(),
                "AskMode should not accumulate turns in memory directly");
    }

    @Test
    void handle_returns_content_across_multiple_turns() throws Exception {
        var memory = new SessionMemory();
        var ctx = Context.builder(placeholderConfig()).memory(memory).build();
        var mode = new AskMode();

        // Turn 1
        Optional<Result> r1 = mode.handle("make me ascii art", WS, ctx);
        assertTrue(r1.isPresent());

        // Turn 2 — AskMode reads history from ConversationManager
        // (history would be populated by TurnProcessor, not by AskMode)
        Optional<Result> r2 = mode.handle("a cat please", WS, ctx);
        assertTrue(r2.isPresent());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Fast-path tests (exact echo, think tags) — no memory interaction
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void exact_echo_does_not_update_memory() throws Exception {
        var memory = new SessionMemory();
        var ctx = Context.builder(placeholderConfig()).memory(memory).build();
        var mode = new AskMode();

        mode.handle("Respond with exactly: test output", WS, ctx);

        assertFalse(memory.hasContent(),
                "Exact echo fast-path should not update memory");
    }

    @Test
    void think_strip_does_not_update_memory() throws Exception {
        var memory = new SessionMemory();
        var ctx = Context.builder(placeholderConfig()).memory(memory).build();
        var mode = new AskMode();

        mode.handle("Print this without the think tags: <think>reasoning</think> output", WS, ctx);

        assertFalse(memory.hasContent(),
                "Think-strip fast-path should not update memory");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void handle_null_returns_empty() throws Exception {
        var mode = new AskMode();
        var ctx = Context.builder(placeholderConfig()).build();
        assertEquals(Optional.empty(), mode.handle(null, WS, ctx));
    }

    @Test
    void handle_blank_returns_empty() throws Exception {
        var mode = new AskMode();
        var ctx = Context.builder(placeholderConfig()).build();
        assertEquals(Optional.empty(), mode.handle("   ", WS, ctx));
    }

    @Test
    void canHandle_accepts_non_blank() {
        var mode = new AskMode();
        assertTrue(mode.canHandle("hello"));
        assertTrue(mode.canHandle("  something  "));
    }

    @Test
    void canHandle_rejects_null_and_blank() {
        var mode = new AskMode();
        assertFalse(mode.canHandle(null));
        assertFalse(mode.canHandle(""));
        assertFalse(mode.canHandle("   "));
    }

    @Test
    void name_is_ask() {
        assertEquals("ask", new AskMode().name());
    }
}


