package dev.talos.runtime;

import dev.talos.core.util.Sanitize;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallParserCanonicalJsonContractTest {
    @Test
    void parserConsumesEscapedNameFromCanonicalSpan() {
        String response = """
                ```json
                {"name":"talos\\u002eread_file","arguments":{"path":"README.md"}}
                ```
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);

        assertEquals(1, calls.size());
        assertEquals("talos.read_file", calls.get(0).toolName());
        assertEquals("README.md", calls.get(0).param("path"));
        assertTrue(ToolCallParser.containsToolCalls(response));
    }

    @Test
    void parserUsesPinnedFirstRootForFenceAndDirectPayload() throws Exception {
        String payload =
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}";
        for (String trailing : List.of(
                " true",
                " <think>hidden</think>",
                " <script>alert(1)</script>",
                " {\"name\":\"talos.list_dir\"}")) {
            String response = "```json\n" + payload + trailing + "\n```";
            List<ToolCall> calls = ToolCallParser.parse(response);
            assertEquals(1, calls.size(), trailing);
            assertEquals("talos.read_file", calls.getFirst().toolName(), trailing);
            assertEquals("README.md", calls.getFirst().param("path"), trailing);

            ToolCall direct = ToolCallParser.parseJson(payload + trailing);
            assertEquals("talos.read_file", direct.toolName(), trailing);
            assertEquals("README.md", direct.param("path"), trailing);
        }

        String ordinaryFirst = "{\"name\":\"ordinary\"} " + payload;
        assertEquals("ordinary", ToolCallParser.parseJson(ordinaryFirst).toolName());
        assertTrue(ToolCallParser.parse(
                "```json\n" + ordinaryFirst + "\n```").isEmpty());
    }

    @Test
    void parserNeverReinterpretsJsonInsideUnclosedFenceAsBare() {
        String response = """
                ```json
                {"name":"talos.read_file","arguments":{"path":"README.md"}}
                """;

        assertTrue(ToolCallParser.parse(response).isEmpty());
    }

    @Test
    void fencedJsonRetainsPriorityButLeadingAdjacentJsonRemainsCompatible() {
        String standalone =
                "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}";
        String fenced = """
                ```json
                {"name":"talos.read_file","arguments":{"path":"README.md"}}
                ```
                """;

        List<ToolCall> calls = ToolCallParser.parse(standalone + "\n" + fenced);

        assertEquals(2, calls.size());
        assertEquals("talos.read_file", calls.get(0).toolName());
        assertEquals("talos.list_dir", calls.get(1).toolName());
    }

    @Test
    void ordinaryLeadingJsonStillAdvancesTheAdjacentCompatibilityPass() {
        String ordinary = "{\"label\":\"ordinary\"}";
        String standalone =
                "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}";
        String fenced = "```json\n"
                + "{\"name\":\"talos.read_file\","
                + "\"arguments\":{\"path\":\"README.md\"}}\n```";

        List<ToolCall> calls = ToolCallParser.parse(
                ordinary + "\n" + standalone + "\n" + fenced);

        assertEquals(List.of("talos.read_file", "talos.list_dir"),
                calls.stream().map(ToolCall::toolName).toList());
    }

    @Test
    void distinctArgumentWhitespaceIsNeverCollapsedByPayloadDeduplication() {
        String first =
                "{\"name\":\"write_file\",\"arguments\":{\"path\":\"a.txt\","
                        + "\"content\":\"x y\"}}";
        String second =
                "{\"name\":\"write_file\",\"arguments\":{\"path\":\"a.txt\","
                        + "\"content\":\"x  y\"}}";

        List<ToolCall> calls = ToolCallParser.parse(first + "\n" + second);

        assertEquals(2, calls.size());
        assertEquals("x y", calls.get(0).param("content"));
        assertEquals("x  y", calls.get(1).param("content"));
    }

    @Test
    void parserKeepsPinnedFirstNameKeyAndFirstWrapperPrecedence() throws Exception {
        for (String payload : List.of(
                "{\"name\":\"ordinary\",\"tool\":\"talos.read_file\"}",
                "{\"tool_call\":{\"name\":\"ordinary\"},"
                        + "\"function_call\":{\"name\":\"talos.read_file\"}}",
                "{\"name\":\"talos.read_file\","
                        + "\"tool_call\":{\"name\":\"ordinary\"}}")) {
            assertTrue(ToolCallParser.parse(
                    "```json\n" + payload + "\n```").isEmpty(), payload);
        }

        for (String payload : List.of(
                "{\"name\":\"talos.read_file\",\"tool\":\"ordinary\"}",
                "{\"tool_call\":{\"name\":\"talos.read_file\"},"
                        + "\"function_call\":{\"name\":\"ordinary\"}}",
                "{\"name\":\"ordinary\","
                        + "\"tool_call\":{\"name\":\"talos.read_file\"}}")) {
            List<ToolCall> calls = ToolCallParser.parse(
                    "```json\n" + payload + "\n```");
            assertEquals(1, calls.size(), payload);
            assertEquals("talos.read_file", calls.getFirst().toolName(), payload);
            assertEquals("talos.read_file",
                    ToolCallParser.parseJson(payload).toolName(), payload);
        }
    }

    @Test
    void sanitizationPreservesSelectedRootButNotUnsafeFenceTail() {
        String payload =
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}";
        for (String trailing : List.of(
                "\n<think>hidden</think>",
                "\n<script>alert(1)</script>",
                "\n{\"name\":\"talos.write_file\",\"arguments\":{"
                        + "\"path\":\"x.js\","
                        + "\"content\":\"<script>alert(1)</script>\"}}")) {
            String raw = "```json\n" + payload + trailing + "\n```";
            assertEquals(1, ToolCallParser.parse(raw).size(), "raw " + trailing);

            String sanitized = Sanitize.sanitizeForOutputPreservingToolCalls(raw);

            assertNotEquals(raw, sanitized, trailing);
            assertTrue(sanitized.contains(payload), sanitized);
            assertFalse(sanitized.contains("<think>"), sanitized);
            assertFalse(sanitized.contains("hidden</think>"), sanitized);
            assertFalse(sanitized.contains("<script>"), sanitized);
            assertFalse(sanitized.contains("alert(1)"), sanitized);
            List<ToolCall> calls = ToolCallParser.parse(sanitized);
            assertEquals(1, calls.size(), sanitized);
            assertEquals("talos.read_file", calls.getFirst().toolName());
            assertEquals("README.md", calls.getFirst().param("path"));
        }
    }

    @Test
    void deprecatedXmlRetainsItsWhitespaceNormalizedDeduplicationContract() {
        String first = "<tool_call>{\"name\":\"talos.write_file\","
                + "\"arguments\":{\"path\":\"x\",\"content\":\"A  B\"}}"
                + "</tool_call>";
        String second = "<tool_call>{\"name\":\"talos.write_file\","
                + "\"arguments\":{\"path\":\"x\",\"content\":\"A B\"}}"
                + "</tool_call>";

        List<ToolCall> calls = ToolCallParser.parse(first + second);

        assertEquals(1, calls.size());
        assertEquals("A  B", calls.getFirst().param("content"));
    }

    @Test
    void deprecatedXmlDeduplicatesAgainstCanonicalJsonUsingLegacyIdentity() {
        String root = "{\"name\":\"talos.write_file\","
                + "\"arguments\":{\"path\":\"x\",\"content\":\"A  B\"}}";
        String json = "```json\n" + root + "\n```";
        String xml = "<tool_call>{\"name\":\"talos.write_file\","
                + "\"arguments\":{\"path\":\"x\",\"content\":\"A B\"}}"
                + "</tool_call>";

        List<ToolCall> calls = ToolCallParser.parse(json + xml);

        assertEquals(1, calls.size());
        assertEquals("A  B", calls.getFirst().param("content"));
    }

    @Test
    void leadingAdjacentAliasDoesNotGainLegacyXmlDedupIdentity() {
        String adjacent = "{\"name\":\"write_file\","
                + "\"arguments\":{\"path\":\"x\",\"content\":\"A  B\"}}";
        String xml = "<tool_call>{\"name\":\"write_file\","
                + "\"arguments\":{\"path\":\"x\",\"content\":\"A B\"}}"
                + "</tool_call>";

        List<ToolCall> calls = ToolCallParser.parse(adjacent + xml);

        assertEquals(2, calls.size(),
                "the predecessor adjacent-JSON pass did not seed XML identity");
        assertEquals("A  B", calls.get(0).param("content"));
        assertEquals("A B", calls.get(1).param("content"));
    }

    @Test
    void fencedTailRemainsPartOfThePredecessorLegacyXmlIdentity() {
        String root = "{\"name\":\"talos.write_file\","
                + "\"arguments\":{\"path\":\"x\",\"content\":\"A  B\"}}";
        String fenced = "```json\n" + root + "\ntrailing explanation\n```";
        String xml = "<tool_call>{\"name\":\"talos.write_file\","
                + "\"arguments\":{\"path\":\"x\",\"content\":\"A B\"}}"
                + "</tool_call>";

        List<ToolCall> calls = ToolCallParser.parse(fenced + xml);

        assertEquals(2, calls.size(),
                "the predecessor fence pass seeded its whole captured payload");
        assertEquals("A  B", calls.get(0).param("content"));
        assertEquals("A B", calls.get(1).param("content"));
    }

    @Test
    void escapedLegacyXmlPayloadRetainsPredecessorFenceDeduplication() {
        String root = "{\"name\":\"talos\\u002eread_file\","
                + "\"arguments\":{\"path\":\"x\"}}";
        String fenced = "```json\n" + root + "\n```";
        String xml = "<tool_call>" + root + "</tool_call>";

        List<ToolCall> calls = ToolCallParser.parse(fenced + xml);

        assertEquals(1, calls.size());
        assertEquals("talos.read_file", calls.getFirst().toolName());
    }
}
