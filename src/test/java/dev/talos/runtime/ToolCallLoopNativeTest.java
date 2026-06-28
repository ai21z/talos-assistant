package dev.talos.runtime;

import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the native tool-call path in {@link ToolCallLoop}.
 *
 * <p>Focuses on the {@code NativeToolCall → ToolCall} conversion and the
 * new {@code run(text, nativeCalls, messages, workspace, ctx)} overload.
 */
class ToolCallLoopNativeTest {

    @Nested
    class ConvertNativeToolCalls {

        @Test
        void singleCall_convertsCorrectly() {
            var ntc = new NativeToolCall("call_0", "talos.list_dir", Map.of("path", "."));
            var result = ToolCallLoop.convertNativeToolCalls(List.of(ntc));

            assertEquals(1, result.size());
            assertEquals("talos.list_dir", result.get(0).toolName());
            assertEquals(".", result.get(0).param("path"));
        }

        @Test
        void multipleCalls_allConverted() {
            var ntc1 = new NativeToolCall("call_0", "talos.list_dir", Map.of("path", "."));
            var ntc2 = new NativeToolCall("call_1", "talos.read_file", Map.of("path", "README.md"));
            var result = ToolCallLoop.convertNativeToolCalls(List.of(ntc1, ntc2));

            assertEquals(2, result.size());
            assertEquals("talos.list_dir", result.get(0).toolName());
            assertEquals("talos.read_file", result.get(1).toolName());
            assertEquals("README.md", result.get(1).param("path"));
        }

        @Test
        void nullArguments_emptyParams() {
            var ntc = new NativeToolCall("call_0", "talos.status", null);
            var result = ToolCallLoop.convertNativeToolCalls(List.of(ntc));

            assertEquals(1, result.size());
            assertEquals("talos.status", result.get(0).toolName());
            assertTrue(result.get(0).parameters().isEmpty());
        }

        @Test
        void emptyArguments_emptyParams() {
            var ntc = new NativeToolCall("call_0", "talos.status", Map.of());
            var result = ToolCallLoop.convertNativeToolCalls(List.of(ntc));

            assertEquals(1, result.size());
            assertTrue(result.get(0).parameters().isEmpty());
        }

        @Test
        void nonStringValues_stringified() {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("path", "test.txt");
            args.put("count", 42);
            args.put("recursive", true);
            var ntc = new NativeToolCall("call_0", "talos.custom", args);
            var result = ToolCallLoop.convertNativeToolCalls(List.of(ntc));

            assertEquals("test.txt", result.get(0).param("path"));
            assertEquals("42", result.get(0).param("count"));
            assertEquals("true", result.get(0).param("recursive"));
        }

        @Test
        void multiValueContentPreserved() {
            // The most important case: write_file with HTML content
            String htmlContent = "<html><head><script src=\"app.js\"></script></head><body></body></html>";
            var ntc = new NativeToolCall("call_0", "talos.write_file",
                    Map.of("path", "index.html", "content", htmlContent));
            var result = ToolCallLoop.convertNativeToolCalls(List.of(ntc));

            assertEquals("index.html", result.get(0).param("path"));
            assertEquals(htmlContent, result.get(0).param("content"),
                    "HTML content including <script> tags must be preserved");
        }

        @Test
        void editFileWithScriptTag_preservedExactly() {
            // This is the exact scenario that caused the SUS_HTML bug.
            // With native tool calls, the content NEVER passes through text sanitization.
            String oldStr = "</body>";
            String newStr = "<script src=\"script.js\"></script></body>";
            var ntc = new NativeToolCall("call_0", "talos.edit_file",
                    Map.of("path", "index.html", "old_string", oldStr, "new_string", newStr));
            var result = ToolCallLoop.convertNativeToolCalls(List.of(ntc));

            assertEquals("index.html", result.get(0).param("path"));
            assertEquals(oldStr, result.get(0).param("old_string"));
            assertEquals(newStr, result.get(0).param("new_string"),
                    "<script> tag in new_string must NOT be stripped - this was the SUS_HTML bug");
        }

        @Test
        void emptyList_emptyResult() {
            var result = ToolCallLoop.convertNativeToolCalls(List.of());
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    class RunOverloadDispatching {

        // Minimal TurnProcessor stub - never actually invoked for no-tool-call tests
        private TurnProcessor stubTp() {
            return new TurnProcessor(null);
        }

        @Test
        void noToolCalls_returnsInitialAnswer() {
            var tp = new ToolCallLoop(stubTp());
            var messages = new java.util.ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("hello"));

            // Using the 2-arg overload directly with empty native calls and no text tool calls
            ToolCallLoop.LoopResult result = tp.run("Just a plain answer.", List.of(),
                    messages, java.nio.file.Path.of("."), null);

            assertEquals("Just a plain answer.", result.finalAnswer());
            assertEquals(0, result.iterations());
            assertEquals(0, result.toolsInvoked());
        }

        @Test
        void noToolCalls_backwardCompatOverload() {
            var tp = new ToolCallLoop(stubTp());
            var messages = new java.util.ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("hello"));

            ToolCallLoop.LoopResult result = tp.run("Just a plain answer.",
                    messages, java.nio.file.Path.of("."), null);

            assertEquals("Just a plain answer.", result.finalAnswer());
            assertEquals(0, result.iterations());
        }

        @Test
        void nullAnswer_returnsEmpty() {
            var tp = new ToolCallLoop(stubTp());
            var messages = new java.util.ArrayList<ChatMessage>();

            ToolCallLoop.LoopResult result = tp.run(null, List.of(),
                    messages, java.nio.file.Path.of("."), null);

            assertEquals("", result.finalAnswer());
            assertEquals(0, result.iterations());
        }
    }
}


