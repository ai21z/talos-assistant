package dev.talos.engine.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OllamaEngine's native tool-calling bridge methods:
 * <ul>
 *   <li>{@code extractChatContentOrToolCalls} - /api/chat JSON response → text (no XML conversion)</li>
 *   <li>{@code convertToolSpecs} - ToolSpec list → Ollama native tool format</li>
 *   <li>{@code parseNativeToolCalls} - Ollama tool_calls JSON → NativeToolCall list</li>
 * </ul>
 *
 * <p>Both methods are package-private for testability.
 */
class OllamaToolCallBridgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private OllamaEngine engine;

    @BeforeEach
    void setUp() {
        // host/model don't matter - we only call package-private bridge methods
        engine = new OllamaEngine("http://localhost:11434", "test-model");
    }

    // ── extractChatContentOrToolCalls ─────────────────────────────────────

    @Nested
    class ExtractChatContentOrToolCalls {

        @Test
        void textOnly_returnsContent() {
            String json = """
                    {"message":{"role":"assistant","content":"Hello, how can I help?"},"done":true}
                    """;
            String result = engine.extractChatContentOrToolCalls(json);
            assertEquals("Hello, how can I help?", result);
        }

        @Test
        void nativeToolCalls_returnsTextOnly_noXml() {
            String json = """
                    {"message":{"role":"assistant","content":"Let me check.",
                    "tool_calls":[{"function":{"name":"talos.list_dir","arguments":{"path":"."}}}]},
                    "done":true}
                    """;
            String result = engine.extractChatContentOrToolCalls(json);

            // Must return only the text content
            assertEquals("Let me check.", result);

            // Must NOT contain any XML tags
            assertFalse(result.contains("<tool_call>"), "No XML tags should be present");
            assertFalse(result.contains("</tool_call>"), "No XML tags should be present");
        }

        @Test
        void nativeToolCalls_emptyText_returnsEmptyString() {
            String json = """
                    {"message":{"role":"assistant","content":"",
                    "tool_calls":[{"function":{"name":"talos.read_file","arguments":{"path":"x.txt"}}}]},
                    "done":true}
                    """;
            String result = engine.extractChatContentOrToolCalls(json);
            assertEquals("", result);
            assertFalse(result.contains("<tool_call>"));
        }

        @Test
        void multipleToolCalls_returnsTextOnly() {
            String json = """
                    {"message":{"role":"assistant","content":"I'll check both.",
                    "tool_calls":[
                      {"function":{"name":"talos.list_dir","arguments":{"path":"src"}}},
                      {"function":{"name":"talos.read_file","arguments":{"path":"README.md"}}}
                    ]},"done":true}
                    """;
            String result = engine.extractChatContentOrToolCalls(json);
            assertEquals("I'll check both.", result);
            assertFalse(result.contains("<tool_call>"));
            assertFalse(result.contains("talos.list_dir"));  // tool call details not in text
        }

        @Test
        void emptyToolCallsArray_returnsContent() {
            String json = """
                    {"message":{"role":"assistant","content":"No tools needed.","tool_calls":[]},"done":true}
                    """;
            String result = engine.extractChatContentOrToolCalls(json);
            assertEquals("No tools needed.", result);
        }

        @Test
        void missingMessageNode_returnsRawJson() {
            String json = """
                    {"some_other_format":"value"}
                    """;
            String result = engine.extractChatContentOrToolCalls(json);
            assertEquals(json.strip(), result.strip());
        }

        @Test
        void malformedJson_fallsBackToRegex() {
            // Invalid JSON but contains "content":"..." pattern
            String json = "not-json {\"content\":\"fallback text\"} end";
            String result = engine.extractChatContentOrToolCalls(json);
            assertEquals("fallback text", result);
        }
    }

    // ── convertToolSpecs ─────────────────────────────────────────────────

    @Nested
    class ConvertToolSpecs {

        @Test
        void nullSpecs_returnsEmptyList() {
            List<Map<String, Object>> result = engine.convertToolSpecs(null);
            assertTrue(result.isEmpty());
        }

        @Test
        void emptySpecs_returnsEmptyList() {
            List<Map<String, Object>> result = engine.convertToolSpecs(List.of());
            assertTrue(result.isEmpty());
        }

        @Test
        void singleToolSpec_convertedCorrectly() throws Exception {
            var spec = new ToolSpec("talos.list_dir", "List directory contents",
                    """
                    {"type":"object","properties":{
                      "path":{"type":"string","description":"Directory path"}
                    },"required":["path"]}""");

            List<Map<String, Object>> result = engine.convertToolSpecs(List.of(spec));

            assertEquals(1, result.size());
            Map<String, Object> tool = result.get(0);
            assertEquals("function", tool.get("type"));

            @SuppressWarnings("unchecked")
            Map<String, Object> fn = (Map<String, Object>) tool.get("function");
            assertEquals("talos.list_dir", fn.get("name"));
            assertEquals("List directory contents", fn.get("description"));

            // parameters should be a parsed JsonNode, not a string
            assertNotNull(fn.get("parameters"), "Should have parameters");
            assertFalse(fn.get("parameters") instanceof String,
                    "Parameters should be parsed, not raw string");
        }

        @Test
        void allSixTools_allConverted() {
            List<ToolSpec> specs = List.of(
                    new ToolSpec("talos.list_dir", "List directory contents", "{}"),
                    new ToolSpec("talos.read_file", "Read a file", "{}"),
                    new ToolSpec("talos.write_file", "Write a file", "{}"),
                    new ToolSpec("talos.grep", "Search for pattern", "{}"),
                    new ToolSpec("talos.shell", "Run shell command", "{}"),
                    new ToolSpec("talos.status", "Show project status", "{}")
            );

            List<Map<String, Object>> result = engine.convertToolSpecs(specs);

            assertEquals(6, result.size(), "All 6 tools should be converted");
            for (int i = 0; i < specs.size(); i++) {
                @SuppressWarnings("unchecked")
                var fn = (Map<String, Object>) result.get(i).get("function");
                assertEquals(specs.get(i).name(), fn.get("name"),
                        "Tool name mismatch at index " + i);
            }
        }

        @Test
        void nullSchema_producesEmptyObjectSchema() {
            var spec = new ToolSpec("talos.status", "Show status", null);

            List<Map<String, Object>> result = engine.convertToolSpecs(List.of(spec));

            @SuppressWarnings("unchecked")
            var fn = (Map<String, Object>) result.get(0).get("function");
            @SuppressWarnings("unchecked")
            var params = (Map<String, Object>) fn.get("parameters");

            assertEquals("object", params.get("type"), "Should default to object type");
            assertNotNull(params.get("properties"), "Should have empty properties");
        }

        @Test
        void blankSchema_producesEmptyObjectSchema() {
            var spec = new ToolSpec("talos.status", "Show status", "   ");

            List<Map<String, Object>> result = engine.convertToolSpecs(List.of(spec));

            @SuppressWarnings("unchecked")
            var fn = (Map<String, Object>) result.get(0).get("function");
            @SuppressWarnings("unchecked")
            var params = (Map<String, Object>) fn.get("parameters");

            assertEquals("object", params.get("type"));
        }

        @Test
        void malformedJsonSchema_fallsBackToEmptyObject() {
            var spec = new ToolSpec("talos.broken", "Broken schema", "not-valid-json{{{");

            List<Map<String, Object>> result = engine.convertToolSpecs(List.of(spec));

            // Should not throw - falls back gracefully
            assertEquals(1, result.size());
            @SuppressWarnings("unchecked")
            var fn = (Map<String, Object>) result.get(0).get("function");
            @SuppressWarnings("unchecked")
            var params = (Map<String, Object>) fn.get("parameters");
            assertEquals("object", params.get("type"), "Should fallback to empty object schema");
        }

        @Test
        void complexSchema_parsedAsObject() throws Exception {
            String schema = """
                    {
                      "type": "object",
                      "properties": {
                        "path": {"type": "string", "description": "File path"},
                        "recursive": {"type": "boolean", "description": "Recurse into subdirs"}
                      },
                      "required": ["path"]
                    }""";
            var spec = new ToolSpec("talos.list_dir", "List dir", schema);

            List<Map<String, Object>> result = engine.convertToolSpecs(List.of(spec));

            // Serialize back to JSON and verify structure
            String json = MAPPER.writeValueAsString(result.get(0));
            JsonNode root = MAPPER.readTree(json);
            JsonNode params = root.path("function").path("parameters");
            assertEquals("object", params.path("type").asText());
            assertTrue(params.path("properties").has("path"), "Should have path property");
            assertTrue(params.path("properties").has("recursive"), "Should have recursive property");
        }

        @Test
        void outputFormat_matchesOllamaExpectation() throws Exception {
            var spec = new ToolSpec("talos.read_file", "Read a file",
                    """
                    {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""");

            List<Map<String, Object>> result = engine.convertToolSpecs(List.of(spec));

            // Serialize to verify the overall shape
            String json = MAPPER.writeValueAsString(result);
            JsonNode arr = MAPPER.readTree(json);
            assertTrue(arr.isArray());
            assertEquals(1, arr.size());

            JsonNode tool = arr.get(0);
            assertEquals("function", tool.path("type").asText());
            assertTrue(tool.has("function"), "Must have 'function' key");
            assertTrue(tool.path("function").has("name"), "Function must have 'name'");
            assertTrue(tool.path("function").has("description"), "Function must have 'description'");
            assertTrue(tool.path("function").has("parameters"), "Function must have 'parameters'");
        }
    }

    // ── nativeToolCalling toggle ─────────────────────────────────────────

    @Nested
    class NativeToolCallingToggle {

        @Test
        void defaultConstructor_enablesNativeToolCalling() {
            // Default constructor should enable native tool calling (backwards-compatible)
            var defaultEngine = new OllamaEngine("http://localhost:11434", "test-model");
            // Can still call convertToolSpecs - toggle only affects request building
            var specs = List.of(new ToolSpec("talos.list_dir", "List dir", "{}"));
            assertFalse(defaultEngine.convertToolSpecs(specs).isEmpty(),
                    "Default engine should convert tool specs");
        }

        @Test
        void explicitTrue_enablesNativeToolCalling() {
            var enabledEngine = new OllamaEngine("http://localhost:11434", "test-model", true);
            var specs = List.of(new ToolSpec("talos.list_dir", "List dir", "{}"));
            assertFalse(enabledEngine.convertToolSpecs(specs).isEmpty());
        }

        @Test
        void explicitFalse_stillConvertsSpecs() {
            // convertToolSpecs itself doesn't check the toggle - the toggle is checked
            // at the chatViaMessages / chatStreamViaMessages level
            var disabledEngine = new OllamaEngine("http://localhost:11434", "test-model", false);
            var specs = List.of(new ToolSpec("talos.list_dir", "List dir", "{}"));
            assertFalse(disabledEngine.convertToolSpecs(specs).isEmpty(),
                    "convertToolSpecs is independent of toggle");
        }

        @Test
        void capabilities_reportNativeToolCalling() {
            var enabledEngine = new OllamaEngine("http://localhost:11434", "test-model", true);
            assertTrue(enabledEngine.caps().nativeTools(),
                    "Capabilities should report nativeTools=true when enabled");

            var disabledEngine = new OllamaEngine("http://localhost:11434", "test-model", false);
            assertFalse(disabledEngine.caps().nativeTools(),
                    "Capabilities should report nativeTools=false when disabled");
        }
    }

    // ── parseNativeToolCalls ──────────────────────────────────────────────

    @Nested
    class ParseNativeToolCalls {

        @Test
        void singleToolCall_parsedCorrectly() throws Exception {
            JsonNode toolCalls = MAPPER.readTree("""
                    [{"function":{"name":"talos.list_dir","arguments":{"path":"."}}}]
                    """);

            var result = engine.parseNativeToolCalls(toolCalls);

            assertEquals(1, result.size());
            assertEquals("call_0", result.get(0).id());
            assertEquals("talos.list_dir", result.get(0).name());
            assertEquals(".", result.get(0).arguments().get("path"));
        }

        @Test
        void multipleToolCalls_allParsed() throws Exception {
            JsonNode toolCalls = MAPPER.readTree("""
                    [
                      {"function":{"name":"talos.list_dir","arguments":{"path":"src"}}},
                      {"function":{"name":"talos.read_file","arguments":{"path":"README.md"}}}
                    ]
                    """);

            var result = engine.parseNativeToolCalls(toolCalls);

            assertEquals(2, result.size());
            assertEquals("call_0", result.get(0).id());
            assertEquals("talos.list_dir", result.get(0).name());
            assertEquals("call_1", result.get(1).id());
            assertEquals("talos.read_file", result.get(1).name());
        }

        @Test
        void emptyArguments_emptyMap() throws Exception {
            JsonNode toolCalls = MAPPER.readTree("""
                    [{"function":{"name":"talos.status","arguments":{}}}]
                    """);

            var result = engine.parseNativeToolCalls(toolCalls);

            assertEquals(1, result.size());
            assertTrue(result.get(0).arguments().isEmpty());
        }

        @Test
        void missingArguments_emptyMap() throws Exception {
            JsonNode toolCalls = MAPPER.readTree("""
                    [{"function":{"name":"talos.status"}}]
                    """);

            var result = engine.parseNativeToolCalls(toolCalls);

            assertEquals(1, result.size());
            assertTrue(result.get(0).arguments().isEmpty());
        }

        @Test
        void missingFunctionNode_skipped() throws Exception {
            JsonNode toolCalls = MAPPER.readTree("""
                    [{"not_function":{"name":"bogus"}},
                     {"function":{"name":"talos.list_dir","arguments":{"path":"."}}}]
                    """);

            var result = engine.parseNativeToolCalls(toolCalls);

            assertEquals(1, result.size());
            assertEquals("talos.list_dir", result.get(0).name());
        }

        @Test
        void emptyName_skipped() throws Exception {
            JsonNode toolCalls = MAPPER.readTree("""
                    [{"function":{"name":"","arguments":{"path":"."}}},
                     {"function":{"name":"talos.list_dir","arguments":{"path":"."}}}]
                    """);

            var result = engine.parseNativeToolCalls(toolCalls);

            assertEquals(1, result.size());
            assertEquals("talos.list_dir", result.get(0).name());
        }

        @Test
        void htmlContentInArguments_preserved() throws Exception {
            // This is the critical regression test: HTML content in arguments
            // must NOT be stripped. With native tool calls, it never touches
            // the SUS_HTML sanitization because it's structured, not text.
            JsonNode toolCalls = MAPPER.readTree("""
                    [{"function":{"name":"talos.edit_file","arguments":{
                      "path":"index.html",
                      "old_string":"</body>",
                      "new_string":"<script src=\\"script.js\\"></script></body>"
                    }}}]
                    """);

            var result = engine.parseNativeToolCalls(toolCalls);

            assertEquals(1, result.size());
            assertEquals("talos.edit_file", result.get(0).name());
            assertEquals("<script src=\"script.js\"></script></body>",
                    result.get(0).arguments().get("new_string"),
                    "<script> tag in arguments must be preserved - this was the SUS_HTML bug root cause");
        }
    }
}


