package dev.talos.core.context;

import java.util.Objects;

/**
 * Read-model snapshot of context-window usage for the {@code /context}
 * meter (T798). Every token figure is the chars/4 estimate the budget and
 * compaction logic themselves use — honest estimates, labeled as such by
 * the renderer.
 *
 * @param historyTokensEstimate    estimated tokens of all stored turns
 * @param historyBudgetTokens      the active compaction budget (mode fraction
 *                                 of the context window)
 * @param contextMaxTokens         configured context window
 *                                 ({@code limits.llm_context_max_tokens})
 * @param responseReserveFraction  fraction reserved for model output
 * @param overheadTokens           fixed structural overhead
 * @param turnPairs                stored user/assistant exchanges
 * @param hasSketch                whether older turns were compacted
 * @param sketchChars              sketch length in characters
 * @param compactionThresholdPairs pair threshold for the active mode
 * @param lastCompaction           latest compaction attempt status
 */
public record ContextMeter(
        int historyTokensEstimate,
        int historyBudgetTokens,
        int contextMaxTokens,
        double responseReserveFraction,
        int overheadTokens,
        int turnPairs,
        boolean hasSketch,
        int sketchChars,
        int compactionThresholdPairs,
        ConversationCompactionStatus lastCompaction
) {
    public ContextMeter {
        lastCompaction = Objects.requireNonNullElseGet(
                lastCompaction, ConversationCompactionStatus::neverAttempted);
    }
}
