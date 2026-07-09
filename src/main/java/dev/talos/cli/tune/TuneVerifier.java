package dev.talos.cli.tune;

import dev.talos.engine.llamacpp.LlamaCppLogEvidence;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/**
 * Verify stage of {@code talos tune} (T987): every claim comes from an
 * artifact the run just produced. A CUDA lane with no offload line in the
 * server log is a verification failure, never a "GPU enabled" claim.
 */
public final class TuneVerifier {

    /**
     * Spill floor, labeled estimate: the 2026-07-08 review measured 5.1 to
     * 5.7 tok/s CPU-class decode and 72 to 80 tok/s on the working CUDA lane
     * for the pinned 14B profile. Full reported offload with generation under
     * this floor matches the Windows shared-memory spill shape.
     */
    public static final double SPILL_FLOOR_TOKENS_PER_SECOND = 15.0;
    private static final int MIN_GENERATION_RATE_SAMPLE_TOKENS = 64;

    private TuneVerifier() {}

    public record Result(boolean passed, boolean spillSuspected, String summary) {}

    public static Result verify(String serverLog, boolean cudaLane, int doctorExit) {
        if (doctorExit != 0) {
            return new Result(false, false,
                    "doctor --start failed (exit " + doctorExit + "); the model smoke did not pass");
        }
        LlamaCppLogEvidence evidence = LlamaCppLogEvidence.parse(serverLog == null ? "" : serverLog);
        Optional<LlamaCppLogEvidence.Timing> generation = evidence.timings().stream()
                .filter(timing -> "eval".equals(timing.kind()))
                .filter(timing -> timing.tokens() >= MIN_GENERATION_RATE_SAMPLE_TOKENS)
                .max(Comparator.comparingInt(LlamaCppLogEvidence.Timing::taskId));

        if (!cudaLane) {
            String rate = generation
                    .map(timing -> String.format(Locale.ROOT,
                            "; generation %.1f tok/s (measured, server log)", timing.tokensPerSecond()))
                    .orElse("");
            return new Result(true, false, "CPU lane verified by doctor smoke" + rate);
        }

        Optional<LlamaCppLogEvidence.Offload> offload = evidence.offload();
        if (offload.isEmpty()) {
            return new Result(false, false,
                    "no offload evidence in the server log; refusing to claim GPU acceleration");
        }
        LlamaCppLogEvidence.Offload facts = offload.get();
        if (facts.offloadedLayers() <= 0) {
            return new Result(false, false, String.format(Locale.ROOT,
                    "offloaded %d/%d layers to %s (server log); refusing to claim GPU acceleration",
                    facts.offloadedLayers(), facts.totalLayers(), facts.target()));
        }
        StringBuilder summary = new StringBuilder(String.format(Locale.ROOT,
                "%soffloaded %d/%d layers to %s (server log)",
                facts.offloadedLayers() < facts.totalLayers() ? "partial " : "",
                facts.offloadedLayers(), facts.totalLayers(), facts.target()));
        boolean spill = false;
        if (generation.isPresent()) {
            double rate = generation.get().tokensPerSecond();
            summary.append(String.format(Locale.ROOT, "; generation %.1f tok/s (measured)", rate));
            if (rate > 0
                    && rate < SPILL_FLOOR_TOKENS_PER_SECOND) {
                spill = true;
                summary.append(String.format(Locale.ROOT,
                        "; below the %.0f tok/s spill floor (estimate), "
                                + "%s",
                        SPILL_FLOOR_TOKENS_PER_SECOND,
                        facts.offloadedLayers() < facts.totalLayers()
                                ? "partial offload or CPU spill suspected"
                                : "Windows shared-memory spill suspected"));
            }
        } else {
            summary.append(String.format(Locale.ROOT,
                    "; generation unavailable (no >=%d-token eval timing in server log)",
                    MIN_GENERATION_RATE_SAMPLE_TOKENS));
        }
        return new Result(true, spill, summary.toString());
    }
}
