package dev.talos.cli.doctor;

import java.util.List;

/**
 * Plain-ASCII, one-line-per-probe report. Statuses are deliberately the
 * bare words PASS/WARN/FAIL/SKIP so transcripts stay grep-able and the
 * talosbench forbidden-substring bank has nothing decorative to collide
 * with.
 */
public final class DoctorReportRenderer {

    private DoctorReportRenderer() {}

    public static String render(List<ProbeResult> results) {
        StringBuilder out = new StringBuilder();
        int pass = 0;
        int warn = 0;
        int fail = 0;
        int skip = 0;
        for (ProbeResult result : results == null ? List.<ProbeResult>of() : results) {
            out.append(String.format("%-4s  %-14s %s%n",
                    result.status(), result.id(), result.detail()));
            if (result.status() == ProbeResult.Status.FAIL && !result.hint().isBlank()) {
                out.append("      fix: ").append(result.hint()).append(System.lineSeparator());
            }
            switch (result.status()) {
                case PASS -> pass++;
                case WARN -> warn++;
                case FAIL -> fail++;
                case SKIP -> skip++;
            }
        }
        out.append(System.lineSeparator());
        out.append(String.format("Doctor summary: %d passed, %d warning(s), %d failed, %d skipped.%n",
                pass, warn, fail, skip));
        out.append(fail == 0
                ? "Environment is ready." + System.lineSeparator()
                : "Fix the failed checks above, then re-run 'talos doctor'." + System.lineSeparator());
        return out.toString();
    }
}
