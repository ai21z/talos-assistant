package dev.talos.runtime.intent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinatedMutationTailParserTest {

    @Test
    void openRestrictiveHeadersCannotResetIntoRequiredLocalAuthority() {
        for (String request : List.of(
                "Maybe then update style.css.",
                "Only as a proposal, then update style.css.",
                "If needed, then update style.css.",
                "The report says then update style.css.",
                "The report says update draft.md and then update style.css.",
                "Would it help to then update style.css.",
                "Could it make sense to then update style.css.",
                "You could then update style.css.",
                "I propose that we then update style.css.",
                "For review only, then update style.css.",
                "One option would be to then update style.css.",
                "My suggestion is to then update style.css.",
                "According to the report, then update style.css.",
                "Do not then update style.css.")) {
            RequestLexicalView view = RequestLexicalView.of(request);

            assertTrue(
                    CoordinatedMutationTailParser
                            .requiredLocalContentActionBindings(
                                    view,
                                    Set.of("style.css"),
                                    0,
                                    request.length())
                            .isEmpty(),
                    request);
        }
    }

    @Test
    void completedRestrictivePayloadCanResetIntoRequiredLocalAuthority() {
        for (String request : List.of(
                "Maybe update draft.md, then update style.css.",
                "The report says no, then update style.css.")) {
            RequestLexicalView view = RequestLexicalView.of(request);
            var bindings = CoordinatedMutationTailParser
                    .requiredLocalContentActionBindings(
                            view,
                            Set.of("style.css"),
                            0,
                            request.length());

            assertEquals(1, bindings.size(), request);
            assertEquals(List.of("style.css"),
                    bindings.getFirst().outputPaths(), request);
            assertEquals(
                    request.indexOf("update style.css"),
                    bindings.getFirst().actionStart(),
                    request);
        }
    }

    @Test
    void pluralRequiredBindingsPreserveEarlierAndLaterExactInstructions() {
        String request =
                "Update style.css. Maybe update style.css; update style.css. "
                        + "The report says update style.css. "
                        + "Update style.css only as a proposal. "
                        + "Update style.css.";
        RequestLexicalView view = RequestLexicalView.of(request);
        var bindings = CoordinatedMutationTailParser
                .requiredLocalContentActionBindings(
                        view,
                        Set.of("style.css", "Style.css"),
                        0,
                        request.length());

        assertEquals(2, bindings.size(), request);
        assertEquals(
                List.of(request.indexOf("Update style.css"),
                        request.lastIndexOf("Update style.css")),
                bindings.stream()
                        .map(CoordinatedMutationTailParser
                                .LocalContentActionBinding::actionStart)
                        .toList(),
                request);
        assertTrue(bindings.stream().allMatch(binding ->
                binding.outputPaths().equals(List.of("style.css"))), request);

        String interveningTarget =
                "Update a.md, maybe update b.md so maybe update a.md.";
        var interveningBindings = CoordinatedMutationTailParser
                .requiredLocalContentActionBindings(
                        RequestLexicalView.of(interveningTarget),
                        Set.of("a.md"),
                        0,
                        interveningTarget.length());
        assertEquals(1, interveningBindings.size(), interveningTarget);
        assertEquals(
                interveningTarget.indexOf("Update a.md"),
                interveningBindings.getFirst().actionStart(),
                interveningTarget);
    }

    @Test
    void pluralRequiredBindingsDoNotBindQuotedOrCaseDistinctTargets() {
        String request =
                "\"Update A.md.\" Maybe update A.md. Update a.md.";
        RequestLexicalView view = RequestLexicalView.of(request);

        assertTrue(
                CoordinatedMutationTailParser
                        .requiredLocalContentActionBindings(
                                view,
                                Set.of("A.md"),
                                0,
                                request.length())
                        .isEmpty(),
                request);
        assertEquals(
                List.of("a.md"),
                CoordinatedMutationTailParser
                        .requiredLocalContentActionBindings(
                                view,
                                Set.of("a.md"),
                                0,
                                request.length())
                        .getFirst()
                        .outputPaths(),
                request);
    }

    @Test
    void exactTailSelectionOwnsVerbatimCanonicalSpan() {
        String request = "Edit index.html so the selector matches, and update style.css too.";

        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("index.html", "style.css"));
        var decision = plan.decision("style.css").orElseThrow();

        assertTrue(plan.present());
        assertEquals(CoordinatedMutationTailPlan.Authority.REQUIRED, decision.authority());
        assertEquals("update style.css too.", decision.sourceText());
        assertEquals(
                decision.sourceText(),
                request.substring(decision.startOffset(), decision.endOffset()));
        assertFalse(plan.decision("index.html").isPresent());
    }

    @Test
    void quotedConstraintAndImperativeBytesAreNeverParsedAsAuthority() {
        for (String request : List.of(
                "Edit index.html with the literal \" so update style.css\".",
                "Edit index.html so it says 'update style.css'.",
                "Edit index.html so it says 'don't then update style.css'.",
                "Edit index.html so it says 'The reviewer doesn't say "
                        + "then update style.css'.",
                "Edit index.html so it says `update style.css`.",
                "Edit index.html so it says ``update style.css``.",
                "Edit index.html so it says ````update style.css````.",
                "Edit index.html so it says \"first; update style.css\".")) {
            CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                    request,
                    Set.of("index.html", "style.css"));

            assertFalse(plan.decision("style.css").isPresent(), request);
        }
    }

    @Test
    void curlySingleQuotedContractionsRemainInsideTheirOuterEnvelope() {
        String request =
                "Edit index.html so it says \u2018the verifier says don\u2019t "
                        + "update style.css\u2019.";

        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("index.html", "style.css"));

        assertFalse(plan.decision("style.css").isPresent(), request);
        RequestLexicalView view = RequestLexicalView.of(request);
        int actionStart = request.indexOf("update style.css");
        assertFalse(
                view.isUnquotedRange(
                        actionStart,
                        actionStart + "update style.css".length()),
                request);
    }

    @Test
    void reportingPredicatesRequireSubjectsAndRuntimeCommandsRemainDirect() {
        assertAuthority(
                "Edit index.html so if required, update style.css.",
                CoordinatedMutationTailPlan.Authority.OPTIONAL);
        assertAuthority(
                "Edit index.html so read source.md then update style.css.",
                CoordinatedMutationTailPlan.Authority.REQUIRED);
        assertAuthority(
                "Edit index.html so Talos must update style.css.",
                CoordinatedMutationTailPlan.Authority.REQUIRED);

        for (String reported : List.of(
                "Edit index.html so the report required update style.css.",
                "Edit index.html so the verifier says update style.css.",
                "Edit index.html so the result reads update style.css.")) {
            assertAuthority(
                    reported,
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE);
        }
    }

    @Test
    void outputLabelsAndReportedProhibitionsCannotGrantOrDenyAuthority() {
        for (String reported : List.of(
                "Edit index.html so verifier output: update style.css.",
                "Edit index.html so Output: update style.css.",
                "Edit index.html so verifier output: and update style.css.",
                "Edit index.html so verifier output: then update style.css.",
                "Edit index.html so output contains the instruction to update "
                        + "style.css.",
                "Edit index.html so output contains the instruction to then "
                        + "update style.css.",
                "Edit index.html so the report says: then update style.css.",
                "Edit index.html so verifier output: do not update style.css.",
                "Edit index.html so Output: never update style.css.",
                "Edit index.html so output contains the instruction to not "
                        + "update style.css.",
                "Edit index.html so output contains the instruction not to "
                        + "update style.css.")) {
            assertAuthority(
                    reported,
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE);
        }

        String fresh =
                "Edit index.html so verifier output: \u2018don\u2019t update "
                        + "style.css\u2019, and update style.css.";
        CoordinatedMutationTailPlan freshPlan =
                CoordinatedMutationTailParser.parse(
                        fresh,
                        Set.of("index.html", "style.css"));
        var decision = freshPlan.decision("style.css").orElseThrow();
        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                decision.authority(),
                fresh);
        assertEquals("update style.css.", decision.sourceText(), fresh);
    }

    @Test
    void optionalReportedAndWorkspaceFamiliesRemainDistinctAndFailClosed() {
        assertAuthority(
                "Edit index.html so it works, and update style.css if needed.",
                CoordinatedMutationTailPlan.Authority.OPTIONAL);
        assertAuthority(
                "Edit index.html so it works, and update style.css only as a proposal.",
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE);
        assertAuthority(
                "Edit index.html so it works; unless needed, update style.css.",
                CoordinatedMutationTailPlan.Authority.OPTIONAL);
        for (String request : List.of(
                "Edit index.html so optional: update style.css.",
                "Edit index.html so possible: update style.css.",
                "Edit index.html so conditional: update style.css.",
                "Edit index.html so update style.css, maybe.",
                "Edit index.html so update style.css optionally.",
                "Edit index.html so update style.css where possible.",
                "Edit index.html so update style.css where feasible.")) {
            assertAuthority(
                    request,
                    CoordinatedMutationTailPlan.Authority.OPTIONAL);
        }
        assertAuthority(
                "Edit index.html so update style.css only as a possible change.",
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE);
        assertAuthority(
                "Edit index.html so the guide says update style.css.",
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE);
        assertAuthority(
                "Edit index.html so the verifier says: update style.css.",
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE);
        assertAuthority(
                "Edit index.html so it works, then rename style.css to theme.css.",
                CoordinatedMutationTailPlan.Authority.WORKSPACE_OPERATION_BLOCKED);
        assertAuthority(
                "Edit index.html so it works, then do not edit style.css.",
                CoordinatedMutationTailPlan.Authority.FORBIDDEN);
    }

    @Test
    void proposalVerifierPreservationAndExclusionProseCannotGrantAuthority() {
        for (String request : List.of(
                "Edit index.html so the build file styles.scss still works.",
                "Edit index.html so the build file styles.scss remains unchanged.",
                "Edit index.html so our build file styles.scss still works.",
                "Edit index.html so my make file styles.scss still works.",
                "Edit index.html so your scaffold file styles.scss still works.",
                "Edit index.html so its save file styles.scss still works.",
                "Edit index.html so the production build file styles.scss still works.",
                "Edit index.html so our build styles.scss still works.",
                "Edit index.html so production build styles.scss still works.",
                "Edit index.html so my update styles.scss remains accurate.",
                "Edit index.html so it works, and would it help to update styles.scss?",
                "Edit index.html so the verifier output is: update styles.scss.",
                "Review index.html so the output says: update styles.scss.",
                "Review index.html so the result reads update styles.scss.",
                "Review index.html so diagnostic output says update styles.scss.",
                "Review index.html so tool output says: update styles.scss.")) {
            CoordinatedMutationTailPlan plan =
                    CoordinatedMutationTailParser.parse(
                            request,
                            CoordinatedMutationTailParser.discoverDirectTargets(
                                    request));

            assertTrue(
                    plan.decision("styles.scss").isEmpty()
                            || plan.decision("styles.scss").orElseThrow()
                                    .authority()
                                    != CoordinatedMutationTailPlan.Authority
                                            .REQUIRED,
                    request + " -> " + plan.decisions());
        }

        assertAuthority(
                "Review index.html so the test failed; then update style.css.",
                CoordinatedMutationTailPlan.Authority.REQUIRED);
        String outputRequest =
                "Edit index.html so it works, then update output.css.";
        CoordinatedMutationTailPlan outputPlan =
                CoordinatedMutationTailParser.parse(
                        outputRequest,
                        Set.of("index.html", "output.css"));
        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                outputPlan.decision("output.css").orElseThrow().authority(),
                outputRequest);
        assertAuthority(
                "Edit index.html so it works, then build style.css.",
                CoordinatedMutationTailPlan.Authority.REQUIRED);
        assertAuthority(
                "Edit index.html so it works, then update style.css.",
                CoordinatedMutationTailPlan.Authority.REQUIRED);
        assertAuthority(
                "Edit index.html so please carefully update style.css.",
                CoordinatedMutationTailPlan.Authority.REQUIRED);
        assertAuthority(
                "Edit index.html so it works; then immediately update style.css.",
                CoordinatedMutationTailPlan.Authority.REQUIRED);
        assertAuthority(
                "Edit index.html so we should minimally update style.css.",
                CoordinatedMutationTailPlan.Authority.REQUIRED);
        assertAuthority(
                "Edit index.html so it works, now update style.css.",
                CoordinatedMutationTailPlan.Authority.REQUIRED);
        assertAuthority(
                "Edit index.html so it works; carefully update style.css.",
                CoordinatedMutationTailPlan.Authority.REQUIRED);
        String firstTail =
                "Edit index.html so first update style.css.";
        RequestLexicalView firstTailView = RequestLexicalView.of(firstTail);
        List<CanonicalMutationClauseMatcher.Action> firstTailActions =
                CanonicalMutationClauseMatcher.actions(
                        firstTailView,
                        CoordinatedMutationTailParser
                                .firstUnquotedConstraintBoundary(firstTailView),
                        firstTail.length());
        assertEquals(1, firstTailActions.size(), firstTail);
        assertFalse(
                firstTailActions.getFirst().nonImperativeUse(),
                firstTail);
        assertAuthority(
                firstTail,
                CoordinatedMutationTailPlan.Authority.REQUIRED);

        for (String request : List.of(
                "Now update style.css.",
                "First update style.css.",
                "Immediately update style.css.")) {
            List<CanonicalMutationClauseMatcher.Action> actions =
                    CanonicalMutationClauseMatcher.actions(
                            RequestLexicalView.of(request),
                            0,
                            request.length());
            assertEquals(1, actions.size(), request);
            assertFalse(actions.getFirst().nonImperativeUse(), request);
        }

        for (String request : List.of(
                "Edit index.html so it works, and do anything but update styles.scss.",
                "Edit index.html so it works, and do anything except update styles.scss.")) {
            CoordinatedMutationTailPlan plan =
                    CoordinatedMutationTailParser.parse(
                            request,
                            CoordinatedMutationTailParser.discoverDirectTargets(
                                    request));

            assertEquals(
                    CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                    plan.decision("styles.scss").orElseThrow().authority(),
                    request);
        }
    }

    @Test
    void targetlessWorkspaceActionStillProducesExactFailClosedEvidence() {
        String request = "Edit it so it works, then delete.";

        CoordinatedMutationTailPlan plan =
                CoordinatedMutationTailParser.parse(request, Set.of());

        assertTrue(plan.blocksWorkspaceOperations());
        var observation =
                plan.workspaceOperationClauseObservations().getFirst();
        assertEquals("delete", observation.action());
        assertEquals("delete.", observation.sourceText());
        assertEquals(
                observation.sourceText(),
                request.substring(
                        observation.startOffset(),
                        observation.endOffset()));
    }

    @Test
    void leadingConstraintConnectorIsNotWorkspaceOperationEvidence() {
        String request = "Repair it so delete cache.tmp.";

        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("cache.tmp"));
        var observation = plan.workspaceOperationObservations().getFirst();
        var clause = plan.workspaceOperationClauseObservations().getFirst();

        assertEquals("delete cache.tmp.", observation.sourceText());
        assertEquals(
                request.indexOf("delete cache.tmp."),
                observation.startOffset());
        assertEquals(
                observation.sourceText(),
                request.substring(
                        observation.startOffset(),
                        observation.endOffset()));
        assertEquals(observation.startOffset(), clause.startOffset());
        assertEquals(observation.endOffset(), clause.endOffset());
        assertEquals(observation.sourceText(), clause.sourceText());
    }

    @Test
    void advisoryScopeCannotSuppressAFreshImperativeAfterButOrThen() {
        for (String request : List.of(
                "Edit index.html so the report says update style.css, "
                        + "but update script.js.",
                "Edit index.html so maybe update style.css, "
                        + "then update script.js.")) {
            CoordinatedMutationTailPlan plan =
                    CoordinatedMutationTailParser.parse(
                            request,
                            Set.of("index.html", "style.css", "script.js"));

            assertEquals(
                    CoordinatedMutationTailPlan.Authority.REQUIRED,
                    plan.decision("script.js").orElseThrow().authority(),
                    request);
            assertFalse(
                    plan.decision("style.css").orElseThrow().authority()
                            == CoordinatedMutationTailPlan.Authority.REQUIRED,
                    request);
        }
    }

    @Test
    void advisoryScopeContinuesAcrossCoordinatedListConnectors() {
        for (String request : List.of(
                "Review index.html so the report says update style.css, "
                        + "also update script.js.",
                "Review index.html so the report says update style.css, "
                        + "update script.js.",
                "Review index.html so the report says update style.css; "
                        + "update script.js.",
                "Review index.html so the report says update style.css: "
                        + "update script.js.",
                "Review index.html so the report says update style.css \u2013 "
                        + "update script.js.",
                "Review index.html so the report says update style.css \u2014 "
                        + "update script.js.",
                "Review index.html so the report says update style.css\n"
                        + "update script.js.",
                "Review index.html so the report says:\n"
                        + "- update style.css\n"
                        + "- update script.js.")) {
            CoordinatedMutationTailPlan plan =
                    CoordinatedMutationTailParser.parse(
                            request,
                            Set.of("index.html", "style.css", "script.js"));

            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    plan.decision("style.css").orElseThrow().authority(),
                    request);
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    plan.decision("script.js").orElseThrow().authority(),
                    request);
            for (var decision : plan.decisions()) {
                assertEquals(
                        decision.sourceText(),
                        request.substring(decision.startOffset(), decision.endOffset()),
                        request);
            }
        }
        CoordinatedMutationTailPlan optional =
                CoordinatedMutationTailParser.parse(
                        "Review index.html so maybe update style.css, "
                                + "also update script.js.",
                        Set.of("index.html", "style.css", "script.js"));
        assertEquals(
                CoordinatedMutationTailPlan.Authority.OPTIONAL,
                optional.decision("style.css").orElseThrow().authority());
        assertEquals(
                CoordinatedMutationTailPlan.Authority.OPTIONAL,
                optional.decision("script.js").orElseThrow().authority());
    }

    @Test
    void restrictiveScopeContinuesAcrossAndThenButNotFreshSentenceOrPlainThen() {
        String coordinated =
                "Review index.html so the report says update style.css, "
                        + "and then update script.js.";
        CoordinatedMutationTailPlan coordinatedPlan =
                CoordinatedMutationTailParser.parse(
                        coordinated,
                        Set.of("index.html", "style.css", "script.js"));
        assertEquals(
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                coordinatedPlan.decision("script.js").orElseThrow().authority());

        String plainThen =
                "Review index.html so the report says update style.css, "
                        + "then update script.js.";
        CoordinatedMutationTailPlan plainThenPlan =
                CoordinatedMutationTailParser.parse(
                        plainThen,
                        Set.of("index.html", "style.css", "script.js"));
        assertEquals(
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                plainThenPlan.decision("script.js").orElseThrow().authority());

        String freshSentence =
                "Review index.html so the report says update style.css. "
                        + "Update script.js.";
        CoordinatedMutationTailPlan freshSentencePlan =
                CoordinatedMutationTailParser.parse(
                        freshSentence,
                        Set.of("index.html", "style.css", "script.js"));
        assertFalse(freshSentencePlan.decision("script.js").isPresent());
    }

    @Test
    void commonAdvisoryAndReportedPhrasesRemainNonAuthoritative() {
        for (String request : List.of(
                "Review index.html so you could update style.css.",
                "Review index.html so you can update style.css.",
                "Review index.html so, as an option, update style.css.",
                "Review index.html so the report asks you to update style.css.",
                "Review index.html so the ticket instructs us to update style.css.",
                "Review index.html so the guide tells you to update style.css.",
                "Review index.html so the note requests: update style.css.",
                "Review index.html so according to the report, update style.css.",
                "Review index.html so per the report, update style.css.",
                "Review index.html so the report requires you to update style.css.",
                "Review index.html so I suggest update style.css.")) {
            assertAuthority(
                    request,
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE);
        }
    }

    @Test
    void commonOptionalPhrasesCannotBecomeRequiredAuthority() {
        for (String request : List.of(
                "Review index.html so possibly update style.css.",
                "Review index.html so potentially update style.css.",
                "Review index.html so update style.css where appropriate.",
                "Review index.html so update style.css where needed.",
                "Review index.html so update style.css only when necessary.")) {
            assertAuthority(
                    request,
                    CoordinatedMutationTailPlan.Authority.OPTIONAL);
        }
    }

    @Test
    void quotedQualifiersAndProhibitionsCannotChangeUnquotedAuthority() {
        for (String request : List.of(
                "Edit index.html so the paragraph says \"do not edit style.css\", "
                        + "and update style.css too.",
                "Edit index.html so it works, and update style.css to contain "
                        + "\"do not edit files\".",
                "Edit index.html so it works, and update style.css with the label "
                        + "\"only as a proposal\".")) {
            assertAuthority(request, CoordinatedMutationTailPlan.Authority.REQUIRED);
        }
    }

    @Test
    void closedQuotedReportedPayloadCannotBleedAcrossFreshConjunction() {
        String request =
                "Edit index.html so the paragraph says "
                        + "\"do not edit style.css\", and update style.css too.";

        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("index.html", "style.css"));
        var decision = plan.decision("style.css").orElseThrow();

        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                decision.authority());
        assertEquals("update style.css too.", decision.sourceText());
        assertEquals(
                request.indexOf("update style.css too."),
                decision.startOffset());
        assertEquals(
                decision.sourceText(),
                request.substring(decision.startOffset(), decision.endOffset()));
    }

    @Test
    void persistentProhibitionDoesNotTaintIndependentTargetsAndCannotBeOverridden() {
        String separateTargets =
                "Edit index.html without changing style.css, and update script.js too.";
        CoordinatedMutationTailPlan separatePlan = CoordinatedMutationTailParser.parse(
                separateTargets,
                Set.of("index.html", "style.css", "script.js"));

        assertEquals(
                CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                separatePlan.decision("style.css").orElseThrow().authority());
        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                separatePlan.decision("script.js").orElseThrow().authority());

        for (String request : List.of(
                "Edit index.html so it works, then do not update style.css, "
                        + "then update style.css.",
                "Edit index.html so it works, then avoid editing style.css, "
                        + "then update style.css.")) {
            assertAuthority(request, CoordinatedMutationTailPlan.Authority.FORBIDDEN);
        }
    }

    @Test
    void prohibitionOnLaterTargetDoesNotBleedIntoEarlierRequiredAction() {
        String request =
                "Edit index.html so it works, then update style.css, "
                        + "but do not touch script.js.";
        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("index.html", "style.css", "script.js"));

        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                plan.decision("style.css").orElseThrow().authority());
        assertEquals(
                CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                plan.decision("script.js").orElseThrow().authority());
    }

    @Test
    void quotedAndPreposedDirectTargetsBindToTheirUnquotedVerb() {
        for (String request : List.of(
                "Edit index.html so it works, and update 'style.css' too.",
                "Edit index.html so it works, and update \"style.css\" too.",
                "Edit index.html so it works, and update `style.css` too.",
                "Edit index.html so it works, and in style.css, update the color.")) {
            assertAuthority(request, CoordinatedMutationTailPlan.Authority.REQUIRED);
        }
    }

    @Test
    void topicalForPhraseCannotMasqueradeAsAPreposedMutationTarget() {
        String request =
                "Edit index.html so it works, and for style.css, "
                        + "update the documentation.";
        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("index.html", "style.css"));

        assertFalse(plan.decision("style.css").isPresent());
    }

    @Test
    void nestedQuoteTextCannotMasqueradeAsAnExactQuotedTarget() {
        String request =
                "Edit index.html so it works, and update "
                        + "\"the example 'style.css' only\".";
        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("index.html", "style.css"));

        assertFalse(plan.decision("style.css").isPresent());
    }

    @Test
    void workspaceOperationSyntaxVariantsProduceExplicitBlockedDecisions() {
        for (String request : List.of(
                "Edit index.html so it works, then create a directory named style.css.",
                "Edit index.html so it works, then make a folder named style.css.",
                "Edit index.html so it works, then rename style.css to theme.css.",
                "Edit index.html so it works, then move style.css to theme.css.",
                "Edit index.html so it works, then copy style.css to theme.css.",
                "Edit index.html so it works, then delete style.css.",
                "Edit index.html so it works, then rm style.css.",
                "Edit index.html so it works, then create me a new folder style.css.",
                "Edit index.html so it works, then make a new dir style.css.",
                "Edit index.html so it works, then create directories style.css.",
                "Edit index.html so it works, then create style.css directory.",
                "Edit index.html so it works, then create the style.css directory.",
                "Edit index.html so it works, then create style.css folder.",
                "Edit index.html so it works, then make style.css folder.")) {
            assertAuthority(
                    request,
                    CoordinatedMutationTailPlan.Authority.WORKSPACE_OPERATION_BLOCKED);
        }
    }

    @Test
    void naturalBatchDirectoryAliasesFailClosedAsWorkspaceOperations() {
        for (String request : List.of(
                "Edit index.html so it works, then create docs and assets, "
                        + "then copy style.css to docs/style.css.",
                "Edit index.html so it works, then create .config and assets, "
                        + "then copy style.css to .config/style.css.",
                "Edit index.html so it works, then create docs.v1 and assets, "
                        + "then copy style.css to docs.v1/style.css.",
                "Edit index.html so it works, then create index.html and style.css, "
                        + "then copy style.css to backup.css.")) {
            CoordinatedMutationTailPlan plan =
                    CoordinatedMutationTailParser.parse(
                            request,
                            Set.of(
                                    "index.html",
                                    "style.css",
                                    "backup.css",
                                    "docs",
                                    "assets",
                                    "docs/style.css",
                                    ".config",
                                    ".config/style.css",
                                    "docs.v1",
                                    "docs.v1/style.css"));

            assertTrue(plan.blocksWorkspaceOperations(), request);
            assertTrue(
                    plan.decisions().stream().noneMatch(decision ->
                            decision.authority()
                                    == CoordinatedMutationTailPlan.Authority.REQUIRED),
                    request);
            plan.decisions().forEach(decision -> {
                if (decision.actionFamily()
                        != CoordinatedMutationTailPlan.ActionFamily
                                .WORKSPACE_OPERATION) {
                    return;
                }
                assertEquals(
                        CoordinatedMutationTailPlan.Authority
                                .WORKSPACE_OPERATION_BLOCKED,
                        decision.authority(),
                        request);
            });
        }

        CoordinatedMutationTailPlan standaloneCreate =
                CoordinatedMutationTailParser.parse(
                        "Edit index.html so it works, then create style.css.",
                        Set.of("index.html", "style.css"));
        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                standaloneCreate.decision("style.css").orElseThrow().authority());
        assertFalse(standaloneCreate.blocksWorkspaceOperations());
    }

    @Test
    void nonTargetObjectsDoNotWidenToLaterFilenameMentions() {
        for (String request : List.of(
                "Edit index.html so it works, and update the documentation according to style.css.",
                "Edit index.html so it works, and update the selector described by style.css.",
                "Edit index.html so it works, and update the documentation based on style.css.")) {
            CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                    request,
                    Set.of("index.html", "style.css"));

            assertFalse(plan.decision("style.css").isPresent(), request);
        }
    }

    @Test
    void lfAndCrlfRepresentationsKeepEquivalentDecisionsAndLocalExactSpans() {
        String lf = "Edit index.html so it works,\nand update style.css too.";
        String crlf = lf.replace("\n", "\r\n");

        CoordinatedMutationTailPlan lfPlan = CoordinatedMutationTailParser.parse(
                lf,
                Set.of("index.html", "style.css"));
        CoordinatedMutationTailPlan crlfPlan = CoordinatedMutationTailParser.parse(
                crlf,
                Set.of("index.html", "style.css"));
        var lfDecision = lfPlan.decision("style.css").orElseThrow();
        var crlfDecision = crlfPlan.decision("style.css").orElseThrow();

        assertEquals(lfDecision.authority(), crlfDecision.authority());
        assertEquals(lfDecision.sourceText(), crlfDecision.sourceText());
        assertEquals(
                lfDecision.sourceText(),
                lf.substring(lfDecision.startOffset(), lfDecision.endOffset()));
        assertEquals(
                crlfDecision.sourceText(),
                crlf.substring(crlfDecision.startOffset(), crlfDecision.endOffset()));
    }

    @Test
    void unicodeCaseFoldingBeforeTailNeverMovesCanonicalOffsets() {
        String request =
                "\u0130 Edit index.html so it works, and update style.css too.";

        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("index.html", "style.css"));
        var decision = plan.decision("style.css").orElseThrow();

        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                decision.authority());
        assertEquals(
                decision.sourceText(),
                request.substring(decision.startOffset(), decision.endOffset()));
        assertEquals(request.indexOf("update style.css"), decision.startOffset());
    }

    @Test
    void pluralPossessiveApostropheDoesNotMaskLaterAuthority() {
        String request =
                "Edit index.html so the files' metadata stays valid, "
                        + "and update style.css too.";

        assertAuthority(request, CoordinatedMutationTailPlan.Authority.REQUIRED);
    }

    @Test
    void currentDirectoryAliasesBindOneNormalizedTargetWithExactSourceBytes() {
        String request =
                "Edit index.html so it works, and update ./style.css too.";

        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("index.html", "style.css", "./style.css"));
        var decision = plan.decision("style.css").orElseThrow();

        assertEquals(1, plan.decisions().size());
        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                decision.authority());
        assertEquals("update ./style.css too.", decision.sourceText());
        assertEquals(
                decision.sourceText(),
                request.substring(decision.startOffset(), decision.endOffset()));
    }

    @Test
    void establishedContentMutationVerbsShareTheSameBoundedAuthorityRule() {
        for (String verb : List.of(
                "edit",
                "update",
                "rewrite",
                "replace",
                "append",
                "refactor",
                "overwrite",
                "modify",
                "change",
                "fix",
                "adjust",
                "tweak",
                "restyle",
                "redesign",
                "polish",
                "repair",
                "improve",
                "regenerate",
                "generate",
                "scaffold",
                "build",
                "make")) {
            assertAuthority(
                    "Edit index.html so it works, and " + verb + " style.css too.",
                    CoordinatedMutationTailPlan.Authority.REQUIRED);
        }
    }

    @Test
    void proposalQuestionsAndDisjunctionsNeverBecomeRequiredAuthority() {
        for (String request : List.of(
                "Edit index.html so it works, and maybe update style.css.",
                "Edit index.html so it works; should we update style.css?",
                "Edit index.html so it works; should I update style.css?",
                "Edit index.html so it works; what if we update style.css?",
                "Edit index.html so it works; could we update style.css?",
                "Edit index.html so it works, and update style.css or script.js.",
                "Edit index.html so it works, and update style.css and/or script.js.",
                "Edit index.html so it works, and update style.css, otherwise "
                        + "leave it unchanged.")) {
            CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                    request,
                    Set.of("index.html", "style.css", "script.js"));

            CoordinatedMutationTailPlan.Authority expected =
                    request.contains("maybe update")
                            ? CoordinatedMutationTailPlan.Authority.OPTIONAL
                            : CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE;
            assertEquals(
                    expected,
                    plan.decision("style.css").orElseThrow().authority(),
                    request);
            assertFalse(
                    plan.pathsWithAuthority(
                            CoordinatedMutationTailPlan.Authority.REQUIRED)
                            .contains("style.css"),
                    request);
        }
    }

    @Test
    void leadingAdvisoryOrConditionalScopeCoversCoordinatedActions() {
        for (String request : List.of(
                "Edit index.html so the guide says update style.css "
                        + "and update script.js.",
                "Edit index.html so if needed, update style.css "
                        + "and update script.js.")) {
            CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                    request,
                    Set.of("index.html", "style.css", "script.js"));

            CoordinatedMutationTailPlan.Authority expected =
                    request.contains("guide says")
                            ? CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE
                            : CoordinatedMutationTailPlan.Authority.OPTIONAL;
            assertEquals(
                    Set.of("style.css", "script.js"),
                    plan.pathsWithAuthority(expected),
                    request);
            assertTrue(
                    plan.pathsWithAuthority(
                            CoordinatedMutationTailPlan.Authority.REQUIRED).isEmpty(),
                    request);
        }
    }

    @Test
    void prohibitionWithAdverbRemainsBoundToItsTarget() {
        String request =
                "Edit index.html so it works, but do not also update style.css.";

        assertAuthority(request, CoordinatedMutationTailPlan.Authority.FORBIDDEN);
    }

    @Test
    void targetContentConstraintsDoNotRewriteTheActionAuthority() {
        for (String request : List.of(
                "Edit index.html so it works, and update style.css "
                        + "without changing its public selectors.",
                "Edit index.html so it works, and update style.css "
                        + "and never regress accessibility.",
                "Edit index.html so it works, and update style.css "
                        + "to support light or dark mode.",
                "Edit index.html so it works, and update style.css because "
                        + "the guide says focus must remain visible.")) {
            assertAuthority(request, CoordinatedMutationTailPlan.Authority.REQUIRED);
        }
    }

    @Test
    void arbitraryConditionsAndNestedConnectorQualifiersFailClosed() {
        assertAuthority(
                "Edit index.html so it works; if tests fail, update style.css.",
                CoordinatedMutationTailPlan.Authority.OPTIONAL);
        assertAuthority(
                "Edit index.html so it works, and update style.css if tests fail.",
                CoordinatedMutationTailPlan.Authority.OPTIONAL);
        assertAuthority(
                "Edit index.html so it works, and update style.css as needed.",
                CoordinatedMutationTailPlan.Authority.OPTIONAL);

        String request =
                "Edit index.html so it works, update style.css, and if needed, "
                        + "then update script.js.";
        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("index.html", "style.css", "script.js"));
        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                plan.decision("style.css").orElseThrow().authority());
        assertEquals(
                CoordinatedMutationTailPlan.Authority.OPTIONAL,
                plan.decision("script.js").orElseThrow().authority());
        assertTrue(
                plan.decision("script.js").orElseThrow().sourceText()
                        .contains("if needed"));
    }

    @Test
    void targetLocalAlternativesAndExclusionsCannotBecomeRequiredAuthority() {
        assertAuthority(
                "Edit index.html so it works, and update style.css or leave it unchanged.",
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE);
        assertAuthority(
                "Edit index.html so it works by changing markup rather than update style.css.",
                CoordinatedMutationTailPlan.Authority.FORBIDDEN);

        assertAuthority(
                "Edit index.html so it works, and update style.css "
                        + "to support light or dark mode.",
                CoordinatedMutationTailPlan.Authority.REQUIRED);
        assertAuthority(
                "Edit index.html so it works, and update style.css too.",
                CoordinatedMutationTailPlan.Authority.REQUIRED);
    }

    @Test
    void touchCannotMasqueradeAsContentMutationAuthority() {
        String request =
                "Edit index.html so it works, and touch config.env.";
        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("index.html", "config.env"));

        assertEquals(
                CoordinatedMutationTailPlan.Authority.WORKSPACE_OPERATION_BLOCKED,
                plan.decision("config.env").orElseThrow().authority());
    }

    @Test
    void metalinguisticImperativesAndBareProhibitionsFailClosed() {
        for (String request : List.of(
                "Edit index.html so the command update style.css remains visible.",
                "Edit index.html so for example, update style.css appears "
                        + "in the help text.")) {
            assertAuthority(
                    request,
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE);
        }
        for (String request : List.of(
                "Edit index.html so it works, but not update style.css.",
                "Edit index.html so it works, but you are not allowed to "
                        + "update style.css.")) {
            assertAuthority(request, CoordinatedMutationTailPlan.Authority.FORBIDDEN);
        }
    }

    @Test
    void coordinatedListsSupportAlsoAndRepeatedCurrentDirectoryAliases() {
        String list =
                "Edit index.html so it works, and update style.css "
                        + "and also script.js.";
        CoordinatedMutationTailPlan listPlan = CoordinatedMutationTailParser.parse(
                list,
                Set.of("index.html", "style.css", "script.js"));
        assertEquals(
                Set.of("style.css", "script.js"),
                listPlan.pathsWithAuthority(
                        CoordinatedMutationTailPlan.Authority.REQUIRED));

        String alias =
                "Edit index.html so it works, and update ././style.css.";
        CoordinatedMutationTailPlan aliasPlan = CoordinatedMutationTailParser.parse(
                alias,
                Set.of("index.html", "style.css"));
        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                aliasPlan.decision("style.css").orElseThrow().authority());
    }

    @Test
    void uncoordinatedTopLevelNewlineRemainsOutsideT1196Tail() {
        String request =
                "Edit index.html so it works\nUpdate style.css too.";

        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("index.html", "style.css"));

        assertTrue(plan.present());
        assertEquals(request.indexOf('\n'), plan.tailEnd());
        assertFalse(plan.decision("style.css").isPresent());
    }

    @Test
    void unicodeSentenceAndLineBoundariesRemainOutsideT1196Tail() {
        for (String separator : List.of("\u2026 ", "\u2028", "\u2029")) {
            String request =
                    "Edit index.html so it works" + separator + "Update style.css too.";
            CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                    request,
                    Set.of("index.html", "style.css"));

            assertTrue(plan.present(), request);
            assertFalse(plan.decision("style.css").isPresent(), request);
        }
    }

    @Test
    void repeatedTargetHasOneEffectiveDecisionAndProhibitionDominates() {
        String request = "Edit index.html so it works, then update style.css, "
                + "then do not edit style.css.";
        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("index.html", "style.css"));

        assertEquals(1, plan.decisions().size());
        assertEquals(
                CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                plan.decision("style.css").orElseThrow().authority());
    }

    @Test
    void pureAdjacentListsBindButSourceDestinationGrammarDoesNot() {
        String list = "Edit index.html so it works, then update style.css and script.js too.";
        CoordinatedMutationTailPlan listPlan = CoordinatedMutationTailParser.parse(
                list,
                Set.of("index.html", "style.css", "script.js"));

        assertEquals(
                Set.of("style.css", "script.js"),
                listPlan.pathsWithAuthority(CoordinatedMutationTailPlan.Authority.REQUIRED));

        String transfer = "Edit index.html so it works, then move style.css to script.js.";
        CoordinatedMutationTailPlan transferPlan = CoordinatedMutationTailParser.parse(
                transfer,
                Set.of("index.html", "style.css", "script.js"));
        assertEquals(
                Set.of("style.css"),
                transferPlan.pathsWithAuthority(
                        CoordinatedMutationTailPlan.Authority.WORKSPACE_OPERATION_BLOCKED));
        assertFalse(transferPlan.decision("script.js").isPresent());
    }

    @Test
    void firstTailStopsBeforeIndependentSequentialSentenceOwnedByT1204() {
        String request = "Edit index.html so it works. Update style.css too.";

        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("index.html", "style.css"));

        assertTrue(plan.present());
        assertFalse(plan.decision("style.css").isPresent());
        assertEquals(request.indexOf(". Update") + 1, plan.tailEnd());
    }

    @Test
    void planRejectsReconstructedOrMismatchedEvidenceText() {
        String request = "Edit index.html so it works, then update style.css.";

        assertThrows(IllegalArgumentException.class, () ->
                new CoordinatedMutationTailPlan(
                        request,
                        request.indexOf(" so "),
                        request.length(),
                        List.of(new CoordinatedMutationTailPlan.TargetDecision(
                                "style.css",
                                CoordinatedMutationTailPlan.Authority.REQUIRED,
                                "update",
                                CoordinatedMutationTailPlan.ActionFamily.CONTENT_MUTATION,
                                request.indexOf("update"),
                                request.indexOf("style.css") + "style.css".length(),
                                 request.length(),
                                 "reconstructed text"))));

        assertThrows(IllegalArgumentException.class, () ->
                new CoordinatedMutationTailPlan(
                        request,
                        request.indexOf(" so "),
                        request.indexOf("update"),
                        List.of(new CoordinatedMutationTailPlan.TargetDecision(
                                "style.css",
                                CoordinatedMutationTailPlan.Authority.REQUIRED,
                                "update",
                                CoordinatedMutationTailPlan.ActionFamily.CONTENT_MUTATION,
                                request.indexOf("update"),
                                request.indexOf("style.css") + "style.css".length(),
                                request.length(),
                                request.substring(request.indexOf("update"))))));

        String lookalike =
                "Edit index.html so it works, then update old-style.css.";
        int update = lookalike.indexOf("update");
        assertThrows(IllegalArgumentException.class, () ->
                new CoordinatedMutationTailPlan(
                        lookalike,
                        lookalike.indexOf(" so "),
                        lookalike.length(),
                        List.of(new CoordinatedMutationTailPlan.TargetDecision(
                                "style.css",
                                CoordinatedMutationTailPlan.Authority.REQUIRED,
                                "update",
                                CoordinatedMutationTailPlan.ActionFamily.CONTENT_MUTATION,
                                update,
                                lookalike.indexOf("old-style.css")
                                        + "old-style.css".length(),
                                lookalike.length(),
                                lookalike.substring(update)))));

        String caseDistinct =
                "Edit index.html so it works, then update a.md.";
        int caseUpdate = caseDistinct.indexOf("update");
        assertThrows(IllegalArgumentException.class, () ->
                new CoordinatedMutationTailPlan(
                        caseDistinct,
                        caseDistinct.indexOf(" so "),
                        caseDistinct.length(),
                        List.of(new CoordinatedMutationTailPlan.TargetDecision(
                                "A.md",
                                CoordinatedMutationTailPlan.Authority.REQUIRED,
                                "update",
                                CoordinatedMutationTailPlan.ActionFamily.CONTENT_MUTATION,
                                caseUpdate,
                                caseDistinct.indexOf("a.md") + "a.md".length(),
                                caseDistinct.length(),
                                caseDistinct.substring(caseUpdate)))));

        String caseDistinctWorkspace =
                "Edit index.html so it works, then copy a.md.";
        int copy = caseDistinctWorkspace.indexOf("copy");
        assertThrows(IllegalArgumentException.class, () ->
                new CoordinatedMutationTailPlan(
                        caseDistinctWorkspace,
                        caseDistinctWorkspace.indexOf(" so "),
                        caseDistinctWorkspace.length(),
                        List.of(),
                        List.of(new CoordinatedMutationTailPlan
                                .WorkspaceOperationObservation(
                                        "A.md",
                                        "copy",
                                        copy,
                                        caseDistinctWorkspace.length(),
                                        caseDistinctWorkspace.substring(copy)))));
    }

    @Test
    void workspaceActionFamilyCannotDivergeFromItsFailClosedAuthority() {
        String request =
                "Edit index.html so it works, then copy style.css.";
        int start = request.indexOf("copy");

        assertThrows(IllegalArgumentException.class, () ->
                new CoordinatedMutationTailPlan.TargetDecision(
                        "style.css",
                        CoordinatedMutationTailPlan.Authority
                                .WORKSPACE_OPERATION_BLOCKED,
                        "copy",
                        CoordinatedMutationTailPlan.ActionFamily.CONTENT_MUTATION,
                        start,
                        request.indexOf("style.css") + "style.css".length(),
                        request.length(),
                        request.substring(start)));
        assertThrows(IllegalArgumentException.class, () ->
                new CoordinatedMutationTailPlan.TargetDecision(
                        "style.css",
                        CoordinatedMutationTailPlan.Authority.REQUIRED,
                        "copy",
                        CoordinatedMutationTailPlan.ActionFamily.WORKSPACE_OPERATION,
                        start,
                        request.indexOf("style.css") + "style.css".length(),
                        request.length(),
                        request.substring(start)));

        var explicit = new CoordinatedMutationTailPlan.TargetDecision(
                "style.css",
                CoordinatedMutationTailPlan.Authority
                        .WORKSPACE_OPERATION_BLOCKED,
                "copy",
                CoordinatedMutationTailPlan.ActionFamily.WORKSPACE_OPERATION,
                start,
                request.indexOf("style.css") + "style.css".length(),
                request.length(),
                request.substring(start));
        assertEquals(
                CoordinatedMutationTailPlan.ActionFamily.WORKSPACE_OPERATION,
                explicit.actionFamily());
    }

    @Test
    void equalProhibitionsCannotEraseAWorkspaceOperationFamily() {
        String request =
                "Edit index.html so it works, then do not edit style.css, "
                        + "and do not copy style.css to script.js.";
        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("index.html", "style.css", "script.js"));

        var style = plan.decision("style.css").orElseThrow();
        assertEquals(
                CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                style.authority());
        assertEquals(
                CoordinatedMutationTailPlan.ActionFamily.WORKSPACE_OPERATION,
                style.actionFamily());
        assertTrue(plan.blocksWorkspaceOperations());
    }

    @Test
    void strongerContentProhibitionCannotEraseObservedWorkspaceAction() {
        String request =
                "Edit index.html so it works, then do not edit style.css, "
                        + "but delete style.css.";
        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("index.html", "style.css"));

        var style = plan.decision("style.css").orElseThrow();
        assertEquals(
                CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                style.authority());
        assertEquals(
                CoordinatedMutationTailPlan.ActionFamily.CONTENT_MUTATION,
                style.actionFamily());
        assertTrue(plan.blocksWorkspaceOperations());
        assertEquals(1, plan.workspaceOperationObservations().size());
        var observation = plan.workspaceOperationObservations().getFirst();
        assertEquals("style.css", observation.path());
        assertEquals("delete", observation.action());
        assertEquals(
                observation.sourceText(),
                request.substring(
                        observation.startOffset(),
                        observation.endOffset()));
    }

    @Test
    void suffixQualifiersMustBeginAtTheSelectedTargetSuffix() {
        for (String request : List.of(
                "Edit index.html so update style.css to explain where needed "
                        + "values appear.",
                "Edit index.html so update style.css to document only when "
                        + "animations run.",
                "Edit index.html so update style.css to mark the setting as an "
                        + "option.",
                "Edit index.html so update style.css to prepare the design for "
                        + "review.",
                "Edit index.html so update style.css to label the block as a "
                        + "recommendation.",
                "Edit index.html so update style.css to explain where possible "
                        + "values appear.",
                "Edit index.html so update style.css to label the control "
                        + "optional.")) {
            assertAuthority(
                    request,
                    CoordinatedMutationTailPlan.Authority.REQUIRED);
        }
        assertAuthority(
                "Edit index.html so update style.css only as a proposal.",
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE);
    }

    @Test
    void boundedReportedAndFirstPersonModifiersRemainNonAuthoritative() {
        for (String request : List.of(
                "Edit index.html so the report then says update style.css.",
                "Edit index.html so the report also says update style.css.",
                "Edit index.html so the report clearly says update style.css.",
                "Edit index.html so I would suggest you update style.css.",
                "Edit index.html so I might suggest you update style.css.",
                "Edit index.html so I strongly suggest you update style.css.",
                "Edit index.html so proposal: update style.css.",
                "Edit index.html so suggestion: update style.css.",
                "Edit index.html so recommendation: update style.css.",
                "Edit index.html so option: update style.css.",
                "Edit index.html so my recommendation is to update style.css.",
                "Edit index.html so one option is to update style.css.",
                "Edit index.html so it works, and the proposal from the "
                        + "reviewer is to update style.css.",
                "Edit index.html so a proposal from the report was to "
                        + "update style.css.",
                "Edit index.html so proposed change: update style.css.",
                "Edit index.html so suggested change: update style.css.",
                "Edit index.html so I will update style.css.",
                "Edit index.html so I can update style.css.",
                "Edit index.html so I could update style.css.",
                "Edit index.html so I may update style.css.",
                "Edit index.html so I might update style.css.",
                "Edit index.html so I plan to update style.css.",
                "Edit index.html so I intend to update style.css.",
                "Edit index.html so we could update style.css.",
                "Edit index.html so we may update style.css.",
                "Edit index.html so we might update style.css.",
                "Edit index.html so the operator will update style.css.",
                "Edit index.html so there is no need to update style.css.",
                "Edit index.html so no need to update style.css.",
                "Edit index.html so it is unnecessary to update style.css.")) {
            assertAuthority(
                    request,
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE);
        }
        for (String required : List.of(
                "Edit index.html so please update style.css.",
                "Edit index.html so you must update style.css.",
                "Edit index.html so you need to update style.css.")) {
            assertAuthority(
                    required,
                    CoordinatedMutationTailPlan.Authority.REQUIRED);
        }

        for (String noun : List.of("then branch", "but clause")) {
            String request =
                    "Review index.html so the report says update style.css to "
                            + "explain the "
                            + noun
                            + ", and update script.js.";
            CoordinatedMutationTailPlan plan =
                    CoordinatedMutationTailParser.parse(
                            request,
                            Set.of("index.html", "style.css", "script.js"));
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    plan.decision("style.css").orElseThrow().authority(),
                    request);
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    plan.decision("script.js").orElseThrow().authority(),
                    request);
            assertTrue(
                    plan.decision("style.css").orElseThrow().sourceText()
                            .contains(noun),
                    request);
        }
    }

    @Test
    void reportedModalAndInfinitiveProseCannotGrantMutationAuthority() {
        for (String request : List.of(
                "Edit index.html so the script will update style.css.",
                "Edit index.html so the help explains how to update style.css.",
                "Edit index.html so suggestions: update style.css.")) {
            assertAuthority(
                    request,
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE);

            RequestLexicalView view = RequestLexicalView.of(request);
            List<CanonicalMutationClauseMatcher.Action> actions =
                    CanonicalMutationClauseMatcher.actions(
                            view,
                            CoordinatedMutationTailParser
                                    .firstUnquotedConstraintBoundary(view),
                            request.length());
            CanonicalMutationClauseMatcher.Action tailAction =
                    actions.getLast();
            assertTrue(tailAction.nonImperativeUse(), request);
            assertFalse(
                    CanonicalMutationClauseMatcher.directlyBindsContentTarget(
                            request,
                            "style.css"),
                    request);
        }

        for (String required : List.of(
                "Edit index.html so please update style.css.",
                "Edit index.html so we should minimally update style.css.",
                "Edit index.html so it works, but update style.css.")) {
            assertAuthority(
                    required,
                    CoordinatedMutationTailPlan.Authority.REQUIRED);
        }
    }

    @Test
    void reportedProhibitionCannotOverrideIndependentImperative() {
        String request =
                "Edit index.html so the help text says do not edit style.css, "
                        + "but Update style.css.";
        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("index.html", "style.css"));

        var decision = plan.decision("style.css").orElseThrow();
        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                decision.authority());
        assertEquals("but Update style.css.", decision.sourceText());
        assertEquals(
                request.indexOf("but Update style.css"),
                decision.startOffset());

        CoordinatedMutationTailPlan suffixPlan =
                CoordinatedMutationTailParser.parse(
                        "Edit index.html so the help text says style.css "
                                + "must remain unchanged, but Update style.css.",
                        Set.of("index.html", "style.css"));
        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                suffixPlan.decision("style.css").orElseThrow().authority());

        assertAuthority(
                "Edit index.html so the help text is wrong, "
                        + "but do not edit style.css.",
                CoordinatedMutationTailPlan.Authority.FORBIDDEN);
        assertAuthority(
                "Edit index.html so the help text says edits are unsafe, "
                        + "but do not edit style.css.",
                CoordinatedMutationTailPlan.Authority.FORBIDDEN);
    }

    @Test
    void curlyApostropheNegationRemainsForbiddenDuringRepair() {
        String request =
                "Repair index.html so it works, but don\u2019t edit style.css.";
        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("index.html", "style.css"));

        var decision = plan.decision("style.css").orElseThrow();
        assertEquals(
                CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                decision.authority());
        assertEquals("don\u2019t edit style.css.", decision.sourceText());
        assertEquals(
                decision.sourceText(),
                request.substring(
                        decision.startOffset(),
                        decision.endOffset()));
    }

    @Test
    void exactBoundTargetEnvelopesCannotBeReparsedAsGrammar() {
        for (String target : List.of(
                "when.css",
                "unless.css",
                "maybe.css",
                "possibly.css",
                "never.css",
                "and.css",
                "but.css",
                "then.css",
                "and",
                "but",
                "then",
                "brand and theme.css",
                "my update file.css")) {
            String request =
                    "Edit index.html so it works, then in "
                            + target
                            + ", update the color.";
            CoordinatedMutationTailPlan plan =
                    CoordinatedMutationTailParser.parse(
                            request,
                            Set.of("index.html", target));
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.REQUIRED,
                    plan.decision(target).orElseThrow().authority(),
                    request);
        }

        for (String target : List.of(
                "and",
                "but",
                "then",
                "brand-and-theme.css",
                "brand and theme.css",
                "brand,theme.css",
                "brand;theme.css",
                "brand\u2013theme.css",
                "brand\u2014theme.css",
                "update.css",
                "update",
                "my update file.css")) {
            String request =
                    "Edit index.html so it works, then update "
                            + target
                            + " and update script.js.";
            CoordinatedMutationTailPlan plan =
                    CoordinatedMutationTailParser.parse(
                            request,
                            Set.of("index.html", target, "script.js"));
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.REQUIRED,
                    plan.decision(target).orElseThrow().authority(),
                    request);
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.REQUIRED,
                    plan.decision("script.js").orElseThrow().authority(),
                    request);
        }

        for (String target : List.of("never", "if", "when", "update")) {
            String request =
                    "Edit index.html so it works, then update "
                            + target
                            + ": update script.js.";
            CoordinatedMutationTailPlan plan =
                    CoordinatedMutationTailParser.parse(
                            request,
                            Set.of("index.html", target, "script.js"));
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.REQUIRED,
                    plan.decision(target).orElseThrow().authority(),
                    request);
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.REQUIRED,
                    plan.decision("script.js").orElseThrow().authority(),
                    request);
        }
    }

    @Test
    void directTargetDiscoveryIsActionBoundAndExtensionNeutral() {
        for (String target : List.of(
                "styles.scss",
                "main.go",
                "Cargo.lock",
                "page.vue",
                "component.svelte",
                "config.conf",
                "foo.sh",
                "notes.rst",
                "data.ndjson")) {
            assertEquals(
                    Set.of(target),
                    CoordinatedMutationTailParser.discoverDirectTargets(
                            "Edit index.html so update " + target + "."),
                    target);
        }
        for (String quoted : List.of(
                "\"brand and theme.css\"",
                "\"docs/site style.css\"",
                "\u201cstyle.css\u201d",
                "`literal target`")) {
            assertEquals(
                    1,
                    CoordinatedMutationTailParser.discoverDirectTargets(
                            "Edit index.html so update " + quoted + ".").size(),
                    quoted);
        }
        assertEquals(
                Set.of(),
                CoordinatedMutationTailParser.discoverDirectTargets(
                        "Edit index.html so update if needed."));
    }

    @Test
    void unrelatedCandidateNamesCannotBecomeSyntaxOrTargets() {
        String optional =
                "Review index.html so optionally update style.css and update "
                        + "script.js.";
        CoordinatedMutationTailPlan baseline =
                CoordinatedMutationTailParser.parse(
                        optional,
                        Set.of("index.html", "style.css", "script.js"));
        CoordinatedMutationTailPlan withConnectorCandidate =
                CoordinatedMutationTailParser.parse(
                        optional,
                        Set.of(
                                "index.html",
                                "style.css",
                                "script.js",
                                "and"));
        assertEquals(baseline.decisions(), withConnectorCandidate.decisions());
        assertFalse(withConnectorCandidate.decision("and").isPresent());

        for (String suffixCandidate : List.of(
                "if", "or", "as", "when", "also")) {
            String request =
                    "Edit index.html so update style.css if needed.";
            CoordinatedMutationTailPlan plan =
                    CoordinatedMutationTailParser.parse(
                            request,
                            Set.of(
                                    "index.html",
                                    "style.css",
                                    suffixCandidate));
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.OPTIONAL,
                    plan.decision("style.css").orElseThrow().authority(),
                    suffixCandidate);
            assertFalse(
                    plan.decision(suffixCandidate).isPresent(),
                    suffixCandidate);
        }

        for (var testCase : Map.of(
                "if", "Edit index.html so it works, then update if needed.",
                "when", "Edit index.html so it works, then update when necessary.",
                "as", "Edit index.html so it works, then update as a proposal.",
                "or", "Edit index.html so it works, then update or leave it unchanged.")
                .entrySet()) {
            CoordinatedMutationTailPlan qualifierBaseline =
                    CoordinatedMutationTailParser.parse(
                            testCase.getValue(),
                            Set.of("index.html"));
            CoordinatedMutationTailPlan withSyntaxCandidate =
                    CoordinatedMutationTailParser.parse(
                            testCase.getValue(),
                            Set.of("index.html", testCase.getKey()));
            assertEquals(
                    qualifierBaseline.decisions(),
                    withSyntaxCandidate.decisions(),
                    testCase.getValue());
            assertFalse(
                    withSyntaxCandidate.decision(testCase.getKey()).isPresent(),
                    testCase.getValue());

            String quotedRequest =
                    "Edit index.html so it works, then update \""
                            + testCase.getKey()
                            + "\".";
            CoordinatedMutationTailPlan quoted =
                    CoordinatedMutationTailParser.parse(
                            quotedRequest,
                            Set.of("index.html", testCase.getKey()));
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.REQUIRED,
                    quoted.decision(testCase.getKey()).orElseThrow().authority(),
                    quotedRequest);
        }
    }

    @Test
    void naturalWorkspaceBatchOwnsItsCompleteStructuralEvidenceSpan() {
        for (String request : List.of(
                "Edit index.html so it works, then create \".config\" and "
                        + "assets, then copy style.css to .config/style.css.",
                "Edit index.html so it works, then create docs, assets, and "
                        + "cache, then copy style.css to docs/style.css.",
                "Edit index.html so it works, then create docs assets; then "
                        + "copy style.css to docs/style.css.",
                "Edit index.html so it works, then create docs and assets "
                        + "first, then copy style.css to docs/style.css.",
                "Edit index.html so it works, then create docs first and "
                        + "assets, then copy style.css to docs/style.css.",
                "Edit index.html so it works, then create docs carefully and "
                        + "assets, then copy style.css to docs/style.css.",
                "Edit index.html so it works, then create docs and assets, "
                        + "then delete old.txt.",
                "Edit index.html so it works, then create docs and assets, "
                        + "then remove old.txt, then copy style.css to "
                        + "docs/style.css.",
                "Edit index.html so it works, then create docs and assets, "
                        + "update index.html, then copy style.css to "
                        + "docs/style.css.",
                "Edit index.html so it works, then create docs and docs, then "
                        + "copy style.css to docs/style.css.",
                "Edit index.html so it works, then create docs and ./docs, "
                        + "then copy style.css to docs/style.css.")) {
            CoordinatedMutationTailPlan plan =
                    CoordinatedMutationTailParser.parse(
                            request,
                            Set.of(
                                    "index.html",
                                    ".config",
                                    "assets",
                                    "docs",
                                    "cache",
                                    "style.css",
                                    "old.txt",
                                    ".config/style.css",
                                    "docs/style.css"));
            assertTrue(plan.blocksWorkspaceOperations(), request);
            assertTrue(
                    plan.decisions().stream().anyMatch(decision ->
                            "create".equals(decision.action())),
                    request);
            for (var decision : plan.decisions()) {
                if (!"create".equals(decision.action())) continue;
                assertEquals(
                        CoordinatedMutationTailPlan.Authority
                                .WORKSPACE_OPERATION_BLOCKED,
                        decision.authority(),
                        request);
                assertTrue(
                        decision.sourceText().contains("copy")
                                || decision.sourceText().contains("delete")
                                || decision.sourceText().contains("remove"),
                        decision.sourceText());
                assertTrue(
                        decision.sourceText().contains("assets")
                                || decision.sourceText().contains("docs"),
                        decision.sourceText());
                assertEquals(
                        decision.sourceText(),
                        request.substring(
                                decision.startOffset(),
                                decision.endOffset()));
            }
        }

        assertAuthority(
                "Edit index.html so it works, then create \"style.css\".",
                CoordinatedMutationTailPlan.Authority.REQUIRED);
        assertAuthority(
                "Edit index.html so it works, then create style.css first.",
                CoordinatedMutationTailPlan.Authority.REQUIRED);
    }

    private static void assertAuthority(
            String request,
            CoordinatedMutationTailPlan.Authority authority
    ) {
        CoordinatedMutationTailPlan plan = CoordinatedMutationTailParser.parse(
                request,
                Set.of("index.html", "style.css", "theme.css"));
        assertTrue(
                plan.decision("style.css").isPresent(),
                request + " -> " + plan.decisions());
        assertEquals(
                authority,
                plan.decision("style.css").orElseThrow().authority(),
                request);
    }
}
