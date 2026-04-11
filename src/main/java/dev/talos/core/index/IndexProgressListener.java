package dev.talos.core.index;

/**
 * Callback for live indexing progress.
 *
 * <p>Implementations must be thread-safe — the indexer may invoke
 * {@link #onFileComplete} from multiple virtual threads concurrently.
 */
@FunctionalInterface
public interface IndexProgressListener {

    /**
     * Called after each file is fully processed (parsed, embedded, written).
     *
     * @param filesCompleted files processed so far (including skipped)
     * @param totalFiles     total files to process
     * @param lastFile       relative path of the file just completed
     */
    void onFileComplete(int filesCompleted, int totalFiles, String lastFile);

    /** A no-op listener for callers that don't need progress. */
    IndexProgressListener NOOP = (completed, total, file) -> {};
}

