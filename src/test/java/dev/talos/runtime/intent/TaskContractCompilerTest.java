package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskContractCompilerTest {

    @Test
    void projectsMustMutateAndOutputDestinationToExpectedTargets() {
        TaskIntent intent = new TaskIntent(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                ArtifactTargetSet.of(
                        TargetRef.of("styles.css", TargetRole.MUST_MUTATE),
                        TargetRef.of("dist/report.md", TargetRole.OUTPUT_DESTINATION),
                        TargetRef.of("index.html", TargetRole.VERIFY_ONLY),
                        TargetRef.of("scripts.js", TargetRole.MAY_MUTATE),
                        TargetRef.of("README.md", TargetRole.MENTIONED_ONLY)),
                "Rewrite styles.css so index.html still works.",
                "roleful-intent-test");

        TaskContract contract = TaskContractCompiler.compile(intent);

        assertEquals(TaskType.FILE_EDIT, contract.type());
        assertTrue(contract.mutationRequested());
        assertTrue(contract.mutationAllowed());
        assertTrue(contract.verificationRequired());
        assertEquals(Set.of("styles.css", "dist/report.md"), contract.expectedTargets());
        assertFalse(contract.expectedTargets().contains("index.html"));
        assertFalse(contract.expectedTargets().contains("scripts.js"));
        assertFalse(contract.expectedTargets().contains("README.md"));
        assertEquals("Rewrite styles.css so index.html still works.", contract.originalUserRequest());
        assertEquals("roleful-intent-test", contract.classificationReason());
    }

    @Test
    void projectsSourceEvidenceMustReadAndForbiddenTargets() {
        TaskIntent intent = new TaskIntent(
                TaskType.FILE_CREATE,
                true,
                true,
                true,
                ArtifactTargetSet.of(
                        TargetRef.of("summary.md", TargetRole.OUTPUT_DESTINATION),
                        TargetRef.of("board-brief.pdf", TargetRole.SOURCE_EVIDENCE),
                        TargetRef.of("notes.md", TargetRole.MUST_READ),
                        TargetRef.of(".env", TargetRole.FORBIDDEN),
                        TargetRef.of("index.html", TargetRole.VERIFY_ONLY)),
                "Create summary.md from board-brief.pdf and notes.md. Do not touch .env.",
                "source-to-target");

        TaskContract contract = TaskContractCompiler.compile(intent);

        assertEquals(Set.of("summary.md"), contract.expectedTargets());
        assertEquals(Set.of("board-brief.pdf", "notes.md"), contract.sourceEvidenceTargets());
        assertEquals(Set.of(".env"), contract.forbiddenTargets());
        assertFalse(contract.sourceEvidenceTargets().contains("index.html"));
    }

    @Test
    void defaultsNullIntentFieldsWithoutThrowing() {
        TaskIntent intent = new TaskIntent(null, false, false, false, null, null, null);

        TaskContract contract = TaskContractCompiler.compile(intent);

        assertEquals(TaskType.UNKNOWN, contract.type());
        assertFalse(contract.mutationRequested());
        assertFalse(contract.mutationAllowed());
        assertFalse(contract.verificationRequired());
        assertEquals(Set.of(), contract.expectedTargets());
        assertEquals(Set.of(), contract.sourceEvidenceTargets());
        assertEquals(Set.of(), contract.forbiddenTargets());
        assertEquals("", contract.originalUserRequest());
        assertEquals("", contract.classificationReason());
    }

    @Test
    void nullIntentCompilesToUnknownContract() {
        TaskContract contract = TaskContractCompiler.compile(null);

        assertEquals(TaskType.UNKNOWN, contract.type());
        assertEquals(Set.of(), contract.expectedTargets());
        assertEquals("", contract.originalUserRequest());
    }

    @Test
    void existingTaskContractResolverBehaviorRemainsUnchanged() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Create a modern synthwave website here with CSS styling and JavaScript interaction.");

        assertEquals(TaskType.FILE_CREATE, contract.type());
        assertTrue(contract.mutationAllowed());
        assertEquals(Set.of("index.html", "style.css", "script.js"), contract.expectedTargets());
    }
}
