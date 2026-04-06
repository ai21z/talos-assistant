package dev.talos.core.retrieval;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/**
 * Records what happened at each stage of a retrieval pipeline execution.
 * Mutable during pipeline execution, immutable snapshot returned to callers.
 */
public final class RetrievalTrace {
    /** A single trace entry from one pipeline stage. */
    public record Entry(String stageName, long durationNanos, int candidatesBefore, int candidatesAfter, String note) {
        /** Backwards-compatible constructor without note. */
        public Entry(String stageName, long durationNanos, int candidatesBefore, int candidatesAfter) {
            this(stageName, durationNanos, candidatesBefore, candidatesAfter, null);
        }
        public double durationMs() { return durationNanos / 1_000_000.0; }
        public boolean wasSkipped() { return candidatesBefore == candidatesAfter && note != null; }
        @Override
        public String toString() {
            String base = stageName + " [" + String.format("%.1f", durationMs()) + "ms] "
                    + candidatesBefore + " -> " + candidatesAfter;
            return note != null ? base + " (" + note + ")" : base;
        }
    }
    private final List<Entry> entries = new ArrayList<>();
    /** Record a stage execution. Called by the pipeline runner. */
    public void record(String stageName, long durationNanos, int candidatesBefore, int candidatesAfter) {
        entries.add(new Entry(stageName, durationNanos, candidatesBefore, candidatesAfter, null));
    }
    /** Record a stage execution with an optional note (e.g., skip reason). */
    public void record(String stageName, long durationNanos, int candidatesBefore, int candidatesAfter, String note) {
        entries.add(new Entry(stageName, durationNanos, candidatesBefore, candidatesAfter, note));
    }
    /** All recorded entries in execution order. */
    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }
    /** Total pipeline duration in nanoseconds. */
    public long totalNanos() {
        long sum = 0;
        for (Entry e : entries) sum += e.durationNanos();
        return sum;
    }
    /** Total pipeline duration in milliseconds. */
    public double totalMs() {
        return totalNanos() / 1_000_000.0;
    }
    /** Human-readable summary for debug output. */
    public String summary() {
        if (entries.isEmpty()) return "(no stages executed)";
        StringBuilder sb = new StringBuilder();
        sb.append("Pipeline trace (").append(String.format("%.1f", totalMs())).append("ms total):\n");
        for (Entry e : entries) {
            sb.append("  ").append(e.toString()).append("\n");
        }
        return sb.toString();
    }
}
