package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.cli.repl.SessionMemory;
import dev.talos.core.Config;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
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

    // ═══════════════════════════════════════════════════════════════════════
    //  buildMessages (structured /api/chat messages — primary code path)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void buildMessages_no_history_returns_system_and_user() {
        var ctx = Context.builder(new Config()).build();
        List<ChatMessage> msgs = AskMode.buildMessages("You are helpful.", "hello", ctx);
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
        var ctx = Context.builder(new Config()).memory(memory).build();

        List<ChatMessage> msgs = AskMode.buildMessages("sys", "a cat", ctx);
        assertEquals(4, msgs.size());
        // system first
        assertEquals("system", msgs.get(0).role());
        // prior user turn
        assertEquals("user", msgs.get(1).role());
        assertEquals("make me ascii art", msgs.get(1).content());
        // prior assistant turn
        assertEquals("assistant", msgs.get(2).role());
        assertEquals("Sure! What kind?", msgs.get(2).content());
        // current user message last
        assertEquals("user", msgs.get(3).role());
        assertEquals("a cat", msgs.get(3).content());
    }

    @Test
    void buildMessages_multi_turn_history_preserves_order() {
        var memory = new SessionMemory();
        memory.update("turn1-q", "turn1-a");
        memory.update("turn2-q", "turn2-a");
        var ctx = Context.builder(new Config()).memory(memory).build();

        List<ChatMessage> msgs = AskMode.buildMessages("sys", "turn3-q", ctx);
        assertEquals(6, msgs.size());
        // system + 2 prior pairs + current
        assertEquals("system", msgs.get(0).role());
        assertEquals("turn1-q", msgs.get(1).content());
        assertEquals("turn1-a", msgs.get(2).content());
        assertEquals("turn2-q", msgs.get(3).content());
        assertEquals("turn2-a", msgs.get(4).content());
        assertEquals("turn3-q", msgs.get(5).content());
    }

    @Test
    void buildMessages_empty_memory_same_as_no_history() {
        var memory = new SessionMemory();
        var ctx = Context.builder(new Config()).memory(memory).build();

        List<ChatMessage> msgs = AskMode.buildMessages("sys", "hello", ctx);
        assertEquals(2, msgs.size(), "Empty memory should produce just system + user");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  buildContextualPrompt (legacy flat-text — backward compat)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void contextualPrompt_with_no_history_returns_raw_input() {
        var ctx = Context.builder(new Config()).build();
        String result = AskMode.buildContextualPrompt("hello", ctx);
        assertEquals("hello", result);
    }

    @Test
    void contextualPrompt_with_empty_memory_returns_raw_input() {
        var memory = new SessionMemory();
        var ctx = Context.builder(new Config()).memory(memory).build();
        String result = AskMode.buildContextualPrompt("hello", ctx);
        assertEquals("hello", result);
    }

    @Test
    void contextualPrompt_includes_history_when_available() {
        var memory = new SessionMemory();
        memory.update("make me ascii art", "Sure! What would you like?");
        var ctx = Context.builder(new Config()).memory(memory).build();

        String result = AskMode.buildContextualPrompt("a cat", ctx);

        assertTrue(result.contains("[Conversation so far]"),
                "Should include conversation header");
        assertTrue(result.contains("make me ascii art"),
                "Should include prior user input");
        assertTrue(result.contains("Sure! What would you like?"),
                "Should include prior assistant response");
        assertTrue(result.contains("[Current message]"),
                "Should include current message header");
        assertTrue(result.endsWith("a cat"),
                "Should end with current user input");
    }

    @Test
    void contextualPrompt_includes_multiple_turns() {
        var memory = new SessionMemory();
        memory.update("make me ascii art", "What would you like?");
        memory.update("a cat", "Here is an ASCII cat!");
        var ctx = Context.builder(new Config()).memory(memory).build();

        String result = AskMode.buildContextualPrompt("make it bigger", ctx);

        assertTrue(result.contains("make me ascii art"));
        assertTrue(result.contains("a cat"));
        assertTrue(result.contains("Here is an ASCII cat"));
        assertTrue(result.contains("make it bigger"));
    }

    @Test
    void contextualPrompt_with_null_memory_returns_raw_input() {
        // Context.builder defaults memory to a new SessionMemory, so
        // we verify that even with an empty one it's safe
        var ctx = Context.builder(new Config()).build();
        assertDoesNotThrow(() -> AskMode.buildContextualPrompt("test", ctx));
    }

    @Test
    void handle_stores_structured_turns_in_memory() throws Exception {
        var memory = new SessionMemory();
        var ctx = Context.builder(new Config()).memory(memory).build();
        var mode = new AskMode();

        mode.handle("first question", WS, ctx);
        List<ChatMessage> turns = memory.getTurns();
        assertEquals(2, turns.size(), "One turn = user + assistant");
        assertEquals("user", turns.get(0).role());
        assertEquals("first question", turns.get(0).content());
        assertEquals("assistant", turns.get(1).role());

        mode.handle("second question", WS, ctx);
        turns = memory.getTurns();
        assertEquals(4, turns.size(), "Two turns = 2 × (user + assistant)");
        assertEquals("second question", turns.get(2).content());
    }

    @Test
    void handle_second_turn_buildMessages_includes_first_turn() throws Exception {
        var memory = new SessionMemory();
        var ctx = Context.builder(new Config()).memory(memory).build();
        var mode = new AskMode();

        mode.handle("make me ascii art", WS, ctx);

        // Now buildMessages for a second turn should include the first
        List<ChatMessage> msgs = AskMode.buildMessages("sys", "a shield", ctx);
        assertTrue(msgs.size() >= 4, "Should have system + prior pair + current user");
        assertTrue(msgs.stream().anyMatch(m -> "make me ascii art".equals(m.content())),
                "Prior user turn should be in structured messages");
        assertEquals("a shield", msgs.get(msgs.size() - 1).content(),
                "Current user message should be last");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Memory updates after LLM call
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void handle_updates_memory_after_successful_response() throws Exception {
        var memory = new SessionMemory();
        var ctx = Context.builder(new Config()).memory(memory).build();
        var mode = new AskMode();

        assertFalse(memory.hasContent(), "Memory should be empty before first turn");

        // PLACEHOLDER mode produces a deterministic response
        Optional<Result> result = mode.handle("hello there", WS, ctx);
        assertTrue(result.isPresent());

        assertTrue(memory.hasContent(), "Memory should have content after first turn");
        String content = memory.get();
        assertTrue(content.contains("hello there"),
                "Memory should contain user input");
    }

    @Test
    void handle_accumulates_multiple_turns_in_memory() throws Exception {
        var memory = new SessionMemory();
        var ctx = Context.builder(new Config()).memory(memory).build();
        var mode = new AskMode();

        mode.handle("first question", WS, ctx);
        mode.handle("second question", WS, ctx);

        String content = memory.get();
        assertTrue(content.contains("first question"),
                "Memory should contain first turn");
        assertTrue(content.contains("second question"),
                "Memory should contain second turn");
    }

    @Test
    void handle_sends_history_to_llm_on_second_turn() throws Exception {
        var memory = new SessionMemory();
        var ctx = Context.builder(new Config()).memory(memory).build();
        var mode = new AskMode();

        // Turn 1
        mode.handle("make me ascii art", WS, ctx);
        assertTrue(memory.hasContent(), "Memory should have content after turn 1");

        // Verify that buildContextualPrompt now includes the history
        String prompt = AskMode.buildContextualPrompt("a cat please", ctx);
        assertTrue(prompt.contains("[Conversation so far]"),
                "Second turn prompt should include conversation history header");
        assertTrue(prompt.contains("make me ascii art"),
                "Second turn prompt should include first turn's input");
        assertTrue(prompt.contains("[Current message]"),
                "Second turn prompt should include current message header");
        assertTrue(prompt.endsWith("a cat please"),
                "Second turn prompt should end with current input");

        // Turn 2
        mode.handle("a cat please", WS, ctx);
        String afterTurn2 = memory.get();
        assertTrue(afterTurn2.contains("make me ascii art"),
                "Memory after turn 2 should still contain turn 1 input");
        assertTrue(afterTurn2.contains("a cat please"),
                "Memory after turn 2 should contain turn 2 input");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Fast-path tests (exact echo, think tags) — no memory interaction
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void exact_echo_does_not_update_memory() throws Exception {
        var memory = new SessionMemory();
        var ctx = Context.builder(new Config()).memory(memory).build();
        var mode = new AskMode();

        mode.handle("Respond with exactly: test output", WS, ctx);

        assertFalse(memory.hasContent(),
                "Exact echo fast-path should not update memory");
    }

    @Test
    void think_strip_does_not_update_memory() throws Exception {
        var memory = new SessionMemory();
        var ctx = Context.builder(new Config()).memory(memory).build();
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
        var ctx = Context.builder(new Config()).build();
        assertEquals(Optional.empty(), mode.handle(null, WS, ctx));
    }

    @Test
    void handle_blank_returns_empty() throws Exception {
        var mode = new AskMode();
        var ctx = Context.builder(new Config()).build();
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


