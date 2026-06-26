package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.DebugLevel;
import dev.talos.runtime.Result;
import dev.talos.cli.repl.Context;

import java.util.List;
import java.util.Optional;

public final class DebugCommand implements Command {
    private static final String USAGE = "Usage: /debug off|brief|rag|tools|prompt|trace [on|off]";

    private final CliRuntime rt;
    public DebugCommand(CliRuntime rt) { this.rt = rt; }

    @Override public CommandSpec spec() {
        return new CommandSpec("debug", List.of(), "/debug [off|brief|rag|tools|prompt|trace] [on|off]",
                "Set debug output level.", CommandGroup.DEBUG);
    }

    @Override public Result execute(String args, Context ctx) {
        String a = (args == null ? "" : args.trim().toLowerCase());
        if (a.isEmpty()) return new Result.Info("debug = " + rt.getDebugLevel().label());

        String[] parts = a.split("\\s+");
        if (parts.length == 1) {
            if ("on".equals(parts[0])) return usageError();
            return DebugLevel.parse(parts[0])
                    .map(this::setLevel)
                    .orElseGet(DebugCommand::usageError);
        }

        if (parts.length == 2) {
            Optional<DebugLevel> level = parseExplicitNonOffLevel(parts[0]);
            if (level.isPresent()) {
                if ("on".equals(parts[1])) return setLevel(level.get());
                if ("off".equals(parts[1])) return setLevel(DebugLevel.OFF);
            }
        }

        return usageError();
    }

    private Result setLevel(DebugLevel level) {
        rt.setDebugLevel(level);
        return new Result.Info("debug = " + level.label());
    }

    private static Optional<DebugLevel> parseExplicitNonOffLevel(String raw) {
        return switch (raw == null ? "" : raw) {
            case "brief" -> Optional.of(DebugLevel.BRIEF);
            case "rag", "retrieval" -> Optional.of(DebugLevel.RAG);
            case "tool", "tools" -> Optional.of(DebugLevel.TOOLS);
            case "prompt", "prompts", "frame" -> Optional.of(DebugLevel.PROMPT);
            case "trace", "all" -> Optional.of(DebugLevel.TRACE);
            default -> Optional.empty();
        };
    }

    private static Result usageError() {
        return new Result.Error(USAGE, 201);
    }
}
