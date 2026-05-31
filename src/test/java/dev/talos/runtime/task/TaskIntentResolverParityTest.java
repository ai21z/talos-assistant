package dev.talos.runtime.task;

import dev.talos.runtime.intent.TaskContractCompiler;
import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.intent.TaskIntentResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskIntentResolverParityTest {

    @Test
    void rolefulProjectionMatchesLegacyContractsForRepresentativePrompts() {
        for (String prompt : List.of(
                "Edit index.html so the title says Night Signal.",
                "Create office-summary.md summarizing board-brief.pdf, client-notes.docx, and revenue.xlsx.",
                "Replace .missing-button with #submit in script.js. Do not edit scripts.js.",
                "Which file does index.html import for the BMI script, script.js or scripts.js?",
                "Create a modern synthwave website here with CSS styling and JavaScript interaction.",
                "Review index.html. Do not change anything.")) {
            TaskContract legacy = TaskContractResolver.resolveLegacyFromUserRequest(prompt);
            TaskIntent intent = TaskIntentResolver.fromLegacyContract(legacy);
            TaskContract projected = TaskContractCompiler.compile(intent);

            assertSameContract(legacy, projected, prompt);
            assertSameContract(legacy, TaskContractResolver.fromUserRequest(prompt), prompt);
        }
    }

    @Test
    void nullAndBlankRequestsRemainUnknownThroughRolefulPath() {
        for (String prompt : List.of("", "   ")) {
            TaskContract legacy = TaskContractResolver.resolveLegacyFromUserRequest(prompt);
            TaskContract projected = TaskContractCompiler.compile(TaskIntentResolver.fromLegacyContract(legacy));

            assertSameContract(legacy, projected, "blank prompt");
            assertSameContract(legacy, TaskContractResolver.fromUserRequest(prompt), "blank prompt");
        }
    }

    private static void assertSameContract(TaskContract expected, TaskContract actual, String prompt) {
        assertEquals(expected.type(), actual.type(), prompt);
        assertEquals(expected.mutationRequested(), actual.mutationRequested(), prompt);
        assertEquals(expected.mutationAllowed(), actual.mutationAllowed(), prompt);
        assertEquals(expected.verificationRequired(), actual.verificationRequired(), prompt);
        assertEquals(expected.expectedTargets(), actual.expectedTargets(), prompt);
        assertEquals(expected.sourceEvidenceTargets(), actual.sourceEvidenceTargets(), prompt);
        assertEquals(expected.forbiddenTargets(), actual.forbiddenTargets(), prompt);
        assertEquals(expected.originalUserRequest(), actual.originalUserRequest(), prompt);
        assertEquals(expected.classificationReason(), actual.classificationReason(), prompt);
    }
}
