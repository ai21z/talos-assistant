package dev.talos.cli.doctor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs the ordered doctor probe list and aggregates the verdict. The probe
 * set is the single source for `talos doctor`, the REPL `/doctor`, and the
 * first-run preflight, so all three surfaces verify the same contract.
 */
public final class DoctorEngine {

    private DoctorEngine() {}

    /** The default fast probe set — never starts the model server. */
    public static List<DoctorProbe> defaultProbes() {
        return probes(false);
    }

    /**
     * @param startServer when true the server probe additionally starts the
     *                    managed server and runs a one-token chat (loads the
     *                    model, then stops the server again). Opt-in via
     *                    {@code talos doctor --start} only.
     */
    public static List<DoctorProbe> probes(boolean startServer) {
        return List.of(
                new ConfigProbe(),
                new RuntimeEnvironmentProbe(),
                new EngineProfileProbe(),
                new EngineFilesProbe(),
                new ServerProbe(startServer),
                new RetrievalStateProbe(),
                new IndexWritableProbe(),
                new HomeWritableProbe());
    }

    /** Run every probe in order; a throwing probe becomes a FAIL, never an abort. */
    public static List<ProbeResult> run(DoctorContext ctx, List<DoctorProbe> probes) {
        Objects.requireNonNull(ctx, "ctx is required");
        List<ProbeResult> results = new ArrayList<>();
        for (DoctorProbe probe : probes == null ? List.<DoctorProbe>of() : probes) {
            if (probe == null) continue;
            try {
                ProbeResult result = probe.run(ctx);
                results.add(result == null
                        ? ProbeResult.fail(probe.id(), "probe returned no result", "")
                        : result);
            } catch (Exception e) {
                results.add(ProbeResult.fail(probe.id(),
                        "internal probe error: " + e.getMessage(),
                        "re-run 'talos doctor'; report this if it persists"));
            }
        }
        return List.copyOf(results);
    }

    /** True when no probe FAILed (WARN and SKIP do not block readiness). */
    public static boolean ok(List<ProbeResult> results) {
        if (results == null) return false;
        return results.stream().noneMatch(r -> r.status() == ProbeResult.Status.FAIL);
    }
}
