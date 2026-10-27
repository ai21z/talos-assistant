package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskIntentProjectorTest {

    @Test
    void changedCanonicalRequestCannotRetainPriorLexicalOffsets() {
        String original =
                "Edit index.html so it works, and update style.css too.";
        TaskIntent prior =
                TaskContractResolver.resolveFromUserRequest(original).intent();
        TargetRef priorStyle = prior.targets().find("style.css").orElseThrow();
        String projectedRequest = "Continue the approved edit to style.css.";
        TaskContract effective = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("style.css"),
                Set.of(),
                Set.of(),
                projectedRequest,
                "repair-projection");

        TaskIntent projected = TaskIntentProjector.project(
                prior,
                effective,
                TargetSource.REPAIR_CONTEXT,
                "repair-projection");
        TargetRef style = projected.targets().find("style.css").orElseThrow();

        assertEquals(IntentDerivation.UNKNOWN_OFFSET, style.derivation().startOffset());
        assertEquals(
                IntentDerivation.UNKNOWN_OFFSET,
                style.derivation().authorityEndOffset());
        assertEquals(IntentDerivation.UNKNOWN_OFFSET, style.derivation().endOffset());
        assertEquals("", style.derivation().sourceText());
        assertEquals(TargetSource.REPAIR_CONTEXT, style.derivation().source());
        assertEquals("repair-projection", style.derivation().reason());
        assertEquals(TargetRole.MUST_MUTATE, style.role());
        assertEquals(projectedRequest, projected.originalUserRequest());
        assertEquals(TargetRole.MUST_MUTATE, priorStyle.role());
        assertEquals(1, projected.targets().targets().size());
    }

    @Test
    void sameRequestAliasProjectionPreservesOneExactDerivation() {
        String request =
                "Edit index.html so it works, and update ./style.css too.";
        TaskIntent prior =
                TaskContractResolver.resolveFromUserRequest(request).intent();
        TargetRef priorStyle = prior.targets().find("style.css").orElseThrow();
        TaskContract effective = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("./style.css"),
                Set.of(),
                Set.of(),
                request,
                "alias-projection");

        TaskIntent projected = TaskIntentProjector.project(
                prior,
                effective,
                TargetSource.RUNTIME_DEFAULT,
                "alias-projection");
        TargetRef style = projected.targets().find("style.css").orElseThrow();

        assertEquals(
                1L,
                projected.targets().targets().stream()
                        .filter(target -> target.path().equals("style.css"))
                        .count());
        assertEquals(1, projected.targets().targets().size());
        assertEquals(priorStyle.derivation(), style.derivation());
        assertEquals(
                request.lastIndexOf("style.css") + "style.css".length(),
                style.derivation().authorityEndOffset());
        assertEquals(
                style.derivation().sourceText(),
                request.substring(
                        style.derivation().startOffset(),
                        style.derivation().endOffset()));
    }

    @Test
    void replayedUserEvidenceRetainsTextButInvalidatesEveryLexicalOffset() {
        String request =
                "Edit index.html so it works, and update style.css too.";
        TaskIntent prior =
                TaskContractResolver.resolveFromUserRequest(request).intent();
        TargetRef priorStyle = prior.targets().find("style.css").orElseThrow();

        TaskIntent replayed =
                TaskIntentProjector.relabelUserRequestEvidenceAsMessageHistory(
                        prior);
        TargetRef style = replayed.targets().find("style.css").orElseThrow();

        assertEquals(TargetSource.MESSAGE_HISTORY, style.derivation().source());
        assertEquals(priorStyle.derivation().sourceText(), style.derivation().sourceText());
        assertEquals(IntentDerivation.UNKNOWN_OFFSET, style.derivation().startOffset());
        assertEquals(
                IntentDerivation.UNKNOWN_OFFSET,
                style.derivation().authorityEndOffset());
        assertEquals(IntentDerivation.UNKNOWN_OFFSET, style.derivation().endOffset());
    }

    @Test
    void effectiveEmptyMutationContractDoesNotRetainDemotedAuthority() {
        String request = "Create style.css.";
        TaskIntent prior =
                TaskContractResolver.resolveFromUserRequest(request).intent();
        TaskContract effective = new TaskContract(
                TaskType.FILE_CREATE,
                true,
                false,
                true,
                Set.of(),
                Set.of(),
                Set.of(),
                request,
                "plan-read-only");

        TaskIntent projected = TaskIntentProjector.project(
                prior,
                effective,
                TargetSource.RUNTIME_DEFAULT,
                "plan-read-only");

        assertEquals(0, projected.targets().targets().size());
    }
}
