package dev.talos.runtime;

import dev.talos.core.tool.ToolProtocolText;
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

    private static void assertEveryTwoChunkSplit(String input, String expected) {
        for (int split = 0; split <= input.length(); split++) {
            int boundary = split;
            String result = joined(filter -> {
                filter.accept(input.substring(0, boundary));
                filter.accept(input.substring(boundary));
            });
            assertEquals(expected, result, "split=" + split);
        }
    }

    private static void assertOneCharacterDelivery(String input, String expected) {
        String result = joined(filter -> {
            for (int index = 0; index < input.length(); index++) {
                filter.accept(input.substring(index, index + 1));
            }
        });
        assertEquals(expected, result);
    }

    private static void assertProtocolOnlyDeliveryHasNoDebris(String input) {
        for (int split = 0; split <= input.length(); split++) {
            int boundary = split;
            String result = joined(filter -> {
                filter.accept(input.substring(0, boundary));
                filter.accept(input.substring(boundary));
            });
            assertTrue(result.isBlank(), "split=" + split + " result=" + result);
            assertFalse(result.contains("```"), "split=" + split);
            assertFalse(result.contains("talos."), "split=" + split);
            assertFalse(result.contains("<script>"), "split=" + split);
            assertFalse(result.contains("<think>"), "split=" + split);
        }

        String result = joined(filter -> {
            for (int index = 0; index < input.length(); index++) {
                filter.accept(input.substring(index, index + 1));
            }
        });
        assertTrue(result.isBlank(), "one-character result=" + result);
        assertFalse(result.contains("```"));
        assertFalse(result.contains("talos."));
        assertFalse(result.contains("<script>"));
        assertFalse(result.contains("<think>"));
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
            // No closing tag - flush should discard the partial block
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

        @Test
        @DisplayName("reset clears retained standalone-boundary evidence")
        void reset_clears_retained_standalone_boundary_evidence() {
            String read = "{\"name\":\"talos.read_file\","
                    + "\"arguments\":{\"path\":\"README.md\"}}";
            List<String> chunks = new ArrayList<>();
            ToolCallStreamFilter filter = new ToolCallStreamFilter(chunks::add);

            filter.accept("before({");
            filter.reset();
            chunks.clear();
            filter.accept(read);
            filter.flush();

            assertEquals("", String.join("", chunks));
        }

        @Test
        @DisplayName("flush clears retained standalone-boundary evidence")
        void flush_clears_retained_standalone_boundary_evidence() {
            String read = "{\"name\":\"talos.read_file\","
                    + "\"arguments\":{\"path\":\"README.md\"}}";
            List<String> chunks = new ArrayList<>();
            ToolCallStreamFilter filter = new ToolCallStreamFilter(chunks::add);

            filter.accept("before({");
            filter.flush();
            chunks.clear();
            filter.accept(read);
            filter.flush();

            assertEquals("", String.join("", chunks));
        }

        @Test
        @DisplayName("flush and reset clear leading JSON sequence state")
        void lifecycle_clears_leading_json_sequence_state() {
            String ordinary = "{\"label\":\"ordinary\"}";
            String read = "{\"name\":\"talos.read_file\","
                    + "\"arguments\":{\"path\":\"README.md\"}}";
            String source = ordinary + "[1]" + read;
            List<String> chunks = new ArrayList<>();
            ToolCallStreamFilter filter = new ToolCallStreamFilter(chunks::add);

            filter.accept(source);
            filter.flush();
            filter.accept(source);
            filter.flush();
            assertEquals(ordinary + "[1]" + ordinary + "[1]",
                    String.join("", chunks));

            chunks.clear();
            filter.accept(ordinary + "[1]{\"name\":");
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

        @Test
        @DisplayName("adversarial unfinished-line backticks remain bounded")
        void unfinished_line_backticks_remain_bounded() {
            String input = "`x".repeat(50_000);
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(2),
                    () -> assertEquals(
                            input, joined(filter -> filter.accept(input))));
        }

        @Test
        @DisplayName("one-character unfinished fence info line remains bounded")
        void unfinished_fence_info_line_one_character_remains_bounded() {
            String input = "```json" + " ".repeat(10_000);
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(2),
                    () -> assertOneCharacterDelivery(input, input));
        }

        @Test
        @DisplayName("disproved short backtick runs settle identically before flush")
        void disproved_short_backtick_runs_are_partition_invariant_before_flush() {
            for (String input : List.of("`xyz", "``xyz")) {
                List<String> completeChunks = new ArrayList<>();
                ToolCallStreamFilter complete =
                        new ToolCallStreamFilter(completeChunks::add);
                complete.accept(input);

                List<String> splitChunks = new ArrayList<>();
                ToolCallStreamFilter split =
                        new ToolCallStreamFilter(splitChunks::add);
                for (int index = 0; index < input.length(); index++) {
                    split.accept(input.substring(index, index + 1));
                }

                assertEquals(
                        String.join("", completeChunks),
                        String.join("", splitChunks),
                        input);
                complete.flush();
                split.flush();
                assertEquals(input, String.join("", completeChunks), input);
                assertEquals(input, String.join("", splitChunks), input);
            }
        }

        @Test
        @DisplayName("disproved short backtick run cannot become protocol at the cap")
        void disproved_short_backtick_run_never_reaches_protocol_cap() {
            String suffix = "x".repeat(10 * 1024 * 1024);

            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(3),
                    () -> assertEquals(
                            "`" + suffix,
                            joined(filter -> {
                                filter.accept("`");
                                filter.accept(suffix);
                            })));
        }

        @Test
        @DisplayName("leading JSON probe advances monotonically across whitespace")
        void leading_json_probe_whitespace_is_partition_invariant_and_bounded() {
            String whitespace = " ".repeat(100_000);
            String ordinary = "{\"label\":\"ordinary\"}";
            String proposal = "{\"name\":\"talos.read_file\","
                    + "\"arguments\":{\"path\":\"README.md\"}}";
            String input = whitespace + ordinary + proposal;
            String expected = whitespace + ordinary;

            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(2),
                    () -> {
                        assertEquals(expected, joined(filter -> filter.accept(input)));
                        assertOneCharacterDelivery(input, expected);
                    });
        }

        @Test
        @DisplayName("early leading-JSON disproof preserves bytes beyond the cap")
        void disproved_leading_json_prefix_preserves_cap_overflow() {
            int cap = 10 * 1024 * 1024;
            String source = "{x" + "A".repeat(cap);

            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(5),
                    () -> {
                        assertEquals(source, joined(filter -> filter.accept(source)));
                        assertEquals(source, joined(filter -> {
                            filter.accept("{x");
                            filter.accept(source.substring(2));
                        }));
                    });
        }

        @Test
        @DisplayName("complete padded JSON opener obeys the same cap across partitions")
        void complete_padded_json_opener_is_partition_invariant_at_the_cap() {
            int cap = 10 * 1024 * 1024;
            String opening = "```json" + " ".repeat(cap - 6) + "\n";
            String payload = "{\"label\":\"ordinary\"}\n```";
            String source = opening + payload;

            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(5),
                    () -> {
                        assertEquals("", joined(filter -> filter.accept(source)));
                        assertEquals("", joined(filter -> {
                            filter.accept(source.substring(0, cap));
                            filter.accept(source.substring(cap, cap + 1));
                            filter.accept(source.substring(cap + 1));
                        }));
                    });
        }

        @Test
        @DisplayName("padded foreign fence opener obeys the same cap across partitions")
        void complete_padded_foreign_opener_is_partition_invariant_at_the_cap() {
            int cap = 10 * 1024 * 1024;
            String opening = "```text" + " ".repeat(cap - 7) + "\n";
            String source = opening + "ordinary\n```";

            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(5),
                    () -> {
                        assertEquals("", joined(filter -> filter.accept(source)));
                        assertEquals("", joined(filter -> {
                            filter.accept(source.substring(0, 7));
                            filter.accept(source.substring(7, cap));
                            filter.accept(source.substring(cap, cap + 1));
                            filter.accept(source.substring(cap + 1));
                        }));
                    });
        }

        @Test
        @DisplayName("contiguous unsettled delimiter run advances monotonically")
        void contiguous_unsettled_delimiter_run_is_bounded() {
            String source = "```text\n\"" + "`".repeat(300_000);

            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(3),
                    () -> assertOneCharacterDelivery(source, source));
        }

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

        @Test void code_fence_backtick_prefix() {
            assertTrue(ToolCallStreamFilter.couldBeCodeFenceOpenPrefix("`"));
            assertTrue(ToolCallStreamFilter.couldBeCodeFenceOpenPrefix("``"));
            assertTrue(ToolCallStreamFilter.couldBeCodeFenceOpenPrefix("```j"));
            assertTrue(ToolCallStreamFilter.couldBeCodeFenceOpenPrefix("```java"));
            assertFalse(ToolCallStreamFilter.couldBeCodeFenceOpenPrefix("```java\n"));
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
        @DisplayName("JSON code-fenced write_file with backticks in content is suppressed")
        void json_fence_write_file_with_backticks_in_content_suppressed() {
            String input = """
                    ```json
                    {"name": "talos.write_file", "arguments": {"path": "scripts.js", "content": "const message = `BMI ${bmi.toFixed(2)}`;"}}
                    ```
                    """;
            String result = joined(f -> f.accept(input));
            assertEquals("", result);
        }

        @Test
        @DisplayName("JSON code-fenced bare write_file alias is suppressed")
        void json_fence_bare_write_file_alias_suppressed() {
            String input = "```json\n{\"name\": \"write_file\", \"arguments\": {\"path\": \"index.html\"}}\n```";
            String result = joined(f -> f.accept(input));
            assertEquals("", result);
        }

        @Test
        @DisplayName("JSON code-fenced function key alias is suppressed")
        void json_fence_function_key_alias_suppressed() {
            String input = "```json\n{\"function\": \"talos.write_file\", \"arguments\": {\"path\": \"index.html\"}}\n```";
            String result = joined(f -> f.accept(input));
            assertEquals("", result);
        }

        @Test
        @DisplayName("JSON code-fenced function_name key alias is suppressed")
        void json_fence_function_name_key_alias_suppressed() {
            String input = "```json\n{\"function_name\": \"talos.write_file\", "
                    + "\"arguments\": {\"path\": \"index.html\"}}\n```";
            String result = joined(f -> f.accept(input));
            assertEquals("", result);
        }

        @Test
        @DisplayName("JSON code-fenced tool_name key alias is suppressed")
        void json_fence_tool_name_key_alias_suppressed() {
            String input = "```json\n{\"tool_name\": \"talos.edit_file\", \"params\": {\"path\": \"index.html\"}}\n```";
            String result = joined(f -> f.accept(input));
            assertEquals("", result);
        }

        @Test
        @DisplayName("adjacent JSON fences with tool aliases are suppressed")
        void adjacent_json_fences_with_tool_aliases_suppressed() {
            String input = "```json\n{\"name\": \"write_file\", \"arguments\": {\"path\": \"a.txt\"}}\n```"
                    + "```json\n{\"tool_name\": \"talos.edit_file\", \"params\": {\"path\": \"b.txt\"}}\n```"
                    + "done";
            assertEveryTwoChunkSplit(input, "done");
        }

        @Test
        @DisplayName("selected roots, unsafe tails, duplicates, and subordinate proposals never leak")
        void selected_root_and_suppression_only_protocol_never_leaks() {
            String read = "{\"name\":\"talos.read_file\","
                    + "\"arguments\":{\"path\":\"README.md\"}}";
            String list = "{\"name\":\"talos.list_dir\","
                    + "\"arguments\":{\"path\":\".\"}}";
            String unsafeWrite = "{\"name\":\"talos.write_file\",\"arguments\":{"
                    + "\"path\":\"x.js\","
                    + "\"content\":\"<script>outside()</script>\"}}";
            String readFence = "```json\n" + read + "\n```";

            for (String input : List.of(
                    "```json\n" + read + "\n<script>outside()</script>\n```",
                    "```json\n" + read + "\n<think>outside</think>\n```",
                    "```json\n" + read + "\n" + unsafeWrite + "\n```",
                    readFence + "\n" + readFence,
                    read + "\n" + read,
                    readFence + "\n" + unsafeWrite,
                    read + "\n" + list + "\n"
                            + "```json\n" + unsafeWrite + "\n```")) {
                assertProtocolOnlyDeliveryHasNoDebris(input);
            }
        }

        @Test
        @DisplayName("nested executable fence recovers from an unclosed generic fence")
        void nested_executable_fence_recovers_from_unclosed_generic_fence() {
            String read = "{\"name\":\"talos.read_file\","
                    + "\"arguments\":{\"path\":\"README.md\"}}";
            String input = "before\n```text\nordinary\n```json\n"
                    + read
                    + "\n```\nafter";
            String expected = "before\n```text\nordinary\nafter";

            assertEveryTwoChunkSplit(input, expected);
            assertOneCharacterDelivery(input, expected);
        }

        @Test
        @DisplayName("unfinished generic fence info line owns embedded JSON")
        void unfinished_generic_fence_info_line_owns_embedded_json() {
            String read = "{\"name\":\"talos.read_file\","
                    + "\"arguments\":{\"path\":\"README.md\"}}";
            for (String input : List.of(
                    "```x " + read + "\na",
                    "````text " + read + "\na",
                    "prefix abc```x " + read + "\na")) {
                assertEveryTwoChunkSplit(input, input);
                assertOneCharacterDelivery(input, input);
            }
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
        @DisplayName("empty json code fence is suppressed as protocol debris")
        void empty_json_fence_suppressed() {
            String input = "Before\n```json\n\n```\nAfter";
            String result = joined(f -> f.accept(input));
            assertEquals("Before\nAfter", result);
        }

        @Test
        @DisplayName("empty json fence before adjacent tool JSON is suppressed")
        void empty_json_fence_before_adjacent_tool_json_suppressed() {
            String input = "```json\n\n```{\"name\": \"talos.edit_file\", \"arguments\": {\"path\": \"index.html\"}}";
            String result = joined(f -> f.accept(input));
            assertEquals("", result);
        }

        @Test
        @DisplayName("empty generic code fence still passes through")
        void empty_generic_fence_passes() {
            String input = "Before\n```\n\n```\nAfter";
            String result = joined(f -> f.accept(input));
            assertEquals(input, result);
        }

        @Test
        @DisplayName("speculative pre-tool prose is suppressed with tool-call fence")
        void speculative_pre_tool_prose_suppressed_with_tool_fence() {
            String input = "Let's assume the relevant section looks like this:\n"
                    + "```json\n"
                    + "{\"name\": \"talos.read_file\", \"parameters\": {\"path\": \"index.html\"}}\n"
                    + "```\n"
                    + "After.";
            String result = joined(f -> f.accept(input));
            assertFalse(result.contains("Let's assume"));
            assertEquals("After.", result);
        }

        @Test
        @DisplayName("ordinary pre-tool prose is preserved with tool-call fence")
        void ordinary_pre_tool_prose_preserved_with_tool_fence() {
            String input = "Let me check.\n"
                    + "```json\n"
                    + "{\"name\": \"talos.read_file\", \"parameters\": {\"path\": \"index.html\"}}\n"
                    + "```\n"
                    + "Done.";
            String result = joined(f -> f.accept(input));
            assertEquals("Let me check.\nDone.", result);
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
        @DisplayName("canonical closing fence without a preceding newline is suppressed")
        void json_fence_close_without_preceding_newline_suppressed() {
            String input = "before```json\n"
                    + "{\"function_name\":\"talos.read_file\",\"arguments\":{\"path\":\"x.txt\"}}"
                    + "```after";
            for (int split = 0; split <= input.length(); split++) {
                int boundary = split;
                String result = joined(filter -> {
                    filter.accept(input.substring(0, boundary));
                    filter.accept(input.substring(boundary));
                });
                assertEquals("beforeafter", result, "split=" + split);
            }
        }

        @Test
        @DisplayName("later executable fence cannot poison an earlier foreign fence")
        void later_executable_fence_does_not_poison_foreign_fence_across_splits() {
            String foreignFence = "```json\n"
                    + "{\"name\":\"foreign.read_file\",\"arguments\":{}}```";
            String toolFence = "```json\n"
                    + "{\"name\":\"talos.read_file\",\"arguments\":{}}```";
            String input = "before" + foreignFence + "between" + toolFence + "after";
            assertEveryTwoChunkSplit(input, "before" + foreignFence + "betweenafter");
        }

        @Test
        @DisplayName("apostrophe pseudo-JSON fence remains visible before Talos proposal")
        void apostrophe_pseudo_json_fence_then_talos_proposal_complete() {
            String ordinary = "```\n{\"message\": 'don\'t stop'}\n```";
            String tool = "```json\n"
                    + "{\"name\":\"talos.read_file\",\"arguments\":{}}```";
            String input = "before" + ordinary + "between" + tool + "after";
            assertEquals("before" + ordinary + "betweenafter",
                    joined(filter -> filter.accept(input)));
        }

        @Test
        @DisplayName("apostrophe pseudo-JSON fence is partition invariant")
        void apostrophe_pseudo_json_fence_then_talos_proposal_every_split() {
            String ordinary = "```\n{\"message\": 'don\'t stop'}\n```";
            String tool = "```json\n"
                    + "{\"name\":\"talos.read_file\",\"arguments\":{}}```";
            String input = "before" + ordinary + "between" + tool + "after";
            assertEveryTwoChunkSplit(input, "before" + ordinary + "betweenafter");
        }

        @Test
        @DisplayName("apostrophe pseudo-JSON fence survives one-character delivery")
        void apostrophe_pseudo_json_fence_then_talos_proposal_one_character() {
            String ordinary = "```\n{\"message\": 'don\'t stop'}\n```";
            String tool = "```json\n"
                    + "{\"name\":\"talos.read_file\",\"arguments\":{}}```";
            String input = "before" + ordinary + "between" + tool + "after";
            assertOneCharacterDelivery(input, "before" + ordinary + "betweenafter");
        }

        @Test
        @DisplayName("triple backticks inside executable JSON string never leak")
        void internal_triple_backticks_before_late_name_never_leak_across_splits() {
            String input = "before```json\n"
                    + "{\"arguments\":{\"path\":\"x.js\",\"content\":"
                    + "\"const marker = ```;\"},\"name\":\"talos.write_file\"}"
                    + "```after";
            assertEveryTwoChunkSplit(input, "beforeafter");
        }

        @Test
        @DisplayName("arbitrarily padded executable opener remains atomic")
        void horizontally_padded_opener_never_partially_emits_across_splits() {
            String input = "before```json" + " ".repeat(257) + "\n"
                    + "{\"name\":\"talos.read_file\",\"arguments\":{}}```after";
            assertEveryTwoChunkSplit(input, "beforeafter");
        }

        @Test
        @DisplayName("malformed Talos fence is suppressed instead of displayed")
        void malformed_talos_fence_suppressed() {
            String input = "before```json\n"
                    + "{\"function_name\":\"talos.read_file\",\"arguments\":{\"path\": }"
                    + "```after";
            assertEquals("beforeafter", joined(f -> f.accept(input)));
        }

        @Test
        @DisplayName("malformed foreign fence remains visible byte-for-byte")
        void malformed_foreign_fence_visible() {
            String input = "before```json\n"
                    + "{\"function_name\":\"foreign.read_file\",\"arguments\":{\"path\": }"
                    + "```after";
            assertEquals(input, joined(f -> f.accept(input)));
        }

        @Test
        @DisplayName("accepted four-backtick proposals leave no delimiter debris")
        void accepted_four_backtick_proposals_leave_no_delimiter_debris() {
            for (String payload : List.of(
                    "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"example.txt\"}}",
                    "{\"function_name\":\"write_file\",\"params\":{\"path\":\"x.txt\",\"content\":\"x\"}}")) {
                String input = "before````json\n" + payload + "````after";
                assertEquals("beforeafter", joined(f -> f.accept(input)), payload);
            }
        }

        @Test
        @DisplayName("accepted four-backtick proposals are suppressed across every split")
        void accepted_four_backtick_proposals_suppressed_across_every_split() {
            for (String payload : List.of(
                    "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"example.txt\"}}",
                    "{\"function_name\":\"write_file\",\"params\":{\"path\":\"x.txt\",\"content\":\"x\"}}")) {
                String input = "before````json\n" + payload + "````after";
                assertEveryTwoChunkSplit(input, "beforeafter");
            }
        }

        @Test
        @DisplayName("accepted four-backtick proposals are suppressed one character at a time")
        void accepted_four_backtick_proposals_suppressed_one_character_at_a_time() {
            for (String payload : List.of(
                    "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"example.txt\"}}",
                    "{\"function_name\":\"write_file\",\"params\":{\"path\":\"x.txt\",\"content\":\"x\"}}")) {
                String input = "before````json\n" + payload + "````after";
                String result = joined(filter -> {
                    for (int index = 0; index < input.length(); index++) {
                        filter.accept(input.substring(index, index + 1));
                    }
                });
                assertEquals("beforeafter", result, payload);
            }
        }

        @Test
        @DisplayName("generic quoted delimiter cannot expose a later Talos fence")
        void generic_quoted_delimiter_then_talos_fence_is_partition_invariant() {
            String ordinary = "```java\nString marker = \"```\";\nordinary\n```\n";
            String tool = "```json\n"
                    + "{\"name\":\"talos.read_file\",\"arguments\":{}}\n```\n";
            String input = "before\n" + ordinary + tool + "after";
            String expected = "before\n" + ordinary + "after";

            assertEveryTwoChunkSplit(input, expected);
            assertOneCharacterDelivery(input, expected);
        }

        @Test
        @DisplayName("canonical inline JSON envelope cannot leak through an outer generic fence")
        void overlapping_generic_fence_cannot_expose_selected_json_envelope() {
            String root = "{\"name\":\"talos.read_file\",\"arguments\":{}}";
            String source = "```x\nx```json\n" + root + "\n```";
            String expected = "```x\nx";

            ToolProtocolText.JsonProposalSelection selection =
                    ToolProtocolText.selectExecutableJsonProposals(source);
            assertEquals(1, selection.executionOrder().size());
            assertEquals(root,
                    selection.executionOrder().getFirst().rootFrom(source));

            assertEquals(expected, joined(filter -> filter.accept(source)));
            assertEveryTwoChunkSplit(source, expected);
            assertOneCharacterDelivery(source, expected);
        }

        @Test
        @DisplayName("incomplete overlapping JSON envelope cannot leak at terminal flush")
        void incomplete_overlapping_generic_fence_cannot_expose_protocol() {
            String root = "{\"name\":\"talos.read_file\",\"arguments\":{}}";
            String source = "```x\nx```json\n" + root;
            String expected = "```x\nx";

            assertFalse(ToolProtocolText
                    .potentialExecutableJsonEnvelopeSpans(source)
                    .isEmpty());
            assertEveryTwoChunkSplit(source, expected);
            assertOneCharacterDelivery(source, expected);
        }

        @Test
        @DisplayName("ordinary generic closing-line spaces survive terminal flush")
        void generic_fence_terminal_closing_line_spaces_are_preserved() {
            String source = "```text\nx\n```  ";

            assertEveryTwoChunkSplit(source, source);
            assertOneCharacterDelivery(source, source);
        }

        @Test
        @DisplayName("unmatched generic quote cannot expose a later Talos fence")
        void unmatched_generic_quote_then_talos_fence_is_partition_invariant() {
            String ordinary = "```text\nString x = \"unterminated;\n```\n";
            String tool = "```json\n"
                    + "{\"name\":\"talos.read_file\",\"arguments\":{}}\n```\n";
            String input = "before\n" + ordinary + tool + "after";
            String expected = "before\n" + ordinary + "after";

            assertEveryTwoChunkSplit(input, expected);
            assertOneCharacterDelivery(input, expected);
        }

        @Test
        @DisplayName("four-open three-close generic fence cannot expose later Talos fence")
        void generic_four_open_three_close_then_talos_fence_is_partition_invariant() {
            String ordinary = "````java\nordinary\n```\n";
            String tool = "```json\n"
                    + "{\"name\":\"talos.read_file\",\"arguments\":{}}\n```\n";
            String input = "before\n" + ordinary + tool + "after";
            String expected = "before\n" + ordinary + "after";

            assertEveryTwoChunkSplit(input, expected);
            assertOneCharacterDelivery(input, expected);
        }

        @Test
        @DisplayName("four-backtick foreign language fence remains visible")
        void four_backtick_foreign_language_fence_visible() {
            String input = "before````java\n"
                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"example.txt\"}}"
                    + "````after";
            assertEveryTwoChunkSplit(input, input);
        }

        @Test
        @DisplayName("four-backtick ordinary JSON fence remains visible")
        void four_backtick_ordinary_json_fence_visible() {
            String input = "before````json\n"
                    + "{\"name\":\"ordinary\",\"value\":true}"
                    + "````after";
            assertEveryTwoChunkSplit(input, input);
        }

        @Test
        @DisplayName("surplus closing delimiter ticks are consumed with accepted proposal")
        void surplus_closing_delimiter_ticks_are_consumed_with_accepted_proposal() {
            String input = "before```json\n"
                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"secret.txt\"}}"
                    + "````after";
            assertEveryTwoChunkSplit(input, "beforeafter");
        }

        @Test
        @DisplayName("suppressed fence line suffix is partition invariant")
        void suppressed_fence_line_suffix_is_partition_invariant() {
            String fence = "```json\n"
                    + "{\"name\":\"talos.read_file\",\"arguments\":{}}"
                    + "```";
            for (String lineBreak : List.of(
                    "\r\n", "\n", "\r", "\u000B", "\f",
                    "\u0085", "\u2028", "\u2029")) {
                String input = "before" + fence + " \t" + lineBreak + "after";

                assertEveryTwoChunkSplit(input, "beforeafter");
                assertOneCharacterDelivery(input, "beforeafter");
            }
        }

        @Test
        @DisplayName("ordinary unfinished generic code fence remains visible")
        void ordinary_unfinished_generic_fence_visible() {
            String input = "before```\nconst answer = { value: true };";
            assertEquals(input, joined(f -> f.accept(input)));
        }

        @Test
        @DisplayName("JSON fence streamed one character at a time is suppressed")
        void json_fence_char_by_char() {
            String input = "```json\n\n```";
            String result = joined(f -> {
                for (char c : input.toCharArray()) {
                    f.accept(String.valueOf(c));
                }
            });
            assertEquals("", result);
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

    // ── Bare JSON tool call suppression ────────────────────────────────

    @Nested
    @DisplayName("Bare JSON tool call suppression")
    class BareJsonSuppression {

        @Test
        @DisplayName("bare standalone JSON tool call is suppressed")
        void bare_json_tool_call_suppressed() {
            String input = """
                    {"name": "talos.read_file", "arguments": {"path": "index.html"}}
                    """;
            String result = joined(f -> f.accept(input));
            assertEquals("\n", result);
        }

        @Test
        @DisplayName("prose around bare JSON tool call is preserved")
        void prose_around_bare_json_is_preserved() {
            String result = joined(f -> f.accept(
                    "Let me check.\n"
                            + "{\"name\": \"talos.read_file\", \"parameters\": {\"path\": \"index.html\"}}\n"
                            + "Done."));
            assertEquals("Let me check.\n\nDone.", result);
        }

        @Test
        @DisplayName("speculative prose before bare JSON tool call is suppressed")
        void speculative_prose_before_bare_json_tool_call_is_suppressed() {
            String result = joined(f -> f.accept(
                    "Assume the relevant section looks like this:\n"
                            + "{\"name\": \"talos.read_file\", \"parameters\": {\"path\": \"index.html\"}}\n"
                            + "Done."));
            assertFalse(result.contains("Assume the relevant"));
            assertEquals("\nDone.", result);
        }

        @Test
        @DisplayName("chunked multiline bare JSON tool call is suppressed")
        void chunked_multiline_bare_json_suppressed() {
            String result = joined(f -> {
                f.accept("Before\n{\n  \"name\": ");
                f.accept("\"talos.grep\",\n  \"arguments\": {\n");
                f.accept("    \"pattern\": \"cta-button\",\n    \"glob\": \"*.html\"\n  }\n}");
                f.accept("\nAfter");
            });
            assertFalse(result.contains("talos.grep"));
            assertEquals("Before\n\nAfter", result);
        }

        @Test
        @DisplayName("adjacent bare JSON tool calls are suppressed")
        void adjacent_bare_json_tool_calls_suppressed() {
            String result = joined(f -> f.accept(
                    "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}"
                            + "{\"tool_name\":\"talos.grep\",\"params\":{\"pattern\":\"cta\"}}"
                            + "final"));
            assertEquals("final", result);
        }

        @Test
        @DisplayName("canonical leading mixed JSON values cannot expose a later proposal")
        void leading_mixed_json_values_are_partition_invariant() {
            String leading = " \t\u2003";
            String ordinary = "{\"label\":\"ordinary\"}";
            String intermediate = "[1]true\"value\"null";
            String tool = "{\"name\":\"talos.read_file\","
                    + "\"arguments\":{\"path\":\"README.md\"}}";
            String source = leading + ordinary + intermediate + tool;
            String expected = leading + ordinary + intermediate;

            ToolProtocolText.JsonProposalSelection selection =
                    ToolProtocolText.selectExecutableJsonProposals(source);
            assertEquals(1, selection.executionOrder().size());
            assertEquals(tool, selection.executionOrder().getFirst().rootFrom(source));

            assertEveryTwoChunkSplit(source, expected);
            assertOneCharacterDelivery(source, expected);
            for (int prefixLength = 1; prefixLength < tool.length(); prefixLength++) {
                String prefix = leading + ordinary + intermediate
                        + tool.substring(0, prefixLength);
                assertEquals(expected, joined(filter -> filter.accept(prefix)),
                        "prefix=" + prefixLength);
            }
        }

        @Test
        @DisplayName("non-JSON termination cannot promote a later inline proposal")
        void terminated_leading_sequence_keeps_inline_proposal_visible() {
            String foreign = "{\"name\":\"foreign.read_file\"}";
            String tool = "{\"name\":\"talos.read_file\","
                    + "\"arguments\":{\"path\":\"README.md\"}}";
            String source = foreign + "x" + tool;

            assertTrue(ToolProtocolText
                    .selectExecutableJsonProposals(source)
                    .executionOrder()
                    .isEmpty());
            assertEveryTwoChunkSplit(source, source);
            assertOneCharacterDelivery(source, source);
        }

        @Test
        @DisplayName("bare JSON tool call with braces inside string is suppressed")
        void bare_json_with_braces_in_string_suppressed() {
            String result = joined(f -> f.accept(
                    "{\"name\":\"talos.edit_file\",\"parameters\":{\"path\":\"style.css\","
                            + "\"old_string\":\".hero { color: red; }\","
                            + "\"new_string\":\".hero { color: blue; }\"}}"
                            + "after"));
            assertEquals("after", result);
        }

        @Test
        @DisplayName("later executable object cannot poison an earlier foreign object")
        void later_executable_object_does_not_poison_foreign_object_across_splits() {
            String foreign = "{\"arguments\":{},\"name\":\"foreign.read_file\"}";
            String tool = "{\"arguments\":{},\"name\":\"talos.read_file\"}";
            String input = "before " + foreign + " between " + tool + " after";
            assertEveryTwoChunkSplit(input, "before " + foreign + " between  after");
        }

        @Test
        @DisplayName("apostrophe standalone pseudo-JSON remains visible before Talos proposal")
        void apostrophe_standalone_pseudo_json_then_talos_proposal_complete() {
            String ordinary = "{\"message\": 'don\'t stop'}";
            String tool = "{\"arguments\":{},\"name\":\"talos.read_file\"}";
            String input = "before " + ordinary + " between " + tool + " after";
            assertEquals("before " + ordinary + " between  after",
                    joined(filter -> filter.accept(input)));
        }

        @Test
        @DisplayName("apostrophe standalone pseudo-JSON is partition invariant")
        void apostrophe_standalone_pseudo_json_then_talos_proposal_every_split() {
            String ordinary = "{\"message\": 'don\'t stop'}";
            String tool = "{\"arguments\":{},\"name\":\"talos.read_file\"}";
            String input = "before " + ordinary + " between " + tool + " after";
            assertEveryTwoChunkSplit(input, "before " + ordinary + " between  after");
        }

        @Test
        @DisplayName("apostrophe standalone pseudo-JSON survives one-character delivery")
        void apostrophe_standalone_pseudo_json_then_talos_proposal_one_character() {
            String ordinary = "{\"message\": 'don\'t stop'}";
            String tool = "{\"arguments\":{},\"name\":\"talos.read_file\"}";
            String input = "before " + ordinary + " between " + tool + " after";
            assertOneCharacterDelivery(input, "before " + ordinary + " between  after");
        }

        @Test
        @DisplayName("disproved object prefix cannot expose a later Talos proposal")
        void disproved_object_prefix_then_talos_proposal_is_partition_invariant() {
            String tool = "{\"arguments\":{},\"name\":\"talos.read_file\"}";
            for (String ordinary : List.of("{not json}", "{1}", "{[ordinary]}")) {
                String input = "before\n" + ordinary + "\n" + tool + "\nafter";
                String expected = "before\n" + ordinary + "\n\nafter";

                assertEveryTwoChunkSplit(input, expected);
                assertOneCharacterDelivery(input, expected);
            }
        }

        @Test
        @DisplayName("disproved object prefix reprocesses a protocol-start boundary")
        void disproved_object_prefix_reprocesses_protocol_start_boundary() {
            String root = "{\"arguments\":{},\"name\":\"talos.read_file\"}";
            for (List<String> protocolAndExpected : List.of(
                    List.of(root, "before\n{\n\nafter"),
                    List.of("```json\n" + root + "\n```", "before\n{\nafter"),
                    List.of("<tool_call>" + root + "</tool_call>",
                            "before\n{\n\nafter"))) {
                String input = "before\n{\n" + protocolAndExpected.get(0)
                        + "\nafter";

                assertEveryTwoChunkSplit(input, protocolAndExpected.get(1));
                assertOneCharacterDelivery(input, protocolAndExpected.get(1));
            }
        }

        @Test
        @DisplayName("disproved inline object prefix cannot promote a Talos proposal")
        void disproved_inline_object_prefix_cannot_promote_talos_proposal() {
            String root = "{\"arguments\":{},\"name\":\"talos.read_file\"}";
            String input = "{" + root;

            assertEveryTwoChunkSplit(input, input);
            assertOneCharacterDelivery(input, input);
        }

        @Test
        @DisplayName("non-boundary inline proposal remains prose across every split")
        void non_boundary_inline_proposal_is_partition_invariant() {
            String root = "{\"arguments\":{},\"name\":\"talos.read_file\"}";
            String input = "before(" + root + ")after";

            assertEveryTwoChunkSplit(input, input);
            assertOneCharacterDelivery(input, input);
        }

        @Test
        @DisplayName("unresolved bare proposal never leaks at the provider ceiling")
        void unresolved_bare_proposal_never_leaks_at_provider_ceiling() {
            String result = joined(filter -> {
                filter.accept("Before\n{\"name\":\"talos.write_file\","
                        + "\"arguments\":{\"content\":\""
                        + "A".repeat(10 * 1024 * 1024));
                filter.accept("\"}}After");
            });
            assertEquals("Before\nAfter", result);
        }

        @Test
        @DisplayName("late tool identity streamed one character at a time is suppressed")
        void late_tool_identity_one_character_stream_is_suppressed() {
            String input = "Before\n{\"arguments\":{\"content\":\""
                    + "A".repeat(256 * 1024)
                    + "\"},\"name\":\"talos.write_file\"}After";
            String result = joined(filter -> {
                for (int index = 0; index < input.length(); index++) {
                    filter.accept(input.substring(index, index + 1));
                }
            });
            assertEquals("Before\nAfter", result);
        }

        @Test
        @DisplayName("complete ordinary and foreign objects above the former bound remain visible")
        void complete_non_tool_objects_above_former_bound_remain_visible() {
            String content = "A".repeat(2 * 1024 * 1024 + 257);
            String ordinary = "{\"content\":\"" + content + "\"}";
            String foreign = "{\"content\":\"" + content
                    + "\",\"name\":\"foreign.write_file\"}";
            assertEquals(ordinary, joined(filter -> filter.accept(ordinary)));
            assertEquals(foreign, joined(filter -> filter.accept(foreign)));
        }

        @Test
        @DisplayName("late identity after the provider ceiling fails closed")
        void late_tool_identity_after_provider_ceiling_fails_closed() {
            String result = joined(filter -> {
                filter.accept("Before\n{\"arguments\":{\"content\":\""
                        + "A".repeat(10 * 1024 * 1024));
                filter.accept("\"},\"name\":\"talos.write_file\"}After");
            });
            assertEquals("Before\nAfter", result);
        }

        @Test
        @DisplayName("malformed bare Talos protocol JSON is suppressed")
        void malformed_bare_talos_protocol_json_is_suppressed() {
            String result = joined(f -> f.accept(
                    "{\n"
                            + "  \"name\": \"talos.edit_file\",\n"
                            + "  \"arguments\": {\n"
                            + "    \"path\": \"index.html\",\n"
                            + "    \"old_string\": '<div class=\"hero-content\">',\n"
                            + "    \"new_string\": '<div class=\"hero-content cta-button\">'\n"
                            + "  }\n"
                            + "}after"));
            assertEquals("after", result);
        }

        @Test
        @DisplayName("non-tool JSON passes through unchanged")
        void non_tool_json_passes_through() {
            String input = "Example: {\"name\": \"ordinary\", \"arguments\": {\"path\": \"x\"}} done";
            String result = joined(f -> f.accept(input));
            assertEquals(input, result);
        }

        @Test
        @DisplayName("ordinary JSON object split across chunks passes through")
        void chunked_non_tool_json_passes_through() {
            String result = joined(f -> {
                f.accept("Data ");
                f.accept("{\"key\": ");
                f.accept("\"value\", \"count\": 2}");
                f.accept(" end");
            });
            assertEquals("Data {\"key\": \"value\", \"count\": 2} end", result);
        }

        @Test
        @DisplayName("CSS braces are not mistaken for bare JSON")
        void css_braces_pass_through() {
            String result = joined(f -> {
                f.accept("Use body {");
                f.accept(" color: red; } here.");
            });
            assertEquals("Use body { color: red; } here.", result);
        }
    }

    @Nested
    @DisplayName("Malformed protocol array suppression")
    class MalformedProtocolArraySuppression {

        @Test
        @DisplayName("observed malformed empty protocol array is suppressed")
        void malformed_empty_protocol_array_suppressed() {
            String input = """
                    [
                        ,

                    ]
                    """;
            assertEveryTwoChunkSplit(input, "");
            assertOneCharacterDelivery(input, "");
        }

        @Test
        @DisplayName("malformed protocol array streamed one character at a time is suppressed")
        void malformed_protocol_array_char_by_char_suppressed() {
            String input = "[\n  ,\n]";
            String result = joined(f -> {
                for (char c : input.toCharArray()) {
                    f.accept(String.valueOf(c));
                }
            });
            assertEquals("", result);
        }

        @Test
        @DisplayName("prose around malformed protocol array is preserved")
        void prose_around_malformed_protocol_array_preserved() {
            String input = "Before\n[\n,\n]\nAfter";
            String expected = "Before\n\nAfter";
            assertEveryTwoChunkSplit(input, expected);
            assertOneCharacterDelivery(input, expected);
        }

        @Test
        @DisplayName("ordinary JSON arrays pass through")
        void ordinary_json_arrays_pass_through() {
            String input = "Examples:\n[]\n[1, 2, 3]\n[{\"name\":\"ordinary\"}]";
            String result = joined(f -> f.accept(input));
            assertEquals(input, result);
        }

        @Test
        @DisplayName("malformed array mentioned inline as text passes through")
        void inline_malformed_array_example_passes_through() {
            String input = "Example JSON: [ , ] is invalid syntax.";
            assertEveryTwoChunkSplit(input, input);
            assertOneCharacterDelivery(input, input);
        }

        @Test
        @DisplayName("disproved array prefix cannot expose a later Talos proposal")
        void disproved_array_prefix_then_talos_proposal_is_partition_invariant() {
            String tool = "{\"arguments\":{},\"name\":\"talos.read_file\"}";
            for (String ordinary : List.of("[x]", "[]", "[1, 2, 3]")) {
                String input = "before\n" + ordinary + "\n" + tool + "\nafter";
                String expected = "before\n" + ordinary + "\n\nafter";

                assertEveryTwoChunkSplit(input, expected);
                assertOneCharacterDelivery(input, expected);
            }
        }

        @Test
        @DisplayName("disproved array prefix reprocesses a protocol-start boundary")
        void disproved_array_prefix_reprocesses_protocol_start_boundary() {
            String root = "{\"arguments\":{},\"name\":\"talos.read_file\"}";
            for (List<String> protocolAndExpected : List.of(
                    List.of(root, "before\n[\n\nafter"),
                    List.of("```json\n" + root + "\n```", "before\n[\nafter"),
                    List.of("<tool_call>" + root + "</tool_call>",
                            "before\n[\n\nafter"))) {
                String input = "before\n[\n" + protocolAndExpected.get(0)
                        + "\nafter";

                assertEveryTwoChunkSplit(input, protocolAndExpected.get(1));
                assertOneCharacterDelivery(input, protocolAndExpected.get(1));
            }
        }

        @Test
        @DisplayName("disproved inline array prefix cannot promote a Talos proposal")
        void disproved_inline_array_prefix_cannot_promote_talos_proposal() {
            String root = "{\"arguments\":{},\"name\":\"talos.read_file\"}";
            String input = "[" + root;

            assertEveryTwoChunkSplit(input, input);
            assertOneCharacterDelivery(input, input);
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
            // No closing ``` - flush should emit as regular content (not a complete tool call)
            filter.flush();
            String result = String.join("", chunks);
            assertTrue(result.contains("text"), "Text should be emitted");
            assertTrue(result.contains("just_data"), "Incomplete fence content should be emitted");
        }

        @Test
        @DisplayName("unclosed executable JSON fence is discarded on flush")
        void flush_discards_unclosed_executable_json_fence() {
            List<String> chunks = new ArrayList<>();
            ToolCallStreamFilter filter = new ToolCallStreamFilter(chunks::add);
            filter.accept("text ```json\n"
                    + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"secret.txt\"}}");
            filter.flush();
            assertEquals("text ", String.join("", chunks));
        }

        @Test
        @DisplayName("unclosed function_name fence is discarded across every split")
        void flush_discards_unclosed_function_name_fence_across_every_split() {
            String input = "text ```json\n"
                    + "{\"function_name\":\"talos.read_file\",\"arguments\":{\"path\":\"secret.txt\"}}";
            for (int split = 0; split <= input.length(); split++) {
                int boundary = split;
                String result = joined(filter -> {
                    filter.accept(input.substring(0, boundary));
                    filter.accept(input.substring(boundary));
                });
                assertEquals("text ", result, "split=" + split);
            }
        }

        @Test
        @DisplayName("unclosed custom and escaped Talos names are discarded")
        void flush_discards_unclosed_custom_and_escaped_talos_names() {
            for (String toolField : List.of(
                    "\"name\":\"talos.echo\"",
                    "\"name\":\"talos\\u002eread_file\"",
                    "\"tool\":\"tool_use:read_file\"",
                    "\"function_name\":\"read_file\"")) {
                String input = "text ```json\n{" + toolField + ",\"arguments\":{";
                assertEquals("text ", joined(filter -> filter.accept(input)), toolField);
            }
        }

        @Test
        @DisplayName("truncated Talos identity prefixes are discarded")
        void flush_discards_truncated_talos_identity_prefixes() {
            for (String input : List.of(
                    "text ```json\n{\"function_name\":\"talos.write_",
                    "text {\"function_name\":\"talos.write_")) {
                assertEquals("text ", joined(filter -> filter.accept(input)), input);
            }
        }

        @Test
        @DisplayName("closed truncated identity is suppressed and following prose survives")
        void closed_truncated_identity_suppressed_with_following_prose() {
            String input = "before```json\n{\"function_name\":\"talos.write_```after";
            assertEquals("beforeafter", joined(filter -> filter.accept(input)));
        }

        @Test
        @DisplayName("truncated identity cannot consume an adjacent fence opening")
        void truncated_identity_before_shared_delimiter_is_partition_invariant() {
            String read = "{\"name\":\"talos.read_file\",\"arguments\":{}}";
            String input = "before```json\n"
                    + "{\"function_name\":\"talos.write_"
                    + "``````json\n" + read + "\n```after";

            assertEveryTwoChunkSplit(input, "beforeafter");
            assertOneCharacterDelivery(input, "beforeafter");
        }

        @Test
        @DisplayName("truncated foreign identity remains visible")
        void flush_emits_truncated_foreign_identity() {
            String input = "text ```json\n{\"function_name\":\"foreign.write_";
            assertEquals(input, joined(filter -> filter.accept(input)));
        }

        @Test
        @DisplayName("unclosed malformed foreign JSON fence remains visible")
        void flush_emits_unclosed_malformed_foreign_json_fence() {
            String input = "text ```json\n"
                    + "{\"function_name\":\"foreign.read_file\",\"arguments\":{\"path\": }";
            assertEquals(input, joined(filter -> filter.accept(input)));
        }

        @Test
        @DisplayName("blank incomplete JSON fence is discarded on flush")
        void flush_discards_blank_incomplete_json_fence() {
            List<String> chunks = new ArrayList<>();
            ToolCallStreamFilter filter = new ToolCallStreamFilter(chunks::add);
            filter.accept("```json\n");
            filter.flush();
            assertEquals("", String.join("", chunks));
        }
    }

    // ── Flush with bare JSON ────────────────────────────────────────────

    @Nested
    @DisplayName("Flush behavior with bare JSON")
    class FlushBareJson {

        @Test
        @DisplayName("incomplete bare tool-call JSON is discarded on flush")
        void flush_discards_incomplete_bare_tool_json() {
            List<String> chunks = new ArrayList<>();
            ToolCallStreamFilter filter = new ToolCallStreamFilter(chunks::add);
            filter.accept("text {\"name\": \"talos.read_file\", \"arguments\": {\"path\": ");
            filter.flush();
            assertEquals("text ", String.join("", chunks));
        }

        @Test
        @DisplayName("incomplete ordinary bare JSON is emitted on flush")
        void flush_emits_incomplete_ordinary_json() {
            List<String> chunks = new ArrayList<>();
            ToolCallStreamFilter filter = new ToolCallStreamFilter(chunks::add);
            filter.accept("text {\"name\": \"ordinary\", \"arguments\": {\"path\": ");
            filter.flush();
            assertEquals("text {\"name\": \"ordinary\", \"arguments\": {\"path\": ",
                    String.join("", chunks));
        }
    }
}

