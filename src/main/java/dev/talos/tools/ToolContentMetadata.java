package dev.talos.tools;

/**
 * Provenance and handoff metadata for tool output.
 *
 * <p>The output string is not enough for privacy decisions. Extracted document
 * text may look like ordinary prose while still being private by origin. This
 * metadata lets the runtime decide what can enter model context, artifacts, and
 * indexes without guessing from regexes.
 */
public record ToolContentMetadata(
        ContentPrivacyClass privacyClass,
        ContentSource source,
        String sourcePath,
        boolean modelHandoffAllowed,
        boolean rawArtifactPersistenceAllowed,
        boolean ragIndexAllowed,
        String decisionReason) {

    public enum ContentPrivacyClass {
        NORMAL,
        PROTECTED_PATH,
        EXTRACTED_DOCUMENT_TEXT,
        PRIVATE_DOCUMENT_EXTRACTED_TEXT,
        PRIVATE_RAG_SNIPPET,
        COMMAND_OUTPUT,
        GENERATED_TEXT
    }

    public enum ContentSource {
        TOOL_OUTPUT,
        READ_FILE,
        DOCUMENT_EXTRACTION,
        RAG_INDEX,
        RAG_RETRIEVE,
        GREP,
        COMMAND,
        MODEL
    }

    public ToolContentMetadata {
        privacyClass = privacyClass == null ? ContentPrivacyClass.NORMAL : privacyClass;
        source = source == null ? ContentSource.TOOL_OUTPUT : source;
        sourcePath = sourcePath == null ? "" : sourcePath;
        decisionReason = decisionReason == null ? "" : decisionReason;
    }

    public static ToolContentMetadata normal() {
        return new ToolContentMetadata(
                ContentPrivacyClass.NORMAL,
                ContentSource.TOOL_OUTPUT,
                "",
                true,
                false,
                true,
                "normal tool output");
    }

    public static ToolContentMetadata extractedDocument(
            String sourcePath,
            boolean modelHandoffAllowed,
            boolean rawArtifactPersistenceAllowed,
            boolean ragIndexAllowed,
            String decisionReason) {
        return extractedDocument(
                sourcePath,
                !modelHandoffAllowed,
                modelHandoffAllowed,
                rawArtifactPersistenceAllowed,
                ragIndexAllowed,
                decisionReason);
    }

    public static ToolContentMetadata extractedDocument(
            String sourcePath,
            boolean privateDocument,
            boolean modelHandoffAllowed,
            boolean rawArtifactPersistenceAllowed,
            boolean ragIndexAllowed,
            String decisionReason) {
        return new ToolContentMetadata(
                privateDocument
                        ? ContentPrivacyClass.PRIVATE_DOCUMENT_EXTRACTED_TEXT
                        : ContentPrivacyClass.EXTRACTED_DOCUMENT_TEXT,
                ContentSource.DOCUMENT_EXTRACTION,
                sourcePath,
                modelHandoffAllowed,
                rawArtifactPersistenceAllowed,
                ragIndexAllowed,
                decisionReason);
    }
}
