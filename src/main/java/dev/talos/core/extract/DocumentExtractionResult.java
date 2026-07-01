package dev.talos.core.extract;

import dev.talos.core.ingest.FileCapabilityPolicy;

import java.util.List;
import java.util.Objects;

public record DocumentExtractionResult(
        String sourcePath,
        DocumentExtractionIntent intent,
        FileCapabilityPolicy.Capability capability,
        DocumentExtractionStatus status,
        String safeText,
        List<DocumentExtractionWarning> warnings,
        DocumentExtractionProvenance provenance,
        boolean modelHandoffAllowed) {
    public DocumentExtractionResult {
        sourcePath = sourcePath == null ? "" : sourcePath;
        intent = intent == null ? DocumentExtractionIntent.READ : intent;
        capability = capability == null ? FileCapabilityPolicy.Capability.UNKNOWN_TEXT_ATTEMPT_ALLOWED : capability;
        status = Objects.requireNonNullElse(status, DocumentExtractionStatus.FAILED);
        safeText = safeText == null ? "" : safeText;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        provenance = provenance == null
                ? new DocumentExtractionProvenance(sourcePath, "", "", DocumentExtractionService.EXTRACTION_POLICY_VERSION)
                : provenance;
    }
}
