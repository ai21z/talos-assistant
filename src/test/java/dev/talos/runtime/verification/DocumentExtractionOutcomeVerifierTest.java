package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentExtractionOutcomeVerifierTest {

    @Test
    void exactTextExtractionSuccessDoesNotVerifyFinalAnswerExactness() {
        TaskVerificationEvidence evidence = DocumentExtractionOutcomeVerifier.verifyWithEvidence(
                TaskContractResolver.fromUserRequest("Extract the exact text from report.pdf."),
                loopResult(readSuccess("report.pdf", "SUCCESS")));

        assertEquals(TaskVerificationStatus.READBACK_ONLY, evidence.compatibilityResult().status());
        assertEquals(TaskVerificationEvidenceSource.DOCUMENT_EXTRACTION_TOOL_RESULT, evidence.source());
        assertTrue(evidence.compatibilityResult().summary().contains("final-answer exactness was not verified"),
                evidence.compatibilityResult().summary());
        assertTrue(evidence.report().authoritativeProofKinds().contains(ProofKind.PARSER_EXTRACTION.name()),
                evidence.report().toString());
        assertTrue(evidence.report().limitations().stream()
                        .anyMatch(l -> l.contains("PDF text extraction may not match visual order")),
                evidence.report().limitations().toString());
    }

    @Test
    void documentSummaryExtractionDoesNotVerifySummarySemantics() {
        TaskVerificationEvidence evidence = DocumentExtractionOutcomeVerifier.verifyWithEvidence(
                TaskContractResolver.fromUserRequest("Summarize report.pdf."),
                loopResult(readSuccess("report.pdf", "SUCCESS")));

        assertEquals(TaskVerificationStatus.READBACK_ONLY, evidence.compatibilityResult().status());
        assertTrue(evidence.compatibilityResult().summary().contains("summary semantics were not verified"),
                evidence.compatibilityResult().summary());
        assertTrue(evidence.report().authoritativeProofKinds().contains(ProofKind.PARSER_EXTRACTION.name()),
                evidence.report().toString());
    }

    @Test
    void partialDocumentExtractionStaysPartialCompatibility() {
        TaskVerificationEvidence evidence = DocumentExtractionOutcomeVerifier.verifyWithEvidence(
                TaskContractResolver.fromUserRequest("Extract the exact text from large-report.docx."),
                loopResult(readSuccess("large-report.docx", "PARTIAL")));

        assertEquals(TaskVerificationStatus.READBACK_ONLY, evidence.compatibilityResult().status());
        assertTrue(evidence.compatibilityResult().summary().contains("partial"),
                evidence.compatibilityResult().summary());
        assertTrue(evidence.report().verifierResults().stream()
                        .anyMatch(result -> result.verdict() == VerificationVerdict.PARTIAL),
                evidence.report().toString());
    }

    @Test
    void unsupportedDocumentReadProducesUnsupportedVerifierResult() {
        TaskVerificationEvidence evidence = DocumentExtractionOutcomeVerifier.verifyWithEvidence(
                TaskContractResolver.fromUserRequest("Extract the exact text from slides.pptx."),
                loopResult(readUnsupported("slides.pptx")));

        assertEquals(TaskVerificationStatus.UNAVAILABLE, evidence.compatibilityResult().status());
        assertTrue(evidence.report().verifierResults().stream()
                        .anyMatch(result -> result.verdict() == VerificationVerdict.UNSUPPORTED),
                evidence.report().toString());
    }

    @Test
    void corruptDocumentExtractionDoesNotProjectToLegacyFailed() {
        TaskVerificationEvidence evidence = DocumentExtractionOutcomeVerifier.verifyWithEvidence(
                TaskContractResolver.fromUserRequest("Summarize report.docx."),
                loopResult(readUnsupportedWithStatus("report.docx", "CORRUPT")));

        assertEquals(TaskVerificationStatus.UNAVAILABLE, evidence.compatibilityResult().status());
        assertTrue(evidence.report().verifierResults().stream()
                        .anyMatch(result -> result.verdict() == VerificationVerdict.FAILED),
                evidence.report().toString());
    }

    private static ToolCallLoop.ToolOutcome readSuccess(String path, String status) {
        return new ToolCallLoop.ToolOutcome(
                "talos.read_file",
                path,
                true,
                false,
                false,
                "Extracted document text from " + path + " (status: " + status + ")",
                "",
                VerificationStatus.UNKNOWN);
    }

    private static ToolCallLoop.ToolOutcome readUnsupported(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.read_file",
                path,
                false,
                false,
                false,
                "",
                "Unsupported binary document format: " + path,
                null,
                "UNSUPPORTED_FORMAT");
    }

    private static ToolCallLoop.ToolOutcome readUnsupportedWithStatus(String path, String status) {
        return new ToolCallLoop.ToolOutcome(
                "talos.read_file",
                path,
                false,
                false,
                false,
                "",
                "Cannot extract text from " + path + " (status: " + status + ").",
                null,
                "UNSUPPORTED_FORMAT");
    }

    private static ToolCallLoop.LoopResult loopResult(ToolCallLoop.ToolOutcome outcome) {
        return new ToolCallLoop.LoopResult(
                "Done.",
                1,
                1,
                List.of(outcome.toolName()),
                List.of(),
                outcome.success() ? 0 : 1,
                0,
                false,
                0,
                outcome.success() ? List.of(outcome.pathHint()) : List.of(),
                0,
                0,
                0,
                0,
                List.of(outcome));
    }
}
