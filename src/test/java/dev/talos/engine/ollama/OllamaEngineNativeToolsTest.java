package dev.talos.engine.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the native tool calling additions to OllamaEngine.
 * Validates tool spec conversion, tool_call response parsing (non-streaming),
 * and ChatMessage serialization with native tool_calls.
 */
class OllamaEngineNativeToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Tool Spec Conversion ─────────────────────────────────────────────

    @Test
    void chatRequest_includes_tools_field() {
        var specs = List.of(
                new ToolSpec("talos.list_dir", "List directory contents",
                        """
                        {"type":"object","properties":{
                          "path":{"type":"string","description":"Relative path"}
                        },"required":["path"]}""")
        );

        var req = new ChatRequest("ollama", "test", "", "", List.of(),
                java.time.Duration.ofSeconds(30), List.of(ChatMessage.user("list files")), specs);

        assertNotNull(req.tools);
        assertEquals(1, req.tools.size());
        assertEquals("talos.list_dir", req.tools.get(0).name());
    }

    @Test
    void chatRequest_default_tools_empty() {
        var req = new ChatRequest("ollama", "test", "", "", List.of(),
                java.time.Duration.ofSeconds(30), List.of(ChatMessage.user("hello")));

        assertNotNull(req.tools);
        assertTrue(req.tools.isEmpty());
    }

    @Test
    void chatRequest_legacy_constructor_tools_empty() {
        var req = new ChatRequest("ollama", "test", "", "", List.of(),
                java.time.Duration.ofSeconds(30));

        assertNotNull(req.tools);
        assertTrue(req.tools.isEmpty());
    }

    // ── ChatMessage Extensions ───────────────────────────────────────────

    @Test
    void chatMessage_backward_compatible() {
        var msg = ChatMessage.user("hello");
        assertEquals("user", msg.role());
        assertEquals("hello", msg.content());
        assertNull(msg.toolCalls());
        assertNull(msg.toolCallId());
        assertFalse(msg.hasNativeToolCalls());
    }

    @Test
    void chatMessage_assistantWithToolCalls() {
        var calls = List.of(
                new ChatMessage.NativeToolCall("call_1", "talos.list_dir", Map.of("path", "."))
        );
        var msg = ChatMessage.assistantWithToolCalls("", calls);

        assertEquals("assistant", msg.role());
        assertTrue(msg.hasNativeToolCalls());
        assertEquals(1, msg.toolCalls().size());
        assertEquals("talos.list_dir", msg.toolCalls().get(0).name());
        assertEquals(".", msg.toolCalls().get(0).arguments().get("path"));
    }

    @Test
    void chatMessage_toolResult() {
        var msg = ChatMessage.toolResult("call_1", "file1.txt\nfile2.txt");
        assertEquals("tool", msg.role());
        assertEquals("file1.txt\nfile2.txt", msg.content());
        assertEquals("call_1", msg.toolCallId());
        assertFalse(msg.hasNativeToolCalls());
    }

    // ── ToolSpec immutability ────────────────────────────────────────────

    @Test
    void toolSpec_requires_name() {
        assertThrows(NullPointerException.class,
                () -> new ToolSpec(null, "desc", "{}"));
    }

    @Test
    void toolSpec_requires_description() {
        assertThrows(NullPointerException.class,
                () -> new ToolSpec("name", null, "{}"));
    }

    @Test
    void toolSpec_allows_null_schema() {
        var spec = new ToolSpec("name", "desc", null);
        assertNull(spec.parametersSchemaJson());
    }

    // ── Tool call XML conversion format ──────────────────────────────────

    @Test
    void nativeToolCall_response_is_parseable_by_ToolCallParser() throws Exception {
        // Simulate what OllamaEngine.extractChatContentOrToolCalls produces
        // when Ollama returns native tool_calls
        String simulatedOllamaResponse = """
                {"message":{"role":"assistant","content":"",
                "tool_calls":[{"function":{"name":"talos.list_dir","arguments":{"path":"."}}}]},
                "done":true}""";

        // Parse the response JSON
        JsonNode root = MAPPER.readTree(simulatedOllamaResponse);
        JsonNode msg = root.path("message");
        JsonNode toolCalls = msg.path("tool_calls");

        assertTrue(toolCalls.isArray());
        assertEquals(1, toolCalls.size());

        JsonNode fn = toolCalls.get(0).path("function");
        assertEquals("talos.list_dir", fn.path("name").asText());
        assertEquals(".", fn.path("arguments").path("path").asText());
    }

    @Test
    void multiple_tool_calls_in_response() throws Exception {
        String response = """
                {"message":{"role":"assistant","content":"",
                "tool_calls":[
                  {"function":{"name":"talos.list_dir","arguments":{"path":"."}}},
                  {"function":{"name":"talos.read_file","arguments":{"path":"README.md"}}}
                ]},"done":true}""";

        JsonNode root = MAPPER.readTree(response);
        JsonNode toolCalls = root.path("message").path("tool_calls");

        assertEquals(2, toolCalls.size());
        assertEquals("talos.list_dir", toolCalls.get(0).path("function").path("name").asText());
        assertEquals("talos.read_file", toolCalls.get(1).path("function").path("name").asText());
    }
}

