package dev.talos.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the tool seam contracts: ToolRegistry, ToolCall, ToolResult,
 * ToolError, ToolDescriptor, and the TalosTool interface.
 */
class ToolRegistryTest {

    /** Minimal test tool implementation. */
    static class EchoTool implements TalosTool {
        @Override public String name() { return "talos.echo"; }
        @Override public String description() { return "Echoes input back."; }
        @Override public ToolDescriptor descriptor() {
            return new ToolDescriptor("talos.echo", "Echoes input back.", "{\"input\": \"string\"}");
        }
        @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
            String input = call.param("input", "(empty)");
            return ToolResult.ok("Echo: " + input);
        }
    }

    private static ToolContext testContext() {
        return new ToolContext(
                java.nio.file.Path.of(".").toAbsolutePath().normalize(),
                new dev.talos.core.security.Sandbox(java.nio.file.Path.of("."), Map.of()),
                new dev.talos.core.Config()
        );
    }

    @Test
    void register_and_retrieve_tool() {
        ToolRegistry registry = new ToolRegistry();
        EchoTool echo = new EchoTool();
        registry.register(echo);

        assertSame(echo, registry.get("talos.echo"));
        assertNull(registry.get("nonexistent"));
    }

    @Test
    void all_returns_registered_tools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());

        Map<String, TalosTool> all = registry.all();
        assertEquals(1, all.size());
        assertTrue(all.containsKey("talos.echo"));
    }

    @Test
    void descriptors_lists_all_tool_descriptors() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());

        var descriptors = registry.descriptors();
        assertEquals(1, descriptors.size());
        assertEquals("talos.echo", descriptors.get(0).name());
    }

    @Test
    void execute_dispatches_to_correct_tool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());

        ToolCall call = new ToolCall("talos.echo", Map.of("input", "hello"));
        ToolResult result = registry.execute(call, testContext());

        assertTrue(result.success());
        assertEquals("Echo: hello", result.output());
        assertNull(result.error());
    }

    @Test
    void execute_unknown_tool_returns_error() {
        ToolRegistry registry = new ToolRegistry();

        ToolCall call = new ToolCall("nonexistent", Map.of());
        ToolResult result = registry.execute(call, testContext());

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

    // --- Context-aware execution tests ---

    @Test
    void execute_with_context_dispatches() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ContextAwareTool());

        ToolCall call = new ToolCall("talos.ctx", Map.of());
        ToolResult result = registry.execute(call, testContext());
        assertTrue(result.success());
        assertEquals("has-context", result.output());
    }

    @Test
    void execute_with_context_unknown_tool() {
        ToolRegistry registry = new ToolRegistry();
        ToolResult result = registry.execute(new ToolCall("missing", Map.of()), testContext());
        assertFalse(result.success());
        assertEquals(ToolError.NOT_FOUND, result.error().code());
    }

    @Test
    void isEmpty_reflects_registry_state() {
        ToolRegistry registry = new ToolRegistry();
        assertTrue(registry.isEmpty());
        registry.register(new EchoTool());
        assertFalse(registry.isEmpty());
    }

    @Test
    void context_aware_contract_is_primary() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ContextAwareTool());

        ToolResult result = registry.execute(new ToolCall("talos.ctx", Map.of()), testContext());
        assertTrue(result.success());
        assertEquals("has-context", result.output());
    }

    /** Tool that differentiates between context and no-context execution. */
    static class ContextAwareTool implements TalosTool {
        @Override public String name() { return "talos.ctx"; }
        @Override public String description() { return "Context-aware test tool"; }
        @Override public ToolDescriptor descriptor() { return new ToolDescriptor("talos.ctx", "test"); }
        @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
            return ToolResult.ok(ctx != null ? "has-context" : "null-context");
        }
    }

    // --- Fuzzy tool name matching tests ---

    @Test
    void fuzzy_match_without_talos_prefix() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());

        // "echo" should resolve to "talos.echo" via prefix addition
        assertNotNull(registry.get("echo"), "Should match talos.echo via prefix");
        assertSame(registry.get("talos.echo"), registry.get("echo"));
    }

    @Test
    void fuzzy_match_known_alias_file_write() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new dev.talos.tools.impl.FileWriteTool());

        // "file_write" is a known alias for "talos.write_file"
        assertNotNull(registry.get("file_write"), "Should match talos.write_file via alias");
        assertEquals("talos.write_file", registry.get("file_write").name());
    }

    @Test
    void fuzzy_match_create_file_aliases_to_write_file() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new dev.talos.tools.impl.FileWriteTool());

        for (String alias : java.util.List.of("create_file", "talos.create_file", "file_create", "createfile")) {
            assertNotNull(registry.get(alias), alias + " should match talos.write_file");
            assertEquals("talos.write_file", registry.get(alias).name(), alias);
        }
    }

    @Test
    void fuzzy_match_known_alias_read_file() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new dev.talos.tools.impl.ReadFileTool());

        assertNotNull(registry.get("read_file"), "Should match talos.read_file via alias");
        assertNotNull(registry.get("file_read"), "Should match talos.read_file via alias");
    }

    @Test
    void fuzzy_match_does_not_match_garbage() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());

        assertNull(registry.get("totally_unknown"));
        assertNull(registry.get(""));
        assertNull(registry.get(null));
    }

    @Test
    void fuzzy_execute_resolves_alias() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());

        // Execute via alias "echo" (without talos. prefix)
        ToolResult result = registry.execute(new ToolCall("echo", Map.of("input", "fuzzy")), testContext());
        assertTrue(result.success());
        assertEquals("Echo: fuzzy", result.output());
    }

    /**
     * Unix muscle-memory alias: bare {@code ls} and {@code talos:ls} (via
     * separator rewrite to {@code talos.ls}, then stripped-prefix alias
     * lookup) must both resolve to {@code talos.list_dir}. Observed real
     * failure: gemma4:26b emitted both forms and got "Unknown tool"
     * responses, wasting tool-loop iterations.
     */
    @Test
    void ls_and_talos_colon_ls_both_resolve_to_list_dir() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new dev.talos.tools.impl.ListDirTool());

        assertNotNull(registry.get("ls"), "bare `ls` must resolve");
        assertEquals("talos.list_dir", registry.get("ls").name());

        // talos:ls → separator rewrite → talos.ls → exact miss →
        // strip-prefix alias lookup of "ls" → talos.list_dir
        assertNotNull(registry.get("talos:ls"), "`talos:ls` must resolve via separator rewrite + alias");
        assertEquals("talos.list_dir", registry.get("talos:ls").name());
    }

    @Test
    void explicitBackendToolAliasesResolveButUnknownNamespacesDoNot() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new dev.talos.tools.impl.FileWriteTool());
        registry.register(new dev.talos.tools.impl.ListDirTool());

        assertNotNull(registry.get("tool_use:write_file"));
        assertEquals("talos.write_file", registry.get("tool_use:write_file").name());
        assertNotNull(registry.get("file_utils:write_file"));
        assertEquals("talos.write_file", registry.get("file_utils:write_file").name());
        assertNotNull(registry.get("tool_use:list_dir"));
        assertEquals("talos.list_dir", registry.get("tool_use:list_dir").name());

        assertNull(registry.get("unknown_provider.write_file"));
    }

    @Test
    void workspaceOperationAliasesResolveToCanonicalTools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new dev.talos.tools.impl.MakeDirectoryTool());
        registry.register(new dev.talos.tools.impl.MovePathTool());
        registry.register(new dev.talos.tools.impl.CopyPathTool());
        registry.register(new dev.talos.tools.impl.RenamePathTool());

        assertEquals("talos.mkdir", registry.get("mkdir").name());
        assertEquals("talos.move_path", registry.get("mv").name());
        assertEquals("talos.copy_path", registry.get("cp").name());
        assertEquals("talos.rename_path", registry.get("rename").name());
    }
}
