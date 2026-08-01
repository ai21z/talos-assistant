package dev.talos.runtime.intent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinatedMutationTailParserWorkTest {

    @Test
    void targetMatchingBuildsOneAutomatonAndScansTheTailOnce() {
        for (String newline : List.of("\n", "\r\n")) {
            for (int targetCount : List.of(8, 32, 64, 128)) {
                Fixture fixture = fixture(targetCount, newline);

                CoordinatedMutationTailParser.ParseWorkResult result =
                        CoordinatedMutationTailParser.parseWithWork(
                                fixture.request(),
                                fixture.targets());
                CoordinatedMutationTailParser.ParseWork work = result.work();

                assertEquals(1, work.targetIndexBuilds(), fixture.label());
                assertEquals(1, work.normalizedRequestCopies(), fixture.label());
                assertEquals(targetCount, work.candidateCount(), fixture.label());
                assertEquals(
                        targetCount,
                        work.indexedOccurrenceCount(),
                        fixture.label());
                assertEquals(
                        fixture.targets().stream()
                                .mapToLong(path ->
                                        path.replace('\\', '/').length())
                                .sum(),
                        work.targetPatternCharacters(),
                        fixture.label());
                assertEquals(
                        work.scanEnd() - work.scanStart(),
                        work.targetInputCharacters(),
                        fixture.label());
                assertEquals(
                        work.targetInputCharacters(),
                        work.targetAutomatonTransitions(),
                        fixture.label());
                assertTrue(
                        work.targetFailureTransitions()
                                <= work.targetInputCharacters(),
                        fixture.label());
                assertEquals(
                        targetCount,
                        work.targetOutputVisits(),
                        fixture.label());
                assertEquals(
                        fixture.targets(),
                        result.plan().decisions().stream()
                                .map(CoordinatedMutationTailPlan
                                        .TargetDecision::path)
                                .collect(java.util.stream.Collectors.toSet()),
                        fixture.label());
                assertTrue(work.targetRangeQueries() > 0, fixture.label());
                assertTrue(
                        work.targetRangeQueries() <= targetCount * 4L,
                        fixture.label());
                assertTrue(
                        work.indexedOccurrenceVisits() <= targetCount * 6L,
                        fixture.label());
                assertTrue(work.fullRangeQueries() > 0, fixture.label());
                assertTrue(
                        work.prohibitionFastRejectCharacters()
                                <= work.targetInputCharacters(),
                        fixture.label());
                assertEquals(1, work.authorityActionScans(), fixture.label());
                assertEquals(
                        work.targetInputCharacters(),
                        work.authorityActionInputCharacters(),
                        fixture.label());
                assertEquals(
                        targetCount,
                        work.authorityNominalPrefixChecks(),
                        fixture.label());
                assertTrue(
                        work.authorityNominalPrefixCharacters()
                                <= work.authorityActionInputCharacters()
                                        + 9L * targetCount,
                        fixture.label());
                assertEquals(1, work.authorityConnectorScans(), fixture.label());
                assertTrue(
                        work.authorityConnectorInputCharacters()
                                <= work.authorityActionInputCharacters(),
                        fixture.label());
                assertEquals(
                        targetCount - 1L,
                        work.authorityTransitions(),
                        fixture.label());
                assertTrue(
                        work.authorityClauseCharacters()
                                <= work.authorityActionInputCharacters(),
                        fixture.label());
                assertEquals(
                        1,
                        work.authorityFallbackClassifications(),
                        fixture.label());
                assertEquals(
                        0,
                        work.authorityFallbackFailClosedClassifications(),
                        fixture.label());
                assertTrue(
                        work.authorityFallbackCharacters()
                                <= work.authorityActionInputCharacters(),
                        fixture.label());
                assertEquals(1, work.authorityTargetEnvelopeIndexBuilds(), fixture.label());
                assertEquals(targetCount, work.authorityTargetEnvelopeIndexEntries(), fixture.label());
                assertEquals(
                        work.authorityActionInputCharacters(),
                        work.authorityTargetEnvelopeBuildCharacters(),
                        fixture.label());
                assertEquals(
                        targetCount,
                        work.authorityTargetEnvelopeContainmentQueries(),
                        fixture.label());
                assertEquals(
                        targetCount - 1L,
                        work.authorityTargetEnvelopeRangeQueries(),
                        fixture.label());
                assertTrue(
                        work.authorityTargetEnvelopeVisits() <= targetCount * 3L,
                        fixture.label());
                assertTrue(
                        work.authorityTargetEnvelopeCoverageChecks()
                                <= work.authorityActionInputCharacters(),
                        fixture.label());
            }
        }
    }

    @Test
    void sharedPrefixesDoNotMultiplyTailCharacterScans() {
        Set<String> targets = new LinkedHashSet<>();
        StringBuilder request = new StringBuilder("Edit index.html so ");
        for (int i = 1; i <= 64; i++) {
            String target = "shared-" + "a".repeat(i) + ".css";
            targets.add(target);
            if (i > 1) request.append(",\n then ");
            request.append("update ").append(target);
        }
        request.append('.');

        CoordinatedMutationTailParser.ParseWorkResult result =
                CoordinatedMutationTailParser.parseWithWork(
                        request.toString(),
                        Set.copyOf(targets));
        CoordinatedMutationTailParser.ParseWork work = result.work();

        assertEquals(1, work.targetIndexBuilds());
        assertEquals(
                work.scanEnd() - work.scanStart(),
                work.targetInputCharacters());
        assertEquals(
                work.targetInputCharacters(),
                work.targetAutomatonTransitions());
        assertTrue(work.targetFailureTransitions() <= work.targetInputCharacters());
        assertEquals(targets.size(), work.indexedOccurrenceCount());
        assertEquals(targets.size(), work.targetOutputVisits());
        assertEquals(
                targets,
                result.plan().decisions().stream()
                        .map(CoordinatedMutationTailPlan.TargetDecision::path)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, work.authorityActionScans());
        assertEquals(
                work.targetInputCharacters(),
                work.authorityActionInputCharacters());
        assertEquals(1, work.authorityConnectorScans());
        assertTrue(
                work.authorityConnectorInputCharacters()
                        <= work.authorityActionInputCharacters());
        assertEquals(targets.size() - 1L, work.authorityTransitions());
        assertEquals(1, work.authorityFallbackClassifications());
        assertEquals(0, work.authorityFallbackFailClosedClassifications());
        assertEquals(targets.size(), work.authorityNominalPrefixChecks());
        assertTrue(
                work.authorityNominalPrefixCharacters()
                        <= work.authorityActionInputCharacters()
                                + 9L * targets.size());
        assertEquals(1, work.authorityTargetEnvelopeIndexBuilds());
        assertEquals(targets.size(), work.authorityTargetEnvelopeIndexEntries());
        assertEquals(targets.size(), work.authorityTargetEnvelopeContainmentQueries());
        assertEquals(targets.size() - 1L, work.authorityTargetEnvelopeRangeQueries());
        assertTrue(work.authorityTargetEnvelopeVisits() <= targets.size() * 3L);
    }

    @Test
    void typedScopeTransitionsMatchTheCanonicalGrammarInLinearWork() {
        for (String newline : List.of("\n", "\r\n")) {
            for (ScopeFixture fixture : List.of(
                    scopeFixture("direct", "", "then", false, false, newline),
                    scopeFixture(
                            "optional",
                            "optionally ",
                            "then",
                            false,
                            false,
                            newline),
                    scopeFixture(
                            "reported",
                            "the report says ",
                            "but",
                            true,
                            false,
                            newline),
                    scopeFixture(
                            "prohibited",
                            "do not ",
                            "then",
                            true,
                            false,
                            newline))) {
                RequestLexicalView view = RequestLexicalView.of(
                        fixture.request());
                CanonicalMutationClauseMatcher.IndexedActions indexed =
                        CanonicalMutationClauseMatcher.indexedActions(
                                view,
                                fixture.start(),
                                fixture.request().length(),
                                fixture.targetSpans());

                assertEquals(
                        fixture.firstNonImperative(),
                        indexed.actions().getFirst().nonImperativeUse(),
                        fixture.label());
                assertEquals(
                        fixture.remainingNonImperative(),
                        indexed.actions().getLast().nonImperativeUse(),
                        fixture.label());
                assertTrue(
                        indexed.actions().stream().skip(1).allMatch(action ->
                                action.nonImperativeUse()
                                        == fixture.remainingNonImperative()),
                        fixture.label());
                assertEquals(1, indexed.work().actionScans(), fixture.label());
                assertEquals(1, indexed.work().connectorScans(), fixture.label());
                assertEquals(
                        fixture.request().length() - fixture.start(),
                        indexed.work().actionInputCharacters(),
                        fixture.label());
                assertTrue(
                        indexed.work().connectorInputCharacters()
                                <= indexed.work().actionInputCharacters(),
                        fixture.label());
                assertEquals(
                        indexed.actions().size()
                                - indexed.work().fallbackClassifications(),
                        indexed.work().authorityTransitions(),
                        fixture.label());
                assertTrue(
                        indexed.work().fallbackClassifications() <= 2,
                        fixture.label());
                assertTrue(
                        indexed.work().fallbackCharacters()
                                <= indexed.work().actionInputCharacters() * 2,
                        fixture.label());
                assertEquals(
                        0,
                        indexed.work().fallbackFailClosedClassifications(),
                        fixture.label());
            }
        }
    }

    @Test
    void adversarialNominalAndEnvelopeWorkScalesWithInputAndTargets() {
        CoordinatedMutationTailParser.ParseWork previous = null;
        for (int targetCount : List.of(64, 128)) {
            AdversarialFixture fixture = adversarialFixture(targetCount);
            CoordinatedMutationTailParser.ParseWorkResult result =
                    CoordinatedMutationTailParser.parseWithWork(
                            fixture.request(), fixture.targets());
            CoordinatedMutationTailParser.ParseWork work = result.work();

            assertEquals(targetCount, result.plan().decisions().size());
            assertEquals(targetCount * 2L, work.authorityNominalPrefixChecks());
            assertTrue(
                    work.authorityNominalPrefixCharacters()
                            <= work.authorityActionInputCharacters()
                                    + 9L * work.authorityNominalPrefixChecks());
            assertEquals(1, work.authorityTargetEnvelopeIndexBuilds());
            assertEquals(targetCount, work.authorityTargetEnvelopeIndexEntries());
            assertEquals(targetCount, work.authorityTargetEnvelopeContainmentQueries());
            assertEquals(targetCount - 1L, work.authorityTargetEnvelopeRangeQueries());
            assertTrue(work.authorityTargetEnvelopeVisits() <= targetCount * 3L);
            assertTrue(
                    work.authorityTargetEnvelopeCoverageChecks()
                            <= work.authorityActionInputCharacters());
            assertTrue(work.authorityFallbackClassifications() > 0);
            assertTrue(work.authorityFallbackFailClosedClassifications() > 0);
            assertEquals(
                    targetCount,
                    work.authorityFallbackClassifications()
                            + work.authorityTransitions()
                            + work.authorityFallbackFailClosedClassifications());
            assertTrue(
                    work.authorityFallbackCharacters()
                            <= work.authorityActionInputCharacters()
                                    * CanonicalMutationClauseMatcher
                                            .MAX_FALLBACK_INPUT_MULTIPLIER,
                    "ambiguous fallbacks must remain within the cumulative budget: "
                            + work.authorityFallbackCharacters()
                            + " > "
                            + work.authorityActionInputCharacters());

            if (previous != null) {
                assertTrue(
                        work.authorityNominalPrefixCharacters()
                                <= previous.authorityNominalPrefixCharacters() * 3L,
                        "doubling the request must not square nominal-prefix work");
                assertTrue(
                        work.authorityTargetEnvelopeVisits()
                                <= previous.authorityTargetEnvelopeVisits() * 3L,
                        "doubling targets must not square envelope visits");
                assertTrue(
                        work.authorityFallbackCharacters()
                                <= previous.authorityFallbackCharacters() * 3L,
                        "doubling the request must not square fallback-prefix work");
            }
            previous = work;
        }
    }

    @Test
    void fallbackClassificationHonorsTheExactPerCallCapAndFailsClosedAboveIt() {
        int cap = CanonicalMutationClauseMatcher
                .MAX_FALLBACK_CLASSIFICATION_CHARACTERS;
        assertEquals(4096, cap);
        assertEquals(
                2,
                CanonicalMutationClauseMatcher.MAX_FALLBACK_INPUT_MULTIPLIER);
        String exactRequest = " ".repeat(cap) + "update style.css";
        CanonicalMutationClauseMatcher.IndexedActions exact =
                CanonicalMutationClauseMatcher.indexedActions(
                        RequestLexicalView.of(exactRequest),
                        0,
                        exactRequest.length(),
                        List.of());

        assertEquals(1, exact.actions().size());
        assertEquals(false, exact.actions().getFirst().nonImperativeUse());
        assertEquals(1, exact.work().fallbackClassifications());
        assertEquals(cap, exact.work().fallbackCharacters());
        assertEquals(0, exact.work().fallbackFailClosedClassifications());

        String overRequest = " ".repeat(cap + 1) + "update style.css";
        CanonicalMutationClauseMatcher.IndexedActions over =
                CanonicalMutationClauseMatcher.indexedActions(
                        RequestLexicalView.of(overRequest),
                        0,
                        overRequest.length(),
                        List.of());

        assertEquals(1, over.actions().size());
        assertEquals(true, over.actions().getFirst().nonImperativeUse());
        assertEquals(0, over.work().fallbackClassifications());
        assertEquals(0, over.work().fallbackCharacters());
        assertEquals(1, over.work().fallbackFailClosedClassifications());
    }

    @Test
    void fallbackCapExcludesTheUnrelatedPrefixBeforeTheBoundedTail() {
        String unrelated = "Review index.html first. ";
        int cap = CanonicalMutationClauseMatcher
                .MAX_FALLBACK_CLASSIFICATION_CHARACTERS;
        String exactTail = " ".repeat(cap) + "update style.css";
        String exactRequest = unrelated + exactTail;
        CanonicalMutationClauseMatcher.IndexedActions exact =
                CanonicalMutationClauseMatcher.indexedActions(
                        RequestLexicalView.of(exactRequest),
                        unrelated.length(),
                        exactRequest.length(),
                        List.of());

        assertEquals(1, exact.actions().size());
        assertEquals(unrelated.length() + cap, exact.actions().getFirst().start());
        assertEquals(exactTail.length(), exact.work().actionInputCharacters());
        assertEquals(cap, exact.work().fallbackCharacters());
        assertEquals(1, exact.work().fallbackClassifications());
        assertEquals(0, exact.work().fallbackFailClosedClassifications());

        String overTail = " ".repeat(cap + 1) + "update style.css";
        String overRequest = unrelated + overTail;
        CanonicalMutationClauseMatcher.IndexedActions over =
                CanonicalMutationClauseMatcher.indexedActions(
                        RequestLexicalView.of(overRequest),
                        unrelated.length(),
                        overRequest.length(),
                        List.of());

        assertEquals(1, over.actions().size());
        assertEquals(unrelated.length() + cap + 1,
                over.actions().getFirst().start());
        assertEquals(overTail.length(), over.work().actionInputCharacters());
        assertEquals(0, over.work().fallbackCharacters());
        assertEquals(0, over.work().fallbackClassifications());
        assertEquals(1, over.work().fallbackFailClosedClassifications());
        assertEquals(true, over.actions().getFirst().nonImperativeUse());
    }

    @Test
    void cumulativeFallbackBudgetBoundsUnboundAndTargetAmbiguousPrefixes() {
        CanonicalMutationClauseMatcher.AuthorityWork previousUnbound = null;
        CanonicalMutationClauseMatcher.AuthorityWork previousAmbiguous = null;
        for (int actionCount : List.of(64, 128)) {
            String unbound = repeatedUnboundActions(actionCount);
            CanonicalMutationClauseMatcher.IndexedActions unboundResult =
                    CanonicalMutationClauseMatcher.indexedActions(
                            RequestLexicalView.of(unbound),
                            0,
                            unbound.length(),
                            List.of());
            assertCappedFallbackAccounting(unboundResult, unbound.length());
            assertEquals(0, unboundResult.work().authorityTransitions());
            assertEquals(
                    true,
                    unboundResult.actions().getLast().nonImperativeUse());

            TargetAmbiguousFixture ambiguous =
                    repeatedTargetAmbiguousActions(actionCount);
            CanonicalMutationClauseMatcher.IndexedActions ambiguousResult =
                    CanonicalMutationClauseMatcher.indexedActions(
                            RequestLexicalView.of(ambiguous.request()),
                            0,
                            ambiguous.request().length(),
                            ambiguous.protectedSpans());
            assertCappedFallbackAccounting(
                    ambiguousResult,
                    ambiguous.request().length());
            assertEquals(0, ambiguousResult.work().authorityTransitions());
            assertEquals(
                    actionCount,
                    ambiguousResult.work().targetEnvelopeContainmentQueries());
            assertEquals(
                    true,
                    ambiguousResult.actions().getLast().nonImperativeUse());

            if (previousUnbound != null) {
                assertTrue(
                        unboundResult.work().fallbackCharacters()
                                <= previousUnbound.fallbackCharacters() * 3L,
                        "doubling unbound actions must not square fallback work");
                assertTrue(
                        ambiguousResult.work().fallbackCharacters()
                                <= previousAmbiguous.fallbackCharacters() * 3L,
                        "doubling target-ambiguous actions must not square fallback work");
            }
            previousUnbound = unboundResult.work();
            previousAmbiguous = ambiguousResult.work();
        }
    }

    @Test
    void indexedAuthorityMatchesForcedCanonicalAcrossTheFiniteGrammar() {
        for (ParityFixture fixture : parityFixtures()) {
            RequestLexicalView view = RequestLexicalView.of(fixture.request());
            List<RequestLexicalView.Span> spans = targetSpans(
                    view, fixture.targets());
            CanonicalMutationClauseMatcher.IndexedActions canonical =
                    CanonicalMutationClauseMatcher.indexedActions(
                            view,
                            fixture.start(),
                            fixture.request().length(),
                            List.of());
            CanonicalMutationClauseMatcher.IndexedActions indexed =
                    CanonicalMutationClauseMatcher.indexedActions(
                            view,
                            fixture.start(),
                            fixture.request().length(),
                            spans);

            assertEquals(canonical.actions(), indexed.actions(), fixture.label());
            assertEquals(fixture.expectedActions(), indexed.actions().size(), fixture.label());
            assertEquals(
                    fixture.expectedFallbacks(),
                    indexed.work().fallbackClassifications(),
                    fixture.label());
            assertEquals(
                    fixture.expectedTransitions(),
                    indexed.work().authorityTransitions(),
                    fixture.label());
            assertEquals(
                    0,
                    indexed.work().fallbackFailClosedClassifications(),
                    fixture.label());
            assertEquals(1, indexed.work().targetEnvelopeIndexBuilds(), fixture.label());
            assertEquals(spans.stream().distinct().count(),
                    indexed.work().targetEnvelopeIndexEntries(), fixture.label());
            assertEquals(indexed.actions().size(),
                    indexed.work().targetEnvelopeContainmentQueries(), fixture.label());
            assertTrue(
                    indexed.work().targetEnvelopeVisits() <= spans.size() * 3L,
                    fixture.label());
            assertTrue(
                    indexed.work().nominalPrefixCharacters()
                            <= indexed.work().actionInputCharacters()
                                    + 9L * indexed.work().nominalPrefixChecks(),
                    fixture.label());
        }
    }

    private static Fixture fixture(int targetCount, String newline) {
        Set<String> targets = new LinkedHashSet<>();
        StringBuilder request = new StringBuilder("Edit index.html so ");
        for (int i = 0; i < targetCount; i++) {
            String target = "target-%03d.css".formatted(i);
            targets.add(target);
            if (i > 0) request.append("then ");
            request.append("update ").append(target);
            if (i + 1 < targetCount) {
                request.append(',').append(newline).append(' ');
            }
        }
        request.append('.');
        return new Fixture(
                request.toString(),
                Set.copyOf(targets),
                targetCount + " targets with "
                        + ("\r\n".equals(newline) ? "CRLF" : "LF"));
    }

    private record Fixture(
            String request,
            Set<String> targets,
            String label
    ) {}

    private static AdversarialFixture adversarialFixture(int targetCount) {
        Set<String> targets = new LinkedHashSet<>();
        StringBuilder request = new StringBuilder("Edit index.html so ");
        for (int index = 0; index < targetCount; index++) {
            String target = "adversarial-%03d.css".formatted(index);
            targets.add(target);
            if (index > 0) request.append(", and then ");
            request.append("the update notes, then update ").append(target);
        }
        request.append('.');
        return new AdversarialFixture(request.toString(), Set.copyOf(targets));
    }

    private record AdversarialFixture(String request, Set<String> targets) {}

    private static void assertCappedFallbackAccounting(
            CanonicalMutationClauseMatcher.IndexedActions result,
            int inputCharacters
    ) {
        long budget = (long) inputCharacters
                * CanonicalMutationClauseMatcher.MAX_FALLBACK_INPUT_MULTIPLIER;
        long expectedCharacters = 0;
        long expectedClassifications = 0;
        long expectedFailClosed = 0;
        for (CanonicalMutationClauseMatcher.Action action : result.actions()) {
            long characters = action.start();
            if (characters
                            <= CanonicalMutationClauseMatcher
                                    .MAX_FALLBACK_CLASSIFICATION_CHARACTERS
                    && expectedCharacters + characters <= budget) {
                expectedCharacters += characters;
                expectedClassifications++;
            } else {
                expectedFailClosed++;
            }
        }
        assertEquals(expectedCharacters, result.work().fallbackCharacters());
        assertEquals(
                expectedClassifications,
                result.work().fallbackClassifications());
        assertEquals(
                expectedFailClosed,
                result.work().fallbackFailClosedClassifications());
        assertTrue(expectedFailClosed > 0);
        assertTrue(result.work().fallbackCharacters() <= budget);
        assertEquals(
                result.actions().size(),
                result.work().fallbackClassifications()
                        + result.work().authorityTransitions()
                        + result.work().fallbackFailClosedClassifications());
    }

    private static String repeatedUnboundActions(int actionCount) {
        StringBuilder request = new StringBuilder("update");
        for (int index = 1; index < actionCount; index++) {
            request.append(", then update");
        }
        return request.toString();
    }

    private static TargetAmbiguousFixture repeatedTargetAmbiguousActions(
            int actionCount
    ) {
        StringBuilder request = new StringBuilder();
        List<RequestLexicalView.Span> protectedSpans =
                new java.util.ArrayList<>();
        for (int index = 0; index < actionCount; index++) {
            if (index > 0) request.append("; ");
            request.append("x ");
            int actionStart = request.length();
            request.append("update");
            int actionEnd = request.length();
            request.append(" y");
            protectedSpans.add(new RequestLexicalView.Span(
                    actionStart - 1,
                    actionEnd + 1));
        }
        return new TargetAmbiguousFixture(
                request.toString(),
                List.copyOf(protectedSpans));
    }

    private record TargetAmbiguousFixture(
            String request,
            List<RequestLexicalView.Span> protectedSpans
    ) {}

    private static List<ParityFixture> parityFixtures() {
        return List.of(
                parity("direct-and", "update a.css and update b.css", 2, 1, 1, "a.css", "b.css"),
                parity("direct-also", "update a.css also update b.css", 2, 1, 1, "a.css", "b.css"),
                parity("direct-comma", "update a.css, update b.css", 2, 1, 1, "a.css", "b.css"),
                parity("direct-semicolon", "update a.css; update b.css", 2, 1, 1, "a.css", "b.css"),
                parity("direct-colon", "update a.css: update b.css", 2, 1, 1, "a.css", "b.css"),
                parity("direct-en-dash", "update a.css – update b.css", 2, 1, 1, "a.css", "b.css"),
                parity("direct-em-dash", "update a.css — update b.css", 2, 1, 1, "a.css", "b.css"),
                parity("direct-lf-bullet", "update a.css\n- update b.css", 2, 1, 1, "a.css", "b.css"),
                parity("direct-crlf-bullet", "update a.css\r\n- update b.css", 2, 1, 1, "a.css", "b.css"),
                parity("direct-and-then", "update a.css and then update b.css", 2, 1, 1, "a.css", "b.css"),
                parity("optional-carry", "maybe update a.css and update b.css", 2, 1, 1, "a.css", "b.css"),
                parity("optional-then-reset", "maybe update a.css, then update b.css", 2, 1, 1, "a.css", "b.css"),
                parity("optional-but-no-reset", "maybe update a.css, but update b.css", 2, 1, 1, "a.css", "b.css"),
                parity("reported-but-reset", "the report says update a.css, but update b.css", 2, 2, 0, "a.css", "b.css"),
                parity("reported-then-no-reset", "the report says update a.css, then update b.css", 2, 2, 0, "a.css", "b.css"),
                parity("prohibition", "do not update a.css, then update b.css", 2, 2, 0, "a.css", "b.css"),
                parity("quote-control", "the literal \"update a.css\" is quoted, then update b.css", 1, 1, 0, "a.css", "b.css"),
                parity("sentence-boundary", "update a.css. Update b.css", 2, 2, 0, "a.css", "b.css"),
                parity("mixed-family", "update a.css and rename b.css", 2, 1, 1, "a.css", "b.css"),
                parity("overlapping-targets", "update dir/a.css and update b.css", 2, 2, 0, "dir/a.css", "a.css", "b.css"),
                parity("unsupported-or", "update a.css or update b.css", 2, 2, 0, "a.css", "b.css"),
                parity("nominal-filter", "the update notes, then update a.css", 1, 1, 0, "a.css"));
    }

    private static ParityFixture parity(
            String label,
            String tail,
            int expectedActions,
            long expectedFallbacks,
            long expectedTransitions,
            String... targets
    ) {
        String prefix = "Edit index.html so ";
        return new ParityFixture(
                label,
                prefix + tail,
                prefix.length(),
                List.of(targets),
                expectedActions,
                expectedFallbacks,
                expectedTransitions);
    }

    private static List<RequestLexicalView.Span> targetSpans(
            RequestLexicalView view,
            List<String> targets
    ) {
        List<RequestLexicalView.Span> spans = new java.util.ArrayList<>();
        for (String target : targets) {
            int cursor = 0;
            while (cursor < view.request().length()) {
                int start = view.request().indexOf(target, cursor);
                if (start < 0) break;
                int end = start + target.length();
                RequestLexicalView.Span quoted =
                        view.exactQuotedEnvelope(start, end);
                spans.add(quoted == null
                        ? new RequestLexicalView.Span(start, end)
                        : quoted);
                cursor = end;
            }
        }
        return spans.stream()
                .distinct()
                .sorted(java.util.Comparator
                        .comparingInt(RequestLexicalView.Span::start)
                        .thenComparingInt(RequestLexicalView.Span::end))
                .toList();
    }

    private record ParityFixture(
            String label,
            String request,
            int start,
            List<String> targets,
            int expectedActions,
            long expectedFallbacks,
            long expectedTransitions
    ) {}

    private static ScopeFixture scopeFixture(
            String label,
            String prefix,
            String connector,
            boolean firstNonImperative,
            boolean remainingNonImperative,
            String newline
    ) {
        int count = 64;
        StringBuilder request = new StringBuilder("Edit index.html so ")
                .append(prefix);
        List<RequestLexicalView.Span> targetSpans = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                request.append(',')
                        .append(newline)
                        .append(' ')
                        .append(connector)
                        .append(' ');
            }
            request.append("update ");
            int targetStart = request.length();
            request.append("scope-%03d.css".formatted(index));
            targetSpans.add(new RequestLexicalView.Span(
                    targetStart,
                    request.length()));
        }
        request.append('.');
        return new ScopeFixture(
                label + " with "
                        + ("\r\n".equals(newline) ? "CRLF" : "LF"),
                request.toString(),
                "Edit index.html so ".length(),
                List.copyOf(targetSpans),
                firstNonImperative,
                remainingNonImperative);
    }

    private record ScopeFixture(
            String label,
            String request,
            int start,
            List<RequestLexicalView.Span> targetSpans,
            boolean firstNonImperative,
            boolean remainingNonImperative
    ) {}
}
