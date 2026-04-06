package dev.talos.cli.commands;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.Config;
import dev.talos.tools.ToolRegistry;
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
        assertTrue(text.contains("talos.read_file"), "Should list ReadFileTool: " + text);
        assertTrue(text.contains("talos.grep"), "Should list GrepTool: " + text);
        assertTrue(text.contains("2"), "Should show count of 2: " + text);
    }
}

