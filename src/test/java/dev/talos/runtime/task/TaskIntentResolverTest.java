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
}
