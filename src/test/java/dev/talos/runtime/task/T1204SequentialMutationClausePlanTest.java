package dev.talos.runtime.task;

import dev.talos.runtime.intent.ArtifactTargetSet;
import dev.talos.runtime.intent.IntentDerivation;
import dev.talos.runtime.intent.MutationPlanSelection;
import dev.talos.runtime.intent.SequentialMutationClausePlan;
import dev.talos.runtime.intent.SequentialMutationClauseParser;
import dev.talos.runtime.intent.TaskContractCompiler;
import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.intent.TaskIntentProjector;
import dev.talos.runtime.intent.TargetRef;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.intent.TargetSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class T1204SequentialMutationClausePlanTest {

    @Test
    void declaredTopLevelBoundariesProduceOneExactRequiredDecision() {
        for (String request : List.of(
                "Edit index.html, update style.css.",
                "Edit index.html; update style.css.",
                "Edit index.html\nUpdate style.css.",
                "Edit index.html\r\nUpdate style.css.",
                "Edit index.html\u2028Update style.css.",
                "Edit index.html\u2029Update style.css.",
                "Edit index.html, then update style.css.",
                "Edit index.html. Update style.css.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            SequentialMutationClausePlan plan =
                    resolution.sequentialMutationPlan();
            var decision = plan.effectiveDecision("style.css")
                    .orElseThrow(() -> new AssertionError(
                            request + " plan=" + plan + " resolution=" + resolution));

            assertEquals(
                    Set.of("index.html", "style.css"),
                    resolution.contract().expectedTargets(),
                    request + " resolution=" + resolution);
            assertEquals(
                    SequentialMutationClausePlan.Authority.REQUIRED,
                    decision.authority(),
                    request);
            assertEquals(List.of("style.css"), plan.occurrenceDecisions().stream()
                    .map(SequentialMutationClausePlan.ClauseDecision::path)
                    .toList(), request);
            assertExactClause(
                    request,
                    request.substring(request.toLowerCase(Locale.ROOT)
                            .lastIndexOf("update style.css.")),
                    decision);
            assertDecisionOwnsEffectiveIntent(resolution, decision);
            assertTrue(
                    resolution.mutationTailPlan().decision("style.css").isEmpty(),
                    "T1196 must not own a top-level sequential clause: " + request);
        }
    }

    @Test
    void repeatedTargetsRetainEveryOrderedOccurrenceAndOneEffectiveDecision() {
        String request =
                "Edit index.html. Update style.css. Rewrite style.css. Update script.js.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);
        SequentialMutationClausePlan plan =
                resolution.sequentialMutationPlan();

        assertEquals(
                List.of("style.css", "style.css", "script.js"),
                plan.occurrenceDecisions().stream()
                        .map(SequentialMutationClausePlan.ClauseDecision::path)
                        .toList());
        List<SequentialMutationClausePlan.ClauseDecision> styleDecisions =
                plan.occurrenceDecisions().stream()
                        .filter(decision -> "style.css".equals(decision.path()))
                        .toList();
        assertEquals(2, styleDecisions.size());
        assertTrue(
                styleDecisions.get(0).startOffset()
                        < styleDecisions.get(1).startOffset());
        assertExactClause(request, "Update style.css.", styleDecisions.get(0));
        assertExactClause(request, "Rewrite style.css.", styleDecisions.get(1));
        assertExactClause(
                request,
                "Update script.js.",
                plan.effectiveDecision("script.js").orElseThrow());

        var effectiveStyle = plan.effectiveDecision("style.css").orElseThrow();
        assertTrue(styleDecisions.contains(effectiveStyle));
        assertDecisionOwnsEffectiveIntent(resolution, effectiveStyle);
        assertEquals(
                Set.of("index.html", "style.css", "script.js"),
                resolution.contract().expectedTargets());
    }

    @Test
    void earlierRequiredAuthorityKeepsLaterRestrictiveOccurrenceEvidence() {
        for (String later : List.of(
                "Maybe update style.css.",
                "The report says update style.css.",
                "Update style.css only as a proposal.",
                "Delete style.css.")) {
            String request = "Update style.css. " + later;
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);

            assertEquals(Set.of("style.css"),
                    resolution.contract().expectedTargets(), request);
            assertEquals(TargetRole.MUST_MUTATE,
                    resolution.intent().targets().find("style.css")
                            .orElseThrow()
                            .role(), request);
            assertFalse(
                    resolution.sequentialMutationPlan()
                            .decisions("style.css")
                            .isEmpty(),
                    request);
            assertEquals(
                    2,
                    resolution.sequentialMutationPlan()
                            .decisions("style.css").size(),
                    request);
            assertEquals(
                    "Update style.css",
                    resolution.sequentialMutationPlan()
                            .effectiveDecision("style.css")
                            .orElseThrow()
                            .sourceText(),
                    request);
            assertTrue(
                    resolution.sequentialMutationPlan()
                            .matchesIntent(resolution.intent()),
                    request);
        }
    }

    @Test
    void crossPlanSamePathUsesOneSourceOrderedWinnerAndKeepsEveryOccurrence() {
        for (CrossPlanCase testCase : List.of(
                new CrossPlanCase(
                        "Edit index.html so the report says update style.css. "
                                + "Update style.css.",
                        TargetRole.MUST_MUTATE,
                        "Update style.css.",
                        MutationPlanSelection.Owner
                                .SEQUENTIAL_MUTATION_CLAUSE),
                new CrossPlanCase(
                        "Edit index.html so it works, and update style.css too. "
                                + "Do not update style.css.",
                        TargetRole.FORBIDDEN,
                        "Do not update style.css.",
                        MutationPlanSelection.Owner
                                .SEQUENTIAL_MUTATION_CLAUSE),
                new CrossPlanCase(
                        "Edit index.html so it works, and update style.css too. "
                                + "Update style.css.",
                        TargetRole.MUST_MUTATE,
                        "Update style.css.",
                        MutationPlanSelection.Owner
                                .SEQUENTIAL_MUTATION_CLAUSE),
                new CrossPlanCase(
                        "Edit index.html so it works, but do not update style.css. "
                                + "Update style.css.",
                        TargetRole.FORBIDDEN,
                        "do not update style.css.",
                        MutationPlanSelection.Owner
                                .COORDINATED_MUTATION_TAIL))) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(
                            testCase.request());
            TargetRef target = resolution.intent().targets()
                    .find("style.css")
                    .orElseThrow();
            var selected = MutationPlanSelection.selected(
                    resolution.mutationTailPlan(),
                    resolution.sequentialMutationPlan()).get("style.css");

            assertEquals(testCase.role(), target.role(), testCase.request());
            assertEquals(testCase.owner(), selected.owner(), testCase.request());
            assertEquals(
                    testCase.selectedSource(),
                    target.derivation().sourceText(),
                    testCase.request());
            assertTrue(
                    resolution.mutationTailPlan()
                            .decision("style.css")
                            .isPresent(),
                    testCase.request());
            assertFalse(
                    resolution.sequentialMutationPlan()
                            .decisions("style.css")
                            .isEmpty(),
                    testCase.request());

            TaskResolution projected = resolution.projectOnto(
                    resolution.contract(),
                    resolution.intent());
            assertTrue(projected.mutationTailPlan()
                    .decision("style.css").isPresent(), testCase.request());
            assertFalse(projected.sequentialMutationPlan()
                    .decisions("style.css").isEmpty(), testCase.request());

            TaskResolution replay = TaskResolution.confirmedReplay(resolution);
            assertTrue(replay.mutationTailPlan()
                    .decision("style.css").isPresent(), testCase.request());
            assertFalse(replay.sequentialMutationPlan()
                    .decisions("style.css").isEmpty(), testCase.request());
        }
    }

    @Test
    void resolutionRejectsMissingOrForgedCrossPlanOwnership() {
        TaskResolution valid = TaskContractResolver.resolveFromUserRequest(
                "Edit index.html so it works, and update style.css too. "
                        + "Update script.js.");
        SequentialMutationClausePlan missingExclusion =
                new SequentialMutationClausePlan(
                        valid.intent().originalUserRequest(),
                        List.of(),
                        valid.sequentialMutationPlan().occurrenceDecisions(),
                        valid.sequentialMutationPlan().ownedRegions());

        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskResolution(
                        valid.contract(),
                        valid.intent(),
                        valid.mutationTailPlan(),
                        missingExclusion));
        assertTrue(java.util.Arrays.stream(
                        TaskResolution.class.getConstructors())
                .noneMatch(constructor ->
                        constructor.getParameterCount() == 3
                                || constructor.getParameterCount() == 5));
    }

    @Test
    void earlierReadAndLaterMutationCompileOneCompositeEffectiveRole() {
        String request =
                "Read style.css and edit index.html, then update style.css.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);
        var decision = resolution.sequentialMutationPlan()
                .effectiveDecision("style.css")
                .orElseThrow();

        assertEquals(
                SequentialMutationClausePlan.Authority.REQUIRED,
                decision.authority());
        assertExactClause(request, "update style.css.", decision);
        assertEquals(
                Set.of("style.css"),
                resolution.contract().sourceEvidenceTargets());
        assertEquals(
                TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE,
                resolution.intent().targets().find("style.css")
                        .orElseThrow()
                        .role());
        assertDecisionOwnsEffectiveIntent(resolution, decision);
    }

    @Test
    void restrictivePreAnchorScopeStopsAtARealTopLevelBoundary() {
        String request =
                "Update styles.css if needed. Rewrite index.html.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertEquals(
                Set.of("index.html"),
                resolution.contract().expectedTargets(),
                request);
        assertEquals(
                TargetRole.MUST_MUTATE,
                resolution.intent().targets()
                        .find("index.html")
                        .orElseThrow()
                        .role(),
                request);

        String sameClause =
                "Update styles.css if needed then rewrite index.html.";
        TaskResolution bounded =
                TaskContractResolver.resolveFromUserRequest(sameClause);
        assertFalse(
                bounded.contract().expectedTargets().contains("index.html"),
                sameClause);
    }

    @Test
    void t1196TailAndT1204SequenceKeepDisjointOccurrences() {
        String requiredTail =
                "Edit index.html so it works, and update style.css too. Update script.js.";
        TaskResolution required =
                TaskContractResolver.resolveFromUserRequest(requiredTail);

        assertEquals(
                dev.talos.runtime.intent.CoordinatedMutationTailPlan.Authority.REQUIRED,
                required.mutationTailPlan()
                        .decision("style.css")
                        .orElseThrow()
                        .authority());
        assertTrue(required.sequentialMutationPlan()
                .effectiveDecision("style.css")
                .isEmpty());
        var script = required.sequentialMutationPlan()
                .effectiveDecision("script.js")
                .orElseThrow(() -> new AssertionError(
                        "required cross-plan sequence lost script.js: "
                                + required));
        assertEquals(
                SequentialMutationClausePlan.Authority.REQUIRED,
                script.authority());
        assertExactClause(requiredTail, "Update script.js.", script);
        assertEquals(
                Set.of("index.html", "style.css", "script.js"),
                required.contract().expectedTargets());

        String reportedTail =
                "Edit index.html so the report says update style.css. Update script.js.";
        TaskResolution reported =
                TaskContractResolver.resolveFromUserRequest(reportedTail);
        assertEquals(
                dev.talos.runtime.intent.CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                reported.mutationTailPlan()
                        .decision("style.css")
                        .orElseThrow()
                        .authority());
        assertTrue(reported.sequentialMutationPlan()
                .effectiveDecision("style.css")
                .isEmpty());
        assertEquals(
                SequentialMutationClausePlan.Authority.REQUIRED,
                reported.sequentialMutationPlan()
                        .effectiveDecision("script.js")
                        .orElseThrow()
                        .authority());
        assertEquals(
                TargetRole.MENTIONED_ONLY,
                reported.intent().targets().find("style.css")
                        .orElseThrow()
                        .role());
        assertEquals(
                TargetRole.MUST_MUTATE,
                reported.intent().targets().find("script.js")
                        .orElseThrow()
                        .role());
    }

    @Test
    void nonAuthoritativeScopesAndMalformedBoundariesNeverBecomeRequired() {
        for (String request : List.of(
                "Edit index.html. \"Update style.css.\"",
                "Edit index.html. The ticket says update style.css.",
                "Edit index.html. The proposal is to update style.css.",
                "Edit index.html. If needed, update style.css.",
                "Edit index.html. Consider updating style.css.",
                "Edit index.html. For reference, update style.css.",
                "Edit index.html (for reference, update style.css).",
                "Edit index.html [for reference; update style.css].",
                "Edit index.html {for reference, update style.css}.",
                "Edit index.html ( ]\nUpdate style.css.",
                "Edit index.html ([)]\nUpdate style.css.",
                "Edit index.html ]\nUpdate style.css.",
                "Edit index.html )\nUpdate style.css.",
                "Edit index.html }\nUpdate style.css.",
                "Edit index.html ( ] ); then update style.css.",
                "Edit index.html ([)]); then update style.css.",
                "Edit index.html. Do not update style.css.",
                "Edit index.html: update style.css.",
                "Edit index.html\rUpdate style.css.",
                "Edit index.html, th\u0000en update style.css.",
                "Edit index.html. Update\u0000 style.css.",
                "Edit index.html. Update\rstyle.css.",
                "Edit index.html. Update style.css\u0000.",
                "Edit index.html. Update style.css if\u0000 needed.",
                "Edit index.html. Update style.css only if tests fail.",
                "Edit index.html. Update style.css when tests pass.",
                "Edit index.html. Update style.css unless tests fail.",
                "Edit index.html. Update style.css if you want.",
                "Edit index.html. Update style.css is what the ticket says.",
                "Edit index.html, e.g., update style.css.",
                "Edit index.html, etc., update style.css.",
                "Edit index.html, as an example, update style.css.",
                "Edit index.html. Update style.css or script.js.",
                "Edit index.html. Update style.css and/or script.js.",
                "Edit index.html. Update anything except style.css.",
                "Edit index.html. Update either style.css or script.js.",
                "Edit index.html \u2014 update style.css.",
                "Edit index.html if needed then update style.css.",
                "Edit index.html. Copy generated output to style.css.",
                "Edit index.html.\n~~~\nUpdate style.css.\n~~~",
                "Edit index.html. style.css.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            var decision = resolution.sequentialMutationPlan()
                    .effectiveDecision("style.css");

            assertTrue(
                    decision.isEmpty()
                            || decision.orElseThrow().authority()
                                    != SequentialMutationClausePlan.Authority.REQUIRED,
                    request);
            assertFalse(
                    resolution.contract().expectedTargets().contains("style.css"),
                    request + " resolution=" + resolution);
            assertFalse(
                    resolution.intent().targets().find("style.css")
                            .map(target -> target.role() == TargetRole.MUST_MUTATE
                                    || target.role()
                                            == TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE)
                            .orElse(false),
                    request);
            if (request.contains("script.js")) {
                assertFalse(
                        resolution.contract().expectedTargets()
                                .contains("script.js"),
                        request);
            }
        }
    }

    @Test
    void runtimeMetadataCannotUpgradeARestrictiveLexicalDecision() {
        for (String request : List.of(
                "Edit a.css. If needed, update b.css.",
                "Edit a.css. The report says update b.css.",
                "Edit a.css. Delete b.css.")) {
            TaskResolution base =
                    TaskContractResolver.resolveFromUserRequest(request);
            var decision = base.sequentialMutationPlan()
                    .effectiveDecision("b.css")
                    .orElseThrow();
            assertTrue(decision.authority()
                            != SequentialMutationClausePlan.Authority.REQUIRED,
                    request);

            List<TargetRef> forgedTargets = new java.util.ArrayList<>(
                    base.intent().targets().targets().stream()
                            .filter(target -> !"b.css".equals(target.path()))
                            .toList());
            forgedTargets.add(new TargetRef(
                    "b.css",
                    TargetRole.MUST_MUTATE,
                    new IntentDerivation(
                            TargetSource.RUNTIME_DEFAULT,
                            "active-contract-projection",
                            decision.startOffset(),
                            decision.authorityEndOffset(),
                            decision.endOffset(),
                            decision.sourceText(),
                            1.0)));
            TaskIntent forged = new TaskIntent(
                    base.intent().type(),
                    true,
                    true,
                    base.intent().verificationRequired(),
                    new ArtifactTargetSet(forgedTargets),
                    base.intent().originalUserRequest(),
                    "forged-runtime-authority");
            TaskContract forgedContract = TaskContractCompiler.compile(forged);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TaskResolution(
                            forgedContract,
                            forged,
                            base.mutationTailPlan(),
                            base.sequentialMutationPlan()),
                    request);

            TaskContract widened = new TaskContract(
                    base.contract().type(),
                    true,
                    true,
                    base.contract().verificationRequired(),
                    Set.of("a.css", "b.css"),
                    base.contract().sourceEvidenceTargets(),
                    base.contract().forbiddenTargets(),
                    base.contract().originalUserRequest(),
                    "attempted-runtime-authority-upgrade");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> base.projectOnto(
                            widened,
                            TaskIntentProjector.project(
                                    base.intent(),
                                    widened,
                                    TargetSource.RUNTIME_DEFAULT,
                                    "active-contract-projection")),
                    request);
        }
    }

    @Test
    void providerRemovedControlCannotLetAPreAnchorClauseEscapeTypedEvidence() {
        for (int control = 0; control <= 0x7f; control++) {
            if (!(control <= 0x08
                    || control >= 0x0b && control <= 0x1f
                    || control == 0x7f)) {
                continue;
            }
            int controlValue = control;
            String unsafe = "Edit index.html."
                    + (char) control
                    + " Update style.css.";
            TaskResolution unsafeResolution =
                    TaskContractResolver.resolveFromUserRequest(unsafe);
            var decision = unsafeResolution.sequentialMutationPlan()
                    .effectiveDecision("style.css")
                    .orElseThrow(() -> new AssertionError(
                            "provider-removed control U+"
                                    + String.format("%04X", controlValue)
                                    + " escaped the typed plan: "
                                    + unsafeResolution));
            assertEquals(
                    SequentialMutationClausePlan.Authority.NON_AUTHORITATIVE,
                    decision.authority());
            assertEquals(TargetRole.MENTIONED_ONLY,
                    unsafeResolution.intent().targets().find("style.css")
                            .orElseThrow()
                            .role());
            assertEquals(Set.of("index.html"),
                    unsafeResolution.contract().expectedTargets());
            var retainedAnchor = unsafeResolution.sequentialMutationPlan()
                    .effectiveDecision("index.html")
                    .orElseThrow();
            assertEquals(
                    SequentialMutationClausePlan.Authority.REQUIRED,
                    retainedAnchor.authority());
            assertEquals(
                    retainedAnchor.sourceText(),
                    unsafe.substring(
                            retainedAnchor.startOffset(),
                            retainedAnchor.endOffset()));
            assertTrue(retainedAnchor.sourceText().indexOf(
                    (char) controlValue) < 0);
            assertTrue(decision.sourceText().indexOf((char) control) >= 0);

            String splitAction = "Upda" + (char) control + "te b.css.";
            TaskResolution splitResolution =
                    TaskContractResolver.resolveFromUserRequest(splitAction);
            assertFalse(splitResolution.contract().expectedTargets()
                    .contains("b.css"));
            assertTrue(splitResolution.sequentialMutationPlan()
                    .occurrenceDecisions().isEmpty());
            assertTrue(splitResolution.sequentialMutationPlan()
                    .ownedRegions().stream().anyMatch(region ->
                            region.kind() == SequentialMutationClausePlan.RegionKind
                                    .PROVIDER_REMOVED_CONTROL_CLAUSE
                                    && region.sourceText().indexOf(
                                            (char) controlValue) >= 0
                                    && region.sourceText().contains("b.css")));
            assertTrue(splitResolution.intent().targets().find("b.css")
                    .map(target -> target.role() == TargetRole.MENTIONED_ONLY)
                    .orElse(true));

            String recovered = "Edit index.html."
                    + (char) control
                    + "\r\nUpdate style.css.";
            TaskResolution recoveredResolution =
                    TaskContractResolver.resolveFromUserRequest(recovered);
            assertEquals(Set.of("index.html", "style.css"),
                    recoveredResolution.contract().expectedTargets(),
                    "U+" + String.format("%04X", controlValue)
                            + " resolution=" + recoveredResolution);
            assertEquals(TargetRole.MUST_MUTATE,
                    recoveredResolution.intent().targets().find("style.css")
                            .orElseThrow()
                            .role());

            for (String locallyRecovered : List.of(
                    "Edit index.html." + (char) control + "; Update style.css.",
                    "Edit index.html." + (char) control + ". Update style.css.",
                    "Edit index.html.\r" + (char) control
                            + "\nUpdate style.css.")) {
                TaskResolution localResolution =
                        TaskContractResolver.resolveFromUserRequest(
                                locallyRecovered);
                assertEquals(
                        Set.of("index.html", "style.css"),
                        localResolution.contract().expectedTargets(),
                        "U+" + String.format("%04X", controlValue)
                                + " request=" + locallyRecovered
                                + " resolution=" + localResolution);
            }

            for (String stillRejected : List.of(
                    "Edit index.html.\r\n" + (char) control
                            + "Update style.css.",
                    "Edit index.html." + (char) control
                            + ", Update style.css.",
                    "Edit index.html." + (char) control
                            + " then Update style.css.",
                    "Edit index.html ]" + (char) control
                            + "\nUpdate style.css.")) {
                TaskResolution rejectedResolution =
                        TaskContractResolver.resolveFromUserRequest(
                                stillRejected);
                assertFalse(
                        rejectedResolution.contract().expectedTargets()
                                .contains("style.css"),
                        "U+" + String.format("%04X", controlValue)
                                + " request=" + stillRejected
                                + " resolution=" + rejectedResolution);
            }
        }

        for (String request : List.of(
                "If needed, update a.css.\u0000 Update b.css.",
                "If needed, update a.css.\rUpdate b.css.",
                "Update b.css\u0000.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            var decision = resolution.sequentialMutationPlan()
                    .effectiveDecision("b.css")
                    .orElseThrow();
            assertEquals(
                    SequentialMutationClausePlan.Authority.NON_AUTHORITATIVE,
                    decision.authority());
            assertEquals(TargetRole.MENTIONED_ONLY,
                    resolution.intent().targets().find("b.css")
                            .orElseThrow()
                            .role());
            assertFalse(resolution.contract().expectedTargets().contains("b.css"));
            assertEquals(
                    decision.sourceText(),
                    request.substring(decision.startOffset(), decision.endOffset()));
        }

        for (String request : List.of(
                "Edit index.html.\u0000; Update style.css.",
                "Edit index.html.\u0000. Update style.css.",
                "Edit index.html.\r\u0000\nUpdate style.css.")) {
            TaskResolution recovered =
                    TaskContractResolver.resolveFromUserRequest(request);
            assertEquals(Set.of("index.html", "style.css"),
                    recovered.contract().expectedTargets(), request);
        }

        for (String request : List.of(
                "Edit index.html.\r\n\u0000Update style.css.",
                "Edit index.html.\u0000, Update style.css.",
                "Edit index.html.\u0000 then Update style.css.")) {
            TaskResolution rejected =
                    TaskContractResolver.resolveFromUserRequest(request);
            assertFalse(rejected.contract().expectedTargets()
                    .contains("style.css"), request);
        }

        TaskResolution localRecovery = TaskContractResolver.resolveFromUserRequest(
                "Edit index.html.\u0000 Update style.css. Update script.js.");
        assertEquals(Set.of("index.html", "script.js"),
                localRecovery.contract().expectedTargets());
        assertFalse(localRecovery.contract().expectedTargets()
                .contains("style.css"));

        TaskResolution clean = TaskContractResolver.resolveFromUserRequest(
                "If needed, update a.css.\r\nUpdate b.css.");
        assertEquals(TargetRole.MUST_MUTATE,
                clean.intent().targets().find("b.css").orElseThrow().role());
    }

    @Test
    void controlCorruptionCannotRebindPayloadOperandsAsMutationTargets() {
        for (String request : List.of(
                "Write \"a.md\" to notes.txt.\u0000 Update style.css.",
                "Append \"a.md\" to notes.txt.\u0000 Update style.css.",
                "Replace \"a.md\" with \"b.md\" in notes.txt.\u0000 "
                        + "Update style.css.",
                "Consider replace \"a.md\" with \"b.md\" in notes.txt.\u0000 "
                        + "Update style.css.",
                "The ticket says replace \"a.md\" with \"b.md\" in "
                        + "notes.txt.\u0000 Update style.css.",
                "Consider update style.css. Write \"a.md\" to notes.txt.",
                "The ticket says update style.css. Append \"a.md\" to "
                        + "notes.txt.",
                "Consider update style.css. Replace \"a.md\" with \"b.md\" "
                        + "in notes.txt.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);

            for (String path : List.of("a.md", "b.md", "style.css")) {
                assertFalse(
                        resolution.contract().expectedTargets().contains(path),
                        request + " resolution=" + resolution);
                assertFalse(
                        resolution.sequentialMutationPlan()
                                .effectiveDecision(path)
                                .map(decision -> decision.authority()
                                        == SequentialMutationClausePlan.Authority
                                                .REQUIRED)
                                .orElse(false),
                        request + " resolution=" + resolution);
                assertFalse(
                        resolution.intent().targets().find(path)
                                .map(target -> target.role()
                                        == TargetRole.MUST_MUTATE
                                        || target.role()
                                                == TargetRole
                                                        .MUST_MUTATE_AFTER_SOURCE_EVIDENCE)
                                .orElse(false),
                        request + " resolution=" + resolution);
            }
        }
    }

    @Test
    void controlQuarantineStopsAtAnExcludedT1196Span() {
        String request = "Upda\u0000te b.css so update style.css.";
        int excludedStart = request.indexOf("so update");
        SequentialMutationClausePlan plan =
                SequentialMutationClauseParser.parse(
                        request,
                        Set.of("b.css", "style.css"),
                        List.of(new SequentialMutationClausePlan.SourceSpan(
                                excludedStart,
                                request.length())));

        var quarantine = plan.ownedRegions().stream()
                .filter(region -> region.kind()
                        == SequentialMutationClausePlan.RegionKind
                                .PROVIDER_REMOVED_CONTROL_CLAUSE)
                .findFirst()
                .orElseThrow();
        assertEquals(0, quarantine.startOffset());
        assertEquals(excludedStart, quarantine.endOffset());
        assertEquals(request.substring(0, excludedStart),
                quarantine.sourceText());
    }

    @Test
    void restrictivePreAnchorClausesRemainTypedBeforeLaterAuthority() {
        for (String request : List.of(
                "Consider update style.css. Update index.html.",
                "The ticket says update style.css. Update index.html.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            var restricted = resolution.sequentialMutationPlan()
                    .effectiveDecision("style.css")
                    .orElseThrow(() -> new AssertionError(
                            request + " resolution=" + resolution));
            var required = resolution.sequentialMutationPlan()
                    .effectiveDecision("index.html")
                    .orElseThrow(() -> new AssertionError(
                            request + " resolution=" + resolution));

            assertEquals(Set.of("index.html"),
                    resolution.contract().expectedTargets(), request);
            assertTrue(
                    restricted.authority()
                            != SequentialMutationClausePlan.Authority.REQUIRED,
                    request);
            assertEquals(
                    SequentialMutationClausePlan.Authority.REQUIRED,
                    required.authority(),
                    request);
            assertEquals(TargetRole.MENTIONED_ONLY,
                    resolution.intent().targets().find("style.css")
                            .orElseThrow()
                            .role(),
                    request);
            assertDecisionOwnsEffectiveIntent(resolution, required);
        }
    }

    @Test
    void globalReadOnlyAndUnstableControlResetsCannotGainAuthority() {
        for (String globallyReadOnly : List.of(
                "Do not change any files, but edit index.html. "
                        + "Update style.css.",
                "Do not edit any files, but update index.html. "
                        + "Update style.css.",
                "Do not change any files, but edit index.html."
                        + '\u0000' + " Update style.css.")) {
            TaskResolution global =
                    TaskContractResolver.resolveFromUserRequest(
                            globallyReadOnly);
            assertFalse(global.contract().mutationAllowed(),
                    global.toString());
            assertTrue(global.contract().expectedTargets().isEmpty(),
                    global.toString());
        }

        for (String request : List.of(
                "Edit index.html. `\u0000`; Update style.css.",
                "Edit index.html. I\u0000f needed; Update style.css.",
                "Edit index.html. I\u001B[31mf needed; Update style.css.",
                "Edit index.html. Consi\u0000der this; Update style.css.",
                "Edit index.html. The ticket sa\u0000ys; Update style.css.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            assertFalse(
                    resolution.contract().expectedTargets()
                            .contains("style.css"),
                    request + " resolution=" + resolution);
            assertFalse(
                    resolution.sequentialMutationPlan()
                            .effectiveDecision("style.css")
                            .map(decision -> decision.authority()
                                    == SequentialMutationClausePlan.Authority
                                            .REQUIRED)
                            .orElse(false),
                    request + " resolution=" + resolution);
        }
    }

    @Test
    void immediatelyFollowingPronounProhibitionOverridesPriorMutationAuthority() {
        for (String request : List.of(
                "Edit index.html. Update style.css; do not change it.",
                "Edit index.html. Update style.css. Leave it unchanged.",
                "Edit index.html. Update style.css. Rewrite style.css; leave it unchanged.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            var decision = resolution.sequentialMutationPlan()
                    .effectiveDecision("style.css")
                    .orElseThrow(() -> new AssertionError(
                            request + " resolution=" + resolution));

            assertEquals(
                    SequentialMutationClausePlan.Authority.FORBIDDEN,
                    decision.authority(),
                    request);
            assertEquals(
                    TargetRole.FORBIDDEN,
                    resolution.intent().targets().find("style.css")
                            .orElseThrow()
                            .role(),
                    request);
            assertFalse(
                    resolution.contract().expectedTargets()
                            .contains("style.css"),
                    request);
            assertTrue(
                    resolution.contract().forbiddenTargets()
                            .contains("style.css"),
                    request);
            assertTrue(
                    decision.sourceText().contains("style.css"),
                    request);
            assertTrue(
                    decision.sourceText().toLowerCase(Locale.ROOT)
                            .matches("(?s).*\\b(?:it|that|this)\\b.*"),
                    request);
            assertDecisionOwnsEffectiveIntent(resolution, decision);
        }
    }

    @Test
    void nestedProhibitionsRemainTypedAndSiblingContainersCannotShareActions() {
        String prohibitedRequest =
                "Edit index.html. (Do not update style.css.)";
        SequentialMutationClausePlan parsed =
                SequentialMutationClauseParser.parse(
                        prohibitedRequest,
                        Set.of("index.html", "style.css"));
        assertTrue(
                parsed.effectiveDecision("style.css").isPresent(),
                parsed.toString());
        TaskResolution prohibited =
                TaskContractResolver.resolveFromUserRequest(prohibitedRequest);
        var prohibition = prohibited.sequentialMutationPlan()
                .effectiveDecision("style.css")
                .orElseThrow(() -> new AssertionError(prohibited.toString()));

        assertEquals(
                SequentialMutationClausePlan.Authority.FORBIDDEN,
                prohibition.authority());
        assertEquals(
                TargetRole.FORBIDDEN,
                prohibited.intent().targets().find("style.css")
                        .orElseThrow()
                        .role());
        assertTrue(prohibited.contract().forbiddenTargets()
                .contains("style.css"));

        String siblingRequest =
                "Edit index.html. (Update script.js.) [For reference, style.css.]";
        TaskResolution sibling =
                TaskContractResolver.resolveFromUserRequest(siblingRequest);
        assertTrue(
                sibling.sequentialMutationPlan()
                        .effectiveDecision("style.css")
                        .isEmpty(),
                sibling.toString());
        assertFalse(sibling.contract().expectedTargets().contains("style.css"));
    }

    @Test
    void createTargetAndFollowingPronounProhibitionStayContentTyped() {
        String request =
                "Edit index.html. Create style.css. Do not touch it.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);
        var decision = resolution.sequentialMutationPlan()
                .effectiveDecision("style.css")
                .orElseThrow(() -> new AssertionError(resolution.toString()));

        assertEquals(
                SequentialMutationClausePlan.ActionFamily.CONTENT_MUTATION,
                decision.actionFamily());
        assertEquals(
                SequentialMutationClausePlan.Authority.FORBIDDEN,
                decision.authority());
        assertEquals(
                TargetRole.FORBIDDEN,
                resolution.intent().targets().find("style.css")
                        .orElseThrow()
                        .role());
        assertFalse(resolution.blocksWorkspaceOperations(), resolution.toString());
        assertTrue(resolution.intent().targets().find("it").isEmpty());
        assertFalse(resolution.contract().forbiddenTargets().contains("it"));
    }

    @Test
    void restrictivePreludeCannotBeLaunderedThroughASequentialDelimiter() {
        for (String request : List.of(
                "Edit index.html. If needed, then update style.css.",
                "Edit index.html. If needed; update style.css.",
                "Edit index.html. If needed\nUpdate style.css.",
                "Edit index.html. If needed\r\nUpdate style.css.",
                "Edit index.html. The report says; update style.css.",
                "Edit index.html. Consider this; update style.css.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            var decision = resolution.sequentialMutationPlan()
                    .effectiveDecision("style.css");

            assertTrue(
                    decision.isEmpty()
                            || decision.orElseThrow().authority()
                                    != SequentialMutationClausePlan.Authority.REQUIRED,
                    request + " resolution=" + resolution);
            assertFalse(
                    resolution.contract().expectedTargets().contains("style.css"),
                    request + " resolution=" + resolution);
        }

        for (String request : List.of(
                "Edit index.html. Consider update style.css; then update script.js.",
                "Edit index.html. You should update style.css; then update script.js.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            assertFalse(
                    resolution.contract().expectedTargets().contains("script.js"),
                    request + " resolution=" + resolution);
            assertTrue(
                    resolution.sequentialMutationPlan()
                            .effectiveDecision("script.js")
                            .map(decision -> decision.authority()
                                    != SequentialMutationClausePlan.Authority.REQUIRED)
                            .orElse(true),
                    request + " resolution=" + resolution);
        }
    }

    @Test
    void typedAdvisoryScopeCarriesAndResetsOnlyAtDeclaredBoundaries() {
        for (String separator : List.of("; ", "\n", "\r\n", "; then ")) {
            String request = "Edit index.html. Consider update style.css"
                    + separator + "update script.js.";
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);

            assertEquals(
                    SequentialMutationClausePlan.Authority.NON_AUTHORITATIVE,
                    resolution.sequentialMutationPlan()
                            .effectiveDecision("style.css")
                            .orElseThrow(() -> new AssertionError(
                                    request + " resolution=" + resolution))
                            .authority(),
                    request);
            assertEquals(
                    SequentialMutationClausePlan.Authority.NON_AUTHORITATIVE,
                    resolution.sequentialMutationPlan()
                            .effectiveDecision("script.js")
                            .orElseThrow(() -> new AssertionError(
                                    request + " resolution=" + resolution))
                            .authority(),
                    request);
            assertEquals(Set.of("index.html"),
                    resolution.contract().expectedTargets(), request);
        }

        for (String request : List.of(
                "Edit index.html. Consider update style.css, but update script.js.",
                "Edit index.html. The report says update style.css, but update script.js.",
                "Edit index.html. Consider update style.css. Update script.js.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            assertEquals(
                    SequentialMutationClausePlan.Authority.REQUIRED,
                    resolution.sequentialMutationPlan()
                            .effectiveDecision("script.js")
                            .orElseThrow(() -> new AssertionError(
                                    request + " resolution=" + resolution))
                            .authority(),
                    request);
            assertTrue(resolution.contract().expectedTargets()
                    .contains("script.js"), request);
            assertFalse(resolution.contract().expectedTargets()
                    .contains("style.css"), request);
        }

        String disjunction =
                "Edit index.html. Update style.css or script.js; then update app.js.";
        TaskResolution disjunctive =
                TaskContractResolver.resolveFromUserRequest(disjunction);
        assertEquals(Set.of("index.html", "app.js"),
                disjunctive.contract().expectedTargets(), disjunctive.toString());
        assertEquals(
                SequentialMutationClausePlan.Authority.REQUIRED,
                disjunctive.sequentialMutationPlan()
                        .effectiveDecision("app.js")
                        .orElseThrow()
                        .authority());
        assertTrue(disjunctive.sequentialMutationPlan()
                .decisions("style.css").stream().allMatch(decision ->
                        decision.authority()
                                != SequentialMutationClausePlan.Authority.REQUIRED));
        assertTrue(disjunctive.sequentialMutationPlan()
                .decisions("script.js").stream().allMatch(decision ->
                        decision.authority()
                                != SequentialMutationClausePlan.Authority.REQUIRED));
    }

    @Test
    void clauseDecisionConstructionIsParserOwned() {
        assertEquals(
                0,
                SequentialMutationClausePlan.ClauseDecision.class
                        .getConstructors()
                        .length,
                "callers must not be able to mint authority by cloning a decision");
    }

    @Test
    void optionalAndWorkspaceOperationsRemainTypedButCannotGainContentAuthority() {
        TaskResolution optional = TaskContractResolver.resolveFromUserRequest(
                "Edit index.html. If needed, update style.css.");
        assertEquals(
                SequentialMutationClausePlan.Authority.OPTIONAL,
                optional.sequentialMutationPlan()
                        .effectiveDecision("style.css")
                        .orElseThrow()
                        .authority());
        assertFalse(optional.contract().expectedTargets().contains("style.css"));
        assertEquals(
                TargetRole.MENTIONED_ONLY,
                optional.intent().targets().find("style.css")
                        .orElseThrow()
                        .role());

        TaskResolution workspaceOperation =
                TaskContractResolver.resolveFromUserRequest(
                        "Edit index.html. Delete style.css.");
        assertEquals(
                SequentialMutationClausePlan.Authority.WORKSPACE_OPERATION_BLOCKED,
                workspaceOperation.sequentialMutationPlan()
                        .effectiveDecision("style.css")
                        .orElseThrow()
                        .authority());
        assertTrue(workspaceOperation.blocksWorkspaceOperations());
        assertFalse(
                workspaceOperation.contract().expectedTargets()
                        .contains("style.css"));
        assertEquals(
                TargetRole.MENTIONED_ONLY,
                workspaceOperation.intent().targets().find("style.css")
                        .orElseThrow()
                        .role());

        TaskResolution suffixWorkspace =
                TaskContractResolver.resolveFromUserRequest(
                        "Edit index.html. Create cache.tmp as a directory.");
        assertTrue(suffixWorkspace.blocksWorkspaceOperations());
        assertFalse(suffixWorkspace.contract().expectedTargets()
                .contains("cache.tmp"));
        TaskResolution folderWorkspace =
                TaskContractResolver.resolveFromUserRequest(
                        "Edit index.html. Create cache.tmp folder.");
        assertTrue(folderWorkspace.blocksWorkspaceOperations());
        assertFalse(folderWorkspace.contract().expectedTargets()
                .contains("cache.tmp"));
    }

    @Test
    void freshTopLevelSentenceResetsEarlierRestrictiveScope() {
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(
                        "Edit index.html. The ticket says no change is needed. "
                                + "Update style.css.");

        assertEquals(
                TargetRole.MUST_MUTATE,
                resolution.intent().targets().find("style.css")
                        .orElseThrow()
                        .role());
        assertEquals(
                "Update style.css.",
                resolution.intent().targets().find("style.css")
                        .orElseThrow()
                        .derivation()
                        .sourceText());
    }

    @Test
    void quotedExtensionlessAndDotfileAnchorsRemainEligible() {
        for (String request : List.of(
                "Create `README`. Update style.css.",
                "Create `.env`. Update index.html.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            assertEquals(2, resolution.contract().expectedTargets().size(), request);
            assertEquals(1,
                    resolution.sequentialMutationPlan()
                            .occurrenceDecisions().size(), request);
        }
    }

    @Test
    void independentVerifyRoleOutranksReportedSequentialMention() {
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(
                        "Rewrite styles.css so index.html still works. "
                                + "The ticket says update index.html.");
        TargetRef target = resolution.intent().targets()
                .find("index.html")
                .orElseThrow();

        assertEquals(TargetRole.VERIFY_ONLY, target.role());
        assertFalse(target.derivation().reason().contains("mutation"));
    }

    private record CrossPlanCase(
            String request,
            TargetRole role,
            String selectedSource,
            MutationPlanSelection.Owner owner
    ) {}

    @Test
    void resolutionRejectsASequentialPlanWhoseExactIntentEvidenceWasShifted() {
        TaskResolution valid = TaskContractResolver.resolveFromUserRequest(
                "Edit index.html. Update style.css.");
        TargetRef style = valid.intent().targets().find("style.css").orElseThrow();
        IntentDerivation original = style.derivation();
        IntentDerivation shifted = new IntentDerivation(
                original.source(),
                original.reason(),
                original.startOffset(),
                original.authorityEndOffset() - 1,
                original.endOffset(),
                original.sourceText(),
                original.confidence());
        ArtifactTargetSet targets = new ArtifactTargetSet(
                valid.intent().targets().targets().stream()
                        .map(target -> "style.css".equals(target.path())
                                ? new TargetRef(target.path(), target.role(), shifted)
                                : target)
                        .toList());
        var shiftedIntent = new dev.talos.runtime.intent.TaskIntent(
                valid.intent().type(),
                valid.intent().mutationRequested(),
                valid.intent().mutationAllowed(),
                valid.intent().verificationRequired(),
                targets,
                valid.intent().originalUserRequest(),
                valid.intent().classificationReason());

        assertFalse(valid.sequentialMutationPlan().matchesIntent(shiftedIntent));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskResolution(
                        valid.contract(),
                        shiftedIntent,
                        valid.mutationTailPlan(),
                        valid.sequentialMutationPlan()));
    }

    private static void assertDecisionOwnsEffectiveIntent(
            TaskResolution resolution,
            SequentialMutationClausePlan.ClauseDecision decision
    ) {
        TargetRef target = resolution.intent().targets()
                .find(decision.path())
                .orElseThrow();
        assertEquals(decision.startOffset(), target.derivation().startOffset());
        assertEquals(
                decision.authorityEndOffset(),
                target.derivation().authorityEndOffset());
        assertEquals(decision.endOffset(), target.derivation().endOffset());
        assertEquals(decision.sourceText(), target.derivation().sourceText());
    }

    private static void assertExactClause(
            String request,
            String expectedSource,
            SequentialMutationClausePlan.ClauseDecision decision
    ) {
        assertEquals(expectedSource, decision.sourceText(), request);
        assertEquals(
                expectedSource,
                request.substring(decision.startOffset(), decision.endOffset()),
                request);
        String lower = expectedSource.toLowerCase(Locale.ROOT);
        int pathStart = lower.lastIndexOf(decision.path().toLowerCase(Locale.ROOT));
        assertTrue(pathStart >= 0, request);
        assertEquals(
                decision.startOffset() + pathStart + decision.path().length(),
                decision.authorityEndOffset(),
                request);
    }
}
