package dev.talos.spi.types;

import java.util.Objects;

/**
 * Identity of a source within a workspace: its path plus its semantic
 * classification (type, format, media type).
 *
 * <p>This is the "proper identity" that replaces bare path strings as the
 * system's root input abstraction. Every file ingested into Talos gets
 * a {@code SourceIdentity} assigned at ingest time, and that identity flows
 * through indexing, retrieval, and context assembly.
 *
 * @param path      relative file path within the workspace (never null)
 * @param type      semantic source category
 * @param format    technical format
 * @param mediaType content modality
 */
public record SourceIdentity(
        String path,
        SourceType type,
        SourceFormat format,
        MediaType mediaType
) {
    public SourceIdentity {
        Objects.requireNonNull(path, "path must not be null");
        if (type == null)      type = SourceType.UNKNOWN;
        if (format == null)    format = SourceFormat.UNKNOWN;
        if (mediaType == null) mediaType = MediaType.UNKNOWN;
    }

    /** Factory for when only the path is known and classification has not run. */
    public static SourceIdentity unclassified(String path) {
        return new SourceIdentity(path, SourceType.UNKNOWN, SourceFormat.UNKNOWN, MediaType.UNKNOWN);
    }

    /** True if at least one classification axis is known (not UNKNOWN). */
    public boolean isClassified() {
        return type != SourceType.UNKNOWN
                || format != SourceFormat.UNKNOWN
                || mediaType != MediaType.UNKNOWN;
    }
}

