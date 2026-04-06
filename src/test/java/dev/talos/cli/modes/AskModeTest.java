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
    void handle_does_not_update_memory_directly() throws Exception {
        // Memory updates are now centralized in TurnProcessor via MemoryUpdateListener.
        // AskMode.handle() should NOT call memory.update() — that's the TurnProcessor's job.
        var memory = new SessionMemory();
        var ctx = Context.builder(new Config()).memory(memory).build();
        var mode = new AskMode();

        mode.handle("first question", WS, ctx);
        // Memory should be empty because AskMode no longer writes to it directly
        assertFalse(memory.hasContent(),
                "AskMode should not update memory directly (centralized in TurnProcessor)");
        assertTrue(memory.getTurns().isEmpty(),
                "No structured turns should be added by AskMode directly");
    }

    @Test
    void handle_second_turn_buildMessages_uses_conversationManager() throws Exception {
        // Simulate what happens when ConversationManager has history from prior turns
        // (populated by TurnProcessor's MemoryUpdateListener, not AskMode)
        var memory = new SessionMemory();
        memory.update("make me ascii art", "Here is some ASCII art!");
        var ctx = Context.builder(new Config()).memory(memory).build();

        List<ChatMessage> msgs = AskMode.buildMessages("sys", "a shield", ctx);
        assertTrue(msgs.size() >= 4, "Should have system + prior pair + current user");
        assertTrue(msgs.stream().anyMatch(m -> "make me ascii art".equals(m.content())),
                "Prior user turn should be in structured messages");
        assertEquals("a shield", msgs.get(msgs.size() - 1).content(),
                "Current user message should be last");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Memory updates are now centralized in TurnProcessor
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void handle_returns_ok_result_for_memory_listener() throws Exception {
        // TurnProcessor's MemoryUpdateListener extracts the answer from Result.Ok
        // Verify AskMode returns a Result.Ok with content that can be recorded
        var ctx = Context.builder(new Config()).build();
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
        var ctx = Context.builder(new Config()).memory(memory).build();
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
        var ctx = Context.builder(new Config()).memory(memory).build();
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


