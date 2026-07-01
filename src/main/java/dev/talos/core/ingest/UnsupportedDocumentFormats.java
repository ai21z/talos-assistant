package dev.talos.core.ingest;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Capability boundary for binary document formats Talos does not extract yet.
 */
public final class UnsupportedDocumentFormats {
    private UnsupportedDocumentFormats() {}

    public static Optional<Format> describe(Path path) {
        return FileCapabilityPolicy.describe(path)
                .map(info -> new Format(info.extension(), info.label(), info.contentName()));
    }

    public static Optional<Format> describeExtension(String extension) {
        if (extension == null || extension.isBlank()) return Optional.empty();
        String ext = extension.strip();
        if (ext.startsWith(".")) ext = ext.substring(1);
        return describe(Path.of("file." + ext));
    }

    public static boolean isUnsupported(Path path) {
        return FileCapabilityPolicy.isUnsupported(path);
    }

    public static String capabilityMessage(Path path) {
        return FileCapabilityPolicy.readCapabilityMessage(path);
    }

    public static String writeCapabilityMessage(Path path) {
        return FileCapabilityPolicy.writeCapabilityMessage(path);
    }

    public record Format(String extension, String label, String contentName) {}
}
