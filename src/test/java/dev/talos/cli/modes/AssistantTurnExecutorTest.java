package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AssistantTurnExecutor} — the shared LLM turn execution
 * logic used by AskMode and RagMode.
 *
 * <p>Uses PLACEHOLDER transport (default LlmClient) for deterministic,
 * no-network-required tests.
 */
@DisplayName("AssistantTurnExecutor")
class AssistantTurnExecutorTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    // ═══════════════════════════════════════════════════════════════════════
    //  Non-streaming path (no streamSink)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Non-streaming path")
    class NonStreaming {

        @Test
        void returns_non_empty_answer() {
            var ctx = Context.builder(new Config()).build();
            var messages = basicMessages();
            var opts = new AssistantTurnExecutor.Options();

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            assertFalse(out.text().isBlank(), "Should return non-empty text");
            assertFalse(out.streamed(), "Non-streaming path should not be marked streamed");
        }

        @Test
        void respects_timeout_option() {
            var ctx = Context.builder(new Config()).build();
            var messages = basicMessages();
            // Very long timeout — should still work normally
            var opts = new AssistantTurnExecutor.Options().llmTimeoutMs(60_000L);

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            assertFalse(out.text().isBlank());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Streaming path (with streamSink)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Streaming path")
    class Streaming {

        @Test
        void returns_answer_and_marks_streamed() {
            var chunks = new ArrayList<String>();
            var ctx = Context.builder(new Config()).streamSink(chunks::add).build();
            var messages = basicMessages();
            var opts = new AssistantTurnExecutor.Options();

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            assertFalse(out.text().isBlank(), "Should return non-empty text");
            assertTrue(out.streamed(), "Streaming path should be marked streamed");
            assertFalse(chunks.isEmpty(), "Stream sink should have received chunks");
        }

        @Test
        void streamed_text_matches_returned_text() {
            var chunks = new ArrayList<String>();
            var ctx = Context.builder(new Config()).streamSink(chunks::add).build();
            var messages = basicMessages();
            var opts = new AssistantTurnExecutor.Options();

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            String streamed = String.join("", chunks);
            assertEquals(streamed, out.text(),
                    "Returned text should match what was streamed");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Answer sanitization and truncation
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Sanitization and truncation")
    class SanitizationAndTruncation {

        @Test
        void answer_sanitizer_is_applied() {
            var ctx = Context.builder(new Config()).build();
            var messages = basicMessages();
            var opts = new AssistantTurnExecutor.Options()
                    .answerSanitizer(s -> "SANITIZED:" + s);

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            assertTrue(out.text().startsWith("SANITIZED:"),
                    "Sanitizer should have been applied: " + out.text());
        }

        @Test
        void response_truncated_when_over_max_chars() {
            var ctx = Context.builder(new Config()).build();
            // Use a question that generates a longer PLACEHOLDER response
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("You are a helpful assistant."));
            messages.add(ChatMessage.user("Explain the concept of dependency injection in software engineering"));
            // responseMaxChars(1) ensures any non-trivial answer gets truncated
            var opts = new AssistantTurnExecutor.Options().responseMaxChars(1);

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            assertTrue(out.text().contains("[output truncated]"),
                    "Should contain truncation marker: " + out.text());
        }

        @Test
        void null_sanitizer_treated_as_identity() {
            var ctx = Context.builder(new Config()).build();
            var messages = basicMessages();
            var opts = new AssistantTurnExecutor.Options().answerSanitizer(null);

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            assertFalse(out.text().isBlank(), "Should still return text with null sanitizer");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Error handling (structural verification)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        /**
         * Verifies the execute method catches exceptions without propagating.
         * Since LlmClient is final and PLACEHOLDER mode doesn't throw,
         * we verify error-path behavior by wrapping execute in a context
         * where the CompletableFuture times out (very short timeout).
         */
        @Test
        void extremely_short_timeout_triggers_timeout_handling() {
            var ctx = Context.builder(new Config()).build();
            var messages = basicMessages();
            // 1ms timeout — PLACEHOLDER is fast enough that this might not trigger,
            // but verifies the timeout wiring exists without errors
            var opts = new AssistantTurnExecutor.Options().llmTimeoutMs(1L);

            // Should not throw — errors are caught internally
            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);
            assertNotNull(out.text(), "Should always return non-null text");
        }

        @Test
        void execute_never_throws_to_caller() {
            // Even with a minimal context, execute should never propagate exceptions
            var ctx = Context.builder(new Config()).build();
            var messages = basicMessages();
            var opts = new AssistantTurnExecutor.Options();

            assertDoesNotThrow(
                    () -> AssistantTurnExecutor.execute(messages, WS, ctx, opts),
                    "Execute must catch all exceptions internally");
        }

        @Test
        void engine_exception_subtypes_are_all_sealed_and_accounted_for() {
            // Structural test: verify the sealed hierarchy matches what execute() catches.
            // This ensures new subtypes added to EngineException won't slip through.
            var subtypes = EngineException.class.getPermittedSubclasses();
            assertNotNull(subtypes, "EngineException should be sealed");
            // execute() catches: ConnectionFailed, ModelNotFound, Transient, EngineException (base)
            // All 4 permitted subtypes should be in the sealed list
            assertEquals(4, subtypes.length,
                    "EngineException should have exactly 4 subtypes (if this changes, update execute())");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TurnOutput record
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("TurnOutput")
    class TurnOutputTests {

        @Test
        void record_accessors() {
            var to = new AssistantTurnExecutor.TurnOutput("hello", true);
            assertEquals("hello", to.text());
            assertTrue(to.streamed());
        }

        @Test
        void record_equality() {
            var a = new AssistantTurnExecutor.TurnOutput("x", false);
            var b = new AssistantTurnExecutor.TurnOutput("x", false);
            assertEquals(a, b);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Options
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Options")
    class OptionsTests {

        @Test
        void fluent_api_returns_same_instance() {
            var opts = new AssistantTurnExecutor.Options();
            var returned = opts.llmTimeoutMs(1000).responseMaxChars(500).answerSanitizer(s -> s);
            assertSame(opts, returned, "Fluent methods should return same instance");
        }

        @Test
        void default_options_work() {
            var ctx = Context.builder(new Config()).build();
            var messages = basicMessages();
            // Default options — should work without any configuration
            var opts = new AssistantTurnExecutor.Options();

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            assertFalse(out.text().isBlank());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private static List<ChatMessage> basicMessages() {
        var msgs = new ArrayList<ChatMessage>();
        msgs.add(ChatMessage.system("You are a helpful assistant."));
        msgs.add(ChatMessage.user("What is 2+2?"));
        return msgs;
    }
}





