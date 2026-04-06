package dev.talos.core.ingest;

/**
 * Structured metadata carried by each {@link ParsedChunk}.
 * <p>
 * Fields are intentionally nullable — a chunk may not have a heading context
 * (e.g. plain-text files), or language detection may not be possible.
 *
 * @param language        programming/markup language inferred from file extension (e.g. "java", "md"), or null
 * @param lineStart       1-based line number where this chunk begins in the source file, or -1 if unknown
 * @param lineEnd         1-based line number where this chunk ends (inclusive), or -1 if unknown
 * @param headingContext  last Markdown heading (e.g. "## Architecture") preceding this chunk, or null
 * @param sourceIdentity  classified identity of the source file, or null if not yet classified
 */
public record ChunkMetadata(
        String language,
        int lineStart,
        int lineEnd,
        String headingContext,
        SourceIdentity sourceIdentity
) {
    /** Backwards-compatible constructor without sourceIdentity. */
    public ChunkMetadata(String language, int lineStart, int lineEnd, String headingContext) {
        this(language, lineStart, lineEnd, headingContext, null);
    }

    /** Convenience factory when no metadata is available. */
    public static ChunkMetadata empty() {
        return new ChunkMetadata(null, -1, -1, null, null);
    }

    /** True if at least one meaningful field is populated. */
    public boolean hasContent() {
        return language != null || lineStart > 0 || lineEnd > 0
                || headingContext != null || sourceIdentity != null;
    }
}

