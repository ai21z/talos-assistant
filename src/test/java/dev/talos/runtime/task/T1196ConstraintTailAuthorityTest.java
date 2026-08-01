package dev.talos.runtime.task;

import dev.talos.runtime.intent.TargetRef;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.intent.TaskIntent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class T1196ConstraintTailAuthorityTest {

    private static final String SCENARIO_19_PROMPT =
            "Now apply the smallest fix by editing index.html so the CSS and JavaScript "
                    + ".cta-button selector has a matching element in the HTML, "
                    + "and update style.css too.";

    @Test
    void explicitMutationTailAfterConstraintRetainsAuthorityAndExactSpan() {
        TaskIntent intent = TaskContractResolver.intentFromUserRequest(SCENARIO_19_PROMPT);
        TaskContract contract = TaskContractResolver.fromUserRequest(SCENARIO_19_PROMPT);

        assertEquals(Set.of("index.html", "style.css"), contract.expectedTargets());
        assertRole(intent, "index.html", TargetRole.MUST_MUTATE);
        TargetRef style = assertRole(intent, "style.css", TargetRole.MUST_MUTATE);
        assertEquals("explicit-mutation-target", style.derivation().reason());
        assertEquals("update style.css too.", style.derivation().sourceText());
        assertTrue(style.derivation().startOffset() >= 0);
        assertTrue(style.derivation().endOffset() > style.derivation().startOffset());
        assertEquals(
                style.derivation().sourceText(),
                intent.originalUserRequest().substring(
                        style.derivation().startOffset(),
                        style.derivation().endOffset()));
    }

    @Test
    void ordinaryConstraintTargetRemainsVerificationOnly() {
        for (String prompt : List.of(
                "Rewrite styles.css so index.html still works.",
                "Rewrite styles.css without breaking index.html.",
                "Update styles.css to stay compatible with index.html.")) {
            TaskIntent intent = TaskContractResolver.intentFromUserRequest(prompt);
            TaskContract contract = TaskContractResolver.fromUserRequest(prompt);

            assertEquals(Set.of("styles.css"), contract.expectedTargets(), prompt);
            assertRole(intent, "styles.css", TargetRole.MUST_MUTATE);
            assertRole(intent, "index.html", TargetRole.VERIFY_ONLY);
        }
    }

    @Test
    void quoteDelimitedImperativesNeverGrantTailAuthority() {
        for (String prompt : List.of(
                "Edit index.html so the paragraph says 'and update style.css'.",
                "Edit index.html so the paragraph says "
                        + "'don't then update style.css'.",
                "Edit index.html so the paragraph says "
                        + "'The reviewer doesn't say then update style.css'.",
                "Edit index.html so the paragraph says \"and update style.css\".",
                "Edit index.html so the paragraph says `and update style.css`.",
                "Edit index.html so the paragraph says \"first; update style.css\".",
                "Edit index.html with the literal \" so update style.css\".")) {
            TaskContract contract = TaskContractResolver.fromUserRequest(prompt);

            assertEquals(Set.of("index.html"), contract.expectedTargets(), prompt);
        }
    }

    @Test
    void optionalProposalAndReportedTailsRemainNonAuthoritative() {
        for (String prompt : List.of(
                "Edit index.html so it works, and update style.css if needed.",
                "Edit index.html so it works; if possible, update style.css.",
                "Edit index.html so the ticket proposes: update style.css.",
                "Edit index.html so the guide says update style.css.",
                "Edit index.html so it works; consider whether to update style.css.",
                "Edit index.html so it works; could we update style.css?",
                "Edit index.html so it works; should we update style.css?",
                "Edit index.html so it works; what if we update style.css?",
                "Edit index.html so possibly update style.css.",
                "Edit index.html so update style.css where appropriate.",
                "Edit index.html so you could update style.css.",
                "Edit index.html so you can update style.css.",
                "Edit index.html so, as an option, update style.css.",
                "Edit index.html so the report asks you to update style.css.",
                "Edit index.html so the ticket instructs us to update style.css.",
                "Edit index.html so the guide tells you to update style.css.",
                "Edit index.html so the note requests: update style.css.",
                "Edit index.html so according to the report, update style.css.",
                "Edit index.html so per the report, update style.css.",
                "Edit index.html so the report requires you to update style.css.",
                "Edit index.html so it works, and the proposal from the "
                        + "reviewer is to update style.css.",
                "Edit index.html so a proposal from the report was to "
                        + "update style.css.",
                "Edit index.html so I suggest update style.css.",
                "Edit index.html so potentially update style.css.",
                "Edit index.html so update style.css where needed.",
                "Edit index.html so update style.css only when necessary.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(prompt);

            assertEquals(
                    Set.of("index.html"),
                    resolution.contract().expectedTargets(),
                    prompt);
            assertRole(
                    resolution.intent(),
                    "style.css",
                    TargetRole.MENTIONED_ONLY);
        }
    }

    @Test
    void advisoryListScopeCannotGrantAuthorityToLaterTargets() {
        for (String prompt : List.of(
                "Edit index.html so the report says update style.css, "
                        + "also update script.js.",
                "Edit index.html so the report says update style.css, "
                        + "update script.js.",
                "Edit index.html so the report says update style.css; "
                        + "update script.js.",
                "Edit index.html so the report says update style.css: "
                        + "update script.js.",
                "Edit index.html so the report says update style.css \u2013 "
                        + "update script.js.",
                "Edit index.html so the report says update style.css \u2014 "
                        + "update script.js.",
                "Edit index.html so the report says update style.css\n"
                        + "update script.js.",
                "Edit index.html so the report says:\n"
                        + "- update style.css\n"
                        + "- update script.js.",
                "Edit index.html so maybe update style.css, "
                        + "also update script.js.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(prompt);

            assertEquals(
                    Set.of("index.html"),
                    resolution.contract().expectedTargets(),
                    prompt);
            assertRole(
                    resolution.intent(),
                    "style.css",
                    TargetRole.MENTIONED_ONLY);
            assertRole(
                    resolution.intent(),
                    "script.js",
                    TargetRole.MENTIONED_ONLY);
        }
    }

    @Test
    void naturalBatchWorkspaceAliasesCannotBecomeContentMutationTargets() {
        for (String prompt : List.of(
                "Edit index.html so it works, then create docs and assets, "
                        + "then copy style.css to docs/style.css.",
                "Edit index.html so it works, then create .config and assets, "
                        + "then copy style.css to .config/style.css.",
                "Edit index.html so it works, then create docs.v1 and assets, "
                        + "then copy style.css to docs.v1/style.css.",
                "Edit index.html so it works, then create index.html and style.css, "
                        + "then copy style.css to backup.css.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(prompt);

            assertTrue(resolution.blocksWorkspaceOperations(), prompt);
            assertTrue(
                    resolution.contract().expectedTargets().stream()
                            .noneMatch(target -> !"index.html".equals(target)),
                    prompt);
            resolution.intent().targets().targets().forEach(target ->
                    assertFalse(
                            !"index.html".equals(target.path())
                                    && Set.of(
                                            TargetRole.MUST_MUTATE,
                                            TargetRole
                                                    .MUST_MUTATE_AFTER_SOURCE_EVIDENCE,
                                            TargetRole.OUTPUT_DESTINATION,
                                            TargetRole.MAY_MUTATE)
                                            .contains(target.role()),
                            prompt));
        }

        TaskResolution standalone =
                TaskContractResolver.resolveFromUserRequest(
                        "Edit index.html so it works, then create style.css.");
        assertEquals(
                Set.of("index.html", "style.css"),
                standalone.contract().expectedTargets());
        assertFalse(standalone.blocksWorkspaceOperations());
    }

    @Test
    void standaloneNaturalBatchAuthorityRemainsParentIdentical() {
        for (String prompt : List.of(
                "Create docs or assets, then delete old.",
                "Create docs and/or assets, then delete old.",
                "Create docs & assets, then delete old.",
                "Create docs first and assets, then delete old.",
                "Create docs, then remove old.",
                "Create docs, then rm old.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(prompt);

            assertEquals(Set.of(), resolution.contract().expectedTargets(), prompt);
            assertFalse(resolution.blocksWorkspaceOperations(), prompt);
        }

        TaskResolution move =
                TaskContractResolver.resolveFromUserRequest(
                        "Create docs or assets, then move old to backup.");
        assertEquals(Set.of("backup"), move.contract().expectedTargets());
        assertFalse(move.blocksWorkspaceOperations());
    }

    @Test
    void extensionlessWorkspaceOperandsRemainTypedAndFailClosed() {
        for (String prompt : List.of(
                "Edit index.html so it works, then delete docs.",
                "Edit index.html so it works, then remove docs.",
                "Edit index.html so it works, then rm docs.",
                "Edit index.html so it works, then touch docs.",
                "Edit index.html so it works, then move docs to backup.",
                "Edit index.html so it works, then copy docs to backup.",
                "Edit index.html so it works, then rename docs to backup.",
                "Edit index.html so it works, then create docs directory.",
                "Edit index.html so it works, then make docs folder.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(prompt);

            assertTrue(resolution.blocksWorkspaceOperations(), prompt);
            assertFalse(
                    resolution.contract().expectedTargets().contains("docs"),
                    prompt);
            assertTrue(
                    resolution.mutationTailPlan()
                            .workspaceOperationClauseObservations()
                            .stream()
                            .anyMatch(observation ->
                                    observation.sourceText().contains("docs")
                                            && observation.sourceText().equals(
                                                    prompt.substring(
                                                            observation.startOffset(),
                                                            observation.endOffset()))),
                    prompt);
        }
    }

    @Test
    void targetlessWorkspaceTailFailsClosedWithoutInventingATarget() {
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(
                        "Edit it so it works, then delete.");

        assertTrue(resolution.blocksWorkspaceOperations());
        assertTrue(resolution.blocksUnscopedMutation());
        assertTrue(resolution.contract().expectedTargets().isEmpty());
        assertEquals(
                "delete.",
                resolution.mutationTailPlan()
                        .workspaceOperationClauseObservations()
                        .getFirst()
                        .sourceText());
    }

    @Test
    void independentReadRoleSurvivesLaterNonAuthoritativeMutationMention() {
        String prompt =
                "Read source.md and edit index.html so the report says "
                        + "update source.md.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(prompt);

        assertEquals(
                Set.of("index.html"),
                resolution.contract().expectedTargets(),
                prompt);
        assertEquals(
                Set.of("source.md"),
                resolution.contract().sourceEvidenceTargets(),
                prompt);
        assertRole(
                resolution.intent(),
                "source.md",
                TargetRole.SOURCE_EVIDENCE);
        assertEquals(
                dev.talos.runtime.intent.CoordinatedMutationTailPlan
                        .Authority.NON_AUTHORITATIVE,
                resolution.mutationTailPlan()
                        .decision("source.md")
                        .orElseThrow()
                        .authority(),
                prompt);
    }

    @Test
    void laterExplicitImperativeEscapesEarlierAdvisoryScope() {
        for (String prompt : List.of(
                "Edit index.html so the report says update style.css, "
                        + "but update script.js.",
                "Edit index.html so maybe update style.css, "
                        + "then update script.js.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(prompt);

            assertEquals(
                    Set.of("index.html", "script.js"),
                    resolution.contract().expectedTargets(),
                    prompt);
            assertRole(
                    resolution.intent(),
                    "script.js",
                    TargetRole.MUST_MUTATE);
            assertFalse(
                    resolution.contract().expectedTargets().contains("style.css"),
                    prompt);
        }
    }

    @Test
    void laterSequentialSentenceRemainsOutsideTheT1196TailPlan() {
        String prompt =
                "Edit index.html so the report says update style.css. "
                        + "Update script.js.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(prompt);

        assertFalse(
                resolution.mutationTailPlan().decision("script.js").isPresent(),
                prompt);
    }

    @Test
    void andThenKeepsTheExistingAdvisoryScope() {
        String prompt =
                "Review index.html so the report says update style.css, "
                        + "and then update script.js.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(prompt);

        assertEquals(Set.of(), resolution.contract().expectedTargets(), prompt);
        assertRole(
                resolution.intent(),
                "style.css",
                TargetRole.MENTIONED_ONLY);
        assertRole(
                resolution.intent(),
                "script.js",
                TargetRole.MENTIONED_ONLY);
    }

    @Test
    void optionalTailIsTypedButCannotActivateT1202MutationAuthority() {
        for (String prompt : List.of(
                "Edit index.html so it works, and update style.css if needed.",
                "Edit index.html so it works, and update style.css if tests fail.",
                "Edit index.html so it works, and update style.css as needed.")) {
            TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(prompt);
            TargetRef style =
                    resolution.intent().targets().find("style.css").orElseThrow();

            assertEquals(
                    dev.talos.runtime.intent.CoordinatedMutationTailPlan.Authority.OPTIONAL,
                    resolution.mutationTailPlan().decision("style.css")
                            .orElseThrow().authority(),
                    prompt);
            assertEquals(TargetRole.MENTIONED_ONLY, style.role(), prompt);
            assertEquals(
                    "optional-mutation-awaits-typed-owner",
                    style.derivation().reason(),
                    prompt);
            assertFalse(
                    resolution.contract().expectedTargets().contains("style.css"),
                    prompt);
        }
    }

    @Test
    void alternativesExclusionsAndTouchCannotAcquireMutationAuthority() {
        for (String prompt : List.of(
                "Edit index.html so it works, and update style.css or leave it unchanged.",
                "Edit index.html so it works by changing markup rather than update style.css.",
                "Edit index.html so it works, and touch config.env.")) {
            TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(prompt);

            assertFalse(
                    resolution.contract().expectedTargets().contains("style.css"),
                    prompt);
            assertFalse(
                    resolution.contract().expectedTargets().contains("config.env"),
                    prompt);
            assertTrue(
                    resolution.mutationTailPlan().pathsWithAuthority(
                            dev.talos.runtime.intent.CoordinatedMutationTailPlan.Authority
                                    .REQUIRED).isEmpty(),
                    prompt);
        }

        assertEquals(
                Set.of("index.html", "style.css"),
                TaskContractResolver.fromUserRequest(
                        "Edit index.html so it works, and update style.css "
                                + "to support light or dark mode.")
                        .expectedTargets());
    }

    @Test
    void canonicalPrimaryMutationVerbsRetainParentTargetBesideTypedTail() {
        for (String verb : List.of(
                "Write", "Create", "Save", "Apply", "Implement", "Replace")) {
            String prompt =
                    verb + " index.html so it works, and update style.css too.";
            assertEquals(
                    Set.of("index.html", "style.css"),
                    TaskContractResolver.fromUserRequest(prompt).expectedTargets(),
                    prompt);
        }
    }

    @Test
    void everyLiveContentMutationVerbCanAuthorizeItsDirectTailTarget() {
        for (String verb : List.of(
                "regenerate",
                "generate",
                "scaffold",
                "build",
                "make")) {
            String prompt =
                    "Edit index.html so it works, and "
                            + verb
                            + " style.css too.";

            assertEquals(
                    Set.of("index.html", "style.css"),
                    TaskContractResolver.fromUserRequest(prompt)
                            .expectedTargets(),
                    prompt);
        }
    }

    @Test
    void exactQuotedPrimaryTargetSurvivesTailPlanningWithoutSubstringEscalation() {
        String quoted =
                "Edit \"index.html\" so it works, and update style.css too.";
        TaskResolution quotedResolution =
                TaskContractResolver.resolveFromUserRequest(quoted);
        assertEquals(
                Set.of("index.html", "style.css"),
                quotedResolution.contract().expectedTargets());

        String lookalike =
                "Edit old-style.css so it works, and maybe update style.css.";
        TaskResolution lookalikeResolution =
                TaskContractResolver.resolveFromUserRequest(lookalike);
        assertEquals(
                Set.of("old-style.css"),
                lookalikeResolution.contract().expectedTargets());
        assertEquals(
                TargetRole.MENTIONED_ONLY,
                lookalikeResolution.intent().targets().find("style.css")
                        .orElseThrow().role());
    }

    @Test
    void prohibitionsAndWorkspaceOperationsCannotBecomeOrdinaryEditAuthority() {
        for (String prompt : List.of(
                "Edit index.html so it works, update style.css; do not edit style.css.",
                "Edit index.html so it works, then rename style.css to script.js.",
                "Edit index.html so it works, then move style.css to script.js.",
                "Edit index.html so it works, then copy style.css to script.js.",
                "Edit index.html so it works, then delete style.css.",
                "Edit index.html so it works, then create directory style.css.")) {
            TaskContract contract = TaskContractResolver.fromUserRequest(prompt);

            assertFalse(contract.expectedTargets().contains("style.css"), prompt);
            assertFalse(contract.expectedTargets().contains("script.js"), prompt);
        }
    }

    @Test
    void typedProhibitionCannotMaskAResidualGlobalReadOnlyNegation() {
        for (String prompt : List.of(
                "Edit index.html so it works, but do not create a directory "
                        + "named docs without changing anything.",
                "Edit index.html so it works, but do not create a directory "
                        + "named docs and leave all files unchanged.",
                "Edit index.html so it works, but do not create a directory "
                        + "named docs or modify anything.",
                "Edit index.html so it works, but do not create a directory "
                        + "named docs, and do not change anything.",
                "Edit index.html so it works, but do not create a directory "
                        + "named docs; do not change anything.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(prompt);

            assertFalse(resolution.contract().mutationAllowed(), prompt);
            assertTrue(
                    resolution.contract().expectedTargets().isEmpty(),
                    prompt);
            assertEquals(
                    "global-read-only-negation",
                    resolution.contract().classificationReason(),
                    prompt);
        }
    }

    @Test
    void quotedProhibitionDoesNotOverrideRealAuthorityButUnquotedConjunctionDoes() {
        String quoted = "Edit index.html so the paragraph says \"do not edit style.css\", "
                + "and update style.css too.";
        TaskIntent quotedIntent = TaskContractResolver.intentFromUserRequest(quoted);
        TaskContract quotedContract = TaskContractResolver.fromUserRequest(quoted);

        assertEquals(Set.of("index.html", "style.css"), quotedContract.expectedTargets());
        assertFalse(quotedContract.forbiddenTargets().contains("style.css"));
        assertRole(quotedIntent, "style.css", TargetRole.MUST_MUTATE);

        String prohibited = "Edit index.html and do not update style.css.";
        TaskIntent prohibitedIntent = TaskContractResolver.intentFromUserRequest(prohibited);
        TaskContract prohibitedContract = TaskContractResolver.fromUserRequest(prohibited);

        assertEquals(Set.of("index.html"), prohibitedContract.expectedTargets());
        assertEquals(Set.of("style.css"), prohibitedContract.forbiddenTargets());
        assertRole(prohibitedIntent, "style.css", TargetRole.FORBIDDEN);
    }

    @Test
    void nonAuthoritativeAndBlockedTailDecisionsRetainExactTraceableSpans() {
        for (String prompt : List.of(
                "Edit index.html so it works, and update style.css only as a proposal.",
                "Edit index.html so it works, then create a directory named style.css.")) {
            TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(prompt);
            var decision = resolution.mutationTailPlan().decision("style.css").orElseThrow();
            TargetRef target = resolution.intent().targets().find("style.css").orElseThrow();

            assertEquals(TargetRole.MENTIONED_ONLY, target.role(), prompt);
            assertEquals(decision.startOffset(), target.derivation().startOffset(), prompt);
            assertEquals(decision.endOffset(), target.derivation().endOffset(), prompt);
            assertEquals(decision.sourceText(), target.derivation().sourceText(), prompt);
            assertEquals(
                    target.derivation().sourceText(),
                    resolution.intent().originalUserRequest().substring(
                            target.derivation().startOffset(),
                            target.derivation().endOffset()),
                    prompt);
            assertFalse(resolution.contract().expectedTargets().contains("style.css"), prompt);
        }
    }

    @Test
    void canonicalVerbCanBindOneTargetOrPureAdjacentTargetList() {
        for (String prompt : List.of(
                "Edit index.html so it works, and update style.css too.",
                "Edit index.html so it works; update style.css too.",
                "Edit index.html so it works—then update style.css too.")) {
            assertEquals(
                    Set.of("index.html", "style.css"),
                    TaskContractResolver.fromUserRequest(prompt).expectedTargets(),
                    prompt);
        }

        String listPrompt =
                "Edit index.html so it works, and update style.css and script.js too.";
        assertEquals(
                Set.of("index.html", "style.css", "script.js"),
                TaskContractResolver.fromUserRequest(listPrompt).expectedTargets());
    }

    @Test
    void spanOffsetsUseTheCanonicalStoredRequestEvenWhenInputHasOuterWhitespace() {
        String raw = " \r\n" + SCENARIO_19_PROMPT + "\r\n ";
        TaskIntent intent = TaskContractResolver.intentFromUserRequest(raw);
        TargetRef style = assertRole(intent, "style.css", TargetRole.MUST_MUTATE);

        assertEquals(SCENARIO_19_PROMPT, intent.originalUserRequest());
        assertEquals(
                style.derivation().sourceText(),
                intent.originalUserRequest().substring(
                        style.derivation().startOffset(),
                        style.derivation().endOffset()));
    }

    @Test
    void normalizedAliasAndUnicodePrefixKeepOneExactAuthorityDecision() {
        String prompt =
                "\u0130 Edit index.html so it works, and update ./style.css too.";
        TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(prompt);
        TargetRef style = assertRole(
                resolution.intent(),
                "style.css",
                TargetRole.MUST_MUTATE);

        assertEquals(Set.of("index.html", "style.css"), resolution.contract().expectedTargets());
        assertEquals(1, resolution.mutationTailPlan().decisions().size());
        assertEquals("update ./style.css too.", style.derivation().sourceText());
        assertEquals(
                style.derivation().sourceText(),
                prompt.substring(
                        style.derivation().startOffset(),
                        style.derivation().endOffset()));
    }

    @Test
    void topLevelNewlineDoesNotBecomeA1196TailDecision() {
        String prompt = "Edit index.html so it works\nUpdate style.css too.";
        TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(prompt);

        assertEquals(
                Set.of("index.html", "style.css"),
                resolution.contract().expectedTargets());
        assertFalse(resolution.mutationTailPlan().decision("style.css").isPresent());
    }

    private static TargetRef assertRole(TaskIntent intent, String path, TargetRole role) {
        TargetRef target = intent.targets().find(path).orElseThrow();
        assertEquals(role, target.role(), path);
        return target;
    }
}
