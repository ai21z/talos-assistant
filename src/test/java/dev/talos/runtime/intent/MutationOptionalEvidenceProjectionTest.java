package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationOptionalEvidenceProjectionTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "Update styles.css if needed. Update styles.css.",
            "Update styles.css. Update styles.css if needed."
    })
    void independentRequiredOccurrenceSurvivesOptionalSourceOverlap(String request) {
        TaskContract legacy = new TaskContract(
                TaskType.FILE_EDIT, true, true, true,
                Set.of("styles.css"), Set.of("styles.css"), Set.of(),
                request, "independent-required-source-overlap");

        TaskIntent intent = TaskIntentResolver.fromUserRequest(request, legacy);
        TaskContract projected = TaskContractCompiler.compile(intent);

        assertTrue(projected.mutationAllowed());
        assertEquals(Set.of("styles.css"), projected.expectedTargets());
        assertEquals(Set.of("styles.css"), projected.sourceEvidenceTargets());
        assertEquals(TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE,
                intent.targets().find("styles.css").orElseThrow().role());
    }
}
