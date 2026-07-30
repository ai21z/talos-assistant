package dev.talos.runtime.task;

import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.intent.TaskContractCompiler;
import dev.talos.runtime.intent.TaskIntentResolver;
import dev.talos.runtime.intent.TargetRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void overlappingLegacyContractRoundTripsWithoutLosingSourceEvidence() {
        TaskContract legacy = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                java.util.Set.of("styles.css"),
                java.util.Set.of("styles.css"),
                java.util.Set.of(),
                "Read styles.css, then update styles.css.",
                "overlap-round-trip");

        TaskIntent intent = TaskIntentResolver.fromLegacyContract(legacy);
        TaskContract projected = TaskContractCompiler.compile(intent);

        assertEquals(
                "MUST_MUTATE_AFTER_SOURCE_EVIDENCE",
                intent.targets().find("styles.css").orElseThrow().role().name());
        assertEquals(java.util.Set.of("styles.css"), projected.expectedTargets());
        assertEquals(java.util.Set.of("styles.css"), projected.sourceEvidenceTargets());
    }

    @Test
    void lexicalPathAliasesStillRoundTripAsOneCompositeObligation() {
        for (TaskContract legacy : java.util.List.of(
                new TaskContract(
                        TaskType.FILE_EDIT,
                        true,
                        true,
                        true,
                        java.util.Set.of("./assets/style.css"),
                        java.util.Set.of("assets\\style.css"),
                        java.util.Set.of(),
                        "Read assets/style.css, then update assets/style.css.",
                        "expected-alias"),
                new TaskContract(
                        TaskType.FILE_EDIT,
                        true,
                        true,
                        true,
                        java.util.Set.of("assets\\style.css"),
                        java.util.Set.of("./assets/style.css"),
                        java.util.Set.of(),
                        "Read assets/style.css, then update assets/style.css.",
                        "source-alias"))) {
            TaskIntent intent = TaskIntentResolver.fromLegacyContract(legacy);
            TaskContract projected = TaskContractCompiler.compile(intent);

            assertEquals(1, intent.targets().targets().size(), legacy.toString());
            assertEquals(
                    "MUST_MUTATE_AFTER_SOURCE_EVIDENCE",
                    intent.targets().find("assets/style.css").orElseThrow().role().name(),
                    legacy.toString());
            assertEquals(java.util.Set.of("assets/style.css"), projected.expectedTargets());
            assertEquals(java.util.Set.of("assets/style.css"), projected.sourceEvidenceTargets());
        }
    }

    @Test
    void optionalPostReadMutationFailsClosedWithoutRequiredCompositeAuthority() {
        String prompt = "Update styles.css if needed.";
        TaskContract legacy = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                java.util.Set.of("styles.css"),
                java.util.Set.of("styles.css"),
                java.util.Set.of(),
                prompt,
                "optional-post-read-test");

        TaskIntent intent = TaskIntentResolver.fromUserRequest(prompt, legacy);
        TaskContract projected = TaskContractCompiler.compile(intent);

        assertFalse(intent.mutationAllowed());
        assertTrue(intent.targets().targets().stream()
                .noneMatch(target -> "MUST_MUTATE_AFTER_SOURCE_EVIDENCE"
                        .equals(target.role().name())));
        assertTrue(intent.targets().targets().stream()
                .noneMatch(target -> target.role()
                        == dev.talos.runtime.intent.TargetRole.MUST_MUTATE));
        assertEquals(
                dev.talos.runtime.intent.TargetRole.SOURCE_EVIDENCE,
                intent.targets().find("styles.css").orElseThrow().role());
        assertFalse(projected.mutationAllowed());
        assertTrue(projected.expectedTargets().isEmpty());
        assertEquals(java.util.Set.of("styles.css"), projected.sourceEvidenceTargets());
    }

    @Test
    void lexicalAliasCannotTurnOptionalPostReadMutationIntoRequiredAuthority() {
        for (TaskContract legacy : java.util.List.of(
                new TaskContract(
                        TaskType.FILE_EDIT,
                        true,
                        true,
                        true,
                        java.util.Set.of("./styles.css"),
                        java.util.Set.of("styles.css"),
                        java.util.Set.of(),
                        "Update styles.css if needed.",
                        "expected-alias"),
                new TaskContract(
                        TaskType.FILE_EDIT,
                        true,
                        true,
                        true,
                        java.util.Set.of("styles.css"),
                        java.util.Set.of("./styles.css"),
                        java.util.Set.of(),
                        "Update styles.css if needed.",
                        "source-alias"))) {
            TaskContract projected = TaskContractCompiler.compile(
                    TaskIntentResolver.fromUserRequest(
                            legacy.originalUserRequest(),
                            legacy));

            assertFalse(projected.mutationAllowed(), legacy.toString());
            assertTrue(projected.expectedTargets().isEmpty(), legacy.toString());
            assertEquals(
                    java.util.Set.of("styles.css"),
                    projected.sourceEvidenceTargets(),
                    legacy.toString());
        }
    }

    @Test
    void windowsSeparatorRequestKeepsOptionalAndRequiredPostReadRolesDistinct() {
        TaskContract optionalLegacy = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                java.util.Set.of("assets/style.css"),
                java.util.Set.of("assets\\style.css"),
                java.util.Set.of(),
                "Update assets\\style.css if needed.",
                "optional-windows-separator");

        TaskContract optionalProjected = TaskContractCompiler.compile(
                TaskIntentResolver.fromUserRequest(
                        optionalLegacy.originalUserRequest(),
                        optionalLegacy));

        assertFalse(optionalProjected.mutationAllowed());
        assertTrue(optionalProjected.expectedTargets().isEmpty());
        assertEquals(
                java.util.Set.of("assets/style.css"),
                optionalProjected.sourceEvidenceTargets());

        TaskContract requiredLegacy = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                java.util.Set.of("assets/style.css"),
                java.util.Set.of("assets\\style.css"),
                java.util.Set.of(),
                "Update assets\\style.css.",
                "required-windows-separator");

        TaskContract requiredProjected = TaskContractCompiler.compile(
                TaskIntentResolver.fromUserRequest(
                        requiredLegacy.originalUserRequest(),
                        requiredLegacy));

        assertTrue(requiredProjected.mutationAllowed());
        assertEquals(
                java.util.Set.of("assets/style.css"),
                requiredProjected.expectedTargets());
        assertEquals(
                java.util.Set.of("assets/style.css"),
                requiredProjected.sourceEvidenceTargets());
    }

    @Test
    void optionalPostReadOverlapDoesNotRemoveUnrelatedRequiredMutation() {
        String prompt = "Update styles.css if needed. Rewrite index.html.";
        TaskContract legacy = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                java.util.Set.of("styles.css", "index.html"),
                java.util.Set.of("styles.css"),
                java.util.Set.of(),
                prompt,
                "mixed-required-and-optional-post-read");

        TaskContract projected = TaskContractCompiler.compile(
                TaskIntentResolver.fromUserRequest(prompt, legacy));

        assertTrue(projected.mutationAllowed());
        assertEquals(java.util.Set.of("index.html"), projected.expectedTargets());
        assertEquals(java.util.Set.of("styles.css"), projected.sourceEvidenceTargets());
    }

    @Test
    void optionalPostReadOverlapDoesNotUpgradeAnotherOptionalTarget() {
        String prompt = "Update styles.css if needed. Update index.html if needed.";
        TaskContract legacy = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                java.util.Set.of("styles.css", "index.html"),
                java.util.Set.of("styles.css"),
                java.util.Set.of(),
                prompt,
                "all-optional-with-post-read-overlap");

        TaskContract projected = TaskContractCompiler.compile(
                TaskIntentResolver.fromUserRequest(prompt, legacy));

        assertFalse(projected.mutationAllowed());
        assertTrue(projected.expectedTargets().isEmpty());
        assertEquals(java.util.Set.of("styles.css"), projected.sourceEvidenceTargets());
    }

    @Test
    void readOnlyOverlapUsesReadRoleAndNeverCarriesCompositeMutationAuthority() {
        TaskContract legacy = new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                java.util.Set.of("styles.css"),
                java.util.Set.of("styles.css"),
                java.util.Set.of(),
                "Read styles.css.",
                "read-only-overlap");

        TaskIntent intent = TaskIntentResolver.fromLegacyContract(legacy);
        TaskContract projected = TaskContractCompiler.compile(intent);

        assertEquals(
                dev.talos.runtime.intent.TargetRole.MUST_READ,
                intent.targets().find("styles.css").orElseThrow().role());
        assertFalse(intent.targets().targets().stream()
                .anyMatch(target -> "MUST_MUTATE_AFTER_SOURCE_EVIDENCE"
                        .equals(target.role().name())));
        assertFalse(projected.mutationAllowed());
        assertEquals(java.util.Set.of("styles.css"), projected.expectedTargets());
        assertEquals(java.util.Set.of("styles.css"), projected.sourceEvidenceTargets());
    }

    @Test
    void ordinaryReadOnlyExpectedTargetRoundTripsWithoutInventingSourceEvidence() {
        TaskContract legacy = new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                java.util.Set.of("calc.py"),
                java.util.Set.of(),
                java.util.Set.of(),
                "Inspect calc.py.",
                "ordinary-read-only");

        TaskContract projected =
                TaskContractCompiler.compile(TaskIntentResolver.fromLegacyContract(legacy));

        assertEquals(legacy, projected);
        assertTrue(projected.sourceEvidenceTargets().isEmpty());
    }

    @Test
    void nullLegacyContractRemainsUnknownWithoutThrowing() {
        TaskIntent intent = TaskIntentResolver.fromUserRequest("Inspect calc.py.", null);
        TaskContract projected = TaskContractCompiler.compile(intent);

        assertEquals(TaskType.UNKNOWN, projected.type());
        assertTrue(projected.expectedTargets().isEmpty());
        assertTrue(projected.sourceEvidenceTargets().isEmpty());
        assertFalse(projected.mutationAllowed());
    }

    @Test
    void scopedSymbolRenameProjectsEditedAndUnchangedFilesWithDistinctRoles() {
        String prompt = "Rename reset to resetAll in script.js only. "
                + "scripts.js is a different file and must remain unchanged.";

        TaskIntent intent = TaskIntentResolver.fromUserRequest(
                prompt,
                TaskContractResolver.resolveLegacyFromUserRequest(prompt));
        TaskContract projected = TaskContractCompiler.compile(intent);

        assertEquals(TargetRole.MUST_MUTATE, intent.targets().find("script.js").orElseThrow().role());
        assertEquals(TargetRole.FORBIDDEN, intent.targets().find("scripts.js").orElseThrow().role());
        assertEquals(java.util.Set.of("script.js"), projected.expectedTargets());
        assertEquals(java.util.Set.of("scripts.js"), projected.forbiddenTargets());
    }

    @Test
    void windowsSeparatorPreserveAliasKeepsTruthfulDerivation() {
        String prompt = "Keep assets\\style.css unchanged. Update index.html.";

        TaskIntent intent = TaskIntentResolver.fromUserRequest(
                prompt,
                TaskContractResolver.resolveLegacyFromUserRequest(prompt));

        var preserved = intent.targets().find("assets/style.css").orElseThrow();
        assertEquals(TargetRole.FORBIDDEN, preserved.role());
        assertEquals("preserve-unchanged-target", preserved.derivation().reason());
        assertTrue(preserved.derivation().sourceText().contains("assets\\style.css"));
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
