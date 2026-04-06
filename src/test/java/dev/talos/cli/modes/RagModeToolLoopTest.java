package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.cli.repl.SessionMemory;
import dev.talos.core.Config;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RagMode's structured message building, conversation history
 * integration, and tool-call loop wiring.
 *
 * <p>Uses PLACEHOLDER transport (no real LLM calls) for fast, deterministic tests.
 */
class RagModeToolLoopTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    // ═══════════════════════════════════════════════════════════════════════
    //  buildMessages — structured /api/chat messages
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class BuildMessages {

        @Test
        void no_history_no_context_returns_system_and_user() {
            var ctx = Context.builder(new Config()).build();
            List<ChatMessage> msgs = RagMode.buildMessages("sys prompt", "my question", List.of(), ctx);

            assertEquals(2, msgs.size());
            assertEquals("system", msgs.get(0).role());
            assertEquals("sys prompt", msgs.get(0).content());
            assertEquals("user", msgs.get(1).role());
            assertEquals("my question", msgs.get(1).content());
        }

        @Test
        void with_context_injects_context_message_before_question() {
            var ctx = Context.builder(new Config()).build();
            List<Map<String, String>> snippets = List.of(
                    Map.of("path", "`src/Main.java#0`", "text", "public class Main {}")
            );

            List<ChatMessage> msgs = RagMode.buildMessages("sys", "explain Main", snippets, ctx);

            // system + context + user = 3
            assertEquals(3, msgs.size());
            assertEquals("system", msgs.get(0).role());
            // context message is user-role
            assertEquals("user", msgs.get(1).role());
            assertTrue(msgs.get(1).content().contains("src/Main.java#0"),
                    "Context message should include snippet path");
            assertTrue(msgs.get(1).content().contains("public class Main {}"),
                    "Context message should include snippet text");
            assertTrue(msgs.get(1).content().contains("retrieved context"),
                    "Context message should have preamble");
            // actual question last
            assertEquals("user", msgs.get(2).role());
            assertEquals("explain Main", msgs.get(2).content());
        }

        @Test
        void multiple_snippets_all_included_in_context_block() {
            var ctx = Context.builder(new Config()).build();
            List<Map<String, String>> snippets = List.of(
                    Map.of("path", "`file1.java`", "text", "class One {}"),
                    Map.of("path", "`file2.java`", "text", "class Two {}"),
                    Map.of("path", "`file3.java`", "text", "class Three {}")
            );

            List<ChatMessage> msgs = RagMode.buildMessages("sys", "q", snippets, ctx);

            assertEquals(3, msgs.size()); // system + context + user
            String ctxContent = msgs.get(1).content();
            assertTrue(ctxContent.contains("file1.java"), "Should contain first snippet");
            assertTrue(ctxContent.contains("file2.java"), "Should contain second snippet");
            assertTrue(ctxContent.contains("file3.java"), "Should contain third snippet");
            assertTrue(ctxContent.contains("class One {}"), "Should contain first snippet text");
            assertTrue(ctxContent.contains("class Three {}"), "Should contain third snippet text");
        }

        @Test
        void with_history_includes_prior_turns_between_system_and_context() {
            var memory = new SessionMemory();
            memory.update("what is foo?", "foo is a variable");
            var ctx = Context.builder(new Config()).memory(memory).build();
            List<Map<String, String>> snippets = List.of(
                    Map.of("path", "`bar.java`", "text", "int bar = 42;")
            );

            List<ChatMessage> msgs = RagMode.buildMessages("sys", "explain bar", snippets, ctx);

            // system + 2 history + context + user = 5
            assertEquals(5, msgs.size());
            assertEquals("system", msgs.get(0).role());
            // history pair
            assertEquals("user", msgs.get(1).role());
            assertEquals("what is foo?", msgs.get(1).content());
            assertEquals("assistant", msgs.get(2).role());
            assertEquals("foo is a variable", msgs.get(2).content());
            // context block
            assertEquals("user", msgs.get(3).role());
            assertTrue(msgs.get(3).content().contains("bar.java"));
            // current question
            assertEquals("user", msgs.get(4).role());
            assertEquals("explain bar", msgs.get(4).content());
        }

        @Test
        void multi_turn_history_preserves_order() {
            var memory = new SessionMemory();
            memory.update("turn1-q", "turn1-a");
            memory.update("turn2-q", "turn2-a");
            var ctx = Context.builder(new Config()).memory(memory).build();

            List<ChatMessage> msgs = RagMode.buildMessages("sys", "turn3-q", List.of(), ctx);

            // system + 4 history + user = 6 (no context snippets)
            assertEquals(6, msgs.size());
            assertEquals("system", msgs.get(0).role());
            assertEquals("turn1-q", msgs.get(1).content());
            assertEquals("turn1-a", msgs.get(2).content());
            assertEquals("turn2-q", msgs.get(3).content());
            assertEquals("turn2-a", msgs.get(4).content());
            assertEquals("turn3-q", msgs.get(5).content());
        }

        @Test
        void empty_history_same_as_no_history() {
            var memory = new SessionMemory();
            var ctx = Context.builder(new Config()).memory(memory).build();

            List<ChatMessage> msgs = RagMode.buildMessages("sys", "hello", List.of(), ctx);

            assertEquals(2, msgs.size(), "Empty memory should produce just system + user");
        }

        @Test
        void empty_snippet_list_skips_context_message() {
            var ctx = Context.builder(new Config()).build();

            List<ChatMessage> msgs = RagMode.buildMessages("sys", "hello", List.of(), ctx);

            assertEquals(2, msgs.size(), "Empty snippet list should not add context message");
            assertEquals("system", msgs.get(0).role());
            assertEquals("user", msgs.get(1).role());
        }

        @Test
        void null_snippet_list_skips_context_message() {
            var ctx = Context.builder(new Config()).build();

            List<ChatMessage> msgs = RagMode.buildMessages("sys", "hello", null, ctx);

            assertEquals(2, msgs.size(), "Null snippet list should not add context message");
        }

        @Test
        void messages_list_is_mutable() {
            // ToolCallLoop mutates the message list in-place, so buildMessages
            // must return a mutable list.
            var ctx = Context.builder(new Config()).build();
            List<ChatMessage> msgs = RagMode.buildMessages("sys", "q", List.of(), ctx);

            assertDoesNotThrow(
                    () -> msgs.add(ChatMessage.assistant("test")),
                    "Messages list must be mutable for ToolCallLoop"
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  handle() — end-to-end with PLACEHOLDER LLM
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class Handle {

        @Test
        void handle_returns_ok_result() throws Exception {
            var ctx = Context.builder(new Config()).build();
            var mode = new RagMode();

            Optional<Result> result = mode.handle("what is this project", WS, ctx);

            assertTrue(result.isPresent());
            assertInstanceOf(Result.Ok.class, result.get());
            assertFalse(result.get().toString().isBlank(),
                    "Result should contain content");
        }

        @Test
        void handle_empty_query_returns_info() throws Exception {
            var ctx = Context.builder(new Config()).build();
            var mode = new RagMode();

            Optional<Result> result = mode.handle("", WS, ctx);

            assertTrue(result.isPresent());
            assertInstanceOf(Result.Info.class, result.get());
        }

        @Test
        void handle_does_not_update_memory_directly() throws Exception {
            // Memory updates are centralized in TurnProcessor via MemoryUpdateListener
            var memory = new SessionMemory();
            var ctx = Context.builder(new Config()).memory(memory).build();
            var mode = new RagMode();

            mode.handle("test query", WS, ctx);

            assertFalse(memory.hasContent(),
                    "RagMode should not update memory directly (centralized in TurnProcessor)");
        }

        @Test
        void handle_null_toolCallLoop_does_not_throw() throws Exception {
            // Context with no toolCallLoop (null) should not cause NPE
            var ctx = Context.builder(new Config()).build();
            var mode = new RagMode();

            assertDoesNotThrow(() -> mode.handle("test query", WS, ctx));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Tool-call loop integration (structural verification)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ToolCallIntegration {

        @Test
        void context_toolCallLoop_is_accessible() {
            // Verify the Context record exposes toolCallLoop() for RagMode to use
            var ctx = Context.builder(new Config()).build();
            // Default builder produces null toolCallLoop
            assertNull(ctx.toolCallLoop(),
                    "Default context should have null toolCallLoop (no TurnProcessor wired)");
        }

        @Test
        void buildMessages_returns_list_compatible_with_tool_loop() {
            // The ToolCallLoop.run() signature takes List<ChatMessage> messages.
            // Verify our buildMessages produces a compatible list.
            var ctx = Context.builder(new Config()).build();
            List<Map<String, String>> snippets = List.of(
                    Map.of("path", "`test.java`", "text", "code")
            );

            List<ChatMessage> msgs = RagMode.buildMessages("sys", "q", snippets, ctx);

            // Must have at least system + user (context optional)
            assertTrue(msgs.size() >= 2);
            assertEquals("system", msgs.get(0).role());
            // Last message must be user (the question)
            assertEquals("user", msgs.get(msgs.size() - 1).role());
            // Must be mutable (ToolCallLoop appends to it)
            assertDoesNotThrow(() -> msgs.add(ChatMessage.assistant("tool response")));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void name_is_rag() {
        assertEquals("rag", new RagMode().name());
    }

    @Test
    void canHandle_accepts_non_blank() {
        var mode = new RagMode();
        assertTrue(mode.canHandle("hello"));
        assertTrue(mode.canHandle("  something  "));
    }

    @Test
    void canHandle_rejects_null_and_blank() {
        var mode = new RagMode();
        assertFalse(mode.canHandle(null));
        assertFalse(mode.canHandle(""));
        assertFalse(mode.canHandle("   "));
    }
}

