package dev.talos.core.ingest;

/**
 * A single chunk produced by {@link Chunker} from a source file.
 *
 * @param id          unique identifier ({@code relPath#chunkId})
 * @param path        relative file path within the workspace
 * @param text        chunk text content
 * @param fileHash    SHA-1 hash of the full source file content
 * @param chunkId     0-based sequential chunk index within the file
 * @param metadata    structured metadata (language, line range, heading context); never null
 */
public record ParsedChunk(String id, String path, String text, String fileHash, int chunkId, ChunkMetadata metadata) {

    /** Backwards-compatible constructor for callers that do not supply metadata. */
    public ParsedChunk(String id, String path, String text, String fileHash, int chunkId) {
        this(id, path, text, fileHash, chunkId, ChunkMetadata.empty());
    }
}
