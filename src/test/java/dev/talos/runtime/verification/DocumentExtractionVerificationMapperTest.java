package dev.talos.runtime.verification;

import dev.talos.core.extract.DocumentExtractionStatus;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
