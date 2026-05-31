package dev.talos.runtime.task;

import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.intent.TaskIntentResolver;
import dev.talos.runtime.intent.TargetRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskIntentResolverTest {

    @Test
    void rolefulIntentTreatsExtraFilesAsScopedOutputConstraint() {
        String prompt = "Improve only styles.css. Do not create extra files. "
                + "Do not modify index.html or scripts.js.";

        TaskIntent intent = TaskIntentResolver.fromUserRequest(
                prompt,
                TaskContractResolver.resolveLegacyFromUserRequest(prompt));

        assertEquals(TaskType.FILE_EDIT, intent.type());
        assertEquals(TargetRole.MUST_MUTATE, intent.targets().find("styles.css").orElseThrow().role());
        assertEquals(TargetRole.FORBIDDEN, intent.targets().find("index.html").orElseThrow().role());
        assertEquals(TargetRole.FORBIDDEN, intent.targets().find("scripts.js").orElseThrow().role());
    }

    @Test
    void rolefulIntentTreatsConstraintTargetsAsVerifyOnly() {
        for (String prompt : java.util.List.of(
                "Rewrite styles.css so index.html still works.",
                "Rewrite styles.css without breaking index.html.",
                "Update styles.css to stay compatible with index.html.")) {
            TaskIntent intent = TaskIntentResolver.fromUserRequest(
                    prompt,
                    TaskContractResolver.resolveLegacyFromUserRequest(prompt));

            assertEquals(TaskType.FILE_EDIT, intent.type(), prompt);
            assertEquals(TargetRole.MUST_MUTATE, intent.targets().find("styles.css").orElseThrow().role(), prompt);
            assertEquals(TargetRole.VERIFY_ONLY, intent.targets().find("index.html").orElseThrow().role(), prompt);
        }
    }
}
