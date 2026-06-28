package dev.talos.cli.doctor;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T784: engine aggregation - probe order, throw containment, verdict. */
class DoctorEngineTest {

    @TempDir Path tempDir;

    @Test
    void runsProbesInOrderAndAggregates() {
        DoctorContext ctx = ctx();
        List<DoctorProbe> probes = List.of(
                fixed("a", ProbeResult.pass("a", "ok")),
                fixed("b", ProbeResult.warn("b", "meh")));

        List<ProbeResult> results = DoctorEngine.run(ctx, probes);

        assertEquals(List.of("a", "b"), results.stream().map(ProbeResult::id).toList());
        assertTrue(DoctorEngine.ok(results), "WARN must not block readiness");
    }

    @Test
    void throwingProbeBecomesAFailNotAnAbort() {
        DoctorContext ctx = ctx();
        List<DoctorProbe> probes = List.of(
                new DoctorProbe() {
                    @Override public String id() { return "boom"; }
                    @Override public ProbeResult run(DoctorContext c) {
                        throw new IllegalStateException("kaput");
                    }
                },
                fixed("after", ProbeResult.pass("after", "still ran")));

        List<ProbeResult> results = DoctorEngine.run(ctx, probes);

        assertEquals(2, results.size());
        assertEquals(ProbeResult.Status.FAIL, results.get(0).status());
        assertTrue(results.get(0).detail().contains("kaput"));
        assertEquals(ProbeResult.Status.PASS, results.get(1).status());
        assertFalse(DoctorEngine.ok(results));
    }

    @Test
    void nullProbeResultBecomesAFail() {
        List<ProbeResult> results = DoctorEngine.run(ctx(), List.of(fixed("n", null)));

        assertEquals(ProbeResult.Status.FAIL, results.get(0).status());
    }

    @Test
    void defaultProbeSetIsTheDocumentedSixInOrder() {
        List<String> ids = DoctorEngine.defaultProbes().stream().map(DoctorProbe::id).toList();

        assertEquals(List.of("config", "runtime-env", "engine-profile", "engine-files",
                "server", "retrieval", "index-writable", "home-writable"), ids);
    }

    private DoctorContext ctx() {
        return new DoctorContext(new Config(), tempDir, tempDir.resolve("home"));
    }

    private static DoctorProbe fixed(String id, ProbeResult result) {
        return new DoctorProbe() {
            @Override public String id() { return id; }
            @Override public ProbeResult run(DoctorContext ctx) { return result; }
        };
    }
}
