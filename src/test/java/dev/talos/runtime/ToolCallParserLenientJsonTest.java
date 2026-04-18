package dev.talos.runtime;

import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the lenient-JSON behavior of {@link ToolCallParser} for payloads that
 * vanilla Jackson rejects.
 *
 * <p><b>Why these exist:</b> in a real transcript (Apr 2026, gemma4 +
 * qwen2.5-coder:14b), the text-fallback parser dropped three consecutive
 * valid {@code talos.edit_file} tool calls because the payload contained
 * literal LF characters inside a JSON string value
 * ({@code "Unrecognized character escape (CTRL-CHAR, code 10)"}). The
 * parser was switched to a {@link com.fasterxml.jackson.core.json.JsonReadFeature}-enabled
 * {@link com.fasterxml.jackson.databind.json.JsonMapper} that permits
 * unescaped control chars and backslash-escape of any character. These
 * tests ensure we never silently regress back to strict-RFC rejection.
 */
class ToolCallParserLenientJsonTest {

    @Test
    void parsesPayloadWithLiteralNewlineInsideStringValue() {
        // Literal \n (0x0A) inside the JSON string for "content".
        // Strict Jackson would throw; lenient mapper must accept it.
        String response = "```json\n"
                + "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"a.txt\",\"content\":\"line1\nline2\nline3\"}}\n"
                + "```";

        List<ToolCall> calls = ToolCallParser.parse(response);

        assertEquals(1, calls.size(), "Literal LF inside a JSON string must not drop the tool call");
        ToolCall c = calls.get(0);
        assertEquals("talos.write_file", c.toolName());
        assertEquals("a.txt", c.parameters().get("path"));
        assertTrue(c.parameters().get("content").contains("line2"),
                "Content field must preserve the multi-line value");
    }

    @Test
    void parsesPayloadWithBackslashEscapeOfNonStandardChar() {
        // Backslash-escape of a character that RFC-8259 does not allow
        // (here: \$). Many local code-tuned models emit this when mirroring
        // shell or template literals from their training data.
        String response = "```json\n"
                + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"cost_\\$100.md\"}}\n"
                + "```";

        List<ToolCall> calls = ToolCallParser.parse(response);

        assertEquals(1, calls.size(), "Non-standard backslash escape must not drop the tool call");
        assertEquals("talos.read_file", calls.get(0).toolName());
        // The parser accepts the escape; it is fine whether the parsed value
        // is "cost_$100.md" or "cost_\\$100.md" — we only pin non-rejection.
        assertNotNull(calls.get(0).parameters().get("path"));
    }

    @Test
    void parsesPayloadWithLiteralTabInsideStringValue() {
        // Literal HT (0x09) inside a JSON string value — same RFC-8259
        // category as LF; another common shape from code-tuned models.
        String response = "```json\n"
                + "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"indent.txt\",\"content\":\"col1\tcol2\"}}\n"
                + "```";

        List<ToolCall> calls = ToolCallParser.parse(response);

        assertEquals(1, calls.size(), "Literal TAB inside a JSON string must not drop the tool call");
        assertTrue(calls.get(0).parameters().get("content").contains("col2"));
    }
}




