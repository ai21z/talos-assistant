package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;
import dev.loqj.core.net.NetPolicy;

import java.nio.file.Path;
import java.util.Optional;

/** Gated web mode; honors NetPolicy (no network calls in this phase). */
public final class WebMode implements Mode {
    @Override public String name() { return "web"; }

    @Override public boolean canHandle(String rawLine) { return rawLine != null && !rawLine.isBlank(); }

    @Override
    public Optional<Result> handle(String rawLine, Path workspace, Context ctx) {
        NetPolicy np = new NetPolicy(ctx.cfg()); // create from current config
        if (!np.enabled) {
            return Optional.of(new Result.Info("Web mode denied: net.enabled=false (enable in config and restart).\n"));
        }
        return Optional.of(new Result.Info("Web mode is reserved. No external network calls are performed in this build.\n"));
    }
}
