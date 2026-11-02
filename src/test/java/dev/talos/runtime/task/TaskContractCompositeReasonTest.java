package dev.talos.runtime.task;

import dev.talos.runtime.policy.ConditionalReviewFixPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An appended reason marker never disables a consumer that asks why a contract was classified. */
class TaskContractCompositeReasonTest {

    @Test
    void membershipSeesThroughAppendedMarkers() {
        TaskContract contract = contract("explicit-review-and-fix-request+" + TaskContractResolver.CONTEXTUAL_STATIC_WEB_SURFACE
                + "+workspace-static-web-surface-targets");

        assertTrue(contract.hasClassificationReason("explicit-review-and-fix-request"));
        assertTrue(contract.hasClassificationReason(TaskContractResolver.CONTEXTUAL_STATIC_WEB_SURFACE));
        assertFalse(contract.hasClassificationReason("explicit-review"));
        assertFalse(contract.hasClassificationReason(""));
        assertEquals("explicit-review-and-fix-request", contract.primaryClassificationReason());
    }

    @Test
    void aMarkedReviewAndFixContractKeepsItsConditionalPolicy() {
        assertTrue(ConditionalReviewFixPolicy.isConditionalReviewAndFix(
                contract("explicit-review-and-fix-request+" + TaskContractResolver.CONTEXTUAL_STATIC_WEB_SURFACE)));
    }

    private static TaskContract contract(String reason) {
        return new TaskContract(TaskType.FILE_EDIT, true, true, true, Set.of(), Set.of(), "review and fix it", reason);
    }
}
