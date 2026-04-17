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

    // ═══════════════════════════════════════════════════════════════════════
    //  R6 — No-tool grounding retry (evidence-required prompts)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("groundingRetryIfNeeded (R6, scoped to non-streaming no-tool branch)")
    class GroundingRetryTests {

        /** A clearly-above-threshold ungrounded-shape answer (no tools were used). */
        private String longUngroundedAnswer() {
            // 900+ chars of confident-sounding but zero-evidence prose. Shaped
            // like the real Turn 2/3/4 transcript fabrications — substantive
            // enough to slip past any deflection tier, short of sanitation.
            return "Based on the typical structure of this kind of project, the site "
                 + "is organized as a single HTML file with separate stylesheet and "
                 + "script references linked from the head and body. The CSS file "
                 + "controls visual presentation — colors, spacing, typography — "
                 + "while the JavaScript file handles the interactive behavior, "
                 + "especially the BMI calculation on form submission. The HTML "
                 + "provides the structural skeleton for both. In practice this "
                 + "means the three components are tightly coupled through the id "
                 + "and class attributes on the HTML elements, which the CSS "
                 + "selectors and the JavaScript document.getElementById calls rely "
                 + "on. As long as those identifiers remain stable the site will "
                 + "work as expected. No obvious cross-linking errors are likely "
                 + "given the conventional nature of the implementation. The "
                 + "general advice would be to keep the class names consistent and "
                 + "to make sure the script tag's src attribute and the link tag's "
                 + "href attribute both resolve correctly at load time.";
        }

        private Context newCtx() { return Context.builder(new Config()).build(); }

        // ── Helper detection tests ────────────────────────────────────

        @Test
        @DisplayName("latestUserRequest returns the last user-role message content")
        void latestUserRequestWalksFromTail() {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("first question"));
            messages.add(ChatMessage.assistant("first answer"));
            messages.add(ChatMessage.user("second question"));
            messages.add(ChatMessage.assistant("second answer"));

            assertEquals("second question", AssistantTurnExecutor.latestUserRequest(messages));
        }

        @Test
        @DisplayName("latestUserRequest returns null when no user message present")
        void latestUserRequestNullWhenAbsent() {
            List<ChatMessage> messages = List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.assistant("answer"));
            assertNull(AssistantTurnExecutor.latestUserRequest(messages));
            assertNull(AssistantTurnExecutor.latestUserRequest(List.of()));
            assertNull(AssistantTurnExecutor.latestUserRequest(null));
        }

        @Test
        @DisplayName("looksLikeEvidenceRequest matches real transcript prompts")
        void evidenceRequestMatchesTranscriptPrompts() {
            // Exact phrases from test-output.txt user turns that failed
            // ungrounded. These are the shapes the gate must catch.
            assertTrue(AssistantTurnExecutor.looksLikeEvidenceRequest(
                    "tell me how this site is wired together: which HTML file "
                    + "loads which CSS and JS files, and whether there are any "
                    + "broken or suspicious references."));
            assertTrue(AssistantTurnExecutor.looksLikeEvidenceRequest(
                    "Read the main HTML, CSS, and JS files and tell me 3 "
                    + "concrete improvement opportunities. Use evidence from "
                    + "the actual files, not generic website advice."));
            assertTrue(AssistantTurnExecutor.looksLikeEvidenceRequest(
                    "Check whether this website has mismatches between HTML "
                    + "classes/IDs and the selectors used in CSS or JavaScript."));
        }

        @Test
        @DisplayName("looksLikeEvidenceRequest does not match casual conversation")
        void evidenceRequestDoesNotMatchCasualPrompts() {
            assertFalse(AssistantTurnExecutor.looksLikeEvidenceRequest(
                    "explain how BMI is calculated"));
            assertFalse(AssistantTurnExecutor.looksLikeEvidenceRequest(
                    "what's the difference between metric and imperial BMI?"));
            assertFalse(AssistantTurnExecutor.looksLikeEvidenceRequest(
                    "can you rewrite this headline to sound more professional?"));
            assertFalse(AssistantTurnExecutor.looksLikeEvidenceRequest(""));
            assertFalse(AssistantTurnExecutor.looksLikeEvidenceRequest(null));
        }

        // ── Gate firing behavior ──────────────────────────────────────

        @Test
        @DisplayName("FIRES: long answer + zero tools + evidence-request prompt")
        void firesOnTranscriptTurn4Shape() {
            var ctx = newCtx();
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Check whether this website has mismatches between HTML "
                    + "classes/IDs and the selectors used in CSS or JavaScript. "
                    + "Do not change anything yet."));

            String ungrounded = longUngroundedAnswer();
            int beforeCount = messages.size();

            String out = AssistantTurnExecutor.groundingRetryIfNeeded(
                    ungrounded, messages, ctx);

            // Retry must have fired: assistant + corrective user message appended.
            assertEquals(beforeCount + 2, messages.size(),
                    "Grounding retry must append assistant + corrective user message");
            assertEquals("assistant", messages.get(beforeCount).role());
            assertEquals("user", messages.get(beforeCount + 1).role());
            assertTrue(messages.get(beforeCount + 1).content().toLowerCase()
                            .contains("without reading any files"),
                    "Corrective prompt must mention the lack of file reads");

            // Result must not be the original. It is either the retry text
            // (when PLACEHOLDER returned something substantive) or the
            // annotated original — both acceptable. Distinguish:
            assertNotEquals(ungrounded, out, "Result must differ from the original");
            if (out.startsWith(AssistantTurnExecutor.UNGROUNDED_ANNOTATION)) {
                // Retry was blank/identical — original was annotated.
                assertTrue(out.contains(ungrounded),
                        "Annotated result must preserve the original answer");
            }
        }

        // ── Non-firing cases ──────────────────────────────────────────

        @Test
        @DisplayName("DOES NOT FIRE: user did not ask for evidence (casual prompt)")
        void doesNotFireOnCasualPrompt() {
            var ctx = newCtx();
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.user("explain how BMI is calculated"));

            String answer = longUngroundedAnswer();
            int beforeCount = messages.size();

            String out = AssistantTurnExecutor.groundingRetryIfNeeded(answer, messages, ctx);

            assertSame(answer, out,
                    "Must not fire when the user did not ask for evidence/inspection");
            assertEquals(beforeCount, messages.size(),
                    "Messages must be unchanged when the gate does not fire");
        }

        @Test
        @DisplayName("DOES NOT FIRE: answer is short (below UNGROUNDED_MIN_CHARS)")
        void doesNotFireOnShortAnswer() {
            var ctx = newCtx();
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.user(
                    "Read the main files and identify the entry points."));

            String shortAnswer = "I'm not sure. Can you rephrase?";
            int beforeCount = messages.size();

            String out = AssistantTurnExecutor.groundingRetryIfNeeded(
                    shortAnswer, messages, ctx);

            assertSame(shortAnswer, out,
                    "Must not fire for answers below UNGROUNDED_MIN_CHARS");
            assertEquals(beforeCount, messages.size());
        }

        @Test
        @DisplayName("DOES NOT FIRE: null / blank answer passes through")
        void doesNotFireOnNullOrBlank() {
            var ctx = newCtx();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("Read the workspace and evidence from actual files."));

            assertNull(AssistantTurnExecutor.groundingRetryIfNeeded(null, messages, ctx));
            assertEquals("", AssistantTurnExecutor.groundingRetryIfNeeded("", messages, ctx));
            assertEquals("   ", AssistantTurnExecutor.groundingRetryIfNeeded("   ", messages, ctx));
        }

        @Test
        @DisplayName("NO OVERREACH: legitimate long explanation without evidence keywords is untouched")
        void doesNotFireOnLegitimateLongExplanation() {
            var ctx = newCtx();
            List<ChatMessage> messages = new ArrayList<>();
            // User asks a general knowledge question. A long, substantive
            // explanation answering it is legitimate — must not be second-guessed.
            messages.add(ChatMessage.user(
                    "explain the difference between BMI and body fat percentage"));

            String longExplanation = longUngroundedAnswer();
            int beforeCount = messages.size();

            String out = AssistantTurnExecutor.groundingRetryIfNeeded(
                    longExplanation, messages, ctx);

            assertSame(longExplanation, out,
                    "Long explanatory answers without an evidence-request prompt "
                    + "must pass through untouched");
            assertEquals(beforeCount, messages.size());
        }

        @Test
        @DisplayName("UNGROUNDED_MIN_CHARS is a boundary: one char below does not fire")
        void boundaryBelowThresholdDoesNotFire() {
            var ctx = newCtx();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("Read the main files and verify the wiring."));

            // Exactly UNGROUNDED_MIN_CHARS - 1 characters.
            String justBelow = "a".repeat(AssistantTurnExecutor.UNGROUNDED_MIN_CHARS - 1);

            String out = AssistantTurnExecutor.groundingRetryIfNeeded(
                    justBelow, messages, ctx);

            assertSame(justBelow, out, "Answer one char below threshold must not fire");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  N2 — Streaming-path grounding annotation
    //
    //  These tests lock in the streaming counterpart to R6. The helper is a
    //  pure predicate — we test it directly so the decision boundary is
    //  deterministic (independent of the PLACEHOLDER LLM's output length).
    //  One integration-level test confirms wiring by asserting absence of
    //  the annotation on a non-evidence prompt regardless of answer length.
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("N2 — Streaming grounding annotation")
    class StreamingGroundingTests {

        /** Long enough to pass {@link AssistantTurnExecutor#UNGROUNDED_MIN_CHARS}. */
        private String longAnswer() {
            return "a".repeat(AssistantTurnExecutor.UNGROUNDED_MIN_CHARS + 50);
        }

        @Test
        @DisplayName("predicate fires: long answer + evidence-request prompt")
        void fires_on_long_answer_plus_evidence_request() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("Please read the source files and verify the wiring."));

            assertTrue(AssistantTurnExecutor.shouldAppendStreamingGroundingAnnotation(
                    longAnswer(), messages),
                    "long answer + evidence marker must fire");
        }

        @Test
        @DisplayName("predicate does NOT fire: answer below UNGROUNDED_MIN_CHARS")
        void does_not_fire_when_answer_too_short() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("Please read the source files and verify the wiring."));

            // Exactly one char below the threshold.
            String justBelow = "a".repeat(AssistantTurnExecutor.UNGROUNDED_MIN_CHARS - 1);
            assertFalse(AssistantTurnExecutor.shouldAppendStreamingGroundingAnnotation(
                    justBelow, messages),
                    "just-below-threshold answer must not fire");
        }

        @Test
        @DisplayName("predicate does NOT fire: no evidence-request marker in prompt")
        void does_not_fire_without_evidence_marker() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("Tell me a joke about fish."));

            assertFalse(AssistantTurnExecutor.shouldAppendStreamingGroundingAnnotation(
                    longAnswer(), messages),
                    "plain conversational prompt must not fire the grounding gate");
        }

        @Test
        @DisplayName("predicate does NOT fire: null or blank answer")
        void does_not_fire_on_null_or_blank_answer() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("Read the files and check the wiring."));

            assertFalse(AssistantTurnExecutor.shouldAppendStreamingGroundingAnnotation(
                    null, messages));
            assertFalse(AssistantTurnExecutor.shouldAppendStreamingGroundingAnnotation(
                    "", messages));
            assertFalse(AssistantTurnExecutor.shouldAppendStreamingGroundingAnnotation(
                    "   \n\t   ", messages));
        }

        @Test
        @DisplayName("predicate inspects ONLY the latest user message")
        void inspects_only_latest_user_message() {
            var messages = new ArrayList<ChatMessage>();
            // Earlier turn had evidence markers; latest turn does not.
            messages.add(ChatMessage.user("Please read the files and verify."));
            messages.add(ChatMessage.assistant("Sure, here is my analysis."));
            messages.add(ChatMessage.user("Now tell me a joke."));

            assertFalse(AssistantTurnExecutor.shouldAppendStreamingGroundingAnnotation(
                    longAnswer(), messages),
                    "earlier evidence-request must not leak into a later conversational turn");
        }

        @Test
        @DisplayName("predicate mirrors non-streaming decision shape on same inputs")
        void predicate_mirrors_non_streaming_decision() {
            // Same gating logic (length + latest user marker) should yield
            // the same yes/no answer on both helpers. We assert this
            // invariant directly so future edits to one without the other
            // are caught.
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("Read the main file and identify the mismatch."));
            String longAns = longAnswer();

            boolean streamingFires = AssistantTurnExecutor
                    .shouldAppendStreamingGroundingAnnotation(longAns, messages);

            // The non-streaming helper has extra retry logic, but its own
            // firing precondition is structurally the same: >= MIN_CHARS
            // and looksLikeEvidenceRequest(latestUserRequest(messages)).
            boolean nonStreamingGatingMatches =
                    longAns.length() >= AssistantTurnExecutor.UNGROUNDED_MIN_CHARS
                    && AssistantTurnExecutor.looksLikeEvidenceRequest(
                            AssistantTurnExecutor.latestUserRequest(messages));

            assertEquals(nonStreamingGatingMatches, streamingFires,
                    "streaming predicate must agree with non-streaming gate on gating inputs");
            assertTrue(streamingFires, "sanity: this shape must fire");
        }

        @Test
        @DisplayName("streaming execute() does NOT inject annotation on non-evidence prompt")
        void streaming_execute_no_annotation_without_evidence_marker() {
            // Integration-level: regardless of what the PLACEHOLDER LLM
            // happens to return, a conversational prompt with no evidence
            // markers MUST NOT cause the annotation to be appended.
            var chunks = new ArrayList<String>();
            var ctx = Context.builder(new Config()).streamSink(chunks::add).build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("Tell me a short joke, please."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            assertTrue(out.streamed(), "streaming path must be marked streamed");
            assertFalse(out.text().contains("Grounding check"),
                    "no annotation must appear on non-evidence prompts. Got: " + out.text());
            String joined = String.join("", chunks);
            assertFalse(joined.contains("Grounding check"),
                    "no annotation must be pushed to the stream sink on non-evidence prompts");
        }

        @Test
        @DisplayName("streaming execute() does not rewrite the streamed prose (annotation is additive)")
        void streaming_execute_does_not_rewrite_streamed_content() {
            // Whatever the PLACEHOLDER returned, it must appear verbatim in
            // out.text() — the annotation may or may not be appended, but
            // the original streamed content is never replaced or shortened.
            var chunks = new ArrayList<String>();
            var ctx = Context.builder(new Config()).streamSink(chunks::add).build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("Read the files and check the wiring."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, WS, ctx, new AssistantTurnExecutor.Options());

            String streamedText = String.join("", chunks);
            // Remove any annotation the gate may have pushed into the sink.
            String streamedWithoutAnnotation = streamedText
                    .replace(AssistantTurnExecutor.UNGROUNDED_ANNOTATION.stripTrailing(), "")
                    .replaceAll("\\s+$", "");
            String textWithoutAnnotation = out.text()
                    .replace(AssistantTurnExecutor.UNGROUNDED_ANNOTATION.stripTrailing(), "")
                    .replaceAll("\\s+$", "");

            // The pre-annotation text content must match in both surfaces
            // (modulo the surrounding newline padding the annotation uses).
            assertTrue(textWithoutAnnotation.startsWith(streamedWithoutAnnotation.stripTrailing()),
                    "streamed content must appear at the start of out.text() — annotation must be additive, not a rewrite.\n"
                    + "streamed=<" + streamedWithoutAnnotation + ">\n"
                    + "text=<" + textWithoutAnnotation + ">");
        }
    }
}





