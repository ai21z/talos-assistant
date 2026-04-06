package dev.talos.cli.commands;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.net.NetPolicy;

import java.util.List;

public final class PolicyCommand implements Command {
    @Override public CommandSpec spec() {
        return new CommandSpec("policy", List.of(), "/policy", "Show active network & workspace policy.");
    }

    @Override public Result execute(String args, Context ctx) {
        NetPolicy np = new NetPolicy(ctx.cfg());
        var cols = List.of("Key", "Value");
        var rows = List.of(
                List.of("net.enabled", String.valueOf(np.enabled)),
                List.of("read_only", String.valueOf(np.readOnly)),
                List.of("allow_domains", String.valueOf(np.allowDomains)),
                List.of("content_types", String.valueOf(np.contentTypes)),
                List.of("max_bytes", String.valueOf(np.maxBytes))
        );
        return new Result.Table("Policy", cols, rows);
    }
}
