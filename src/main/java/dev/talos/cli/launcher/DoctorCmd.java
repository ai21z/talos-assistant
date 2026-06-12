package dev.talos.cli.launcher;

import dev.talos.cli.doctor.DoctorContext;
import dev.talos.cli.doctor.DoctorEngine;
import dev.talos.cli.doctor.DoctorReportRenderer;
import dev.talos.cli.doctor.ProbeResult;
import dev.talos.core.Config;
import picocli.CommandLine;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code talos doctor} — fast environment preflight: config loads, the
 * engine profile resolves, the managed server binary and model file exist,
 * the server responds (or is honestly reported as not-yet-started), and the
 * index/home directories are writable.
 *
 * <p>Unlike {@code talos diagnose} (a retrieval/answer-quality deep dive
 * that generates a real answer), doctor never loads a model unless
 * {@code --start} is passed explicitly, and always releases it afterwards.
 */
@CommandLine.Command(
        name = "doctor",
        description = "Verify the local environment: config, engine profile, model files, server, index and home")
public class DoctorCmd implements Callable<Integer> {

    @CommandLine.Option(names = "--root",
            description = "Workspace root (default: current dir or TALOS_WORKSPACE env)")
    String root;

    @CommandLine.Option(names = "--start",
            description = "Also start the managed model server and run a one-word chat"
                    + " (loads the model, then stops the server again; may take minutes)")
    boolean start;

    @Override
    public Integer call() {
        try {
            return run(new Config(), resolveWorkspace(root),
                    DoctorContext.defaultTalosHome(), start, System.out);
        } catch (Exception e) {
            System.err.println("Doctor failed: " + e.getMessage());
            return 2;
        }
    }

    /** Testable seam: everything after CLI parsing. Exit 0 = no FAIL, 1 = FAIL. */
    static int run(Config cfg, Path workspace, Path talosHome, boolean start, PrintStream out) {
        DoctorContext ctx = new DoctorContext(cfg, workspace, talosHome);
        List<ProbeResult> results = DoctorEngine.run(ctx, DoctorEngine.probes(start));
        out.print(DoctorReportRenderer.render(results));
        return DoctorEngine.ok(results) ? 0 : 1;
    }

    /** Same resolution chain as {@code talos status}: --root > TALOS_WORKSPACE > cwd. */
    static Path resolveWorkspace(String root) {
        if (root != null && !root.isBlank()) {
            return Path.of(root).toAbsolutePath().normalize();
        }
        String envRoot = System.getenv("TALOS_WORKSPACE");
        if (envRoot != null && !envRoot.isBlank()) {
            return Path.of(envRoot).toAbsolutePath().normalize();
        }
        return Path.of(".").toAbsolutePath().normalize();
    }
}
