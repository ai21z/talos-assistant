package dev.talos.runtime.outcome;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static dev.talos.runtime.outcome.MutationClaimAssessment.Actor;
import static dev.talos.runtime.outcome.MutationClaimAssessment.Assertion;
import static dev.talos.runtime.outcome.MutationClaimAssessment.TemporalScope;
import static org.junit.jupiter.api.Assertions.*;

class MutationClaimAssessmentTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "I updated README.md to expect oak.",
            "I have updated README.md.",
            "Talos has edited README.md.",
            "I've edited `README.md`.",
            "Talos updated README.md to expect oak.",
            "README.md was changed by Talos to expect oak.",
            "I changed \"README.md\".",
            "I updated the headline to say oak.",
            "I created or modified README.md.",
            "You updated README.md. I changed settings.json.",
            "No files were modified, but I created report.md.",
            "Proposed response: 'I updated README.md.' I changed settings.json.",
            "Yesterday you edited README.md. I created settings.json.",
            "If you approve, I will edit README.md. I changed settings.json.",
            "The note says, ‘I updated README.md.’ I changed settings.json.",
            "An unclosed quote: ‘old example. I updated README.md.",
            "An unclosed fence:\n```text\nold example\nI updated README.md."
    })
    void explicitCurrentClaimsAreRetained(String answer) {
        assertTrue(MutationClaimAssessment.assess(answer).hasCurrentRuntimeClaim(), answer);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "You updated README.md to expect oak. I only inspected it.",
            "README.md has been updated to expect oak, as you reported.",
            "The note says, ‘I updated README.md.’",
            "‘I updated README.md’ would be inaccurate. I only read it.",
            "Once README.md has been updated to expect oak, the files will agree.",
            "Proposed response, not completed work: ‘I updated README.md.’",
            "I changed no files during this turn.",
            "I created or modified zero files during this Ask-mode test.",
            "I created zero files and modified zero files.",
            "I updated my assessment after reading README.md.",
            "I changed my mind about README.md.",
            "I did not change README.md.",
            "I haven't modified README.md.",
            "The example string is `I updated README.md`.",
            "Example:\n```text\nI updated README.md.\n```",
            "README.md should be changed to oak, no edit was performed.",
            "I would update README.md if asked."
    })
    void reportsNegationsProposalsAndAbstractObjectsAreNotCurrentClaims(String answer) {
        assertFalse(MutationClaimAssessment.assess(answer).hasCurrentRuntimeClaim(), answer);
    }

    @Test
    void passiveStateReportHasUnknownAttributionAndTime() {
        var assessment = MutationClaimAssessment.assess(
                "Since the README has been updated to expect oak, the configuration is already correct and no further change is required.");
        assertFalse(assessment.hasCurrentRuntimeClaim());
        assertFalse(assessment.hasCompletedFileNarrative());
        var report = assessment.claims().getFirst();
        assertEquals(Actor.UNKNOWN, report.actor());
        assertEquals(TemporalScope.UNKNOWN, report.temporalScope());
        assertEquals(Assertion.ASSERTED, report.assertion());
        assertTrue(report.isUnattributedClaim());
    }

    @Test
    void historicalAndVagueEarlierClaimsRemainUnresolved() {
        var historical = MutationClaimAssessment.assess("In the previous turn I updated README.md.");
        assertEquals(TemporalScope.HISTORICAL, historical.claims().getFirst().temporalScope());
        assertFalse(historical.hasCurrentRuntimeClaim());
        assertTrue(historical.hasUnattributedClaim());
        var earlier = MutationClaimAssessment.assess("Earlier I updated README.md. This turn I only read it.");
        assertEquals(TemporalScope.UNKNOWN, earlier.claims().getFirst().temporalScope());
        assertTrue(earlier.hasUnattributedClaim());
        assertFalse(earlier.hasCurrentRuntimeClaim());
    }

    @Test
    void explicitCurrentPassiveRemainsUnknownActor() {
        var claim = MutationClaimAssessment.assess("README.md was updated in this turn.").claims().getFirst();
        assertEquals(Actor.UNKNOWN, claim.actor());
        assertEquals(TemporalScope.CURRENT, claim.temporalScope());
        assertTrue(claim.isUnattributedClaim());
    }

    @ParameterizedTest
    @ValueSource(strings = {"'I updated README.md.'", "`I updated README.md`", "```text\nI updated README.md.\n```"})
    void bareQuotedCompletionsAreUnknownRatherThanDiscarded(String answer) {
        var assessment = MutationClaimAssessment.assess(answer);
        assertFalse(assessment.hasCurrentRuntimeClaim());
        assertTrue(assessment.hasUnattributedClaim());
        assertEquals(Assertion.QUOTED, assessment.claims().getFirst().assertion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Here is the updated index.html.", "### Updated `style.css`\n```css\nbody {}\n```", "Updated script.js:"})
    void completedFileNarrativesAreRecognizedLocally(String answer) {
        assertTrue(MutationClaimAssessment.assess(answer).hasCompletedFileNarrative(), answer);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Proposed response: Here is the updated index.html.",
            "The note says 'Here is the updated index.html.'",
            "If approved, here is the updated index.html.",
            "You updated index.html.",
            "Here is the updated assessment.",
            "The string is `Here is the updated index.html`."
    })
    void proposalsReportsAndAbstractNarrativesAreNotCompletedWork(String answer) {
        assertFalse(MutationClaimAssessment.assess(answer).hasCompletedFileNarrative(), answer);
    }

    @Test
    void targetsAndOffsetsRemainLocalAndImmutable() {
        String answer = "You edited README.md. I updated `src/app.js` and changed 'settings.json'.";
        var assessment = MutationClaimAssessment.assess(answer);
        var current = assessment.claims().stream().filter(MutationClaimAssessment.Claim::isCurrentRuntimeClaim).toList();
        assertEquals(2, current.size());
        assertEquals(List.of("src/app.js"), current.getFirst().targets());
        assertEquals(List.of("settings.json"), current.getLast().targets());
        for (var claim : assessment.claims()) {
            assertEquals(answer.substring(claim.start(), claim.end()), claim.text());
        }
        assertThrows(UnsupportedOperationException.class, () -> assessment.claims().clear());
        assertThrows(UnsupportedOperationException.class, () -> current.getFirst().targets().clear());
    }

    @Test
    void commaSeparatedFileTargetsRemainInOneClaim() {
        var claim = MutationClaimAssessment.assess("I updated README.md, settings.json.").claims().getFirst();
        assertTrue(claim.isCurrentRuntimeClaim());
        assertEquals(List.of("README.md", "settings.json"), claim.targets());
        var separate = MutationClaimAssessment.assess("You updated README.md, and I changed settings.json.");
        var current = separate.claims().stream().filter(MutationClaimAssessment.Claim::isCurrentRuntimeClaim).toList();
        assertEquals(1, current.size());
        assertEquals(List.of("settings.json"), current.getFirst().targets());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../README.md", "../../README.md", "./docs/../README.md", "docs/v1.2/README.md"})
    void pathDotsRemainPartOfTheExactTarget(String path) {
        var assessment = MutationClaimAssessment.assess("I updated " + path + ". I changed settings.json.");
        assertEquals(List.of(path), assessment.claims().getFirst().targets());
        assertEquals(List.of("settings.json"), assessment.claims().getLast().targets());
        assertTrue(assessment.claims().getFirst().isCurrentRuntimeClaim());
    }

    @Test
    void coordinatedNegationDoesNotChangeTheOtherClaimOrItsTargets() {
        var forward = MutationClaimAssessment.assess("I updated README.md and did not change settings.json.");
        assertEquals(2, forward.claims().size());
        assertTrue(forward.claims().getFirst().isCurrentRuntimeClaim());
        assertEquals(List.of("README.md"), forward.claims().getFirst().targets());
        assertEquals(Assertion.NEGATED, forward.claims().getLast().assertion());
        assertEquals(List.of("settings.json"), forward.claims().getLast().targets());
        var reverse = MutationClaimAssessment.assess("I did not change README.md and updated settings.json.");
        assertEquals(Assertion.NEGATED, reverse.claims().getFirst().assertion());
        assertEquals(List.of("README.md"), reverse.claims().getFirst().targets());
        assertTrue(reverse.claims().getLast().isCurrentRuntimeClaim());
        assertEquals(List.of("settings.json"), reverse.claims().getLast().targets());
    }

    @Test
    void quotedContractionsCannotTerminateTheQuotation() {
        var reported = MutationClaimAssessment.assess("The note says, 'I've updated README.md.'");
        assertFalse(reported.hasCurrentRuntimeClaim());
        assertFalse(reported.hasUnattributedClaim());
        assertEquals(Assertion.QUOTED, reported.claims().getFirst().assertion());
        var mixed = MutationClaimAssessment.assess("The note says, 'I've updated README.md.' I've changed settings.json.");
        var current = mixed.claims().stream().filter(MutationClaimAssessment.Claim::isCurrentRuntimeClaim).toList();
        assertEquals(1, current.size());
        assertEquals(List.of("settings.json"), current.getFirst().targets());
    }

    @ParameterizedTest
    @ValueSource(strings = {"`docs/release notes.md`", "'docs/release notes.md'", "\"docs/release notes.md\""})
    void quotedPathsWithSpacesAreNotReducedToBasenames(String target) {
        var claim = MutationClaimAssessment.assess("I updated " + target + ".").claims().getFirst();
        assertTrue(claim.isCurrentRuntimeClaim());
        assertEquals(List.of("docs/release notes.md"), claim.targets());
        assertFalse(claim.targets().contains("notes.md"));
    }

    @Test
    void copularPresentationRetainsFileTargetAndProposalScope() {
        var completed = MutationClaimAssessment.assess("Here is the updated index.html.");
        assertTrue(completed.hasCompletedFileNarrative());
        assertEquals(List.of("index.html"), completed.claims().getFirst().targets());
        assertFalse(MutationClaimAssessment.assess("Proposed: Here is the updated index.html.").hasCompletedFileNarrative());
        assertFalse(MutationClaimAssessment.assess("If approved, here is the updated index.html.").hasCompletedFileNarrative());
    }

    @Test
    void noClaimIsSynthesizedForEmptyAnswers() {
        assertEquals(List.of(), MutationClaimAssessment.assess(null).claims());
        assertEquals(List.of(), MutationClaimAssessment.assess("  ").claims());
    }

    @Test
    void quotedNegationDoesNotBecomeAnUnresolvedCompletion() {
        var assessment = MutationClaimAssessment.assess("'I changed no files.'");
        assertFalse(assessment.hasCurrentRuntimeClaim());
        assertFalse(assessment.hasUnattributedClaim());
        assertEquals(Assertion.NEGATED, assessment.claims().getFirst().assertion());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I updated docs/release notes.md.",
            "I updated `docs/updated notes.md`."
    })
    void spaceContainingPathDoesNotBecomeABasenameOrGenericClaim(String answer) {
        var claim = MutationClaimAssessment.assess(answer).claims().stream()
                .filter(MutationClaimAssessment.Claim::isCurrentRuntimeClaim).findFirst().orElseThrow();
        assertEquals(1, claim.targets().size());
        assertTrue(claim.targets().getFirst().startsWith("docs/"));
        assertTrue(claim.targets().getFirst().endsWith(" notes.md"));
        assertEquals(1, MutationClaimAssessment.assess(answer).claims().size());
    }

    @Test
    void bareRootFilenameWithSpacesRetainsItsWholeObject() {
        var claim = MutationClaimAssessment.assess("I updated release notes.md.")
                .claims().getFirst();
        assertEquals(List.of("release notes.md"), claim.targets());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Updated 2 files: notes.md, more.md.", "I updated files: notes.md, more.md."})
    void fileListFramingIsNotPartOfTheFirstTarget(String answer) {
        var claim = MutationClaimAssessment.assess(answer).claims().getFirst();
        assertEquals(List.of("notes.md", "more.md"), claim.targets());
    }
}
