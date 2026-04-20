package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Result;

import java.util.*;

public final class CommandRegistry {
    private final Map<String, Command> byName = new HashMap<>();

    public void register(Command c) {
        CommandSpec s = c.spec();
        byName.put(s.name(), c);
        if (s.aliases() != null) for (String a : s.aliases()) {
            if (a != null && !a.isBlank()) byName.put(a, c);
        }
    }

    public boolean has(String name) {
        return name != null && byName.containsKey(name);
    }

    public Result execute(String name, String args, dev.talos.cli.repl.Context ctx) throws Exception {
        Command c = byName.get(name);
        if (c == null) return new Result.Error("Unknown command: :" + name, 204);
        return c.execute(args == null ? "" : args.trim(), ctx);
    }

    public List<CommandSpec> allSpecs() {
        // de-duplicate by primary name
        Map<String, CommandSpec> primaries = new TreeMap<>();
        for (Command c : new HashSet<>(byName.values())) {
            primaries.put(c.spec().name(), c.spec());
        }
        return List.copyOf(primaries.values());
    }
}
