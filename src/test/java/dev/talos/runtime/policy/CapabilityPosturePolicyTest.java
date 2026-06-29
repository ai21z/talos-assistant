package dev.talos.runtime.policy;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CapabilityPosturePolicyTest {

    @Test
    void planReadOnlyDoesNotTreatCreateTargetAsReadEvidence() {
        TaskContract raw = TaskContractResolver.fromUserRequest(
                "Create plan-refuse.txt with exactly PLAN MODE MUST NOT WRITE.");

        TaskContract effective = CapabilityPosturePolicy
                .apply(CapabilityPosture.PLAN_READ_ONLY, raw)
                .taskContract();

        assertFalse(effective.mutationAllowed());
        assertEquals(Set.of(), effective.expectedTargets());
        assertEquals(Set.of(), effective.sourceEvidenceTargets());
    }

    @Test
    void askReadOnlyDoesNotTreatCreateTargetAsReadEvidence() {
        TaskContract raw = TaskContractResolver.fromUserRequest(
                "Create ask-refuse.txt with exactly ASK MODE MUST NOT WRITE.");

        TaskContract effective = CapabilityPosturePolicy
                .apply(CapabilityPosture.ASK_READ_ONLY, raw)
                .taskContract();

        assertFalse(effective.mutationAllowed());
        assertEquals(Set.of(), effective.expectedTargets());
        assertEquals(Set.of(), effective.sourceEvidenceTargets());
    }

    @Test
    void planReadOnlyDoesNotTreatNewFilePlanTargetAsReadEvidence() {
        TaskContract raw = TaskContractResolver.fromUserRequest(
                "Plan how to add a new file plan-only.txt with exactly PLAN ONLY. Do not edit anything.");

        TaskContract effective = CapabilityPosturePolicy
                .apply(CapabilityPosture.PLAN_READ_ONLY, raw)
                .taskContract();

        assertFalse(effective.mutationAllowed());
        assertEquals(Set.of(), effective.expectedTargets());
    }

    @Test
    void readOnlyCreateFromSourceKeepsSourceEvidenceButDropsOutputTarget() {
        TaskContract raw = TaskContractResolver.fromUserRequest(
                "Summarize long-notes.txt into docs/summary.md.");

        TaskContract effective = CapabilityPosturePolicy
                .apply(CapabilityPosture.PLAN_READ_ONLY, raw)
                .taskContract();

        assertFalse(effective.mutationAllowed());
        assertEquals(Set.of(), effective.expectedTargets());
        assertEquals(Set.of("long-notes.txt"), effective.sourceEvidenceTargets());
    }

    @Test
    void readOnlyEditTargetCanStillBeReadForPlanning() {
        TaskContract raw = TaskContractResolver.fromUserRequest(
                "Edit README.md to add usage notes.");

        TaskContract effective = CapabilityPosturePolicy
                .apply(CapabilityPosture.PLAN_READ_ONLY, raw)
                .taskContract();

        assertFalse(effective.mutationAllowed());
        assertEquals(Set.of("README.md"), effective.expectedTargets());
    }
}
