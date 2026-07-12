package dev.talos.tools;

import java.util.LinkedHashSet;
import java.util.List;

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
        String decisionReason,
        List<String> sourcePaths) {

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
        sourcePaths = normalizeSourcePaths(sourcePaths);
    }

    public ToolContentMetadata(
            ContentPrivacyClass privacyClass,
            ContentSource source,
            String sourcePath,
            boolean modelHandoffAllowed,
            boolean rawArtifactPersistenceAllowed,
            boolean ragIndexAllowed,
            String decisionReason) {
        this(privacyClass, source, sourcePath, modelHandoffAllowed,
                rawArtifactPersistenceAllowed, ragIndexAllowed, decisionReason, List.of());
    }

    public static ToolContentMetadata normal() {
        return new ToolContentMetadata(
                ContentPrivacyClass.NORMAL,
                ContentSource.TOOL_OUTPUT,
                "",
                true,
                false,
                true,
                "normal tool output",
                List.of());
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

    public static ToolContentMetadata commandOutput(
            String profileId,
            boolean modelHandoffAllowed,
            String decisionReason) {
        return new ToolContentMetadata(
                ContentPrivacyClass.COMMAND_OUTPUT,
                ContentSource.COMMAND,
                profileId,
                modelHandoffAllowed,
                false,
                false,
                decisionReason == null || decisionReason.isBlank()
                        ? "command output"
                        : decisionReason,
                List.of());
    }

    public static ToolContentMetadata workspaceEvidence(
            ContentSource source,
            List<String> sourcePaths,
            String decisionReason) {
        List<String> paths = normalizeSourcePaths(sourcePaths);
        String primary = paths.isEmpty() ? "" : paths.getFirst();
        return new ToolContentMetadata(
                ContentPrivacyClass.NORMAL,
                source,
                primary,
                true,
                false,
                true,
                decisionReason == null || decisionReason.isBlank()
                        ? "workspace evidence"
                        : decisionReason,
                paths);
    }

    public ToolContentMetadata withModelHandoffAllowed(boolean allowed, String reason) {
        return new ToolContentMetadata(
                privacyClass,
                source,
                sourcePath,
                allowed,
                rawArtifactPersistenceAllowed,
                ragIndexAllowed,
                reason == null || reason.isBlank() ? decisionReason : reason,
                sourcePaths);
    }

    private static List<String> normalizeSourcePaths(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            out.add(value.strip().replace('\\', '/'));
        }
        return List.copyOf(out);
    }
}
