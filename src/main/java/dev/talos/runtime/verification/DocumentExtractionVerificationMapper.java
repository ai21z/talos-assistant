package dev.talos.runtime.verification;

import dev.talos.core.extract.DocumentExtractionResult;
import dev.talos.core.extract.DocumentExtractionStatus;
import dev.talos.core.extract.DocumentExtractionWarning;

import java.util.ArrayList;
import java.util.List;

public final class DocumentExtractionVerificationMapper {
    private DocumentExtractionVerificationMapper() {}

    public static VerificationVerdict toVerdict(DocumentExtractionStatus status) {
        if (status == null) return VerificationVerdict.FAILED;
        return switch (status) {
            case NOT_ATTEMPTED -> VerificationVerdict.NOT_RUN;
            case SUCCESS -> VerificationVerdict.VERIFIED;
            case PARTIAL, LIMIT_EXCEEDED -> VerificationVerdict.PARTIAL;
            case OCR_REQUIRED,
                    UNSUPPORTED_DISABLED,
                    DEFERRED_UNSUPPORTED,
                    UNSUPPORTED_ARCHIVE,
                    UNSUPPORTED_BINARY -> VerificationVerdict.UNSUPPORTED;
            case OCR_UNAVAILABLE,
                    PASSWORD_PROTECTED,
                    ENCRYPTED,
                    BLOCKED_BY_PRIVACY -> VerificationVerdict.UNAVAILABLE;
            case CORRUPT, FAILED -> VerificationVerdict.FAILED;
        };
    }

    public static VerifierResult toVerifierResult(String sourcePath, DocumentExtractionResult result) {
        DocumentExtractionStatus status = result == null ? null : result.status();
        VerificationVerdict verdict = toVerdict(status);
        String path = displayPath(sourcePath, result);
        List<String> facts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<String> limitations = new ArrayList<>();

        switch (verdict) {
            case VERIFIED -> facts.add(path
                    + ": extracted text was produced by the local document parser (status="
                    + statusName(status) + ").");
            case PARTIAL -> limitations.add(path
                    + ": document extraction was partial (status=" + statusName(status)
                    + "); extracted text may be truncated or incomplete.");
            case UNSUPPORTED -> limitations.add(path
                    + ": document extraction is unsupported in the current lane (status="
                    + statusName(status) + ").");
            case UNAVAILABLE -> limitations.add(path
                    + ": document extraction was unavailable (status=" + statusName(status) + ").");
            case FAILED -> problems.add(path
                    + ": document extraction failed (status=" + statusName(status) + ").");
            case NOT_RUN -> limitations.add(path
                    + ": document extraction did not run (status=" + statusName(status) + ").");
            case UNVERIFIED -> limitations.add(path
                    + ": document extraction did not produce verified parser evidence (status="
                    + statusName(status) + ").");
        }

        if (result != null) {
            for (DocumentExtractionWarning warning : result.warnings()) {
                if (warning == null || warning.message().isBlank()) continue;
                limitations.add(path + ": " + warning.message());
            }
        }

        return new VerifierResult(
                null,
                ProofKind.PARSER_EXTRACTION,
                EvidenceAuthority.AUTHORITATIVE,
                EvidenceCoverage.SCOPED,
                verdict,
                facts,
                problems,
                limitations);
    }

    private static String displayPath(String sourcePath, DocumentExtractionResult result) {
        if (sourcePath != null && !sourcePath.isBlank()) return sourcePath.strip().replace('\\', '/');
        if (result != null && !result.sourcePath().isBlank()) return result.sourcePath().replace('\\', '/');
        return "document";
    }

    private static String statusName(DocumentExtractionStatus status) {
        return status == null ? "null" : status.name();
    }
}
