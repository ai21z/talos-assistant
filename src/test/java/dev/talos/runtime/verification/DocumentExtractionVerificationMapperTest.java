package dev.talos.runtime.verification;

import dev.talos.core.extract.DocumentExtractionStatus;
import dev.talos.core.extract.DocumentExtractionResult;
import dev.talos.core.extract.DocumentExtractionWarning;
import dev.talos.core.ingest.FileCapabilityPolicy;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentExtractionVerificationMapperTest {

    @Test
    void mapsEveryDocumentExtractionStatusToVerificationVerdict() {
        Map<DocumentExtractionStatus, VerificationVerdict> expected = new EnumMap<>(DocumentExtractionStatus.class);
        expected.put(DocumentExtractionStatus.NOT_ATTEMPTED, VerificationVerdict.NOT_RUN);
        expected.put(DocumentExtractionStatus.SUCCESS, VerificationVerdict.VERIFIED);
        expected.put(DocumentExtractionStatus.PARTIAL, VerificationVerdict.PARTIAL);
        expected.put(DocumentExtractionStatus.OCR_REQUIRED, VerificationVerdict.UNSUPPORTED);
        expected.put(DocumentExtractionStatus.OCR_UNAVAILABLE, VerificationVerdict.UNAVAILABLE);
        expected.put(DocumentExtractionStatus.PASSWORD_PROTECTED, VerificationVerdict.UNAVAILABLE);
        expected.put(DocumentExtractionStatus.ENCRYPTED, VerificationVerdict.UNAVAILABLE);
        expected.put(DocumentExtractionStatus.CORRUPT, VerificationVerdict.FAILED);
        expected.put(DocumentExtractionStatus.LIMIT_EXCEEDED, VerificationVerdict.PARTIAL);
        expected.put(DocumentExtractionStatus.FAILED, VerificationVerdict.FAILED);
        expected.put(DocumentExtractionStatus.BLOCKED_BY_PRIVACY, VerificationVerdict.UNAVAILABLE);
        expected.put(DocumentExtractionStatus.UNSUPPORTED_DISABLED, VerificationVerdict.UNSUPPORTED);
        expected.put(DocumentExtractionStatus.DEFERRED_UNSUPPORTED, VerificationVerdict.UNSUPPORTED);
        expected.put(DocumentExtractionStatus.UNSUPPORTED_ARCHIVE, VerificationVerdict.UNSUPPORTED);
        expected.put(DocumentExtractionStatus.UNSUPPORTED_BINARY, VerificationVerdict.UNSUPPORTED);

        for (DocumentExtractionStatus status : DocumentExtractionStatus.values()) {
            assertEquals(expected.get(status), DocumentExtractionVerificationMapper.toVerdict(status), status.name());
        }
    }

    @Test
    void successExtractionMapsToAuthoritativeScopedParserEvidence() {
        DocumentExtractionResult extraction = new DocumentExtractionResult(
                "report.pdf",
                null,
                FileCapabilityPolicy.Capability.EXTRACTABLE_TEXT_ENABLED,
                DocumentExtractionStatus.SUCCESS,
                "CANONICAL_PDF_TEXT_ALPHA",
                List.of(new DocumentExtractionWarning("pdf-text-order", "PDF visual order may differ.")),
                null,
                true);

        VerifierResult result = DocumentExtractionVerificationMapper.toVerifierResult("report.pdf", extraction);

        assertEquals(ProofKind.PARSER_EXTRACTION, result.proofKind());
        assertEquals(EvidenceAuthority.AUTHORITATIVE, result.authority());
        assertEquals(EvidenceCoverage.SCOPED, result.coverage());
        assertEquals(VerificationVerdict.VERIFIED, result.verdict());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains("report.pdf")
                                && f.contains("extracted text was produced by the local document parser")),
                result.facts().toString());
        assertTrue(result.limitations().stream()
                        .anyMatch(l -> l.contains("PDF visual order may differ")),
                result.limitations().toString());
    }

    @Test
    void partialExtractionStaysPartialAndCannotBecomeVerifiedEvidence() {
        DocumentExtractionResult extraction = new DocumentExtractionResult(
                "large-report.docx",
                null,
                FileCapabilityPolicy.Capability.EXTRACTABLE_TEXT_ENABLED,
                DocumentExtractionStatus.PARTIAL,
                "partial text",
                List.of(new DocumentExtractionWarning("extraction-truncated", "Extraction was truncated.")),
                null,
                true);

        VerifierResult result = DocumentExtractionVerificationMapper.toVerifierResult("large-report.docx", extraction);

        assertEquals(ProofKind.PARSER_EXTRACTION, result.proofKind());
        assertEquals(EvidenceAuthority.AUTHORITATIVE, result.authority());
        assertEquals(EvidenceCoverage.SCOPED, result.coverage());
        assertEquals(VerificationVerdict.PARTIAL, result.verdict());
        assertTrue(result.limitations().stream()
                        .anyMatch(l -> l.contains("status=PARTIAL")),
                result.limitations().toString());
    }
}
