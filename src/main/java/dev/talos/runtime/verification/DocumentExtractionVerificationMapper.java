package dev.talos.runtime.verification;

import dev.talos.core.extract.DocumentExtractionStatus;

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
}
