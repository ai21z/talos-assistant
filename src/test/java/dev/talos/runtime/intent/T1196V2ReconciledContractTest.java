package dev.talos.runtime.intent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class T1196V2ReconciledContractTest {

    private static final Set<String> TARGETS =
            Set.of("index.html", "style.css", "script.js");

    @Test
    void connectorTypeNeverSuppliesPositiveAuthorityByItself() {
        for (ConnectorCase testCase : List.of(
                new ConnectorCase(
                        "Edit index.html so maybe, then update style.css.",
                        CoordinatedMutationTailPlan.Authority.REQUIRED,
                        "then update style.css."),
                new ConnectorCase(
                        "Edit index.html so the report says, but update style.css.",
                        CoordinatedMutationTailPlan.Authority.REQUIRED,
                        "but update style.css."),
                new ConnectorCase(
                        "Edit index.html so maybe, but update style.css.",
                        CoordinatedMutationTailPlan.Authority.OPTIONAL,
                        "maybe, but update style.css."),
                new ConnectorCase(
                        "Edit index.html so the report says, then update style.css.",
                        CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                        "the report says, then update style.css."),
                new ConnectorCase(
                        "Edit index.html so maybe, and then update style.css.",
                        CoordinatedMutationTailPlan.Authority.OPTIONAL,
                        "maybe, and then update style.css."),
                new ConnectorCase(
                        "Edit index.html so the report says, and then update style.css.",
                        CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                        "the report says, and then update style.css."))) {
            CoordinatedMutationTailPlan.TargetDecision decision =
                    decision(testCase.request(), "style.css");

            assertEquals(testCase.authority(), decision.authority(), testCase.request());
            assertExactSpan(
                    testCase.request(),
                    testCase.sourceText(),
                    testCase.request().indexOf(testCase.sourceText()),
                    testCase.request().indexOf("style.css", 1)
                            + "style.css".length(),
                    testCase.request().indexOf(testCase.sourceText())
                            + testCase.sourceText().length(),
                    decision);
        }
    }

    @Test
    void plainConnectorsWithoutTheirRequiredDelimiterCannotResetScope() {
        for (ConnectorCase testCase : List.of(
                new ConnectorCase(
                        "Edit index.html so maybe then update style.css.",
                        CoordinatedMutationTailPlan.Authority.OPTIONAL,
                        "maybe then update style.css."),
                new ConnectorCase(
                        "Edit index.html so maybe but update style.css.",
                        CoordinatedMutationTailPlan.Authority.OPTIONAL,
                        "maybe but update style.css."),
                new ConnectorCase(
                        "Edit index.html so the report says then update style.css.",
                        CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                        "the report says then update style.css."),
                new ConnectorCase(
                        "Edit index.html so the report says but update style.css.",
                        CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                        "the report says but update style.css."))) {
            assertDecision(testCase);
        }
    }

    @Test
    void everyTypedConnectorControlPreservesLfAndCrlfCoordinates() {
        for (String lineEnding : List.of("\n", "\r\n")) {
            for (ConnectorCase testCase : List.of(
                    new ConnectorCase(
                            "Edit index.html so maybe," + lineEnding
                                    + "then update style.css.",
                            CoordinatedMutationTailPlan.Authority.REQUIRED,
                            "then update style.css."),
                    new ConnectorCase(
                            "Edit index.html so the report says," + lineEnding
                                    + "but update style.css.",
                            CoordinatedMutationTailPlan.Authority.REQUIRED,
                            "but update style.css."),
                    new ConnectorCase(
                            "Edit index.html so maybe," + lineEnding
                                    + "but update style.css.",
                            CoordinatedMutationTailPlan.Authority.OPTIONAL,
                            "maybe," + lineEnding + "but update style.css."),
                    new ConnectorCase(
                            "Edit index.html so the report says," + lineEnding
                                    + "then update style.css.",
                            CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                            "the report says," + lineEnding
                                    + "then update style.css."))) {
                assertDecision(testCase);
            }
        }
    }

    @Test
    void loneCarriageReturnCannotActAsAnAuthorityConnector() {
        for (String source : List.of(
                "maybe\rthen update style.css.",
                "the report says\rbut update style.css.")) {
            CoordinatedMutationTailPlan.TargetDecision decision =
                    decision("Edit index.html so " + source, "style.css");

            assertTrue(
                    decision.authority()
                                    != CoordinatedMutationTailPlan.Authority
                                            .REQUIRED,
                    source);
        }
    }

    @Test
    void loneCarriageReturnInsidePositiveBindingFailsClosedWithExactSpan() {
        for (String source : List.of(
                "update\rstyle.css.",
                "maybe update\rstyle.css.",
                "update\r\r\nstyle.css.",
                "update\r\n\rstyle.css.")) {
            String request = "Edit index.html so " + source;
            CoordinatedMutationTailPlan.TargetDecision decision =
                    decision(request, "style.css");
            int expectedStart = request.indexOf(source);
            int targetStart = request.indexOf("style.css", expectedStart);

            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    decision.authority(),
                    request);
            assertExactSpan(
                    request,
                    source,
                    expectedStart,
                    targetStart + "style.css".length(),
                    expectedStart + source.length(),
                    decision);
        }
    }

    @Test
    void providerRemovedControlsCannotCreateBroaderRawAuthority() {
        for (String source : List.of(
                "ma\u0000ybe, update style.css.",
                "ma\u001b[31mybe, update style.css.")) {
            String request = "Edit index.html so " + source;
            CoordinatedMutationTailPlan.TargetDecision decision =
                    decision(request, "style.css");
            int expectedStart = request.indexOf(source);
            int targetStart = request.indexOf("style.css", expectedStart);

            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    decision.authority(),
                    request);
            assertExactSpan(
                    request,
                    source,
                    expectedStart,
                    targetStart + "style.css".length(),
                    expectedStart + source.length(),
                    decision);
        }
    }

    @Test
    void providerRemovedControlInsideProhibitionRemainsFailClosed() {
        String source = "do\rnot update style.css.";
        String request = "Edit index.html so " + source;
        CoordinatedMutationTailPlan.TargetDecision decision =
                decision(request, "style.css");
        int expectedStart = request.indexOf(source);
        int targetStart = request.indexOf("style.css", expectedStart);

        assertEquals(
                CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                decision.authority(),
                request);
        assertExactSpan(
                request,
                source,
                expectedStart,
                targetStart + "style.css".length(),
                expectedStart + source.length(),
                decision);
    }

    @Test
    void onlyTheNamedFiniteReportedComplementCanResetPlainThen() {
        assertDecision(new ConnectorCase(
                "Edit index.html so the report says no, then update style.css.",
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                "then update style.css."));

        for (String literal : List.of("green", "0", "nonsense")) {
            String source = "the report says " + literal
                    + ", then update style.css.";
            assertDecision(new ConnectorCase(
                    "Edit index.html so " + source,
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    source));
        }
    }

    @Test
    void closedQuotedPayloadCanResetReportedScope() {
        assertDecision(new ConnectorCase(
                "Edit index.html so the report says \"no\", then update style.css.",
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                "then update style.css."));
    }

    @Test
    void openOrArbitraryReportedPayloadCannotReset() {
        String arbitrarySource =
                "the report says update script.js, then update style.css.";
        assertDecision(new ConnectorCase(
                "Edit index.html so " + arbitrarySource,
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                arbitrarySource));

        String unclosed =
                "Edit index.html so the report says \"no, then update style.css.";
        assertFalse(
                CoordinatedMutationTailParser.parse(unclosed, TARGETS)
                        .decision("style.css")
                        .isPresent(),
                unclosed);
    }

    @Test
    void aCompletedLeftScopeStillRequiresAnIndependentPositiveRightClause() {
        for (ConnectorCase testCase : List.of(
                new ConnectorCase(
                        "Edit index.html so maybe, then maybe update style.css.",
                        CoordinatedMutationTailPlan.Authority.OPTIONAL,
                        "maybe, then maybe update style.css."),
                new ConnectorCase(
                        "Edit index.html so the report says, but maybe update style.css.",
                        CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                        "the report says, but maybe update style.css."),
                new ConnectorCase(
                        "Edit index.html so the report says no, then maybe update style.css.",
                        CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                        "the report says no, then maybe update style.css."),
                new ConnectorCase(
                        "Edit index.html so maybe, then the report says update style.css.",
                        CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                        "maybe, then the report says update style.css."))) {
            assertDecision(testCase);
        }
    }

    @Test
    void lineEndingMaterializationPreservesScopeAndOriginalCoordinates() {
        for (String lineEnding : List.of("\n", "\r\n")) {
            String request = "Edit index.html so maybe update style.css,"
                    + lineEnding
                    + "and then update script.js.";
            CoordinatedMutationTailPlan.TargetDecision decision =
                    decision(request, "script.js");
            String expectedSource = "maybe update style.css,"
                    + lineEnding
                    + "and then update script.js.";
            int expectedStart = request.indexOf("maybe");
            int expectedAuthorityEnd = request.indexOf("script.js")
                    + "script.js".length();

            assertEquals(
                    CoordinatedMutationTailPlan.Authority.OPTIONAL,
                    decision.authority(),
                    request);
            assertExactSpan(
                    request,
                    expectedSource,
                    expectedStart,
                    expectedAuthorityEnd,
                    expectedStart + expectedSource.length(),
                    decision);
        }
    }

    @Test
    void perfectPassiveSemanticsAreFiniteAndDoNotInferCurrentAuthority() {
        for (PassiveCase testCase : List.of(
                new PassiveCase(
                        "has been requested",
                        CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE),
                new PassiveCase(
                        "has never been requested",
                        CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE),
                new PassiveCase(
                        "has not been requested",
                        CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE),
                new PassiveCase(
                        "has been authorized",
                        CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE),
                new PassiveCase(
                        "has been forbidden",
                        CoordinatedMutationTailPlan.Authority.FORBIDDEN),
                new PassiveCase(
                        "has never been allowed",
                        CoordinatedMutationTailPlan.Authority.FORBIDDEN),
                new PassiveCase(
                        "has not been permitted",
                        CoordinatedMutationTailPlan.Authority.FORBIDDEN))) {
            String request = "Edit index.html so update style.css "
                    + testCase.suffix()
                    + ".";
            CoordinatedMutationTailPlan.TargetDecision decision =
                    decision(request, "style.css");

            assertEquals(testCase.authority(), decision.authority(), request);
            assertExactSpan(
                    request,
                    "update style.css " + testCase.suffix() + ".",
                    request.indexOf("update style.css"),
                    request.indexOf("style.css") + "style.css".length(),
                    request.length(),
                    decision);
        }

        for (String verb : List.of(
                "proposed",
                "suggested",
                "recommended",
                "requested",
                "reported",
                "mentioned",
                "documented",
                "described",
                "rejected")) {
            assertPassiveAuthority(
                    "has been " + verb,
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE);
        }
        for (String verb : List.of(
                "proposed",
                "suggested",
                "recommended",
                "requested",
                "reported",
                "mentioned",
                "documented",
                "described",
                "forbidden",
                "prohibited",
                "disallowed",
                "rejected")) {
            assertPassiveAuthority(
                    "had never been " + verb,
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE);
        }
    }

    @Test
    void repeatedTargetClausesRetainTheSelectedOccurrenceCoordinates() {
        String request = "Edit index.html so the report says update style.css, "
                + "but update style.css.";
        CoordinatedMutationTailPlan.TargetDecision decision =
                decision(request, "style.css");
        String selected = "but update style.css.";
        int selectedStart = request.lastIndexOf(selected);

        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                decision.authority());
        assertExactSpan(
                request,
                selected,
                selectedStart,
                request.lastIndexOf("style.css") + "style.css".length(),
                selectedStart + selected.length(),
                decision);
    }

    @Test
    void boundedCollaboratorsWorkInBothOrdersWithExactEvidence() {
        for (String source : List.of(
                "Alice and you need to update style.css.",
                "you and Alice need to update style.css.",
                "the build bot and Talos need to update style.css.",
                "Talos and the build bot need to update style.css.",
                "Team 7 and we need to update style.css.",
                "we and Team 7 need to update style.css.")) {
            assertDecision(new ConnectorCase(
                    "Edit index.html so " + source,
                    CoordinatedMutationTailPlan.Authority.REQUIRED,
                    source));
        }
    }

    @Test
    void unboundedCollaboratorsFailClosedInBothOrders() {
        for (String source : List.of(
                "Alice Smith and you need to update style.css.",
                "you and Alice Smith need to update style.css.",
                "7 and you need to update style.css.",
                "you and 7 need to update style.css.",
                "Alice & you need to update style.css.",
                "you & Alice need to update style.css.",
                "Alice + you need to update style.css.",
                "you + Alice need to update style.css.",
                "Alice / you need to update style.css.",
                "you / Alice need to update style.css.",
                "Alice or you need to update style.css.",
                "you or Alice need to update style.css.",
                "Alice nor you need to update style.css.",
                "you nor Alice need to update style.css.",
                "Alice as well as you need to update style.css.",
                "you as well as Alice need to update style.css.",
                "Alice together with you need to update style.css.",
                "you together with Alice need to update style.css.")) {
            assertDecision(new ConnectorCase(
                    "Edit index.html so " + source,
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    source));
        }
    }

    @Test
    void laterSentencesAndTopLevelSequencesRemainOutsideT1196() {
        for (String request : List.of(
                "Edit index.html so it works. Then update style.css.",
                "Edit index.html so it works.\r\nThen update style.css.",
                "Edit index.html so it works\nUpdate style.css.",
                "Edit index.html, then update style.css.")) {
            assertFalse(
                    CoordinatedMutationTailParser.parse(request, TARGETS)
                            .decision("style.css")
                            .isPresent(),
                    request);
        }
    }

    @Test
    void reportedOutputOwnsItsNestedProhibition() {
        String prefix = "verifier output: do not update ";

        assertTrue(
                CanonicalMutationClauseMatcher
                        .reportedOwnerPrecedesProhibition(prefix));
        assertEquals(
                CanonicalMutationClauseMatcher.ScopeKind.REPORTED_ADVISORY,
                CanonicalMutationClauseMatcher.finiteScopeKind(prefix));
    }

    private static CoordinatedMutationTailPlan.TargetDecision decision(
            String request,
            String target
    ) {
        return CoordinatedMutationTailParser.parse(request, TARGETS)
                .decision(target)
                .orElseThrow(() -> new AssertionError(request));
    }

    private static void assertDecision(ConnectorCase testCase) {
        CoordinatedMutationTailPlan.TargetDecision decision =
                decision(testCase.request(), "style.css");
        int expectedStart = testCase.request().indexOf(testCase.sourceText());
        int targetStart = testCase.request().indexOf("style.css", expectedStart);

        assertTrue(expectedStart >= 0, testCase.request());
        assertTrue(targetStart >= expectedStart, testCase.request());
        assertEquals(testCase.authority(), decision.authority(), testCase.request());
        assertExactSpan(
                testCase.request(),
                testCase.sourceText(),
                expectedStart,
                targetStart + "style.css".length(),
                expectedStart + testCase.sourceText().length(),
                decision);
    }

    private static void assertPassiveAuthority(
            String suffix,
            CoordinatedMutationTailPlan.Authority authority
    ) {
        String request = "Edit index.html so update style.css " + suffix + ".";
        assertEquals(authority, decision(request, "style.css").authority(), request);
    }

    private static void assertExactSpan(
            String request,
            String expectedSource,
            int expectedStart,
            int expectedAuthorityEnd,
            int expectedEnd,
            CoordinatedMutationTailPlan.TargetDecision decision
    ) {
        assertEquals(expectedSource, decision.sourceText(), request);
        assertEquals(expectedStart, decision.startOffset(), request);
        assertEquals(expectedAuthorityEnd, decision.authorityEndOffset(), request);
        assertEquals(expectedEnd, decision.endOffset(), request);
        assertEquals(
                expectedSource,
                request.substring(expectedStart, expectedEnd),
                request);
    }

    private record ConnectorCase(
            String request,
            CoordinatedMutationTailPlan.Authority authority,
            String sourceText
    ) {}

    private record PassiveCase(
            String suffix,
            CoordinatedMutationTailPlan.Authority authority
    ) {}
}
