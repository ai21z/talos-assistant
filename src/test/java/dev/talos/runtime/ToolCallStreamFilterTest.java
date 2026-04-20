package dev.talos.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ToolCallStreamFilter}.
 *
 * Verifies that internal tool-call protocol blocks (XML and JSON code-fence)
 * are suppressed from user-visible stream output while natural text passes through.
 */
@DisplayName("ToolCallStreamFilter")
class ToolCallStreamFilterTest {

    @org.junit.jupiter.api.BeforeEach
    void resetXmlCompatTelemetry() {
        XmlCompatTelemetry.resetForTests();
    }

    /** Collect all emitted chunks into a list for assertion. */
    private static List<String> collect(java.util.function.Consumer<ToolCallStreamFilter> scenario) {
        List<String> chunks = new ArrayList<>();
        ToolCallStreamFilter filter = new ToolCallStreamFilter(chunks::add);
        scenario.accept(filter);
        filter.flush();
        return chunks;
    }

    private static String joined(java.util.function.Consumer<ToolCallStreamFilter> scenario) {
        return String.join("", collect(scenario));
    }

    // ── Plain text passthrough ──────────────────────────────────────────

    @Nested
    @DisplayName("Plain text passthrough")
    class PlainText {

        @Test
        @DisplayName("plain text passes through unchanged")
        void plain_text_passes() {
            String result = joined(f -> f.accept("Hello, how can I help you today?"));
            assertEquals("Hello, how can I help you today?", result);
        }

        @Test
        @DisplayName("empty string does not emit")
        void empty_string() {
            List<String> chunks = collect(f -> f.accept(""));
            assertTrue(chunks.isEmpty());
        }

        @Test
        @DisplayName("null chunk does not emit")
        void null_chunk() {
            List<String> chunks = collect(f -> f.accept(null));
            assertTrue(chunks.isEmpty());
        }

        @Test
        @DisplayName("multiple plain chunks concatenate correctly")
        void multiple_plain_chunks() {
            String result = joined(f -> {
                f.accept("Hello ");
                f.accept("world!");
            });
            assertEquals("Hello world!", result);
        }

        @Test
        @DisplayName("HTML content with angle brackets passes through")
        void html_content_passes() {
            String result = joined(f -> f.accept("Use <div class=\"foo\"> for layout."));
            assertEquals("Use <div class=\"foo\"> for layout.", result);
        }
    }

    // ── Tool call suppression ───────────────────────────────────────────

    @Nested
    @DisplayName("Tool call suppression")
    class Suppression {

        @Test
        @DisplayName("complete <tool_call> block is suppressed")
        void complete_tool_call_suppressed() {
            String input = "<tool_call>\n{\"name\":\"talos.read_file\",\"parameters\":{\"path\":\"foo.txt\"}}\n</tool_call>";
            String result = joined(f -> f.accept(input));
            assertEquals("", result);
            assertEquals(1, XmlCompatTelemetry.snapshot().streamSuppressedBlocks());
        }

        @Test
        @DisplayName("<function_call> variant is suppressed")
        void function_call_variant_suppressed() {
            String input = "<function_call>{\"name\":\"talos.list_dir\"}</function_call>";
            String result = joined(f -> f.accept(input));
            assertEquals("", result);
        }

        @Test
        @DisplayName("<tool> variant is suppressed")
        void tool_variant_suppressed() {
            String input = "<tool>{\"name\":\"talos.grep\"}</tool>";
            String result = joined(f -> f.accept(input));
            assertEquals("", result);
        }

        @Test
        @DisplayName("<function> variant is suppressed")
        void function_variant_suppressed() {
            String input = "<function>{\"name\":\"talos.read_file\"}</function>";
            String result = joined(f -> f.accept(input));
            assertEquals("", result);
        }

        @Test
        @DisplayName("multiple tool call blocks are all suppressed")
        void multiple_blocks_suppressed() {
            String input = "<tool_call>{\"name\":\"a\"}</tool_call>\n<tool_call>{\"name\":\"b\"}</tool_call>";
            String result = joined(f -> f.accept(input));
            assertEquals("\n", result);
        }
    }

    // ── Mixed text + tool calls ─────────────────────────────────────────

    @Nested
    @DisplayName("Mixed text and tool calls")
    class Mixed {

        @Test
        @DisplayName("text before tool call passes through")
        void text_before_tool_call() {
            String result = joined(f -> f.accept(
                    "Let me read that file. <tool_call>{\"name\":\"talos.read_file\"}</tool_call>"));
            assertEquals("Let me read that file. ", result);
        }

        @Test
        @DisplayName("text after tool call passes through")
        void text_after_tool_call() {
            String result = joined(f -> f.accept(
                    "<tool_call>{\"name\":\"talos.read_file\"}</tool_call>Here is what I found."));
            assertEquals("Here is what I found.", result);
        }

        @Test
        @DisplayName("text before and after tool call both pass through")
        void text_before_and_after() {
            String result = joined(f -> f.accept(
                    "Reading now. <tool_call>{}</tool_call> Done!"));
            assertEquals("Reading now.  Done!", result);
        }

        @Test
        @DisplayName("multiple tool calls with interspersed text")
        void multiple_with_text() {
            String result = joined(f -> {
                f.accept("First, ");
                f.accept("<tool_call>{\"name\":\"a\"}</tool_call>");
                f.accept(" then ");
                f.accept("<tool_call>{\"name\":\"b\"}</tool_call>");
                f.accept(" done.");
            });
            assertEquals("First,  then  done.", result);
        }
    }

    // ── Chunk boundary handling ──────────────────────────────────────────

    @Nested
    @DisplayName("Chunk boundaries")
    class ChunkBoundaries {

        @Test
        @DisplayName("tag split across two chunks: <tool_ + call>")
        void tag_split_across_chunks() {
            String result = joined(f -> {
                f.accept("Hello <tool_");
                f.accept("call>{\"name\":\"x\"}</tool_call> world");
            });
            assertEquals("Hello  world", result);
        }

        @Test
        @DisplayName("opening tag one char at a time")
        void opening_tag_char_by_char() {
            String result = joined(f -> {
                for (char c : "<tool_call>".toCharArray()) {
                    f.accept(String.valueOf(c));
                }
                f.accept("{\"name\":\"x\"}");
                f.accept("</tool_call>");
                f.accept("after");
            });
            assertEquals("after", result);
        }

        @Test
        @DisplayName("closing tag split across chunks")
        void closing_tag_split() {
            String result = joined(f -> {
                f.accept("<tool_call>{\"data\":\"long content\"}");
                f.accept("</tool_");
                f.accept("call>rest");
            });
            assertEquals("rest", result);
        }

        @Test
        @DisplayName("partial < at end of chunk that is NOT a tag")
        void partial_angle_not_tag() {
            String result = joined(f -> {
                f.accept("x < y and ");
                f.accept("z > w");
            });
            assertEquals("x < y and z > w", result);
        }

        @Test
        @DisplayName("partial <f at end of chunk resolves to non-tag")
        void partial_f_resolves_to_nontag() {
            // <f could be start of <function>, but <fo is not
            String result = joined(f -> {
                f.accept("value <fo");
                f.accept("o> bar");
            });
            assertEquals("value <foo> bar", result);
        }
    }

    // ── Flush behavior ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Flush behavior")
    class FlushBehavior {

        @Test
        @DisplayName("flush emits pending non-tool text")
        void flush_emits_pending() {
            List<String> chunks = new ArrayList<>();
            ToolCallStreamFilter filter = new ToolCallStreamFilter(chunks::add);
            filter.accept("some text");
            filter.flush();
            assertEquals("some text", String.join("", chunks));
        }

        @Test
        @DisplayName("flush discards incomplete tool call block")
        void flush_discards_incomplete_block() {
            List<String> chunks = new ArrayList<>();
            ToolCallStreamFilter filter = new ToolCallStreamFilter(chunks::add);
            filter.accept("text <tool_call>{\"name\":\"x\"}");
            // No closing tag — flush should discard the partial block
            filter.flush();
            assertEquals("text ", String.join("", chunks));
        }

        @Test
        @DisplayName("reset clears all state")
        void reset_clears_state() {
            List<String> chunks = new ArrayList<>();
            ToolCallStreamFilter filter = new ToolCallStreamFilter(chunks::add);
            filter.accept("<tool_call>partial");
            filter.reset();
            filter.accept("fresh text");
            filter.flush();
            assertEquals("fresh text", String.join("", chunks));
        }
    }

    // ── Prefix detection helper ─────────────────────────────────────────

    @Nested
    @DisplayName("couldBeOpenTagPrefix")
    class PrefixDetection {

        @Test void bare_angle_bracket() {
            assertTrue(ToolCallStreamFilter.couldBeOpenTagPrefix("<"));
        }

        @Test void tool_prefix() {
            assertTrue(ToolCallStreamFilter.couldBeOpenTagPrefix("<tool"));
        }

        @Test void full_tool_call_tag() {
            assertTrue(ToolCallStreamFilter.couldBeOpenTagPrefix("<tool_call>"));
        }

        @Test void function_prefix() {
            assertTrue(ToolCallStreamFilter.couldBeOpenTagPrefix("<func"));
        }

        @Test void not_a_tag_prefix() {
            assertFalse(ToolCallStreamFilter.couldBeOpenTagPrefix("<div"));
        }

        @Test void not_a_tag_html() {
            assertFalse(ToolCallStreamFilter.couldBeOpenTagPrefix("<html"));
        }
    }

    // ── Large content suppression ───────────────────────────────────────

    @Nested
    @DisplayName("Large content")
    class LargeContent {

        @Test
        @DisplayName("large tool call content is fully suppressed")
        void large_tool_call_suppressed() {
            String bigContent = "x".repeat(50_000);
            String input = "before<tool_call>{\"name\":\"talos.write_file\",\"parameters\":{\"content\":\""
                    + bigContent + "\"}}</tool_call>after";
            String result = joined(f -> f.accept(input));
            assertEquals("beforeafter", result);
        }

        @Test
        @DisplayName("large tool call streamed in many chunks is suppressed")
        void large_tool_call_chunked() {
            StringBuilder sb = new StringBuilder();
            sb.append("intro ");
            sb.append("<tool_call>");
            sb.append("{\"name\":\"talos.write_file\",\"parameters\":{\"content\":\"");
            sb.append("A".repeat(10_000));
            sb.append("\"}}");
            sb.append("</tool_call>");
            sb.append(" outro");

            // Simulate streaming in 100-char chunks
            String full = sb.toString();
            String result = joined(f -> {
                for (int i = 0; i < full.length(); i += 100) {
                    f.accept(full.substring(i, Math.min(i + 100, full.length())));
                }
            });
            assertEquals("intro  outro", result);
        }
    }

    // ── JSON code-fence tool call suppression ──────────────────────────

    @Nested
    @DisplayName("JSON code-fence tool call suppression")
    class JsonFenceSuppression {

        @Test
        @DisplayName("JSON code-fenced tool call is suppressed")
        void json_fence_tool_call_suppressed() {
            String input = "Let me check.\n```json\n{\"name\": \"talos.read_file\", \"parameters\": {\"path\": \"foo.txt\"}}\n```\n";
            String result = joined(f -> f.accept(input));
            assertFalse(result.contains("talos.read_file"),
                    "JSON code-fenced tool call should be suppressed");
            assertTrue(result.contains("Let me check."),
                    "Prose before tool call should pass through");
        }

        @Test
        @DisplayName("bare code fence with tool call is suppressed")
        void bare_fence_tool_call_suppressed() {
            String input = "```\n{\"name\": \"talos.list_dir\", \"parameters\": {\"path\": \".\"}}\n```";
            String result = joined(f -> f.accept(input));
            assertFalse(result.contains("talos.list_dir"),
                    "Bare code-fenced tool call should be suppressed");
        }

        @Test
        @DisplayName("non-tool-call code fence passes through")
        void non_tool_code_fence_passes() {
            String input = "Here is some code:\n```json\n{\"key\": \"value\", \"count\": 42}\n```\nDone.";
            String result = joined(f -> f.accept(input));
            assertTrue(result.contains("\"key\": \"value\""),
                    "Non-tool code fence should pass through");
            assertTrue(result.contains("Done."),
                    "Text after non-tool fence should pass through");
        }

        @Test
        @DisplayName("multiple JSON tool calls suppressed, prose preserved")
        void multiple_json_fences_suppressed() {
            String input = "First.\n```json\n{\"name\": \"talos.read_file\", \"parameters\": {\"path\": \"a.txt\"}}\n```\nThen.\n```json\n{\"name\": \"talos.grep\", \"parameters\": {\"pattern\": \"TODO\"}}\n```\nDone.";
            String result = joined(f -> f.accept(input));
            assertFalse(result.contains("talos.read_file"));
            assertFalse(result.contains("talos.grep"));
            assertTrue(result.contains("First."));
            assertTrue(result.contains("Then."));
            assertTrue(result.contains("Done."));
        }

        @Test
        @DisplayName("JSON fence streamed in chunks is suppressed")
        void json_fence_chunked() {
            String result = joined(f -> {
                f.accept("intro ");
                f.accept("```json\n{\"name\":");
                f.accept(" \"talos.read_file\", \"parameters\":");
                f.accept(" {\"path\": \"x.txt\"}}\n```");
                f.accept(" outro");
            });
            assertFalse(result.contains("talos.read_file"),
                    "Chunked JSON fence tool call should be suppressed");
            assertTrue(result.contains("intro"),
                    "Text before chunked fence should pass through");
            assertTrue(result.contains("outro"),
                    "Text after chunked fence should pass through");
        }

        @Test
        @DisplayName("mixed XML and JSON tool calls both suppressed")
        void mixed_xml_and_json_suppressed() {
            String result = joined(f -> {
                f.accept("A ");
                f.accept("<tool_call>{\"name\":\"talos.list_dir\"}</tool_call>");
                f.accept(" B ");
                f.accept("```json\n{\"name\": \"talos.read_file\", \"parameters\": {\"path\": \"y\"}}\n```");
                f.accept(" C");
            });
            assertFalse(result.contains("talos.list_dir"));
            assertFalse(result.contains("talos.read_file"));
            assertTrue(result.contains("A "));
            assertTrue(result.contains(" B "));
            assertTrue(result.contains(" C"));
        }
    }

    // ── Flush with JSON fences ───────────────────────────────────────────

    @Nested
    @DisplayName("Flush behavior with JSON fences")
    class FlushJsonFence {

        @Test
        @DisplayName("incomplete JSON fence is emitted as regular content on flush")
        void flush_emits_incomplete_fence() {
            List<String> chunks = new ArrayList<>();
            ToolCallStreamFilter filter = new ToolCallStreamFilter(chunks::add);
            filter.accept("text ```json\n{\"just_data\": true");
            // No closing ``` — flush should emit as regular content (not a complete tool call)
            filter.flush();
            String result = String.join("", chunks);
            assertTrue(result.contains("text"), "Text should be emitted");
            assertTrue(result.contains("just_data"), "Incomplete fence content should be emitted");
        }
    }
}

