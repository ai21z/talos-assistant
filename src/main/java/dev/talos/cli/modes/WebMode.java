package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.core.net.NetPolicy;

import java.nio.file.Path;
import java.util.Optional;

/** Reserved web mode stub; honors NetPolicy but performs no external network calls in this build. */
public final class WebMode implements Mode {
    @Override public String name() { return "web"; }

    @Override public boolean canHandle(String rawLine) { return rawLine != null && !rawLine.isBlank(); }

    @Override
    public Optional<Result> handle(String rawLine, Path workspace, Context ctx) {
        NetPolicy np = new NetPolicy(ctx.cfg()); // create from current config
        if (!np.enabled) {
            return Optional.of(new Result.Info("Web mode is reserved and currently disabled: net.enabled=false.\n"
                    + "Enable network and restart only when a real web implementation exists.\n"));
        }
        return Optional.of(new Result.Info("Web mode is reserved in this build.\n"
                + "No external network calls are performed, and no browser/web capability is implemented yet.\n"));
    }
}
