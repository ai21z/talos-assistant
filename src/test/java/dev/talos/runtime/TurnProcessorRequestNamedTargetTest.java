package dev.talos.runtime;

import dev.talos.runtime.intent.ArtifactTargetSet;
import dev.talos.runtime.intent.IntentDerivation;
import dev.talos.runtime.intent.TargetRef;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.intent.TargetSource;
import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L5: a remembered session approval is bound to the targets the current request named (MICA authority origin). */
class TurnProcessorRequestNamedTargetTest {
    private static final String REQUEST = "Update index.html.";

    @TempDir
    Path workspace;

    @Test
    void aFileTheRequestNamesForChangeIsNamedInEverySpellingOfIt() {
        String request = "Update index.html so the heading says Done.";
        TaskIntent intent = TaskContractResolver.resolveFromUserRequest(request).intent();
        assertTrue(TurnProcessor.targetNamedByRequest(intent, request, workspace, "index.html"), intent.toString());
        assertTrue(TurnProcessor.targetNamedByRequest(intent, request, workspace, "./index.html"));
        assertTrue(TurnProcessor.targetNamedByRequest(intent, request, workspace,
                workspace.resolve("index.html").toString()));
        assertFalse(TurnProcessor.targetNamedByRequest(intent, request, workspace, "style.css"));
    }

    @Test
    void aRequestWithoutAFileNameNamesNoTarget() {
        String request = "Fix the typo in the story.";
        TaskIntent intent = TaskContractResolver.resolveFromUserRequest(request).intent();
        assertFalse(TurnProcessor.targetNamedByRequest(intent, request, workspace, "story.txt"), intent.toString());
    }

    @Test
    void aTargetRecordedFromAnotherMessageIsNotNamedByTheCurrentOne() {
        String origin = "Please save story.txt now. Write a short story.";
        TaskIntent intent = TaskContractResolver.resolveFromUserRequest(origin).intent();
        assertTrue(TurnProcessor.targetNamedByRequest(intent, origin, workspace, "story.txt"), intent.toString());
        assertFalse(TurnProcessor.targetNamedByRequest(intent, "Save draft 0123456789ab", workspace, "story.txt"));
    }

    @ParameterizedTest
    @EnumSource(value = TargetSource.class, names = "USER_REQUEST", mode = EnumSource.Mode.EXCLUDE)
    void aTargetFromAnyOtherSourceIsNotNamed(TargetSource source) {
        assertFalse(TurnProcessor.targetNamedByRequest(
                intent(TargetRole.MUST_MUTATE, source), REQUEST, workspace, "index.html"));
    }

    @ParameterizedTest
    @EnumSource(value = TargetRole.class,
            names = {"MUST_MUTATE", "MUST_MUTATE_AFTER_SOURCE_EVIDENCE", "OUTPUT_DESTINATION", "MAY_MUTATE"})
    void aRequestTargetToBeChangedIsNamed(TargetRole role) {
        assertTrue(TurnProcessor.targetNamedByRequest(
                intent(role, TargetSource.USER_REQUEST), REQUEST, workspace, "index.html"));
    }

    @ParameterizedTest
    @EnumSource(value = TargetRole.class,
            names = {"FORBIDDEN", "MUST_READ", "SOURCE_EVIDENCE", "VERIFY_ONLY", "MENTIONED_ONLY"})
    void aRequestTargetThatIsNotToBeChangedIsNotNamed(TargetRole role) {
        assertFalse(TurnProcessor.targetNamedByRequest(
                intent(role, TargetSource.USER_REQUEST), REQUEST, workspace, "index.html"));
    }

    @Test
    void missingInputsNameNoTarget() {
        TaskIntent intent = intent(TargetRole.MUST_MUTATE, TargetSource.USER_REQUEST);
        assertFalse(TurnProcessor.targetNamedByRequest(null, REQUEST, workspace, "index.html"));
        assertFalse(TurnProcessor.targetNamedByRequest(intent, null, workspace, "index.html"));
        assertFalse(TurnProcessor.targetNamedByRequest(intent, REQUEST, null, "index.html"));
        assertFalse(TurnProcessor.targetNamedByRequest(intent, REQUEST, workspace, null));
        assertFalse(TurnProcessor.targetNamedByRequest(intent, REQUEST, workspace, " "));
    }

    private static TaskIntent intent(TargetRole role, TargetSource source) {
        TargetRef target = new TargetRef("index.html", role,
                new IntentDerivation(source, "fixture", 7, 17, "index.html", 1.0));
        return new TaskIntent(TaskType.FILE_EDIT, true, true, true, ArtifactTargetSet.of(target),
                REQUEST, "fixture");
    }
}
