package dev.talos.cli.commands;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;

import java.util.List;
import java.util.Locale;

/** Handles ':set model <name>' */
public final class SetCommand implements Command {

    @Override public CommandSpec spec() {
        return new CommandSpec("set", List.of(), ":set model <name>", "Set options; currently supports 'model'.");
    }

    @Override
    public Result execute(String args, Context ctx) throws Exception {
        String a = args == null ? "" : args.trim();
        if (a.isEmpty() || !a.toLowerCase(Locale.ROOT).startsWith("model")) {
            return new Result.Error("Usage: :set model <name>\nExample: :set model qwen3:8b\n", 200);
        }
        String rest = a.substring("model".length()).trim();
        if (rest.isEmpty()) return new Result.Error("Usage: :set model <name>\n", 200);

        String name = sanitizeModelName(rest);
        if (name.isEmpty()) return new Result.Error("Invalid model name.\n", 200);

        ctx.llm().setModel(name);
        ctx.audit().log("model.switch", java.util.Map.of("name", name));
        return new Result.Info("Model set to: " + name + "\n");
    }

    private static String sanitizeModelName(String raw) {
        String s = raw.trim();
        if ((s.startsWith("<") && s.endsWith(">")) || (s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1);
        }
        while (!s.isEmpty() && (s.charAt(0) == '-' || s.charAt(0) == '<')) s = s.substring(1);
        while (!s.isEmpty() && (s.charAt(s.length() - 1) == '>')) s = s.substring(0, s.length() - 1);
        s = s.replaceAll("[^A-Za-z0-9._:-]", "");
        if (s.contains("..") || s.contains("//") || s.contains("\\\\")) return "";
        if (s.length() > 64) s = s.substring(0, 64);
        if (s.isEmpty() || !Character.isLetterOrDigit(s.charAt(0))) return "";
        return s;
    }
}
