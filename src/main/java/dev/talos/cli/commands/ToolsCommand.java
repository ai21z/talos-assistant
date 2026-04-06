package dev.talos.cli.commands;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.tools.ToolDescriptor;

import java.util.List;

/**
 * Lists all registered tools available for LLM invocation.
 * DX command for introspection — shows tool names, descriptions, and schemas.
 */
public final class ToolsCommand implements Command {

    @Override
    public CommandSpec spec() {
        return new CommandSpec("tools", List.of("t"), "/tools", "List registered tools.", CommandGroup.DEBUG);
    }

    @Override
    public Result execute(String args, Context ctx) {
        var descriptors = ctx.toolRegistry().descriptors();
        if (descriptors.isEmpty()) {
            return new Result.Info("No tools registered.");
        }

        var sb = new StringBuilder();
        sb.append("Registered tools (").append(descriptors.size()).append("):\n\n");
        for (ToolDescriptor d : descriptors) {
            sb.append("  ").append(d.name()).append(" — ").append(d.description()).append('\n');
        }
        return new Result.Ok(sb.toString());
    }
}

