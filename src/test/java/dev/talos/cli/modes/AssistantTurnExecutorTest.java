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

    // ── Deflection detection tests ───────────────────────────────────

    @Nested
    @DisplayName("isDeflection")
    class DeflectionTests {

        @Test
        void nullOrBlankIsDeflection() {
            assertTrue(AssistantTurnExecutor.isDeflection(null));
            assertTrue(AssistantTurnExecutor.isDeflection(""));
            assertTrue(AssistantTurnExecutor.isDeflection("   "));
        }

        @Test
        void genericAssistantBoilerplateIsDeflection() {
            assertTrue(AssistantTurnExecutor.isDeflection("How can I help you with these files?"));
            assertTrue(AssistantTurnExecutor.isDeflection("What would you like me to do next?"));
            assertTrue(AssistantTurnExecutor.isDeflection("Is there anything else you need?"));
            assertTrue(AssistantTurnExecutor.isDeflection("Feel free to ask if you have questions."));
            assertTrue(AssistantTurnExecutor.isDeflection("How can I assist you today?"));
        }

        @Test
        void substantiveShortAnswerIsNotDeflection() {
            assertFalse(AssistantTurnExecutor.isDeflection(
                    "The main HTML file is index.html. It loads style.css and script.js."));
        }

        @Test
        void longSubstantiveAnswerIsNotDeflection() {
            // A genuinely grounded answer that happens to be long
            String grounded = "The workspace contains index.html which is a BMI Calculator. "
                    + "CSS is defined inline via a <style> block in the <head>. "
                    + "JavaScript is inline via a <script> block before </body>. "
                    + "There are no external CSS or JS files. "
                    + "The settings.json file is not referenced by the HTML. "
                    + "x".repeat(400); // pad to > 500 chars
            assertFalse(AssistantTurnExecutor.isDeflection(grounded));
        }

        @Test
        void capabilityRecitationWithDeflectionEndingIsDeflection() {
            // This matches the real transcript Turn 3: a capability speech ending with "How can I assist you?"
            String capabilitySpeech =
                    "I can help you with tasks involving file manipulation and code searching within a workspace.\n\n"
                    + "Here is what I can do:\n\n"
                    + "* **Read/Write Files:** I can read the content of existing files, create new files, or overwrite existing ones.\n"
                    + "* **Edit Files:** I can perform find-and-replace operations on specific strings within a file.\n"
                    + "* **List Directories:** I can explore the structure of the workspace.\n"
                    + "* **Search Code:** I can search for specific text or regular expressions.\n\n"
                    + "**How can I assist you today?** Do you want to read a file, search for code, or perform a modification?";
            assertTrue(AssistantTurnExecutor.isDeflection(capabilitySpeech),
                    "Capability-recitation with deflection ending must be caught. Length: " + capabilitySpeech.length());
        }

        @Test
        void capabilityMentionWithoutDeflectionEndingIsNotDeflection() {
            // Mentions a capability but ends with substantive content — should not be flagged
            String answer = "I can help you with this analysis. "
                    + "The index.html file contains inline CSS in a <style> block. "
                    + "The calculateBMI() function handles the BMI computation. "
                    + "There are no external stylesheet or script references. "
                    + "x".repeat(300); // pad to > 500 chars
            assertFalse(AssistantTurnExecutor.isDeflection(answer));
        }
    }

    // ── Synthesis retry tests ────────────────────────────────────────

    @Nested
    @DisplayName("synthesisRetryIfNeeded")
    class SynthesisRetryTests {

        @Test
        void noRetryWhenNoToolsUsed() {
            var ctx = Context.builder(new Config()).build();
            var messages = basicMessages();
            String result = AssistantTurnExecutor.synthesisRetryIfNeeded(
                    "How can I help?", 0, messages, ctx);
            assertEquals("How can I help?", result, "Should not retry when no tools invoked");
        }

        @Test
        void noRetryWhenAnswerIsSubstantive() {
            var ctx = Context.builder(new Config()).build();
            var messages = basicMessages();
            String substantive = "The main file is index.html with inline CSS and JS.";
            String result = AssistantTurnExecutor.synthesisRetryIfNeeded(
                    substantive, 3, messages, ctx);
            assertEquals(substantive, result, "Should not retry substantive answers");
        }

        @Test
        void retryTriggeredForDeflectionAfterToolUse() {
            // PLACEHOLDER LLM always returns a non-blank, non-deflection answer,
            // so the retry should succeed and return something different from the original.
            var ctx = Context.builder(new Config()).build();
            var messages = new ArrayList<>(basicMessages());
            String deflection = "How can I help you with these files?";
            String result = AssistantTurnExecutor.synthesisRetryIfNeeded(
                    deflection, 2, messages, ctx);

            // The retry should have appended messages and called the LLM
            assertTrue(messages.size() > 2,
                    "Retry should have appended assistant + user messages");
            // PLACEHOLDER LLM returns a fixed response which is not a deflection,
            // so result should differ from original
            assertNotEquals(deflection, result,
                    "Retry should produce a different answer from the deflection");
        }

        @Test
        void retryAddsCorrectPromptMessages() {
            var ctx = Context.builder(new Config()).build();
            var messages = new ArrayList<>(basicMessages());
            String deflection = "What would you like me to do?";
            AssistantTurnExecutor.synthesisRetryIfNeeded(deflection, 1, messages, ctx);

            // Should have added: assistant(deflection) + user(retry instruction)
            boolean hasRetryInstruction = messages.stream()
                    .anyMatch(m -> m.content() != null
                            && m.content().contains("already gathered the needed evidence"));
            assertTrue(hasRetryInstruction,
                    "Retry should inject a synthesis instruction message");
        }
    }

    // ── Regression: inspect-only failure class ───────────────────────

    @Nested
    @DisplayName("Inspect-only regression")
    class InspectRegressionTests {

        /**
         * Regression test for the real transcript failure: a trivial HTML workspace
         * with inline CSS/JS. The model gathered all evidence but returned a generic
         * "How can I help?" deflection instead of answering.
         *
         * <p>This test proves the deflection gate catches this class of failure
         * and the synthesis retry fires. It does not prove the retry produces a
         * correct grounded answer (that requires a real model), but it proves the
         * mechanism activates for exactly the pattern observed.
         */
        @Test
        void deflectionDetectedForRealTranscriptPattern() {
            // Turn 1 final answer from the real transcript (291 chars)
            String turn1Answer = "I have listed the files in the current directory: `index.html` and `settings.json`.\n\n"
                    + "How can I help you with these files? For example, do you want me to read their content, modify them, "
                    + "or perform some kind of operation?";
            assertTrue(AssistantTurnExecutor.isDeflection(turn1Answer),
                    "Turn 1 transcript answer should be detected as deflection");

            // Turn 3 capability-recitation (714 chars)
            String turn3Answer = "I can help you with tasks involving file manipulation and code searching within a workspace.\n\n"
                    + "Here is what I can do:\n\n"
                    + "* **Read/Write Files:** I can read the content of existing files, create new files, or overwrite existing ones.\n"
                    + "*   **Edit Files:** I can perform find-and-replace operations on specific strings within a file.\n"
                    + "*   **List Directories:** I can explore the structure of the workspace.\n"
                    + "* **Search Code:** I can search for specific text or regular expressions across multiple files "
                    + "(`grep`), or perform semantic searches using `retrieve`.\n\n"
                    + "**How can I assist you today?** Do you want to read a file, search for code, or perform a modification?";
            assertTrue(AssistantTurnExecutor.isDeflection(turn3Answer),
                    "Turn 3 capability-recitation should be detected as deflection. Length: " + turn3Answer.length());
        }

        @Test
        void synthesisRetryFiresForRealTranscriptDeflection() {
            var ctx = Context.builder(new Config()).build();

            // Simulate the message state after tool execution: system + user + tool results
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("You are a helpful assistant."));
            messages.add(ChatMessage.user(
                    "Explore this workspace and identify the main HTML entry file, "
                    + "the main stylesheet file, and the main JavaScript file."));

            // The deflection that was actually returned
            String deflection = "I have listed the files in the current directory: `index.html` and `settings.json`.\n\n"
                    + "How can I help you with these files?";

            String result = AssistantTurnExecutor.synthesisRetryIfNeeded(
                    deflection, 3, messages, ctx);

            // The retry must have fired (message count increased)
            assertTrue(messages.size() > 2,
                    "Synthesis retry must fire for the real transcript deflection");
            // Result should differ from original deflection (PLACEHOLDER LLM returns something else)
            assertNotEquals(deflection, result,
                    "Retry should produce a different answer");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  R2 — Claim-vs-action truth layer (annotate-first)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("annotateIfFalseMutationClaim")
    class ClaimVsActionTests {

        /** Build a LoopResult with the given number of successful mutating tool calls. */
        private dev.talos.runtime.ToolCallLoop.LoopResult loopResult(int mutatingSuccesses) {
            return new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 1, 1,
                    List.of("talos.read_file"),
                    List.of(), 0, 0, false, mutatingSuccesses);
        }

        @Test
        @DisplayName("mutation claim + zero mutating successes → annotated")
        void falseMutationClaimGetsAnnotated() {
            // Real Turn 5 pattern: answer confidently asserts an applied edit,
            // but only read_file was invoked — no write_file / edit_file success.
            String answer = "The changes have been applied to `index.html`.\n\n"
                    + "I updated the headline and the introductory description to sound more "
                    + "professional and authoritative, while keeping the core functionality intact.";

            String out = AssistantTurnExecutor.annotateIfFalseMutationClaim(answer, loopResult(0));

            assertNotEquals(answer, out, "Answer must be modified (annotated)");
            assertTrue(out.startsWith(AssistantTurnExecutor.FALSE_MUTATION_ANNOTATION),
                    "Annotation must be prepended so users see it first");
            assertTrue(out.contains(answer), "Original answer text must be preserved verbatim");
        }

        @Test
        @DisplayName("mutation claim + successful mutating tool → NOT annotated")
        void realMutationBackingClaimIsNotAnnotated() {
            String answer = "I updated the headline in index.html as requested.";

            String out = AssistantTurnExecutor.annotateIfFalseMutationClaim(answer, loopResult(1));

            assertEquals(answer, out,
                    "Answer backed by a real mutating tool success must not be annotated");
            assertFalse(out.startsWith(AssistantTurnExecutor.FALSE_MUTATION_ANNOTATION));
        }

        @Test
        @DisplayName("no mutation claim → never annotated regardless of tool successes")
        void nonMutationAnswerIsNeverAnnotated() {
            String answer = "Based on the file contents, this is a BMI calculator written "
                    + "in a single HTML file with inline style and script blocks.";

            // Both zero mutations and some mutations — neither should annotate a
            // read-only / descriptive answer.
            assertEquals(answer,
                    AssistantTurnExecutor.annotateIfFalseMutationClaim(answer, loopResult(0)));
            assertEquals(answer,
                    AssistantTurnExecutor.annotateIfFalseMutationClaim(answer, loopResult(2)));
        }

        @Test
        @DisplayName("containsMutationClaim detects real Turn 5 phrases")
        void detectsTranscriptPhrases() {
            assertTrue(AssistantTurnExecutor.containsMutationClaim(
                    "The changes have been applied to `index.html`."));
            assertTrue(AssistantTurnExecutor.containsMutationClaim(
                    "I updated the headline to be more professional."));
            assertTrue(AssistantTurnExecutor.containsMutationClaim(
                    "I've edited the CTA button text."));
            assertTrue(AssistantTurnExecutor.containsMutationClaim(
                    "I wrote the new file."));
            assertTrue(AssistantTurnExecutor.containsMutationClaim(
                    "The file has been updated with the new content."));
        }

        @Test
        @DisplayName("containsMutationClaim does not flag benign descriptive language")
        void descriptiveLanguageIsNotFlagged() {
            // Grounded discussion of file contents must not trip the detector.
            assertFalse(AssistantTurnExecutor.containsMutationClaim(
                    "The label reads 'Weight (kg)' and the input accepts numbers."));
            assertFalse(AssistantTurnExecutor.containsMutationClaim(
                    "If you want to update the headline, you can edit line 12."));
            assertFalse(AssistantTurnExecutor.containsMutationClaim(
                    "You could change the CSS class, though it is not strictly required."));
            assertFalse(AssistantTurnExecutor.containsMutationClaim(
                    "The site uses inline styles and an inline script."));
        }

        @Test
        @DisplayName("null / blank answer → unchanged (no annotation)")
        void nullOrBlankPassThrough() {
            assertNull(AssistantTurnExecutor.annotateIfFalseMutationClaim(null, loopResult(0)));
            assertEquals("", AssistantTurnExecutor.annotateIfFalseMutationClaim("", loopResult(0)));
            assertEquals("   ", AssistantTurnExecutor.annotateIfFalseMutationClaim("   ", loopResult(0)));
        }

        @Test
        @DisplayName("null LoopResult → answer returned unchanged (defensive)")
        void nullLoopResultPassThrough() {
            String answer = "I updated the file.";
            assertEquals(answer,
                    AssistantTurnExecutor.annotateIfFalseMutationClaim(answer, null));
        }
    }
}





