package dev.talos.cli.commands;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.modes.PromptRouter;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;

import java.util.List;

/**
 * Diagnostic command that explains how the prompt router would classify
 * a given input without executing it.
 *
 * <pre>
 * :route hey
 * :route explain RagService.java
 * :route what about the parse method?
 * </pre>
 *
 * <p>Shows the route decision, the trigger signal, and the full evaluation
 * trace. Useful for developers debugging routing behavior and for users
 * who want to understand why a prompt was handled a certain way.
 */
public final class RouteCommand implements Command {

    private final ModeController modes;

    public RouteCommand(ModeController modes) {
        this.modes = modes;
    }

    @Override
    public CommandSpec spec() {
        return new CommandSpec("route", List.of("explain-route"),
                "/route <prompt>",
                "Explain how a prompt would be routed in auto mode (diagnostic).",
                CommandGroup.DEBUG);
    }

    @Override
    public Result execute(String args, Context ctx) {
        if (args == null || args.isBlank()) {
            return new Result.Info(
                    "Usage: /route <prompt>\n" +
                    "Shows how the prompt would be routed in auto mode.\n" +
                    "Example: /route explain RagService.java\n");
        }

        PromptRouter.Route lastRoute = modes.lastRoute();
        var checker = modes.getSymbolChecker();

        PromptRouter.RouteResult result = PromptRouter.explainRoute(args, lastRoute, checker);

        StringBuilder sb = new StringBuilder();
        sb.append("Route:   ").append(result.route()).append('\n');
        sb.append("Trigger: ").append(result.trigger()).append('\n');
        if (lastRoute != null) {
            sb.append("Context: last route was ").append(lastRoute).append('\n');
        } else {
            sb.append("Context: first turn (no prior route)\n");
        }
        sb.append("Checker: ").append(checker != null ? "active" : "not available").append('\n');

        if (!result.steps().isEmpty()) {
            sb.append("Steps:\n");
            for (String step : result.steps()) {
                sb.append("  • ").append(step).append('\n');
            }
        }

        return new Result.Ok(sb.toString());
    }
}

