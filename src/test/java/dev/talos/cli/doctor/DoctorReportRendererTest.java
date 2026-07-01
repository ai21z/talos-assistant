package dev.talos.cli.doctor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T784: report format - one ASCII line per probe, hints only on FAIL. */
class DoctorReportRendererTest {

    @Test
    void rendersOneLinePerProbeWithFixHintsOnFailuresOnly() {
        String report = DoctorReportRenderer.render(List.of(
                ProbeResult.pass("config", "loaded from classpath"),
                ProbeResult.warn("server", "managed server not running"),
                ProbeResult.fail("engine-files", "model file missing", "run 'talos setup models'"),
                ProbeResult.skip("other", "not applicable")));

        assertTrue(report.contains("PASS  config"));
        assertTrue(report.contains("WARN  server"));
        assertTrue(report.contains("FAIL  engine-files"));
        assertTrue(report.contains("SKIP  other"));
        assertTrue(report.contains("fix: run 'talos setup models'"));
        assertTrue(report.contains("Doctor summary: 1 passed, 1 warning(s), 1 failed, 1 skipped."));
        assertTrue(report.contains("Fix the failed checks above, then re-run 'talos doctor'."));
        assertFalse(report.contains("Environment is ready."));
    }

    @Test
    void allGreenReportsReady() {
        String report = DoctorReportRenderer.render(List.of(
                ProbeResult.pass("config", "ok")));

        assertTrue(report.contains("Doctor summary: 1 passed, 0 warning(s), 0 failed, 0 skipped."));
        assertTrue(report.contains("Environment is ready."));
        assertFalse(report.contains("fix:"));
    }

    @Test
    void outputIsPlainAscii() {
        String report = DoctorReportRenderer.render(List.of(
                ProbeResult.fail("a", "detail", "hint"),
                ProbeResult.pass("b", "ok")));

        report.chars().forEach(c ->
                assertTrue(c < 128, "doctor output must stay plain ASCII, found char " + c));
    }
}
