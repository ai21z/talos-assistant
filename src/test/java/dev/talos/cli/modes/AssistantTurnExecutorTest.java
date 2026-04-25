package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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

    private static Context scriptedContext(String... responses) {
        return Context.builder(new Config())
                .llm(LlmClient.scripted(List.of(responses)))
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Non-streaming path (no streamSink)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Non-streaming path")
    class NonStreaming {

        @Test
        void returns_non_empty_answer() {
            var ctx = scriptedContext("non-streamed answer");
            var messages = basicMessages();
            var opts = new AssistantTurnExecutor.Options();

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            assertFalse(out.text().isBlank(), "Should return non-empty text");
            assertFalse(out.streamed(), "Non-streaming path should not be marked streamed");
        }

        @Test
        void respects_timeout_option() {
            var ctx = scriptedContext("timeout-safe answer");
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
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted("streamed answer"))
                    .streamSink(chunks::add)
                    .build();
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
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted("streamed parity"))
                    .streamSink(chunks::add)
                    .build();
            var messages = basicMessages();
            var opts = new AssistantTurnExecutor.Options();

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            String streamed = String.join("", chunks);
            assertEquals(streamed, out.text(),
                    "Returned text should match what was streamed");
        }

        @Test
        void stream_filter_hides_bare_json_while_tool_loop_still_executes(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("index.html"), "<h1>Hello</h1>");

            var visibleChunks = new ArrayList<String>();
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.impl.ReadFileTool());
            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var streamFilter = new dev.talos.runtime.ToolCallStreamFilter(visibleChunks::add);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "I will inspect.\n"
                                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}",
                            "The file contains Hello.")))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .streamSink(streamFilter)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Read index.html and summarize it."));

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());

            String visible = String.join("", visibleChunks);
            assertFalse(visible.contains("\"name\""),
                    "bare tool-call JSON must not be visible in streamed output");
            assertFalse(visible.contains("talos.read_file"),
                    "tool protocol must be suppressed from streamed output");
            assertTrue(visible.contains("I will inspect."),
                    "ordinary prose before the tool call should remain visible");
            assertTrue(visible.contains("The file contains Hello."),
                    "post-tool streamed answer should remain visible");
            assertTrue(out.text().contains("The file contains Hello."),
                    "raw response must still enter the tool loop and complete normally");
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
            var ctx = scriptedContext("raw answer");
            var messages = basicMessages();
            var opts = new AssistantTurnExecutor.Options()
                    .answerSanitizer(s -> "SANITIZED:" + s);

            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(messages, WS, ctx, opts);

            assertTrue(out.text().startsWith("SANITIZED:"),
                    "Sanitizer should have been applied: " + out.text());
        }

        @Test
        void response_truncated_when_over_max_chars() {
            var ctx = scriptedContext("long answer");
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
            var ctx = scriptedContext("identity answer");
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
            var ctx = scriptedContext("fast answer");
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
            var ctx = scriptedContext("no throw");
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
            var ctx = scriptedContext("default options answer");
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
            var ctx = scriptedContext("Scripted retry answer.");
            var messages = new ArrayList<>(basicMessages());
            String deflection = "How can I help you with these files?";
            String result = AssistantTurnExecutor.synthesisRetryIfNeeded(
                    deflection, 2, messages, ctx);

            // The retry should have appended messages and called the LLM
            assertTrue(messages.size() > 2,
                    "Retry should have appended assistant + user messages");
            assertNotEquals(deflection, result,
                    "Retry should produce a different answer from the deflection");
        }

        @Test
        void retryAddsCorrectPromptMessages() {
            var ctx = scriptedContext("retry message");
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

        // ── Part A regression: post-tool task-anchor loss (real transcript) ───

        /**
         * Regression A: the real manual transcript (test-output.txt, Turn 2 / 6)
         * ended with "the original question is not visible in our current
         * conversation history" because the old retry prompt was generic. The
         * new retry must pin the user's verbatim request into the retry message
         * so the model cannot claim the question is missing.
         */
        @Test
        void retryPromptAnchorsToVerbatimUserRequest() {
            var ctx = scriptedContext("anchored retry answer");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("You are a helpful assistant."));
            String originalRequest =
                    "I dont like this site's look and feel... I want to completely change it and "
                    + "make it look like a garden in the spring where almonds starting blooming";
            messages.add(ChatMessage.user(originalRequest));
            // Simulate post-tool assistant + tool-result messages that push the
            // user request back in the context (matches native tool-call path).
            messages.add(ChatMessage.assistant("I'll inspect the files."));
            messages.add(ChatMessage.toolResult("call-1", "[tool_result] index.html contents…"));
            messages.add(ChatMessage.toolResult("call-2", "[tool_result] index.html, settings.json"));

            // A short deflection that the gate reliably catches (real Turn 2
            // ended with this family of phrasing once the retry didn't anchor).
            String deflection = "How can I help you with these files?";

            AssistantTurnExecutor.synthesisRetryIfNeeded(deflection, 2, messages, ctx);

            // Find the retry-instruction user message (most recently appended).
            String retryContent = null;
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage m = messages.get(i);
                if ("user".equals(m.role()) && m.content() != null
                        && m.content().contains("already gathered the needed evidence")) {
                    retryContent = m.content();
                    break;
                }
            }
            assertNotNull(retryContent, "Retry prompt must be appended as a user-role message");
            assertTrue(retryContent.contains("almonds starting blooming"),
                    "Retry prompt must include the verbatim original user request so the model "
                    + "cannot claim the question is missing. Actual: " + retryContent);
            assertTrue(retryContent.contains("Do not say the question is missing"),
                    "Retry prompt must explicitly forbid the 'question not visible' failure mode.");
        }

        /**
         * Regression A (helper-level): {@link AssistantTurnExecutor#latestUserRequest}
         * must return the ORIGINAL user request, not an intermediate tool_result,
         * on the native tool-call path where tool results have role="tool".
         */
        @Test
        void latestUserRequestReturnsOriginalOnNativeToolPath() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("redesign index.html as a spring garden"));
            messages.add(ChatMessage.assistant("reading…"));
            messages.add(ChatMessage.toolResult("c1", "file contents"));
            messages.add(ChatMessage.toolResult("c2", "dir listing"));

            String req = AssistantTurnExecutor.latestUserRequest(messages);
            assertEquals("redesign index.html as a spring garden", req,
                    "latestUserRequest must skip role=tool messages and return the user turn");
        }

        @Test
        void latestUserRequestSkipsSyntheticToolResultsOnTextPath() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("hey can you tell me what is in this workspace?"));
            messages.add(ChatMessage.assistant("{\"name\":\"talos.edit_file\",\"arguments\":{}}"));
            messages.add(ChatMessage.user("[tool_result: talos.edit_file]\n"
                    + "[error] This exact edit was already attempted and failed. "
                    + "Alternatively, use talos.write_file to replace the entire file content.\n"
                    + "[/tool_result]"));

            String req = AssistantTurnExecutor.latestUserRequest(messages);

            assertEquals("hey can you tell me what is in this workspace?", req,
                    "latestUserRequest must not treat text-path tool results as user intent");
        }

        @Test
        void mutationRetryExecutesTextFallbackToolCallsInsteadOfReturningRawJson() {
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.TalosTool() {
                @Override public String name() { return "talos.list_dir"; }
                @Override public String description() { return "List files"; }
                @Override public dev.talos.tools.ToolDescriptor descriptor() {
                    return new dev.talos.tools.ToolDescriptor(
                            name(), description(), "{\"path\":\"string\"}");
                }
                @Override public dev.talos.tools.ToolResult execute(
                        dev.talos.tools.ToolCall call, dev.talos.tools.ToolContext ctx) {
                    return dev.talos.tools.ToolResult.ok("index.html\nstyle.css");
                }
            });

            var processor = new dev.talos.runtime.TurnProcessor(
                    null, new dev.talos.runtime.NoOpApprovalGate(), registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}",
                            "Listed files from the retry.")))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("change the file"));
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "original answer", 1, 1, List.of("talos.read_file"), messages,
                    0, 0, false, 0, List.of(), 0, 0, 0, 0);

            var result = AssistantTurnExecutor.mutationRequestRetryIfNeeded(
                    "original answer", messages, loopResult, WS, ctx);

            assertEquals("Listed files from the retry.", result.answer());
            assertFalse(result.answer().contains("\"name\""),
                    "text-fallback tool JSON must not leak as the final answer");
            assertNotNull(result.extraSummary(),
                    "text-fallback retry tool calls should re-enter the tool loop");
        }

        @Test
        void mutationRetryDoesNotFireFromSyntheticToolResultTail() {
            var ctx = scriptedContext("retry should not be called");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("hey can you tell me what is in this workspace?"));
            messages.add(ChatMessage.assistant("{\"name\":\"talos.edit_file\",\"arguments\":{}}"));
            messages.add(ChatMessage.user("[tool_result: talos.edit_file]\n"
                    + "[error] This exact edit was already attempted and failed. "
                    + "Alternatively, use talos.write_file to replace the entire file content.\n"
                    + "[/tool_result]"));
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "original answer", 10, 8, List.of("talos.edit_file"), messages,
                    3, 2, true, 0, List.of("index.html"), 0, 0, 2, 0);

            var result = AssistantTurnExecutor.mutationRequestRetryIfNeeded(
                    "original answer", messages, loopResult, WS, ctx);

            assertEquals("original answer", result.answer(),
                    "synthetic B3 diagnostic must not be treated as mutation intent");
            assertEquals(0, result.mutationsInRetry());
            assertNull(result.extraSummary());
        }

        @Test
        void mutationRetryDoesNotFireAfterApprovalDeniedMutation() {
            var ctx = scriptedContext("retry should not be called");
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("I think the html is completely wrong. Can you fix it?"));
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "manual replacement prose", 3, 5,
                    List.of("talos.read_file", "talos.edit_file", "talos.write_file"),
                    messages, 2, 0, false, 0, List.of("index.html"),
                    0, 0, 0, 0,
                    List.of(
                            new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                                    "talos.edit_file", "index.html", false, true, true, "",
                                    "User did not approve the talos.edit_file call."),
                            new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "index.html", false, true, true, "",
                                    "User did not approve the talos.write_file call.")
                    ));

            var result = AssistantTurnExecutor.mutationRequestRetryIfNeeded(
                    "manual replacement prose", messages, loopResult, WS, ctx);

            assertEquals("manual replacement prose", result.answer());
            assertEquals(0, result.mutationsInRetry());
            assertNull(result.extraSummary(),
                    "approval denial already explains zero mutations, so missing-mutation retry must not fire");
        }

        @Test
        void mutationRetryApprovalDenialUsesDeniedMutationSummary() {
            var registry = new dev.talos.tools.ToolRegistry();
            registry.register(new dev.talos.tools.TalosTool() {
                @Override public String name() { return "talos.edit_file"; }
                @Override public String description() { return "Edit file"; }
                @Override public dev.talos.tools.ToolDescriptor descriptor() {
                    return new dev.talos.tools.ToolDescriptor(
                            name(), description(), null, dev.talos.tools.ToolRiskLevel.WRITE);
                }
                @Override public dev.talos.tools.ToolResult execute(
                        dev.talos.tools.ToolCall call, dev.talos.tools.ToolContext ctx) {
                    return dev.talos.tools.ToolResult.ok("edit-ok");
                }
            });

            var processor = new dev.talos.runtime.TurnProcessor(
                    null, (description, detail) -> false, registry);
            var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted(List.of(
                            "{\"name\":\"talos.edit_file\",\"arguments\":{\"path\":\"index.html\","
                                    + "\"old_string\":\"<div class=\\\"hero-content\\\">\","
                                    + "\"new_string\":\"<div class=\\\"hero-content cta-button\\\">\"}}")))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Now apply the smallest fix by editing index.html."));
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "raw malformed tool call", 1, 0, List.of(), messages,
                    0, 0, false, 0, List.of(), 0, 0, 0, 0);

            var result = AssistantTurnExecutor.mutationRequestRetryIfNeeded(
                    "raw malformed tool call", messages, loopResult, WS, ctx);

            assertEquals(0, result.mutationsInRetry());
            assertNotNull(result.extraSummary());
            assertTrue(result.answer().contains("No file changes were applied because approval was denied for:"));
            assertTrue(result.answer().contains("index.html: approval denied"));
            assertFalse(result.answer().contains("Tool loop stopped because the requested mutation was not approved."),
                    "retry-path denial should use the same denied-mutation summary as the main tool loop");
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
            var ctx = scriptedContext("Grounded follow-up based on inspected files.");

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
                    List.of(), 0, 0, false, mutatingSuccesses, List.of(),
                    0, 0, 0, 0);
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

        @Test
        @DisplayName("partial mutation success replaces answer with verified outcome summary")
        void partialMutationTurnGetsVerifiedSummary() {
            String answer = "Great! The title, header, hero copy, and stylesheet have all been updated.";
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 2, 4,
                    List.of("talos.edit_file", "talos.edit_file", "talos.edit_file", "talos.write_file"),
                    List.of(), 1, 0, false, 3, List.of(),
                    0, 0, 0, 0,
                    List.of(
                            new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                                    "talos.edit_file", "index.html", false, true, "",
                                    "old_string not found in index.html. The exact text was not found in the file."),
                            new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                                    "talos.edit_file", "index.html", true, true,
                                    "Edited index.html: replaced 4 line(s) with 4 line(s)", ""),
                            new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                                    "talos.edit_file", "index.html", true, true,
                                    "Edited index.html: replaced 6 line(s) with 6 line(s)", ""),
                            new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "style.css", true, true,
                                    "Updated style.css (28 lines, 540 bytes)", "")
                    ));

            String out = AssistantTurnExecutor.summarizePartialMutationOutcomesIfNeeded(answer, loopResult, 0);

            assertTrue(out.startsWith(AssistantTurnExecutor.PARTIAL_MUTATION_ANNOTATION));
            assertTrue(out.contains("Succeeded:"));
            assertTrue(out.contains("Failed:"));
            assertTrue(out.contains("style.css"));
            assertTrue(out.contains("old_string not found"));
            assertFalse(out.contains("title, header, hero copy, and stylesheet have all been updated"),
                    "unverified model prose must be replaced on partial-success mutation turns");
        }

        @Test
        @DisplayName("denied mutation turn replaces manual-update prose with factual no-change summary")
        void deniedMutationTurnGetsNoChangeSummary() {
            String answer = """
                    I understand the user's request and will proceed by manually updating the file.

                    ### Corrected `index.html` Content:
                    ```html
                    <!DOCTYPE html><html>broken replacement</html>
                    ```
                    """;
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("I think the html is completely wrong. Can you fix it?"));
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 2, 3,
                    List.of("talos.read_file", "talos.edit_file"),
                    messages, 1, 0, false, 0, List.of("index.html"),
                    0, 0, 0, 0,
                    List.of(
                            new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                                    "talos.edit_file", "index.html", false, true, true, "",
                                    "User did not approve the talos.edit_file call.")
                    ));

            String out = AssistantTurnExecutor.summarizeDeniedMutationOutcomesIfNeeded(
                    answer, messages, loopResult, 0);

            assertTrue(out.startsWith(AssistantTurnExecutor.DENIED_MUTATION_ANNOTATION));
            assertTrue(out.contains("No file changes were applied"));
            assertTrue(out.contains("approval was denied"));
            assertTrue(out.contains("index.html"));
            assertFalse(out.contains("Corrected `index.html` Content"),
                    "manual replacement prose must not survive a denied mutation turn");
        }

        @Test
        @DisplayName("denied mutation does not also get generic false-mutation annotation")
        void deniedMutationSkipsGenericFalseMutationAnnotation() {
            String answer = "The changes have been applied to `index.html`.";
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 1, 1,
                    List.of("talos.edit_file"),
                    List.of(), 1, 0, false, 0, List.of("index.html"),
                    0, 0, 0, 0,
                    List.of(
                            new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                                    "talos.edit_file", "index.html", false, true, true, "",
                                    "User did not approve the talos.edit_file call.")
                    ));

            String out = AssistantTurnExecutor.annotateIfFalseMutationClaim(answer, loopResult, 0);

            assertEquals(answer, out,
                    "denied mutation turns should be handled by the dedicated denied-mutation summary only");
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

        private Context newCtx() { return scriptedContext("grounded retry answer"); }

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
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted("This is a short scripted answer."))
                    .streamSink(chunks::add)
                    .build();
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
            var ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted("Streamed content for evidence request."))
                    .streamSink(chunks::add)
                    .build();
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

    // ═══════════════════════════════════════════════════════════════════════
    //  N1 — Transcript regression anchors
    //
    //  One test per transcript turn from test-output.txt (the playground run
    //  that exposed the trust layer gaps). Each test pins an exact user-prompt
    //  shape + an answer shape that today's trust gates MUST catch, so a
    //  future regression that weakens a gate (tightens a threshold, narrows
    //  a marker set, loosens a claim detector) fails with a clear turn
    //  reference.
    //
    //  Scope note: these are executor-seam (static-gate) tests, not harness
    //  scenarios. The harness seam (ToolCallLoop) bypasses AssistantTurnExecutor,
    //  so R2/R6/N2 cannot fire there. LlmClient is final with no scripted-mode
    //  seam, so driving execute() end-to-end with scripted LLM responses would
    //  require extracting an interface — a speculative abstraction the branch
    //  rules discourage. The static-gate pattern (already used by
    //  ClaimVsActionTests, GroundingRetryTests, StreamingGroundingTests) is
    //  the correct and lowest-risk anchor for transcript-level assertions.
    //
    //  Turn mapping:
    //    T1 (under-inspection)      → NO TEST YET. P4 gate does not exist.
    //    T2 (wiring fabrication)    → t2_wiringFabrication_triggersR6
    //    T3 (code fabrication)      → t3_codeFabrication_triggersR6
    //    T4 (selector fabrication)  → see GroundingRetryTests#firesOnTranscriptTurn4Shape
    //    T5 (false mutation claim)  → t5_falseMutationClaim_triggersR2
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("N1 — Transcript regressions (test-output.txt anchors)")
    class TranscriptRegressions {

        /** Turn 2 prompt, verbatim from test-output.txt. */
        private static final String TURN2_USER_PROMPT =
                "tell me how this site is wired together: which HTML file "
              + "loads which CSS and JS files, and whether there are any "
              + "broken or suspicious references.";

        /** Turn 3 prompt, verbatim from test-output.txt. */
        private static final String TURN3_USER_PROMPT =
                "Read the main HTML, CSS, and JS files and tell me 3 "
              + "concrete improvement opportunities. Use evidence from "
              + "the actual files, not generic website advice.";

        /**
         * Turn 2 fabrication shape: confident wiring narrative asserting
         * external link/script references that the workspace did not contain.
         * Must exceed UNGROUNDED_MIN_CHARS (600) so the R6 length gate passes
         * and the evidence-marker gate determines firing.
         */
        private String turn2WiringFabrication() {
            return "The site is wired together as three coordinated files loaded "
                 + "by index.html. The <head> section contains a <link rel=\"stylesheet\" "
                 + "href=\"style.css\"> element that pulls in the visual presentation, "
                 + "and the <body> closes with a <script src=\"script.js\"></script> "
                 + "reference that wires up the interactive behavior. The CSS selectors "
                 + "target the form's input ids and the result container, while the "
                 + "JavaScript listens for the submit event on the form element and "
                 + "writes the computed BMI back into the result div via "
                 + "document.getElementById. There are no obvious broken references — "
                 + "the href and src attributes match the sibling file names, and the "
                 + "class/id naming is consistent across all three files. As long as "
                 + "the files remain in the same directory the load order will resolve "
                 + "correctly and the calculator will function end to end. This is the "
                 + "conventional multi-file layout you would expect for a small "
                 + "single-page utility like this one.";
        }

        /**
         * Turn 3 fabrication shape: "three concrete improvements" that reference
         * code patterns the files do not actually contain. Again must exceed
         * UNGROUNDED_MIN_CHARS so only the evidence-marker gate determines firing.
         */
        private String turn3CodeFabrication() {
            return "Here are three concrete improvement opportunities based on "
                 + "the files. First, the form submission handler in script.js "
                 + "uses an inline onsubmit attribute which mixes behavior into "
                 + "markup; moving to addEventListener('submit', ...) would "
                 + "separate concerns and make the event chain easier to test. "
                 + "Second, the CSS in style.css relies on element selectors like "
                 + "'input' and 'div' that match too broadly — switching to "
                 + "scoped class selectors (e.g. .bmi-input, .bmi-result) would "
                 + "reduce the risk of style leakage if the page ever grows. "
                 + "Third, the BMI formula in the JavaScript assumes metric "
                 + "input without validating the number range, so extremely "
                 + "large or negative weights produce nonsensical results; "
                 + "adding a simple min/max guard before the division would "
                 + "harden the calculator against bad input. Together these "
                 + "changes keep the single-file simplicity while tightening "
                 + "structure, style scope, and input validation.";
        }

        // ── T2 ────────────────────────────────────────────────────────

        @Test
        @DisplayName("T2 — Turn-2 wiring fabrication shape triggers R6 retry")
        void t2_wiringFabrication_triggersR6() {
            var ctx = scriptedContext("grounded T2 retry answer");
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(TURN2_USER_PROMPT));

            String fabrication = turn2WiringFabrication();
            assertTrue(fabrication.length() >= AssistantTurnExecutor.UNGROUNDED_MIN_CHARS,
                    "fixture precondition: Turn-2 fabrication must be long enough "
                  + "to pass the R6 length gate (got " + fabrication.length() + ")");

            int before = messages.size();
            String out = AssistantTurnExecutor.groundingRetryIfNeeded(
                    fabrication, messages, ctx);

            assertEquals(before + 2, messages.size(),
                    "T2 regression: R6 must fire for the Turn-2 wiring prompt + "
                  + "fabrication shape (expect assistant + corrective user message "
                  + "appended)");
            assertNotEquals(fabrication, out,
                    "T2 regression: result must differ from the original fabrication");
        }

        // ── T3 ────────────────────────────────────────────────────────

        @Test
        @DisplayName("T3 — Turn-3 code-fabrication shape triggers R6 retry")
        void t3_codeFabrication_triggersR6() {
            var ctx = scriptedContext("grounded T3 retry answer");
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(TURN3_USER_PROMPT));

            String fabrication = turn3CodeFabrication();
            assertTrue(fabrication.length() >= AssistantTurnExecutor.UNGROUNDED_MIN_CHARS,
                    "fixture precondition: Turn-3 fabrication must be long enough "
                  + "to pass the R6 length gate (got " + fabrication.length() + ")");

            int before = messages.size();
            String out = AssistantTurnExecutor.groundingRetryIfNeeded(
                    fabrication, messages, ctx);

            assertEquals(before + 2, messages.size(),
                    "T3 regression: R6 must fire for the Turn-3 'evidence from the "
                  + "actual files' prompt + code-fabrication shape");
            assertNotEquals(fabrication, out,
                    "T3 regression: result must differ from the original fabrication");
        }

        // ── T4 ────────────────────────────────────────────────────────
        //
        // Turn 4 (selector-mismatch audit fabrication) is already pinned by
        // GroundingRetryTests#firesOnTranscriptTurn4Shape. No duplicate here —
        // see that test's transcript-anchored prompt for the T4 regression.

        // ── T5 ────────────────────────────────────────────────────────

        @Test
        @DisplayName("T5 — Turn-5 false mutation claim (verbatim) is annotated")
        void t5_falseMutationClaim_triggersR2() {
            // Verbatim Turn-5 final narration from test-output.txt: Talos
            // invoked only read_file, then claimed the edit was applied.
            String answer =
                    "I've updated the CTA button text to 'Let's Get Healthy'. "
                  + "The changes have been applied to the `index.html` file.";

            // Loop shape that matches the transcript: 1 tool call (read_file),
            // zero mutating successes (no write_file / edit_file).
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 1, 1,
                    List.of("talos.read_file"),
                    List.of(), 0, 0, false, /*mutatingSuccesses*/ 0, List.of(),
                    0, 0, 0, 0);

            String out = AssistantTurnExecutor.annotateIfFalseMutationClaim(
                    answer, loopResult);

            assertNotEquals(answer, out,
                    "T5 regression: verbatim Turn-5 phrasing must be annotated "
                  + "when no mutating tool succeeded");
            assertTrue(out.startsWith(AssistantTurnExecutor.FALSE_MUTATION_ANNOTATION),
                    "T5 regression: FALSE_MUTATION_ANNOTATION must be prepended so "
                  + "the user sees the correction before the fabricated claim");
            assertTrue(out.contains(answer),
                    "T5 regression: original answer text must be preserved verbatim "
                  + "inside the annotated output");
        }

        // ── T1 ────────────────────────────────────────────────────────

        /** Turn 1 prompt, verbatim from test-output.txt (line 22). */
        private static final String TURN1_USER_PROMPT =
                "Explore this workspace and identify the main HTML entry file, "
              + "the main stylesheet file, and the main JavaScript file. "
              + "Read the relevant files first, then summarize the site "
              + "structure with exact file names.";

        /**
         * Turn 1 under-inspection shape: the verbatim transcript turn read
         * only {@code index.html} (1 read) and then produced a confident
         * three-file summary. The fabricated answer is ≥ 500 chars to pass
         * {@code INSPECT_MIN_CHARS}.
         */
        private String turn1UnderInspectionAnswer() {
            return "The site is built from three coordinated files. "
                 + "index.html is the main entry point and references the "
                 + "stylesheet style.css in its <head> plus the JavaScript "
                 + "file script.js at the bottom of <body>. The CSS file "
                 + "defines the visual presentation for the BMI calculator "
                 + "form and result panel, while the JavaScript file wires "
                 + "up the form submit handler and computes the BMI from "
                 + "the input values before writing the result back into "
                 + "the DOM. The three files live side-by-side in the same "
                 + "directory and together produce a single-page BMI "
                 + "calculator that works end to end when index.html is "
                 + "opened in a browser.";
        }

        @Test
        @DisplayName("T1 — Turn-1 under-inspection (1 read, multi-file prompt) is annotated")
        void t1_underInspection_triggersN3() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(TURN1_USER_PROMPT));

            String answer = turn1UnderInspectionAnswer();
            assertTrue(answer.length() >= AssistantTurnExecutor.INSPECT_MIN_CHARS,
                    "fixture precondition: Turn-1 answer must be long enough "
                  + "to pass the N3 length gate (got " + answer.length() + ")");

            // Loop shape that matches the transcript: 1 read_file call,
            // zero mutating successes (no write_file / edit_file).
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 1, 1,
                    List.of("talos.read_file"),
                    List.of(), 0, 0, false, /*mutatingSuccesses*/ 0, List.of(),
                    0, 0, 0, 0);

            String out = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    answer, messages, loopResult);

            assertNotEquals(answer, out,
                    "T1 regression: verbatim Turn-1 prompt + 1-read "
                  + "loopResult must trigger N3 annotation");
            assertTrue(out.startsWith(AssistantTurnExecutor.UNDER_INSPECTION_ANNOTATION),
                    "T1 regression: UNDER_INSPECTION_ANNOTATION must be "
                  + "prepended so the user sees the correction before the "
                  + "under-inspected answer");
            assertTrue(out.contains(answer),
                    "T1 regression: original answer text must be preserved "
                  + "verbatim inside the annotated output");
        }
    }

    @Nested
    @DisplayName("Streaming no-tool truthfulness")
    class StreamingNoToolTruthfulnessTests {

        @Test
        @DisplayName("evidence-request fabrication is visibly annotated on streaming no-tool path")
        void streamingEvidenceFabricationIsAnnotated() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Check whether this website has mismatches between HTML classes/IDs and the selectors used in CSS or JavaScript. Do not change anything yet."));

            String fabricated = "Based on the workspace contents, index.html contains a CTA button, "
                    + "style.css defines `.cta-button`, and script.js wires it up with querySelector. "
                    + "There are no mismatches between the files. "
                    + "x".repeat(AssistantTurnExecutor.UNGROUNDED_MIN_CHARS);

            String out = AssistantTurnExecutor.enforceStreamingNoToolTruthfulness(fabricated, messages);

            assertTrue(out.startsWith(AssistantTurnExecutor.UNGROUNDED_ANNOTATION),
                    "streaming no-tool evidence fabrication must be visibly annotated");
            assertTrue(out.contains(fabricated));
        }

        @Test
        @DisplayName("explicit mutation no-tool narration is replaced with factual no-change notice")
        void streamingMutationNarrationIsReplaced() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("I think the html is completely wrong. Can you fix it?"));

            String fabricated = """
                    Sure! Here is the updated index.html.

                    ### Updated `index.html`
                    Summary of changes:
                    - updated index.html
                    - these changes should ensure the selectors now match
                    """;

            String out = AssistantTurnExecutor.enforceStreamingNoToolTruthfulness(fabricated, messages);

            assertEquals(AssistantTurnExecutor.STREAMING_NO_TOOL_MUTATION_REPLACEMENT, out,
                    "explicit mutation no-tool narration must not survive as final answer text");
        }

        @Test
        @DisplayName("narrow mutation narrative marker set does not flag descriptive analysis")
        void streamingMutationNarrativeMarkersStayNarrow() {
            String descriptive = "The label has been updated to read 'Weight', and the CSS class is documented below.";
            assertFalse(AssistantTurnExecutor.containsStreamingMutationNarrative(descriptive));
        }

        @Test
        @DisplayName("meta-question about tool use does not trigger explicit mutation replacement")
        void metaQuestionAboutEditToolRemainsReadOnly() {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Why didn't you call the edit tool?"));

            String answer = """
                    I should have called the edit tool once you explicitly requested a change.
                    """; 

            assertFalse(AssistantTurnExecutor.shouldReplaceStreamingNoToolMutationNarrative(answer, messages));
            assertEquals(answer, AssistantTurnExecutor.enforceStreamingNoToolTruthfulness(answer, messages));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  N3 — Inspect under-completion truth layer
    //
    //  Covers the annotate-first gate that fires when the user asked for
    //  multi-file inspection ("read the entry files", "all three", …) but
    //  the turn made ≤ 1 read-only tool call and emitted a substantive
    //  answer. Annotate-only by design (a retry would require re-running
    //  the tool loop). Sibling to ClaimVsActionTests / GroundingRetryTests.
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("N3 — Inspect under-completion")
    class InspectUnderCompletionTests {

        /** Long enough to pass {@link AssistantTurnExecutor#INSPECT_MIN_CHARS}. */
        private String longAnswer() {
            return "a".repeat(AssistantTurnExecutor.INSPECT_MIN_CHARS + 50);
        }

        private static List<ChatMessage> msgsWith(String userText) {
            var m = new ArrayList<ChatMessage>();
            m.add(ChatMessage.system("sys"));
            m.add(ChatMessage.user(userText));
            return m;
        }

        /** Loop result with {@code reads} read_file calls, zero mutating successes. */
        private static dev.talos.runtime.ToolCallLoop.LoopResult loopWithReads(int reads) {
            var names = new ArrayList<String>();
            for (int i = 0; i < reads; i++) names.add("talos.read_file");
            return new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", reads, reads, names, List.of(),
                    0, 0, false, /*mutatingSuccesses*/ 0, List.of(),
                    0, 0, 0, 0);
        }

        // ── Positive cases ────────────────────────────────────────────

        @Test
        @DisplayName("fires: long answer + one read + multi-file prompt marker")
        void fires_on_canonical_shape() {
            var messages = msgsWith("Read the relevant files first, then summarize.");
            String answer = longAnswer();
            String out = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    answer, messages, loopWithReads(1));
            assertTrue(out.startsWith(AssistantTurnExecutor.UNDER_INSPECTION_ANNOTATION));
            assertTrue(out.contains(answer));
        }

        @Test
        @DisplayName("fires: zero reads but tools were invoked (e.g. only list_dir-less path)")
        void fires_when_tools_invoked_but_no_reads() {
            // A turn that used a non-read tool (hypothetical) — still under-inspected.
            var messages = msgsWith("Read all the entry files and summarize.");
            String answer = longAnswer();
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 1, 1, List.of("talos.some_non_read_tool"),
                    List.of(), 0, 0, false, 0, List.of(),
                    0, 0, 0, 0);
            String out = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    answer, messages, loopResult);
            assertTrue(out.startsWith(AssistantTurnExecutor.UNDER_INSPECTION_ANNOTATION));
        }

        // ── Negative cases ────────────────────────────────────────────

        @Test
        @DisplayName("does NOT fire: two reads (inspection complete)")
        void does_not_fire_with_two_reads() {
            var messages = msgsWith("Read the relevant files first.");
            String out = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    longAnswer(), messages, loopWithReads(2));
            assertEquals(longAnswer(), out);
        }

        @Test
        @DisplayName("does NOT fire: zero tools invoked (R6 / N2 territory)")
        void does_not_fire_when_zero_tools() {
            var messages = msgsWith("Read the entry files first.");
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 0, 0, List.of(), List.of(), 0, 0, false, 0, List.of(),
                    0, 0, 0, 0);
            String out = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    longAnswer(), messages, loopResult);
            assertEquals(longAnswer(), out);
        }

        @Test
        @DisplayName("does NOT fire: mutating tool succeeded (did the work)")
        void does_not_fire_when_mutating_success() {
            var messages = msgsWith("Read the entry files then fix style.css.");
            var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 2, 2,
                    List.of("talos.read_file", "talos.edit_file"),
                    List.of(), 0, 0, false, /*mutatingSuccesses*/ 1, List.of(),
                    0, 0, 0, 0);
            String out = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    longAnswer(), messages, loopResult);
            assertEquals(longAnswer(), out,
                    "mutating success means the turn did real work — signal is noise");
        }

        @Test
        @DisplayName("does NOT fire: answer below INSPECT_MIN_CHARS")
        void does_not_fire_when_answer_short() {
            var messages = msgsWith("Read the relevant files first.");
            String shortAnswer = "a".repeat(AssistantTurnExecutor.INSPECT_MIN_CHARS - 1);
            String out = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    shortAnswer, messages, loopWithReads(1));
            assertEquals(shortAnswer, out);
        }

        @Test
        @DisplayName("does NOT fire: prompt has no inspect-first marker")
        void does_not_fire_without_inspect_marker() {
            var messages = msgsWith("What is the BMI formula?");
            String out = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    longAnswer(), messages, loopWithReads(1));
            assertEquals(longAnswer(), out);
        }

        @Test
        @DisplayName("does NOT fire: null or blank answer")
        void does_not_fire_on_null_or_blank_answer() {
            var messages = msgsWith("Read the entry files first.");
            assertNull(AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    null, messages, loopWithReads(1)));
            assertEquals("   ", AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    "   ", messages, loopWithReads(1)));
        }

        @Test
        @DisplayName("does NOT fire: null loopResult")
        void does_not_fire_on_null_loop_result() {
            var messages = msgsWith("Read the entry files first.");
            String out = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                    longAnswer(), messages, null);
            assertEquals(longAnswer(), out);
        }

        // ── Predicate and helper invariants ───────────────────────────

        @Test
        @DisplayName("looksLikeInspectFirstRequest: transcript markers hit, generic prompts miss")
        void inspect_marker_set_discriminates() {
            assertTrue(AssistantTurnExecutor.looksLikeInspectFirstRequest(
                    "Read the relevant files first, then answer."));
            assertTrue(AssistantTurnExecutor.looksLikeInspectFirstRequest(
                    "Identify the main HTML entry file."));
            assertTrue(AssistantTurnExecutor.looksLikeInspectFirstRequest(
                    "All three components should be inspected."));
            assertTrue(AssistantTurnExecutor.looksLikeInspectFirstRequest(
                    "Start by reading the main files."));
            assertFalse(AssistantTurnExecutor.looksLikeInspectFirstRequest(
                    "What is the capital of France?"));
            assertFalse(AssistantTurnExecutor.looksLikeInspectFirstRequest(null));
            assertFalse(AssistantTurnExecutor.looksLikeInspectFirstRequest(""));
        }

        @Test
        @DisplayName("readOnlyToolCount: counts read_file / list_dir / grep, ignores others, strips talos.")
        void read_only_tool_count_is_correct() {
            var mixed = new dev.talos.runtime.ToolCallLoop.LoopResult(
                    "unused", 4, 4,
                    List.of("talos.read_file", "talos.edit_file",
                            "list_dir", "talos.grep", "talos.write_file"),
                    List.of(), 0, 0, false, 1, List.of(),
                    0, 0, 0, 0);
            assertEquals(3, AssistantTurnExecutor.readOnlyToolCount(mixed),
                    "should count read_file + list_dir + grep, not edit_file / write_file");
            assertEquals(0, AssistantTurnExecutor.readOnlyToolCount(null));
        }
    }

    @Nested
    @DisplayName("Selector mismatch grounding")
    class SelectorMismatchGroundingTests {

        @Test
        @DisplayName("selector mismatch request is overridden by deterministic workspace analysis")
        void selectorMismatchAnswerIsGroundedFromWorkspace() throws Exception {
            Path ws = Files.createTempDirectory("talos-selector-grounding-");
            try {
                Files.writeString(ws.resolve("index.html"), """
                        <!DOCTYPE html>
                        <html>
                          <body class="synthwave-theme">
                            <section id="hero">
                              <div class="hero-content"></div>
                            </section>
                          </body>
                        </html>
                        """);
                Files.writeString(ws.resolve("style.css"), """
                        body.synthwave-theme {}
                        #hero {}
                        .hero-content {}
                        .cta-button {}
                        """);
                Files.writeString(ws.resolve("script.js"), """
                        document.querySelector('.cta-button');
                        """);

                var messages = new ArrayList<ChatMessage>();
                messages.add(ChatMessage.system("sys"));
                messages.add(ChatMessage.user("Check whether this website has mismatches between HTML classes/IDs and the selectors used in CSS or JavaScript. Do not change anything yet."));

                var loopResult = new dev.talos.runtime.ToolCallLoop.LoopResult(
                        "unused", 4, 4,
                        List.of("talos.list_dir", "talos.read_file", "talos.read_file", "talos.read_file"),
                        List.of(), 0, 0, false, 0, List.of("index.html", "style.css", "script.js"),
                        0, 0, 0, 0);

                String bogus = "There are no mismatches. The class `cta-button` is present in HTML and JavaScript.";
                String out = AssistantTurnExecutor.overrideSelectorMismatchAnalysisIfNeeded(
                        bogus, messages, loopResult, ws);

                assertNotEquals(bogus, out);
                assertTrue(out.contains("Mismatches found:"));
                assertTrue(out.contains("`.cta-button`"));
                assertFalse(out.contains("present in HTML and JavaScript"));
                assertFalse(out.contains("#ff4500"));
                assertFalse(out.contains("#ffffff"));
            } finally {
                try (var walk = Files.walk(ws)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }
    }
}





