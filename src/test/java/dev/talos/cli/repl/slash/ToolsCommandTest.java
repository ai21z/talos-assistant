package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.core.Config;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.GrepTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolsCommandTest {

    @Test
    void spec_name_and_alias() {
        var cmd = new ToolsCommand();
        assertEquals("tools", cmd.spec().name());
        assertTrue(cmd.spec().aliases().contains("t"));
        assertEquals(CommandGroup.DEBUG, cmd.spec().group());
    }

    @Test
    void empty_registry_returns_info() {
        var cmd = new ToolsCommand();
        var ctx = Context.builder(new Config())
                .toolRegistry(new ToolRegistry())
                .build();

        Result r = cmd.execute("", ctx);
        assertInstanceOf(Result.Info.class, r);
        assertTrue(r.toString().contains("No tools"));
    }

    @Test
    void populated_registry_lists_tools() {
        var cmd = new ToolsCommand();
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new GrepTool());

        var ctx = Context.builder(new Config())
                .toolRegistry(registry)
                .build();

        Result r = cmd.execute("", ctx);
        assertInstanceOf(Result.Ok.class, r);
        String text = r.toString();
        // Tool names shown without talos. prefix
        assertTrue(text.contains("read_file"), "Should list read_file: " + text);
        assertTrue(text.contains("grep"), "Should list grep: " + text);
        // Count shown in header
        assertTrue(text.contains("2"), "Should show count of 2: " + text);
    }

    @Test
    void output_contains_header_explanation() {
        var cmd = new ToolsCommand();
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        var ctx = Context.builder(new Config()).toolRegistry(registry).build();

        String text = cmd.execute("", ctx).toString();
        assertTrue(text.contains("AI calls these"), "Should explain AI invocation: " + text);
        assertTrue(text.contains("plain language"), "Should mention plain language: " + text);
    }

    @Test
    void output_contains_examples() {
        var cmd = new ToolsCommand();
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        var ctx = Context.builder(new Config()).toolRegistry(registry).build();

        String text = cmd.execute("", ctx).toString();
        assertTrue(text.contains("Examples"), "Should show examples section: " + text);
    }

    @Test
    void write_tools_show_write_badge() {
        var cmd = new ToolsCommand();
        var registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        var ctx = Context.builder(new Config()).toolRegistry(registry).build();

        String text = cmd.execute("", ctx).toString();
        assertTrue(text.contains("write"), "Should show write badge for FileWriteTool: " + text);
    }

    @Test
    void edit_tool_description_is_ascii_safe() {
        var cmd = new ToolsCommand();
        var registry = new ToolRegistry();
        registry.register(new FileEditTool());
        var ctx = Context.builder(new Config()).toolRegistry(registry).build();

        String text = cmd.execute("", ctx).toString();
        // description is now word-wrapped (T882), so collapse whitespace before the phrase check
        String collapsed = text.replaceAll("\\s+", " ");
        assertTrue(collapsed.contains("old_string must match the file exactly - strip"), text);
        assertFalse(text.contains("? strip"), text);
        assertTrue(text.chars().allMatch(ch -> ch < 128),
                "installed transcript path should not need replacement characters: " + text);
    }

    @Test
    void read_tools_show_read_badge() {
        var cmd = new ToolsCommand();
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        var ctx = Context.builder(new Config()).toolRegistry(registry).build();

        String text = cmd.execute("", ctx).toString();
        assertTrue(text.contains("read"), "Should show read badge for ReadFileTool: " + text);
    }

    @Test
    void parameters_are_displayed() {
        var cmd = new ToolsCommand();
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        var ctx = Context.builder(new Config()).toolRegistry(registry).build();

        String text = cmd.execute("", ctx).toString();
        assertTrue(text.contains("path"), "Should show path parameter: " + text);
    }

    @Test
    void extractParams_returns_required_and_optional() {
        String schema = """
                {"type":"object","properties":{
                  "path":{"type":"string"},
                  "max_lines":{"type":"integer"}
                },"required":["path"]}""";
        String result = ToolsCommand.extractParams(schema);
        assertNotNull(result);
        assertTrue(result.contains("path"), "Should contain path");
        assertTrue(result.contains("max_lines?"), "max_lines should be optional");
        assertFalse(result.contains("path?"), "path should NOT be optional");
    }

    @Test
    void extractParams_null_schema_returns_null() {
        assertNull(ToolsCommand.extractParams(null));
        assertNull(ToolsCommand.extractParams(""));
    }

    @Test
    void wrap_breaks_long_text_into_width_bounded_lines_that_rejoin() {
        String text = "Apply a batch of workspace operations from a native operations "
                + "array (preferred) or a legacy operations_json string.";
        var lines = ToolsCommand.wrap(text, 40);
        assertFalse(lines.isEmpty());
        for (String line : lines) {
            assertTrue(line.length() <= 40, "line over width: '" + line + "'");
        }
        assertEquals(text, String.join(" ", lines), "wrapped lines should rejoin to the original");
    }

    @Test
    void wrap_handles_null_and_blank() {
        assertTrue(ToolsCommand.wrap(null, 40).isEmpty());
        assertTrue(ToolsCommand.wrap("   ", 40).isEmpty());
    }

    @Test
    void long_description_tool_renders_with_no_over_width_line() {
        var cmd = new ToolsCommand();
        var registry = new ToolRegistry();
        registry.register(new FileEditTool());   // has a long, multi-sentence description
        var ctx = Context.builder(new Config()).toolRegistry(registry).build();

        String text = cmd.execute("", ctx).toString();
        for (String raw : text.split("\n", -1)) {
            String line = raw.replaceAll("\\[[0-9;]*m", ""); // strip ANSI -> visible width
            assertTrue(line.length() <= 80, "over-width /tools line (" + line.length() + "): '" + line + "'");
        }
    }
}

