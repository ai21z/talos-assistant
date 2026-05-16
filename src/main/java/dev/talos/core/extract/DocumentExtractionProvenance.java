package dev.talos.core.extract;

public record DocumentExtractionProvenance(
        String sourcePath,
        String adapterName,
        String adapterVersion,
        String extractionPolicyVersion) {
    public DocumentExtractionProvenance {
        sourcePath = sourcePath == null ? "" : sourcePath;
        adapterName = adapterName == null ? "" : adapterName;
        adapterVersion = adapterVersion == null ? "" : adapterVersion;
        extractionPolicyVersion = extractionPolicyVersion == null ? "" : extractionPolicyVersion;
    }
}
