package dev.talos.runtime.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseObligationVerifierTest {

    @Test
    void conditionalReviewFixRetrySummaryDoesNotStateUnconditionalWriteEditRequirement() {
        String summary = ResponseObligationVerifier.retryFailureSummary(
                ActionObligation.CONDITIONAL_REVIEW_FIX,
                "I inspected the files and found an issue.");

        assertTrue(summary.contains("conditional review-and-fix obligation"), summary);
        assertTrue(summary.contains("concrete repair claim requires a write/edit tool call"), summary);
        assertFalse(summary.contains("required write/edit tool calls"), summary);
    }
}
