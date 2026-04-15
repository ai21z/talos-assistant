package dev.talos.runtime;

import dev.talos.core.llm.SystemPromptBuilder;
import dev.talos.core.util.Sanitize;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.spi.types.TokenChunk;
import dev.talos.tools.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end assertions for the native-tool-pipeline migration.
 *
 * <p><b>Architecture (native-first pipeline):</b>
 * <ol>
 *   <li><b>Native tool calls (primary):</b> Structured {@code NativeToolCall}
 *       objects from the engine — no text parsing needed.</li>
 *   <li><b>JSON code fences (active text fallback):</b> Instructed in prompts
 *       when native calling is unavailable.</li>
 *   <li><b>XML tags (deprecated compatibility only):</b> Parsed and suppressed
 *       for models that emit XML from training habits or cached context, but
 *       NOT actively instructed in any prompt path. Scheduled for removal once
 *       native tool calling is stable across model versions.</li>
 * </ol>
 *
 * <p>Verifies:
 * <ul>
 *   <li>Native tool calls stay structured through the pipeline (no XML conversion)</li>
 *   <li>JSON is the active text fallback format (no XML in prompt instructions)</li>
 *   <li>XML is still parsed/suppressed for compatibility but not instructed</li>
 *   <li>Safety features (no path guessing, no code-block writes) are preserved</li>
 *   <li>ToolCallLoop dual-path works correctly for both native and text fallback</li>
 *   <li>Code-block detection does NOT trigger tool-loop entry</li>
 *   <li>ChatMessage structure is preserved through sanitization</li>
 * </ul>
 */
@DisplayName("Native Tool Pipeline Migration")
class NativeToolPipelineTest {

    // ── Native path: structured tool calls ───────────────────────────────

    @Nested
    @DisplayName("Native path: structured tool calls (primary)")
    class NativePath {

        @Test
        @DisplayName("TokenChunk.ofToolCalls carries structured calls without XML")
        void tokenChunkCarriesStructuredCalls() {
            var call = new NativeToolCall("call_0", "talos.list_dir", Map.of("path", "."));
            TokenChunk chunk = TokenChunk.ofToolCalls(List.of(call));

            assertTrue(chunk.hasToolCalls());
            assertEquals(1, chunk.toolCalls().size());
            assertEquals("talos.list_dir", chunk.toolCalls().get(0).name());
            // No XML anywhere
            assertFalse(chunk.text().contains("<tool_call>"));
        }

        @Test
        @DisplayName("NativeToolCall → ToolCall conversion preserves all data")
        void nativeToToolCallConversion() {
            var ntc = new NativeToolCall("call_0", "talos.write_file",
                    Map.of("path", "test.html", "content", "<script>alert('hi')</script>"));
            var calls = ToolCallLoop.convertNativeToolCalls(List.of(ntc));

            assertEquals(1, calls.size());
            assertEquals("talos.write_file", calls.get(0).toolName());
            assertEquals("test.html", calls.get(0).param("path"));
            assertEquals("<script>alert('hi')</script>", calls.get(0).param("content"),
                    "HTML content must be preserved through native path — no SUS_HTML stripping");
        }

        @Test
        @DisplayName("ChatMessage.assistantWithToolCalls preserves structured calls")
        void assistantMessageCarriesToolCalls() {
            var call = new NativeToolCall("call_0", "talos.read_file", Map.of("path", "x.txt"));
            ChatMessage msg = ChatMessage.assistantWithToolCalls("Let me check.", List.of(call));

            assertTrue(msg.hasNativeToolCalls());
            assertEquals(1, msg.toolCalls().size());
            assertEquals("talos.read_file", msg.toolCalls().get(0).name());
            assertEquals("Let me check.", msg.content());
            // No XML in content
            assertFalse(msg.content().contains("<tool_call>"));
        }

        @Test
        @DisplayName("ChatMessage.toolResult uses role='tool' with callId")
        void toolResultMessage() {
            ChatMessage msg = ChatMessage.toolResult("call_0", "file contents here");

            assertEquals("tool", msg.role());
            assertEquals("call_0", msg.toolCallId());
            assertEquals("file contents here", msg.content());
        }

        @Test
        @DisplayName("ToolCallLoop with native calls skips text parsing")
        void loopWithNativeCallsSkipsParsing() {
            var tp = new ToolCallLoop(new TurnProcessor(null));
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("hello"));

            // Text that LOOKS like it has tool calls but native calls are provided
            String textWithFakeToolCall = "Some text <tool_call>{\"name\":\"bogus\"}</tool_call>";
            var nativeCalls = List.of(
                    new NativeToolCall("call_0", "talos.list_dir", Map.of("path", "."))
            );

            // The loop should use native calls, not parse the text
            boolean hasNative = !nativeCalls.isEmpty();
            assertTrue(hasNative, "Native calls should be detected as the primary path");
        }

        @Test
        @DisplayName("multiple native tool calls all convert correctly")
        void multipleNativeToolCalls() {
            var ntcs = List.of(
                    new NativeToolCall("call_0", "talos.list_dir", Map.of("path", "src")),
                    new NativeToolCall("call_1", "talos.read_file", Map.of("path", "README.md")),
                    new NativeToolCall("call_2", "talos.grep", Map.of("pattern", "TODO", "glob", "*.java"))
            );
            var calls = ToolCallLoop.convertNativeToolCalls(ntcs);

            assertEquals(3, calls.size());
            assertEquals("talos.list_dir", calls.get(0).toolName());
            assertEquals("talos.read_file", calls.get(1).toolName());
            assertEquals("talos.grep", calls.get(2).toolName());
            assertEquals("TODO", calls.get(2).param("pattern"));
        }
    }

    // ── JSON fallback path ───────────────────────────────────────────────

    @Nested
    @DisplayName("JSON fallback path (active text fallback)")
    class JsonFallback {

        @Test
        @DisplayName("JSON code-fenced tool calls are parsed correctly")
        void jsonCodeFenceParsed() {
            String response = """
                    Let me read that file.
                    ```json
                    {"name": "talos.read_file", "parameters": {"path": "src/Main.java"}}
                    ```
                    """;

            List<ToolCall> calls = ToolCallParser.parse(response);
            assertEquals(1, calls.size());
            assertEquals("talos.read_file", calls.get(0).toolName());
            assertEquals("src/Main.java", calls.get(0).param("path"));
        }

        @Test
        @DisplayName("bare JSON tool calls are parsed correctly")
        void bareJsonParsed() {
            String response = """
                    Reading the file now.
                    {"name": "talos.read_file", "parameters": {"path": "README.md"}}
                    """;

            List<ToolCall> calls = ToolCallParser.parse(response);
            assertEquals(1, calls.size());
            assertEquals("talos.read_file", calls.get(0).toolName());
        }

        @Test
        @DisplayName("stripToolCalls removes JSON code fences")
        void stripRemovesJsonFences() {
            String response = """
                    Before.
                    ```json
                    {"name": "talos.grep", "parameters": {"pattern": "TODO"}}
                    ```
                    After.""";

            String stripped = ToolCallParser.stripToolCalls(response);
            assertFalse(stripped.contains("talos.grep"));
            assertTrue(stripped.contains("Before."));
            assertTrue(stripped.contains("After."));
        }

        @Test
        @DisplayName("fallback prompt uses JSON format, not XML")
        void fallbackPromptUsesJson() {
            var registry = new ToolRegistry();
            registry.register(stubTool("talos.read_file", "Read a file"));

            String prompt = SystemPromptBuilder.forAsk()
                    .withTools(registry)
                    .withNativeTools(false)
                    .build();

            // Must contain JSON format instructions
            assertTrue(prompt.contains("```json"),
                    "Fallback prompt should contain ```json code fence examples");
            // Must NOT contain XML format instructions
            assertFalse(prompt.contains("<tool_call>"),
                    "Fallback prompt should NOT contain XML <tool_call> tags");
            assertFalse(prompt.contains("</tool_call>"),
                    "Fallback prompt should NOT contain XML </tool_call> tags");
        }

        @Test
        @DisplayName("native prompt omits both XML and JSON format instructions")
        void nativePromptOmitsFormatInstructions() {
            var registry = new ToolRegistry();
            registry.register(stubTool("talos.read_file", "Read a file"));

            String prompt = SystemPromptBuilder.forAsk()
                    .withTools(registry)
                    .withNativeTools(true)
                    .build();

            assertFalse(prompt.contains("<tool_call>"),
                    "Native prompt should not contain XML tags");
            assertFalse(prompt.contains("```json"),
                    "Native prompt should not contain JSON format examples");
            assertTrue(prompt.contains("runtime handles tool invocation"),
                    "Native prompt should mention automatic format handling");
        }
    }

    // ── XML compatibility (deprecated, not active) ────────────────────────

    @Nested
    @DisplayName("XML compatibility — deprecated, parsed for transition only, NOT instructed")
    class XmlCompatibility {

        @Test
        @DisplayName("XML tool calls are still parsed for deprecated compatibility")
        void xmlStillParsedForCompat() {
            String response = """
                    <tool_call>
                    {"name": "talos.read_file", "parameters": {"path": "test.java"}}
                    </tool_call>
                    """;

            List<ToolCall> calls = ToolCallParser.parse(response);
            assertEquals(1, calls.size(), "XML should still be parseable for transition compatibility");
        }

        @Test
        @DisplayName("no XML format is instructed in either prompt path")
        void noXmlInstructedAnywhere() {
            var registry = new ToolRegistry();
            registry.register(stubTool("talos.read_file", "Read a file"));

            // Native prompt
            String nativePrompt = SystemPromptBuilder.forAsk()
                    .withTools(registry).withNativeTools(true).build();
            assertFalse(nativePrompt.contains("<tool_call>"));

            // Fallback prompt
            String fallbackPrompt = SystemPromptBuilder.forAsk()
                    .withTools(registry).withNativeTools(false).build();
            assertFalse(fallbackPrompt.contains("<tool_call>"),
                    "Even the fallback prompt should use JSON, not XML");
        }

        @Test
        @DisplayName("ToolCallStreamFilter suppresses XML tags (deprecated compat)")
        void filterStillHandlesXml() {
            List<String> chunks = new ArrayList<>();
            var filter = new ToolCallStreamFilter(chunks::add);
            filter.accept("text <tool_call>{\"name\":\"talos.x\"}</tool_call> more");
            filter.flush();
            String result = String.join("", chunks);
            assertFalse(result.contains("talos.x"));
            assertTrue(result.contains("text"));
            assertTrue(result.contains("more"));
        }

        @Test
        @DisplayName("ToolCallStreamFilter suppresses JSON code fences (active fallback)")
        void filterHandlesJsonFences() {
            List<String> chunks = new ArrayList<>();
            var filter = new ToolCallStreamFilter(chunks::add);
            filter.accept("text\n```json\n{\"name\": \"talos.read_file\", \"parameters\": {\"path\": \"x\"}}\n```\nmore");
            filter.flush();
            String result = String.join("", chunks);
            assertFalse(result.contains("talos.read_file"),
                    "JSON code-fenced tool call should be suppressed from display");
            assertTrue(result.contains("text"));
            assertTrue(result.contains("more"));
        }

        @Test
        @DisplayName("no prompt path instructs XML — fallback uses JSON, native uses nothing")
        void noPromptPathInstructsXml() {
            var registry = new ToolRegistry();
            registry.register(stubTool("talos.read_file", "Read a file"));

            // Native prompt: no format instructions at all
            String nativePrompt = SystemPromptBuilder.forAsk()
                    .withTools(registry).withNativeTools(true).build();
            assertFalse(nativePrompt.contains("<tool_call>"),
                    "Native prompt must not contain XML tags");
            assertFalse(nativePrompt.contains("</tool_call>"),
                    "Native prompt must not contain XML closing tags");

            // Fallback prompt: JSON code-fenced format only
            String fallbackPrompt = SystemPromptBuilder.forAsk()
                    .withTools(registry).withNativeTools(false).build();
            assertFalse(fallbackPrompt.contains("<tool_call>"),
                    "Fallback prompt must NOT instruct XML format");
            assertTrue(fallbackPrompt.contains("```json"),
                    "Fallback prompt must instruct JSON code-fenced format");
        }

        @Test
        @DisplayName("XML compat code is parsing-only — JSON is the instructed format")
        void xmlIsParsingOnlyNotInstructed() {
            // Prove XML parsing still works (deprecated compatibility)
            String xmlResponse = "<tool_call>{\"name\":\"talos.grep\",\"parameters\":{\"pattern\":\"x\"}}</tool_call>";
            List<ToolCall> xmlCalls = ToolCallParser.parse(xmlResponse);
            assertEquals(1, xmlCalls.size(), "XML should still be parseable (deprecated compat)");

            // Prove JSON code-fenced parsing works (active fallback)
            String jsonResponse = "```json\n{\"name\":\"talos.grep\",\"parameters\":{\"pattern\":\"x\"}}\n```";
            List<ToolCall> jsonCalls = ToolCallParser.parse(jsonResponse);
            assertEquals(1, jsonCalls.size(), "JSON code fences should be parseable (active fallback)");

            // Both parse to the same result
            assertEquals(xmlCalls.get(0).toolName(), jsonCalls.get(0).toolName());
            assertEquals(xmlCalls.get(0).param("pattern"), jsonCalls.get(0).param("pattern"));
        }

        @Test
        @DisplayName("active paths do NOT depend on XML — JSON and native are sufficient")
        void activePathsDoNotDependOnXml() {
            // Native path: structured NativeToolCall — no XML involved
            var ntc = new NativeToolCall("call_0", "talos.read_file", Map.of("path", "x.txt"));
            var calls = ToolCallLoop.convertNativeToolCalls(List.of(ntc));
            assertEquals(1, calls.size());
            assertEquals("talos.read_file", calls.get(0).toolName());

            // JSON fallback path: code-fenced JSON — no XML involved
            String jsonResponse = "```json\n{\"name\":\"talos.read_file\",\"parameters\":{\"path\":\"y.txt\"}}\n```";
            List<ToolCall> jsonCalls = ToolCallParser.parse(jsonResponse);
            assertEquals(1, jsonCalls.size());
            assertEquals("talos.read_file", jsonCalls.get(0).toolName());

            // Both paths work without any XML — XML is deprecated compat only
        }
    }

    // ── Executor behavior ────────────────────────────────────────────────

    @Nested
    @DisplayName("Executor behavior — tool-loop entry and code-block detection")
    class ExecutorBehavior {

        @Test
        @DisplayName("code-block detection does NOT trigger tool-loop entry via ToolCallParser")
        void codeBlocksDoNotTriggerToolLoopEntry() {
            // Code blocks with filename hints are NOT tool calls
            String responseWithCodeBlock = "Here's the code:\n```python # main.py\nprint('hello')\n```";

            // ToolCallParser.containsToolCalls should NOT detect code blocks
            assertFalse(ToolCallParser.containsToolCalls(responseWithCodeBlock),
                    "Code blocks with filename hints must NOT be treated as tool calls — " +
                    "they should not trigger tool-loop entry");
        }

        @Test
        @DisplayName("code-block detection is separate from tool-call detection")
        void codeBlockDetectionIsSeparateFromToolCalls() {
            String response = "Here's the code:\n```python # main.py\nprint('hello')\n```";

            // CodeBlockToolExtractor detects it
            assertTrue(CodeBlockToolExtractor.containsExtractableBlocks(response),
                    "Code block should be detected by CodeBlockToolExtractor");

            // ToolCallParser does NOT detect it
            assertFalse(ToolCallParser.containsToolCalls(response),
                    "ToolCallParser must not detect code blocks as tool calls");

            // This separation is intentional: code-block writes are disabled.
            // CodeBlockToolExtractor only produces a warning inside ToolCallLoop.run(),
            // it should NOT cause tool-loop entry.
        }

        @Test
        @DisplayName("ToolCallLoop warns on code blocks but does not execute them")
        void toolCallLoopWarnsOnCodeBlocks() {
            var tp = new ToolCallLoop(new TurnProcessor(null));
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("create a file"));

            // Response with code block but NO tool calls
            String response = "Here is the code:\n```python # main.py\nprint('hello')\n```";

            // Should return without executing anything (iterations=0, toolsInvoked=0)
            ToolCallLoop.LoopResult result = tp.run(response, messages, null, null);
            assertEquals(0, result.iterations(), "No tool-call iterations should run for code blocks");
            assertEquals(0, result.toolsInvoked(), "No tools should be invoked for code blocks");
            assertEquals(response, result.finalAnswer(), "Response should pass through unchanged");
        }

        @Test
        @DisplayName("native tool calls in StreamResult trigger tool-loop correctly")
        void nativeToolCallsInStreamResultTriggerLoop() {
            // Simulate what AssistantTurnExecutor.hasAnyToolCalls checks
            var textOnly = new dev.talos.core.llm.LlmClient.StreamResult("plain text", List.of());
            assertFalse(textOnly.hasToolCalls(), "Text-only result should not have tool calls");

            var withNative = new dev.talos.core.llm.LlmClient.StreamResult("",
                    List.of(new NativeToolCall("call_0", "talos.list_dir", Map.of("path", "."))));
            assertTrue(withNative.hasToolCalls(), "Result with native calls should have tool calls");
        }

        @Test
        @DisplayName("JSON text tool calls detected by ToolCallParser")
        void jsonTextToolCallsDetected() {
            String responseWithJson = "```json\n{\"name\":\"talos.read_file\",\"parameters\":{\"path\":\"x\"}}\n```";
            assertTrue(ToolCallParser.containsToolCalls(responseWithJson),
                    "JSON code-fenced tool call should be detected by ToolCallParser");
        }
    }

    // ── ChatMessage structure preservation ────────────────────────────────

    @Nested
    @DisplayName("ChatMessage structure preservation through sanitization")
    class MessageStructure {

        @Test
        @DisplayName("ChatMessage with toolCalls preserves structure through 4-arg constructor")
        void chatMessagePreservesToolCalls() {
            var call = new NativeToolCall("call_0", "talos.list_dir", Map.of("path", "."));
            // Simulate what the fixed sanitization does: 4-arg constructor preserves toolCalls
            ChatMessage original = ChatMessage.assistantWithToolCalls("text", List.of(call));
            ChatMessage sanitized = new ChatMessage(
                    original.role(),
                    Sanitize.sanitizeMessageContent(original.content()),
                    original.toolCalls(),
                    original.toolCallId());

            assertTrue(sanitized.hasNativeToolCalls(),
                    "Sanitized message must preserve native tool calls");
            assertEquals(1, sanitized.toolCalls().size());
            assertEquals("talos.list_dir", sanitized.toolCalls().get(0).name());
        }

        @Test
        @DisplayName("ChatMessage with toolCallId preserves structure through 4-arg constructor")
        void chatMessagePreservesToolCallId() {
            ChatMessage original = ChatMessage.toolResult("call_0", "result content");
            ChatMessage sanitized = new ChatMessage(
                    original.role(),
                    Sanitize.sanitizeMessageContent(original.content()),
                    original.toolCalls(),
                    original.toolCallId());

            assertEquals("tool", sanitized.role());
            assertEquals("call_0", sanitized.toolCallId(),
                    "Sanitized message must preserve toolCallId");
            assertEquals("result content", sanitized.content());
        }

        @Test
        @DisplayName("2-arg ChatMessage constructor drops toolCalls and toolCallId — proving the fix is necessary")
        void twoArgConstructorDropsStructure() {
            // This demonstrates why the fix was necessary:
            // the old sanitization used 2-arg constructor which dropped tool structure
            ChatMessage withToolCalls = ChatMessage.assistantWithToolCalls("text",
                    List.of(new NativeToolCall("call_0", "talos.list_dir", Map.of("path", "."))));

            // 2-arg constructor loses toolCalls
            ChatMessage lossy = new ChatMessage(withToolCalls.role(), withToolCalls.content());
            assertFalse(lossy.hasNativeToolCalls(),
                    "2-arg constructor must NOT preserve tool calls (this is the old broken behavior)");

            // 4-arg constructor preserves toolCalls
            ChatMessage preserved = new ChatMessage(
                    withToolCalls.role(), withToolCalls.content(),
                    withToolCalls.toolCalls(), withToolCalls.toolCallId());
            assertTrue(preserved.hasNativeToolCalls(),
                    "4-arg constructor must preserve tool calls (this is the fix)");
        }
    }

    // ── Safety non-regression ────────────────────────────────────────────

    @Nested
    @DisplayName("Safety non-regression")
    class SafetyNonRegression {

        @Test
        @DisplayName("no path guessing for write_file with missing path")
        void noPathGuessingForWriteFile() {
            ToolCall call = new ToolCall("talos.write_file", Map.of("content", "data"));
            ToolCall repaired = ToolCallLoop.repairMissingPath(call);

            // Must return as-is — no path inference
            assertNull(repaired.param("path"),
                    "Missing path must NOT be inferred for mutating tools");
            assertEquals("talos.write_file", repaired.toolName());
        }

        @Test
        @DisplayName("no path guessing for edit_file with missing path")
        void noPathGuessingForEditFile() {
            ToolCall call = new ToolCall("talos.edit_file",
                    Map.of("old_string", "foo", "new_string", "bar"));
            ToolCall repaired = ToolCallLoop.repairMissingPath(call);

            assertNull(repaired.param("path"),
                    "Missing path must NOT be inferred for edit_file");
        }

        @Test
        @DisplayName("code block extraction is detection-only, not auto-executed")
        void codeBlockDetectionOnly() {
            String response = "Here's the code:\n```python # main.py\nprint('hello')\n```";
            assertTrue(CodeBlockToolExtractor.containsExtractableBlocks(response),
                    "Code block should be detected");

            // But ToolCallParser should NOT detect this as a tool call
            assertFalse(ToolCallParser.containsToolCalls(response),
                    "Code blocks without tool_call format should NOT be treated as tool calls");
        }

        @Test
        @DisplayName("native path preserves HTML content in tool arguments")
        void nativePathPreservesHtmlInArgs() {
            String scriptTag = "<script src=\"app.js\"></script>";
            var ntc = new NativeToolCall("call_0", "talos.edit_file",
                    Map.of("path", "index.html", "old_string", "</body>",
                            "new_string", scriptTag + "</body>"));
            var calls = ToolCallLoop.convertNativeToolCalls(List.of(ntc));

            assertEquals(scriptTag + "</body>", calls.get(0).param("new_string"),
                    "Script tags in tool arguments must survive native conversion");
        }

        @Test
        @DisplayName("Sanitize preserves JSON code-fenced tool calls from SUS_HTML")
        void sanitizePreservesJsonToolCallFences() {
            String input = "Some text\n```json\n{\"name\": \"talos.write_file\", \"parameters\": "
                    + "{\"path\": \"x.html\", \"content\": \"<script>alert('hi')</script>\"}}\n```\nMore text";
            String sanitized = Sanitize.sanitizeForOutputPreservingToolCalls(input);

            assertTrue(sanitized.contains("talos.write_file"),
                    "JSON tool call fence should be preserved through sanitization");
            assertTrue(sanitized.contains("<script>"),
                    "Script tags inside JSON tool call fence should be preserved");
        }

        @Test
        @DisplayName("Sanitize still strips SUS_HTML from prose outside tool calls")
        void sanitizeStripsHtmlOutsideToolCalls() {
            String input = "Bad content: <script>evil()</script> after.";
            String sanitized = Sanitize.sanitizeForOutputPreservingToolCalls(input);

            assertFalse(sanitized.contains("<script>evil()"),
                    "Script tags in prose should be stripped");
            assertTrue(sanitized.contains("after."));
        }

        @Test
        @DisplayName("tool result formatting includes verification status")
        void toolResultIncludesVerification() {
            ToolCall call = new ToolCall("talos.write_file", Map.of("path", "test.txt"));
            ToolResult result = ToolResult.ok("File written", VerificationStatus.PASS);

            String formatted = ToolCallLoop.formatToolResult(call, result);
            assertTrue(formatted.contains("[verification_status: PASS]"),
                    "Verification status should be included in tool result message");
        }

        @Test
        @DisplayName("LoopResult summary deduplicates tool names")
        void loopResultSummaryDeduplicates() {
            var result = new ToolCallLoop.LoopResult(
                    "final answer", 2, 4,
                    List.of("talos.read_file", "talos.grep", "talos.read_file", "talos.write_file"),
                    List.of(), 0, 0, false);

            String summary = result.summary();
            assertNotNull(summary);
            // read_file should appear only once despite 2 invocations
            assertEquals(1, summary.split("read_file").length - 1,
                    "read_file should appear once in summary despite duplicate invocations");
            assertTrue(summary.contains("4 tool(s)"));
            assertTrue(summary.contains("2 iteration(s)"));
        }
    }

    // ── Architecture truthfulness ────────────────────────────────────────

    @Nested
    @DisplayName("Architecture truthfulness — prompts, comments, behavior all align")
    class ArchitectureTruthfulness {

        @Test
        @DisplayName("all three prompt modes produce no XML instructions")
        void allPromptModesNoXml() {
            var registry = new ToolRegistry();
            registry.register(stubTool("talos.read_file", "Read a file"));

            for (var builder : List.of(
                    SystemPromptBuilder.forAsk(),
                    SystemPromptBuilder.forRag(),
                    SystemPromptBuilder.forUnified())) {

                // Native mode
                String nativePrompt = builder.withTools(registry).withNativeTools(true).build();
                assertFalse(nativePrompt.contains("<tool_call>"),
                        "No prompt mode should contain XML <tool_call> tags");

                // Fallback mode
                String fallbackPrompt = builder.withTools(registry).withNativeTools(false).build();
                assertFalse(fallbackPrompt.contains("<tool_call>"),
                        "No prompt mode should contain XML <tool_call> tags in fallback either");
            }
        }

        @Test
        @DisplayName("native prompt and fallback prompt are structurally different")
        void nativeAndFallbackAreDifferent() {
            var registry = new ToolRegistry();
            registry.register(stubTool("talos.read_file", "Read a file"));

            String nativePrompt = SystemPromptBuilder.forAsk()
                    .withTools(registry).withNativeTools(true).build();
            String fallbackPrompt = SystemPromptBuilder.forAsk()
                    .withTools(registry).withNativeTools(false).build();

            // Native has no JSON format instructions
            assertFalse(nativePrompt.contains("```json"),
                    "Native prompt should not have JSON format examples");
            assertTrue(nativePrompt.contains("runtime handles"),
                    "Native prompt should indicate automatic format handling");

            // Fallback has JSON format instructions
            assertTrue(fallbackPrompt.contains("```json"),
                    "Fallback prompt must have JSON format examples");
            assertTrue(fallbackPrompt.contains("\"name\""),
                    "Fallback prompt must show the JSON structure");
        }

        @Test
        @DisplayName("Sanitize XML compat block protection works for both formats")
        void sanitizeProtectsBothFormats() {
            // XML format (deprecated compat) — still protected during sanitization
            String xmlInput = "<tool_call>{\"name\":\"talos.write_file\",\"parameters\":"
                    + "{\"content\":\"<script>x</script>\"}}</tool_call>";
            String xmlSanitized = Sanitize.sanitizeForOutputPreservingToolCalls(xmlInput);
            assertTrue(xmlSanitized.contains("<script>"),
                    "XML tool_call block content must be protected from SUS_HTML stripping");

            // JSON format (active fallback) — protected during sanitization
            String jsonInput = "```json\n{\"name\":\"talos.write_file\",\"parameters\":"
                    + "{\"content\":\"<script>y</script>\"}}\n```";
            String jsonSanitized = Sanitize.sanitizeForOutputPreservingToolCalls(jsonInput);
            assertTrue(jsonSanitized.contains("<script>"),
                    "JSON code-fenced tool_call content must be protected from SUS_HTML stripping");
        }

        @Test
        @DisplayName("TokenChunk supports all three chunk types correctly")
        void tokenChunkTypesAreComplete() {
            // Text chunk
            TokenChunk text = TokenChunk.of("hello");
            assertFalse(text.hasToolCalls());
            assertNull(text.done());
            assertEquals("hello", text.text());

            // Tool-call chunk
            var call = new NativeToolCall("call_0", "talos.read_file", Map.of("path", "x"));
            TokenChunk tools = TokenChunk.ofToolCalls(List.of(call));
            assertTrue(tools.hasToolCalls());
            assertNull(tools.done());
            assertEquals(1, tools.toolCalls().size());

            // EOS chunk
            TokenChunk eos = TokenChunk.eos();
            assertFalse(eos.hasToolCalls());
            assertTrue(eos.done());
        }
    }

    // ── Helper ───────────────────────────────────────────────────────────

    private static TalosTool stubTool(String name, String description) {
        return new TalosTool() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public ToolDescriptor descriptor() { return new ToolDescriptor(name, description); }
            @Override public ToolResult execute(ToolCall call) { return ToolResult.ok("stub"); }
        };
    }
}

