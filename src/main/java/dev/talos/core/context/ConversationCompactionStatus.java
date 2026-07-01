package dev.talos.core.context;

/** Redacted operational summary of the latest conversation compaction attempt. */
public record ConversationCompactionStatus(
        boolean attempted,
        String status,
        String category,
        String reason,
        int consecutiveFailureCount,
        int summarizedTurnCount,
        int preservedTailTurnCount,
        String integrityStatus
) {
    public static final String NOT_DERIVED = "NOT_DERIVED";

    public ConversationCompactionStatus {
        status = safe(status, attempted ? "UNKNOWN" : "NEVER_ATTEMPTED");
        category = safe(category, NOT_DERIVED);
        reason = safe(reason, NOT_DERIVED);
        consecutiveFailureCount = Math.max(0, consecutiveFailureCount);
        summarizedTurnCount = Math.max(0, summarizedTurnCount);
        preservedTailTurnCount = Math.max(0, preservedTailTurnCount);
        integrityStatus = safe(integrityStatus, NOT_DERIVED);
    }

    public static ConversationCompactionStatus neverAttempted() {
        return new ConversationCompactionStatus(
                false,
                "NEVER_ATTEMPTED",
                NOT_DERIVED,
                NOT_DERIVED,
                0,
                0,
                0,
                NOT_DERIVED);
    }

    public static ConversationCompactionStatus skipped(
            String reason,
            int consecutiveFailureCount,
            int preservedTailTurnCount
    ) {
        return new ConversationCompactionStatus(
                false,
                "SKIPPED",
                ConversationCompactor.CompactionResult.Category.SKIPPED.name(),
                reason,
                consecutiveFailureCount,
                0,
                preservedTailTurnCount,
                NOT_DERIVED);
    }

    public static ConversationCompactionStatus fromResult(
            ConversationCompactor.CompactionResult result,
            int consecutiveFailureCount,
            int summarizedTurnCount,
            int preservedTailTurnCount
    ) {
        if (result == null) {
            return new ConversationCompactionStatus(
                    true,
                    "FAILED",
                    "NULL_RESULT",
                    "null-result",
                    consecutiveFailureCount,
                    summarizedTurnCount,
                    preservedTailTurnCount,
                    "NOT_CHECKED");
        }
        boolean succeeded = result.succeeded();
        return new ConversationCompactionStatus(
                true,
                succeeded ? "SUCCEEDED" : "FAILED",
                result.category().name(),
                result.reason(),
                consecutiveFailureCount,
                summarizedTurnCount,
                preservedTailTurnCount,
                integrityStatus(result.category(), succeeded));
    }

    public String renderCompact() {
        return "status=" + status
                + " category=" + category
                + " reason=" + reason
                + " failures=" + consecutiveFailureCount
                + " oldTurns=" + summarizedTurnCount
                + " preservedTail=" + preservedTailTurnCount
                + " integrity=" + integrityStatus;
    }

    private static String integrityStatus(
            ConversationCompactor.CompactionResult.Category category,
            boolean succeeded
    ) {
        if (succeeded) return "ACCEPTED";
        if (category == ConversationCompactor.CompactionResult.Category.INTEGRITY_REJECT) {
            return "REJECTED";
        }
        if (category == ConversationCompactor.CompactionResult.Category.BLANK_OUTPUT
                || category == ConversationCompactor.CompactionResult.Category.LLM_FAILURE) {
            return "NOT_CHECKED";
        }
        return NOT_DERIVED;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
