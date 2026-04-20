package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;

import java.util.List;

public final class AuditToggleCommand implements Command {
    @Override public CommandSpec spec() {
        return new CommandSpec("audit", List.of(), "/audit on|off", "Toggle audit logging.",
                CommandGroup.SECURITY);
    }

    @Override public Result execute(String args, Context ctx) {
        String a = args == null ? "" : args.trim().toLowerCase();
        boolean on = a.equals("on") || a.equals("enable");
        boolean off = a.equals("off") || a.equals("disable");
        if (!on && !off) return new Result.Error("Usage: /audit on|off", 201);
        ctx.audit().setEnabled(on);
        return new Result.Info("Audit " + (on ? "ON" : "OFF"));
    }
}
