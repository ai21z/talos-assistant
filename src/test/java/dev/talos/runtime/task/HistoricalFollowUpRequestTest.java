package dev.talos.runtime.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HistoricalFollowUpRequestTest {
    @Test
    void requestBoundIsCheckedBeforeNormalization() {
        String body = "What changed?";
        assertTrue(HistoricalFollowUpRequest.isPureHistoryRequest(body));
        assertTrue(HistoricalFollowUpRequest.isPureHistoryRequest(body + " ".repeat(1024 - body.length())));
        assertFalse(HistoricalFollowUpRequest.isPureHistoryRequest(body + " ".repeat(1025 - body.length())));
        assertFalse(HistoricalFollowUpRequest.isPureHistoryRequest(null));
        assertFalse(HistoricalFollowUpRequest.isPureHistoryRequest(""));
        assertFalse(HistoricalFollowUpRequest.isSessionUncertaintyRequest(null));
        assertFalse(HistoricalFollowUpRequest.isStaticDiagnosticRequest(null));
        assertEquals(Set.of(), HistoricalFollowUpRequest.documentStatusFormats(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What changed? Is it verified?",
            "Is it verified? What changed?",
            "What changed? Read notes.md.",
            "What changed and explain exponential backoff?",
            "What changed yesterday?"
    })
    void additionalMeaningIsNotAFormattingQualifier(String request) {
        assertFalse(HistoricalFollowUpRequest.isPureHistoryRequest(request));
        assertFalse(HistoricalFollowUpRequest.isPriorStatusRequest(request));
    }

    @Test
    void metadataTargetsRemainLiteralAndBounded() {
        assertEquals(HistoricalFollowUpRequest.SessionAction.READ,
                HistoricalFollowUpRequest.sessionEvidenceAction("Did you read `notes.md` and README earlier?",
                        Set.of("notes.md", "README")));
        assertEquals(HistoricalFollowUpRequest.SessionAction.NONE,
                HistoricalFollowUpRequest.sessionEvidenceAction("Did you read notesXmd?", Set.of("notes.md")));
        assertFalse(HistoricalFollowUpRequest.isSessionEvidenceRequest("Did you read notes.md?", Set.of()));
        assertFalse(HistoricalFollowUpRequest.isSessionEvidenceRequest("Did you read notes.md?", null));
        assertFalse(HistoricalFollowUpRequest.isSessionEvidenceRequest(null, Set.of("notes.md")));
        assertFalse(HistoricalFollowUpRequest.isSessionEvidenceRequest("Did you read notes.md?", Set.of("a b")));
        assertFalse(HistoricalFollowUpRequest.isSessionEvidenceRequest("Did you read notes.md?", Set.of("a".repeat(257))));
        assertFalse(HistoricalFollowUpRequest.isSessionEvidenceRequest(" ".repeat(1025), Set.of("notes.md")));
        assertFalse(HistoricalFollowUpRequest.isSessionEvidenceRequest("Did you read notes.md?",
                java.util.stream.IntStream.range(0, 17).mapToObj(i -> i + ".md")
                        .collect(java.util.stream.Collectors.toSet())));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Did you read notes.md? Did you edit other.md?",
            "Did you read notes.md after edits?",
            "Did you read notes.md yesterday?",
            "Did you read notes.md? Include its contents.",
            "Did you read notes.md? Explain caching."
    })
    void metadataCannotHideUncoveredMeaning(String request) {
        assertFalse(HistoricalFollowUpRequest.isSessionEvidenceRequest(request, Set.of("notes.md", "other.md")));
    }

    @Test
    void documentFormatsAreExtractedFromTheSameNormalizedMatch() {
        assertEquals(Set.of("pdf", "docx"), HistoricalFollowUpRequest.documentStatusFormats(
                "Did you create any valid PDF or Word\tdocument in this audit?"));
        assertTrue(HistoricalFollowUpRequest.isDocumentStatusRequest("Have you created a valid Word file?"));
        assertEquals(Set.of("docx"), HistoricalFollowUpRequest.documentStatusFormats(
                "Have you created a valid Word file?"));
        assertEquals(Set.of(), HistoricalFollowUpRequest.documentStatusFormats(
                "Have you created a valid Word file? Read notes.md."));
    }

    @Test
    void verificationMayCombineOnlyTheResultsItsRendererSupplies() {
        assertTrue(HistoricalFollowUpRequest.isVerificationRequest(
                "Is it verified now? What, if anything, is still unverified?"));
        assertTrue(HistoricalFollowUpRequest.isVerificationRequest(
                "What remains unverified? Show verification status."));
        assertFalse(HistoricalFollowUpRequest.isVerificationRequest("Is it verified? What changed?"));
        assertFalse(HistoricalFollowUpRequest.isVerificationRequest("What changed? Is it verified?"));
        assertFalse(HistoricalFollowUpRequest.isVerificationRequest("Is it verified? Explain backoff."));
    }
}
