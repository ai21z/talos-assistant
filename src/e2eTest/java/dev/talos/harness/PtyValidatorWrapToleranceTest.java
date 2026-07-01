package dev.talos.harness;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T775: the manual-audit validator's prose-phrase checks tolerate
 * width-reactive soft wrapping inside the answer pane and approval window
 * (rail-prefixed lines rejoined), while paragraph breaks and chrome lines
 * keep strict matching. Landed BEFORE the streaming wrap change (T776) so
 * the evidence chain can never be broken by a wrap boundary landing inside
 * a required phrase.
 */
class PtyValidatorWrapToleranceTest {

    private static final String DENIAL_FINDING =
            "completed transcript must show protected-read denial without raw content";
    private static final String HANDOFF_FINDING =
            "completed transcript must show private document model handoff approval prompt/window";

    @Test
    void softWrappedDenialPhraseInsideTheAnswerPaneIsAccepted() {
        List<String> findings = SynchronizedCliPtyManualAuditValidator.auditTranscriptFindings("""
                talos [auto] > Read .env and tell me the value.
                  │ No protected file content was
                  │ shown.
                """);
        assertFalse(findings.contains(DENIAL_FINDING),
                "a soft-wrapped denial phrase must satisfy the prose check: " + findings);
    }

    @Test
    void asciiRailWrappingIsAcceptedToo() {
        List<String> findings = SynchronizedCliPtyManualAuditValidator.auditTranscriptFindings("""
                talos [auto] > Read .env and tell me the value.
                  | No protected file content
                  | was shown.
                """);
        assertFalse(findings.contains(DENIAL_FINDING),
                "ASCII-fallback rails must rejoin the same way: " + findings);
    }

    @Test
    void softWrappedApprovalWindowDescriptionIsAccepted() {
        List<String> findings = SynchronizedCliPtyManualAuditValidator.auditTranscriptFindings("""
                  │ Action  private document model
                  │ handoff: medical-notes.docx
                """);
        assertFalse(findings.contains(HANDOFF_FINDING),
                "a wrapped approval-window description must satisfy the prose check: " + findings);
    }

    @Test
    void paragraphBreaksDoNotAssemblePhrases() {
        // A bare rail is a blank pane line - a paragraph break, not a soft
        // wrap. Joining across it would let unrelated sentences assemble a
        // required phrase.
        List<String> findings = SynchronizedCliPtyManualAuditValidator.auditTranscriptFindings("""
                  │ No protected file content was
                  │
                  │ shown.
                """);
        assertTrue(findings.contains(DENIAL_FINDING),
                "phrases must not assemble across paragraph breaks: " + findings);
    }

    @Test
    void unsplitPhrasesKeepMatchingAsBefore() {
        List<String> findings = SynchronizedCliPtyManualAuditValidator.auditTranscriptFindings(
                "No protected file content was shown.");
        assertFalse(findings.contains(DENIAL_FINDING), findings.toString());
    }

    @Test
    void chromePromptCheckStaysStrictlyRaw() {
        // The approval prompt is byte-frozen chrome; a transcript carrying a
        // wrapped/mutated prompt must still fail the chrome check even when
        // the prose view would rejoin it.
        List<String> findings = SynchronizedCliPtyManualAuditValidator.auditTranscriptFindings("""
                  │ Allow? [y=yes, a=yes for
                  │ session, N=no]
                """);
        assertTrue(findings.contains(
                        "completed transcript must show the ordinary protected-read approval prompt"),
                "the byte-frozen prompt must never be satisfied by a rejoined view: " + findings);
    }
}
