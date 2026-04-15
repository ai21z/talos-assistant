package dev.talos.core.util;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Sanitize#sanitizeForOutputPreservingToolCalls} and
 * {@link Sanitize#sanitizeMessageContent} — verifying that HTML tags inside
 * tool_call JSON parameters are NOT stripped.
 *
 * <p>Regression tests for the bug where {@code SUS_HTML} pattern stripped
 * {@code <script>}, {@code <style>}, etc. from tool_call JSON values,
 * making {@code old_string} and {@code new_string} identical and causing
 * the no-op edit rejection loop.
 */
class SanitizeToolCallPreservationTest {

    // ── Realistic payloads ────────────────────────────────────────────────

    /** The exact scenario from the bug report: edit_file adding a script tag. */
    private static final String TOOL_CALL_WITH_SCRIPT =
            "<tool_call>\n" +
            "{\"name\":\"talos.edit_file\",\"parameters\":{\"path\":\"index.html\"," +
            "\"old_string\":\"</body>\",\"new_string\":\"<script src=\\\"script.js\\\"></script>\\n</body>\"}}\n" +
            "</tool_call>";

    /** Tool call with a <style> tag in the new_string. */
    private static final String TOOL_CALL_WITH_STYLE =
            "<tool_call>\n" +
            "{\"name\":\"talos.write_file\",\"parameters\":{\"path\":\"page.html\"," +
            "\"content\":\"<html><head><style>body{color:red}</style></head><body></body></html>\"}}\n" +
            "</tool_call>";

    /** Prose with malicious script tag (should still be stripped). */
    private static final String PROSE_WITH_SCRIPT =
            "Here is an example: <script>alert('xss')</script> injected.";

    // ── sanitizeForOutputPreservingToolCalls ──────────────────────────────

    @Nested
    class PreservingToolCalls {

        @Test
        void preserves_script_tag_inside_tool_call_json() {
            String result = Sanitize.sanitizeForOutputPreservingToolCalls(TOOL_CALL_WITH_SCRIPT);
            assertTrue(result.contains("<script src=\\\"script.js\\\"></script>"),
                    "Script tag inside tool_call JSON must be preserved. Got: " + result);
        }

        @Test
        void preserves_style_tag_inside_tool_call_json() {
            String result = Sanitize.sanitizeForOutputPreservingToolCalls(TOOL_CALL_WITH_STYLE);
            assertTrue(result.contains("<style>body{color:red}</style>"),
                    "Style tag inside tool_call JSON must be preserved. Got: " + result);
        }

        @Test
        void strips_script_tag_from_prose_outside_tool_call() {
            String input = PROSE_WITH_SCRIPT + "\n" + TOOL_CALL_WITH_SCRIPT;
            String result = Sanitize.sanitizeForOutputPreservingToolCalls(input);

            // Prose script tag is stripped
            assertFalse(result.contains("alert('xss')"),
                    "Script tag in prose must be stripped");

            // Tool_call script tag is preserved
            assertTrue(result.contains("<script src=\\\"script.js\\\"></script>"),
                    "Script tag inside tool_call must be preserved");
        }

        @Test
        void strips_script_tag_when_no_tool_call_blocks() {
            String result = Sanitize.sanitizeForOutputPreservingToolCalls(PROSE_WITH_SCRIPT);
            assertFalse(result.contains("<script>"),
                    "Without tool_call blocks, script tags should be stripped");
        }

        @Test
        void handles_multiple_tool_call_blocks() {
            String input = "Some text\n" + TOOL_CALL_WITH_SCRIPT + "\nmiddle text\n" + TOOL_CALL_WITH_STYLE + "\nend text";
            String result = Sanitize.sanitizeForOutputPreservingToolCalls(input);

            assertTrue(result.contains("<script src=\\\"script.js\\\"></script>"));
            assertTrue(result.contains("<style>body{color:red}</style>"));
            assertTrue(result.contains("Some text"));
            assertTrue(result.contains("middle text"));
            assertTrue(result.contains("end text"));
        }

        @Test
        void handles_null_and_empty() {
            assertEquals("", Sanitize.sanitizeForOutputPreservingToolCalls(null));
            assertEquals("", Sanitize.sanitizeForOutputPreservingToolCalls(""));
        }

        @Test
        void strips_think_blocks() {
            String input = "<think>internal reasoning</think>" + TOOL_CALL_WITH_SCRIPT;
            String result = Sanitize.sanitizeForOutputPreservingToolCalls(input);
            assertFalse(result.contains("internal reasoning"));
            assertTrue(result.contains("<script src=\\\"script.js\\\"></script>"));
        }

        @Test
        void strips_control_characters() {
            String input = "hello\u0000world\n" + TOOL_CALL_WITH_SCRIPT;
            String result = Sanitize.sanitizeForOutputPreservingToolCalls(input);
            assertFalse(result.contains("\u0000"));
            assertTrue(result.contains("helloworld"));
        }
    }

    // ── sanitizeMessageContent ───────────────────────────────────────────

    @Nested
    class MessageContent {

        @Test
        void preserves_html_in_file_content() {
            String fileContent = "<html><head><script>var x = 1;</script></head><body></body></html>";
            String result = Sanitize.sanitizeMessageContent(fileContent);
            assertEquals(fileContent, result, "HTML file content must be preserved in messages");
        }

        @Test
        void strips_control_characters() {
            String input = "clean\u0000text\u0007here";
            String result = Sanitize.sanitizeMessageContent(input);
            assertEquals("cleantexthere", result);
        }

        @Test
        void preserves_script_style_tags() {
            String input = "<script src=\"app.js\"></script><style>.btn{color:blue}</style>";
            String result = Sanitize.sanitizeMessageContent(input);
            assertEquals(input, result, "Script and style tags must not be stripped from messages");
        }

        @Test
        void handles_null_and_empty() {
            assertEquals("", Sanitize.sanitizeMessageContent(null));
            assertEquals("", Sanitize.sanitizeMessageContent(""));
        }
    }

    // ── Regression: the exact bug scenario ───────────────────────────────

    @Nested
    class RegressionBug {

        /**
         * Simulates the exact bug: model wants to add {@code <script src="script.js"></script>}
         * before {@code </body>}. The old SUS_HTML stripping made old_string == new_string.
         */
        @Test
        void edit_file_script_tag_not_corrupted_by_sanitization() {
            // XML-format tool_call block (deprecated compatibility — native path is primary)
            String toolCallXml =
                    "<tool_call>\n" +
                    "{\"name\":\"talos.edit_file\",\"parameters\":{" +
                    "\"path\":\"index.html\"," +
                    "\"old_string\":\"</body>\"," +
                    "\"new_string\":\"<script src=\\\"script.js\\\"></script></body>\"}}\n" +
                    "</tool_call>";

            String sanitized = Sanitize.sanitizeForOutputPreservingToolCalls(toolCallXml);

            // The JSON inside the tool_call block must be intact
            assertTrue(sanitized.contains("\"new_string\":\"<script src=\\\"script.js\\\"></script></body>\""),
                    "new_string must still contain <script> tag after sanitization. Got: " + sanitized);
            assertTrue(sanitized.contains("\"old_string\":\"</body>\""),
                    "old_string must be unchanged. Got: " + sanitized);
        }

        /**
         * Verifies that the old sanitizeForOutput WOULD have corrupted the same input
         * (confirms the bug existed and our fix is meaningful).
         */
        @Test
        void old_sanitizeForOutput_would_corrupt_script_tag() {
            String toolCallXml =
                    "<tool_call>\n" +
                    "{\"name\":\"talos.edit_file\",\"parameters\":{" +
                    "\"path\":\"index.html\"," +
                    "\"old_string\":\"</body>\"," +
                    "\"new_string\":\"<script src=\\\"script.js\\\"></script></body>\"}}\n" +
                    "</tool_call>";

            // The old method strips HTML globally — this SHOULD corrupt the JSON
            String corrupted = Sanitize.sanitizeForOutput(toolCallXml);
            assertFalse(corrupted.contains("<script src=\\\"script.js\\\"></script>"),
                    "sanitizeForOutput should strip <script> (proving the bug). Got: " + corrupted);
        }
    }
}

