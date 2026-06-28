package dev.talos.cli.repl.slash;

import dev.talos.cli.doctor.DoctorContext;
import dev.talos.cli.doctor.DoctorEngine;
import dev.talos.cli.doctor.DoctorReportRenderer;
import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;

import java.nio.file.Path;
import java.util.List;

/**
 * {@code /doctor} - runs the environment preflight from inside the REPL.
 *
 * <p>Deliberately never starts the managed server (that is the CLI-only
 * {@code talos doctor --start}): a slash command must not block the session
 * on a multi-minute model load or churn the GPU mid-conversation.
 */
public final class DoctorCommand implements Command {

    private final Path workspace;
    private final Path talosHome;

    public DoctorCommand(Path workspace) {
        this(workspace, null);
    }

    /** Test seam: an explicit Talos home keeps probe writes out of the real {@code ~/.talos}. */
    DoctorCommand(Path workspace, Path talosHome) {
        this.workspace = workspace;
        this.talosHome = talosHome;
    }

    @Override
    public CommandSpec spec() {
        return new CommandSpec("doctor", List.of(), "/doctor",
                "Run environment preflight checks.", CommandGroup.DEBUG);
    }

    @Override
    public Result execute(String args, Context ctx) {
        DoctorContext doctorCtx = talosHome == null
                ? DoctorContext.of(ctx.cfg(), workspace)
                : new DoctorContext(ctx.cfg(), workspace, talosHome);
        return new Result.TrustedInfo(DoctorReportRenderer.render(
                DoctorEngine.run(doctorCtx, DoctorEngine.defaultProbes())));
    }
}
