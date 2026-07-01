package dev.talos.runtime.verification;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbeddedStaticVerificationResultParserTest {
    @Test
    void returnsNotRunWhenAnswerHasNoEmbeddedStaticVerificationFailure() {
        TaskVerificationResult result = EmbeddedStaticVerificationResultParser.parse(
                "The task is blocked by policy.");

        assertEquals(TaskVerificationStatus.NOT_RUN, result.status());
        assertEquals("Post-apply verification was not applicable.", result.summary());
        assertEquals(List.of(), result.problems());
    }

    @Test
    void ignoresEmbeddedStaticVerificationPassMarker() {
        TaskVerificationResult result = EmbeddedStaticVerificationResultParser.parse(
                "[Static verification: passed - Static web coherence checks passed.]");

        assertEquals(TaskVerificationStatus.NOT_RUN, result.status());
        assertEquals("Post-apply verification was not applicable.", result.summary());
        assertEquals(List.of(), result.problems());
    }

    @Test
    void removesEmbeddedStaticVerificationPassMarkerFromAssistantText() {
        String sanitized = EmbeddedStaticVerificationResultParser.removePositivePassMarkers("""
                [Static verification: passed - Static web coherence checks passed.]

                Updated README.md.
                """);

        assertEquals("Updated README.md.\n", sanitized);
    }

    @Test
    void extractsSummaryAndProblemsFromRenderedStaticFailure() {
        TaskVerificationResult result = EmbeddedStaticVerificationResultParser.parse("""
                [Task incomplete: Static verification failed - HTML references missing JavaScript file: `script.js`]

                Unresolved static verification problems:
                - HTML references missing JavaScript file: `script.js`
                - Expected target `script.js` was not mutated.

                The requested task is not verified complete.
                """);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertEquals("HTML references missing JavaScript file: `script.js`", result.summary());
        assertEquals(List.of(
                "HTML references missing JavaScript file: `script.js`",
                "Expected target `script.js` was not mutated."),
                result.problems());
    }

    @Test
    void fallsBackToSummaryWhenRenderedFailureHasNoProblemBullets() {
        TaskVerificationResult result = EmbeddedStaticVerificationResultParser.parse("""
                [Task incomplete: Static verification failed - selector mismatch]

                The requested task is not verified complete.
                """);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertEquals("selector mismatch", result.summary());
        assertEquals(List.of("selector mismatch"), result.problems());
    }

    @Test
    void usesDefaultSummaryWhenRenderedFailureSummaryIsBlank() {
        TaskVerificationResult result = EmbeddedStaticVerificationResultParser.parse("""
                [Task incomplete: Static verification failed - ]

                The requested task is not verified complete.
                """);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertEquals("Static verification failed.", result.summary());
        assertEquals(List.of("Static verification failed."), result.problems());
    }

    @Test
    void usesLineEndWhenRenderedFailureClosingBracketIsMissing() {
        TaskVerificationResult result = EmbeddedStaticVerificationResultParser.parse("""
                [Task incomplete: Static verification failed - target mismatch

                Unresolved static verification problems:
                - target mismatch
                """);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertEquals("target mismatch", result.summary());
        assertEquals(List.of("target mismatch"), result.problems());
    }
}
