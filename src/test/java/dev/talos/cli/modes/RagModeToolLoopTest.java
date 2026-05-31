package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.runtime.SessionMemory;
import dev.talos.core.Config;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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

    private static Config placeholderConfig() {
        Config cfg = new Config();
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "placeholder");
        llm.put("default_backend", "ollama");
        cfg.data.put("llm", llm);
        return cfg;
    }

    private static Path tinyWorkspace(Path workspace) throws java.io.IOException {
        Files.writeString(workspace.resolve("README.md"), "Tiny RAG fixture workspace.\n");
        return workspace;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  buildMessages — structured /api/chat messages
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class BuildMessages {

        @Test
        void no_history_no_context_returns_system_guidance_and_user() {
            List<ChatMessage> msgs = RagMode.buildMessages("sys prompt", "my question", List.of(), List.of());

            // system + empty-retrieval guidance + user = 3
            assertEquals(3, msgs.size());
            assertEquals("system", msgs.get(0).role());
            assertEquals("sys prompt", msgs.get(0).content());
            // guidance message for empty retrieval
            assertEquals("user", msgs.get(1).role());
            assertTrue(msgs.get(1).content().contains("No context snippets"),
                    "Empty retrieval should inject guidance message");
            assertEquals("user", msgs.get(2).role());
            assertEquals("my question", msgs.get(2).content());
        }

        @Test
        void with_context_injects_context_message_before_question() {
            List<Map<String, String>> snippets = List.of(
                    Map.of("path", "`src/Main.java#0`", "text", "public class Main {}")
            );

            List<ChatMessage> msgs = RagMode.buildMessages("sys", "explain Main", snippets, List.of());

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
            List<Map<String, String>> snippets = List.of(
                    Map.of("path", "`file1.java`", "text", "class One {}"),
                    Map.of("path", "`file2.java`", "text", "class Two {}"),
                    Map.of("path", "`file3.java`", "text", "class Three {}")
            );

            List<ChatMessage> msgs = RagMode.buildMessages("sys", "q", snippets, List.of());

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
            List<ChatMessage> history = memory.getTurns();
            List<Map<String, String>> snippets = List.of(
                    Map.of("path", "`bar.java`", "text", "int bar = 42;")
            );

            List<ChatMessage> msgs = RagMode.buildMessages("sys", "explain bar", snippets, history);

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
            List<ChatMessage> history = memory.getTurns();

            List<ChatMessage> msgs = RagMode.buildMessages("sys", "turn3-q", List.of(), history);

            // system + 4 history + guidance + user = 7 (empty context → guidance message)
            assertEquals(7, msgs.size());
            assertEquals("system", msgs.get(0).role());
            assertEquals("turn1-q", msgs.get(1).content());
            assertEquals("turn1-a", msgs.get(2).content());
            assertEquals("turn2-q", msgs.get(3).content());
            assertEquals("turn2-a", msgs.get(4).content());
            assertTrue(msgs.get(5).content().contains("No context snippets"),
                    "Empty retrieval should inject guidance message");
            assertEquals("turn3-q", msgs.get(6).content());
        }

        @Test
        void empty_history_same_as_no_history() {
            List<ChatMessage> msgs = RagMode.buildMessages("sys", "hello", List.of(), List.of());

            assertEquals(3, msgs.size(), "Empty history + empty snippets should produce system + guidance + user");
        }

        @Test
        void empty_snippet_list_injects_guidance_message() {
            List<ChatMessage> msgs = RagMode.buildMessages("sys", "hello", List.of(), List.of());

            assertEquals(3, msgs.size(), "Empty snippet list should add guidance message");
            assertEquals("system", msgs.get(0).role());
            assertTrue(msgs.get(1).content().contains("No context snippets"),
                    "Should inject empty-retrieval guidance");
            assertEquals("user", msgs.get(2).role());
        }

        @Test
        void null_snippet_list_injects_guidance_message() {
            List<ChatMessage> msgs = RagMode.buildMessages("sys", "hello", null, List.of());

            assertEquals(3, msgs.size(), "Null snippet list should add guidance message");
            assertTrue(msgs.get(1).content().contains("No context snippets"),
                    "Should inject empty-retrieval guidance for null snippets");
        }

        @Test
        void messages_list_is_mutable() {
            // ToolCallLoop mutates the message list in-place, so buildMessages
            // must return a mutable list.
            List<ChatMessage> msgs = RagMode.buildMessages("sys", "q", List.of(), List.of());

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
        void handle_returns_ok_result(@TempDir Path workspace) throws Exception {
            var ctx = Context.builder(placeholderConfig()).build();
            var mode = new RagMode();

            Optional<Result> result = mode.handle("what is this project", tinyWorkspace(workspace), ctx);

            assertTrue(result.isPresent());
            assertInstanceOf(Result.Ok.class, result.get());
            assertFalse(result.get().toString().isBlank(),
                    "Result should contain content");
        }

        @Test
        void handle_empty_query_returns_info() throws Exception {
            var ctx = Context.builder(placeholderConfig()).build();
            var mode = new RagMode();

            Optional<Result> result = mode.handle("", WS, ctx);

            assertTrue(result.isPresent());
            assertInstanceOf(Result.Info.class, result.get());
        }

        @Test
        void handle_does_not_update_memory_directly(@TempDir Path workspace) throws Exception {
            // Memory updates are centralized in TurnProcessor via MemoryUpdateListener
            var memory = new SessionMemory();
            var ctx = Context.builder(placeholderConfig()).memory(memory).build();
            var mode = new RagMode();

            mode.handle("test query", tinyWorkspace(workspace), ctx);

            assertFalse(memory.hasContent(),
                    "RagMode should not update memory directly (centralized in TurnProcessor)");
        }

        @Test
        void handle_null_toolCallLoop_does_not_throw(@TempDir Path workspace) throws Exception {
            // Context with no toolCallLoop (null) should not cause NPE
            var ctx = Context.builder(placeholderConfig()).build();
            var mode = new RagMode();

            assertDoesNotThrow(() -> mode.handle("test query", tinyWorkspace(workspace), ctx));
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
            var ctx = Context.builder(placeholderConfig()).build();
            // Default builder produces null toolCallLoop
            assertNull(ctx.toolCallLoop(),
                    "Default context should have null toolCallLoop (no TurnProcessor wired)");
        }

        @Test
        void buildMessages_returns_list_compatible_with_tool_loop() {
            // The ToolCallLoop.run() signature takes List<ChatMessage> messages.
            // Verify our buildMessages produces a compatible list.
            List<Map<String, String>> snippets = List.of(
                    Map.of("path", "`test.java`", "text", "code")
            );

            List<ChatMessage> msgs = RagMode.buildMessages("sys", "q", snippets, List.of());

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

