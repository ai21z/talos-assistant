package dev.talos.runtime.task;

import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.intent.TaskContractCompiler;
import dev.talos.runtime.intent.TaskIntentResolver;
import dev.talos.runtime.intent.TargetRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskIntentResolverTest {

    private static final String RETROCATS_AUDIT_PROMPT =
            "Create a complete modern dark synthwave static website for a band called Retrocats. "
                    + "Use exactly index.html, style.css, and script.js as the local files. "
                    + "Use Tailwind correctly only through the official browser CDN or through generated CSS. "
                    + "Do not create a local tailwind.min.css file, no broken tailwind.min.css, "
                    + "no placeholder Tailwind file, and no unprocessed @tailwind directives. "
                    + "The site must preserve these required visible facts: Retrocats, Costanza, Merri, "
                    + "formed in 2024, analog synth sounds, electric guitars, 80s rock and metal blended "
                    + "with synthwave, Cassette Love, Nine-zero vhs, Future tense, Past Perfect Vibes, "
                    + "Dust to Dust, Gold for the old, Life span, Rome 15 July 2026, Barcelona 18 July 2026, "
                    + "Berlin 22 July 2026. Make it visually strong: dark base, pink/orange synthwave "
                    + "accents, band hero, albums, top songs, concerts, and a small interactive JavaScript enhancement.";

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

    @Test
    void rolefulIntentKeepsExplicitForbiddenTargetsOutOfMutationTargetsOnCommonPath() {
        String prompt = "Rewrite styles.css so index.html still works. "
                + "Do not edit index.html. Do not edit scripts.js.";

        TaskIntent intent = TaskIntentResolver.fromUserRequest(
                prompt,
                TaskContractResolver.resolveLegacyFromUserRequest(prompt));
        TaskContract projected = TaskContractCompiler.compile(intent);

        assertEquals(TaskType.FILE_EDIT, intent.type());
        assertEquals(TargetRole.MUST_MUTATE, intent.targets().find("styles.css").orElseThrow().role());
        assertEquals(TargetRole.FORBIDDEN, intent.targets().find("index.html").orElseThrow().role());
        assertEquals(TargetRole.FORBIDDEN, intent.targets().find("scripts.js").orElseThrow().role());
        assertEquals(java.util.Set.of("styles.css"), projected.expectedTargets());
        assertEquals(java.util.Set.of("index.html", "scripts.js"), projected.forbiddenTargets());
    }

    @Test
    void rolefulIntentCapturesMultipleConsecutiveForbiddenTargetsOnParityPath() {
        String prompt = "Edit styles.css. Do not edit index.html. Do not edit scripts.js.";

        TaskIntent intent = TaskIntentResolver.fromUserRequest(
                prompt,
                TaskContractResolver.resolveLegacyFromUserRequest(prompt));
        TaskContract projected = TaskContractCompiler.compile(intent);

        assertEquals(TaskType.FILE_EDIT, intent.type());
        assertEquals(TargetRole.MUST_MUTATE, intent.targets().find("styles.css").orElseThrow().role());
        assertEquals(TargetRole.FORBIDDEN, intent.targets().find("index.html").orElseThrow().role());
        assertEquals(TargetRole.FORBIDDEN, intent.targets().find("scripts.js").orElseThrow().role());
        assertEquals(java.util.Set.of("styles.css"), projected.expectedTargets());
        assertEquals(java.util.Set.of("index.html", "scripts.js"), projected.forbiddenTargets());
    }

    @Test
    void rolefulIntentKeepsExactStaticWebFileListAsRequiredTargets() {
        TaskIntent intent = TaskIntentResolver.fromUserRequest(
                RETROCATS_AUDIT_PROMPT,
                TaskContractResolver.resolveLegacyFromUserRequest(RETROCATS_AUDIT_PROMPT));
        TaskContract projected = TaskContractCompiler.compile(intent);

        assertEquals(TaskType.FILE_CREATE, intent.type());
        assertEquals(TargetRole.MUST_MUTATE, intent.targets().find("index.html").orElseThrow().role());
        assertEquals(TargetRole.MUST_MUTATE, intent.targets().find("style.css").orElseThrow().role());
        assertEquals(TargetRole.MUST_MUTATE, intent.targets().find("script.js").orElseThrow().role());
        assertEquals(TargetRole.FORBIDDEN, intent.targets().find("tailwind.min.css").orElseThrow().role());
        assertEquals(TargetRole.FORBIDDEN, intent.targets().find("tailwind.css").orElseThrow().role());
        assertEquals(java.util.Set.of("index.html", "style.css", "script.js"), projected.expectedTargets());
        assertEquals(java.util.Set.of("tailwind.css", "tailwind.min.css"), projected.forbiddenTargets());
    }
}
