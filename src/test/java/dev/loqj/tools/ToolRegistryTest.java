package dev.loqj.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the tool seam contracts: ToolRegistry, ToolCall, ToolResult,
 * ToolError, ToolDescriptor, and the LoqjTool interface.
 */
class ToolRegistryTest {

    /** Minimal test tool implementation. */
    static class EchoTool implements LoqjTool {
        @Override public String name() { return "loqj.echo"; }
        @Override public String description() { return "Echoes input back."; }
        @Override public ToolDescriptor descriptor() {
            return new ToolDescriptor("loqj.echo", "Echoes input back.", "{\"input\": \"string\"}");
        }
        @Override public ToolResult execute(ToolCall call) {
            String input = call.param("input", "(empty)");
            return ToolResult.ok("Echo: " + input);
        }
    }

    @Test
    void register_and_retrieve_tool() {
        ToolRegistry registry = new ToolRegistry();
        EchoTool echo = new EchoTool();
        registry.register(echo);

        assertSame(echo, registry.get("loqj.echo"));
        assertNull(registry.get("nonexistent"));
    }

    @Test
    void all_returns_registered_tools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());

        Map<String, LoqjTool> all = registry.all();
        assertEquals(1, all.size());
        assertTrue(all.containsKey("loqj.echo"));
    }

    @Test
    void descriptors_lists_all_tool_descriptors() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());

        var descriptors = registry.descriptors();
        assertEquals(1, descriptors.size());
        assertEquals("loqj.echo", descriptors.get(0).name());
    }

    @Test
    void execute_dispatches_to_correct_tool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());

        ToolCall call = new ToolCall("loqj.echo", Map.of("input", "hello"));
        ToolResult result = registry.execute(call);

        assertTrue(result.success());
        assertEquals("Echo: hello", result.output());
        assertNull(result.error());
    }

    @Test
    void execute_unknown_tool_returns_error() {
        ToolRegistry registry = new ToolRegistry();

        ToolCall call = new ToolCall("nonexistent", Map.of());
        ToolResult result = registry.execute(call);

        assertFalse(result.success());
        assertNotNull(result.error());
        assertEquals(ToolError.NOT_FOUND, result.error().code());
        assertTrue(result.errorMessage().contains("nonexistent"));
    }

    // --- ToolCall tests ---

    @Test
    void toolCall_null_params_become_empty_map() {
        ToolCall call = new ToolCall("test", null);
        assertNotNull(call.parameters());
        assertTrue(call.parameters().isEmpty());
    }

    @Test
    void toolCall_param_convenience_methods() {
        ToolCall call = new ToolCall("test", Map.of("key", "value"));
        assertEquals("value", call.param("key"));
        assertNull(call.param("missing"));
        assertEquals("default", call.param("missing", "default"));
    }

    // --- ToolResult tests ---

    @Test
    void toolResult_ok() {
        ToolResult result = ToolResult.ok("output");
        assertTrue(result.success());
        assertEquals("output", result.output());
        assertNull(result.error());
    }

    @Test
    void toolResult_fail_with_message() {
        ToolResult result = ToolResult.fail("something broke");
        assertFalse(result.success());
        assertNull(result.output());
        assertEquals("something broke", result.errorMessage());
    }

    @Test
    void toolResult_fail_with_toolError() {
        ToolError error = ToolError.invalidParams("bad input");
        ToolResult result = ToolResult.fail(error);
        assertFalse(result.success());
        assertEquals(ToolError.INVALID_PARAMS, result.error().code());
        assertEquals("bad input", result.errorMessage());
    }

    // --- ToolError factory tests ---

    @Test
    void toolError_factories() {
        assertEquals(ToolError.INVALID_PARAMS, ToolError.invalidParams("x").code());
        assertEquals(ToolError.NOT_FOUND, ToolError.notFound("x").code());
        assertEquals(ToolError.INTERNAL_ERROR, ToolError.internal("x").code());
    }

    // --- ToolDescriptor tests ---

    @Test
    void toolDescriptor_with_schema() {
        ToolDescriptor d = new ToolDescriptor("t", "desc", "{\"type\":\"object\"}");
        assertEquals("t", d.name());
        assertEquals("desc", d.description());
        assertEquals("{\"type\":\"object\"}", d.parametersSchema());
    }

    @Test
    void toolDescriptor_without_schema() {
        ToolDescriptor d = new ToolDescriptor("t", "desc");
        assertNull(d.parametersSchema());
    }
}

