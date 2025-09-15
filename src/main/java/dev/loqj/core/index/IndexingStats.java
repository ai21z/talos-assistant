package dev.loqj.core.index;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks indexing performance metrics and timings.
 */
public class IndexingStats {
    // Counters
    private final AtomicInteger filesScanned = new AtomicInteger();
    private final AtomicInteger filesSkipped = new AtomicInteger();
    private final AtomicInteger filesEmbedded = new AtomicInteger();
    private final AtomicInteger chunksWritten = new AtomicInteger();

    // Timings (milliseconds)
    private final AtomicLong walkTime = new AtomicLong();
    private final AtomicLong parseTime = new AtomicLong();
    private final AtomicLong embedTime = new AtomicLong();
    private final AtomicLong luceneTime = new AtomicLong();
    private final AtomicLong commitTime = new AtomicLong();
    private final AtomicLong totalTime = new AtomicLong();

    // Increment counters
    public void incrementFilesScanned() { filesScanned.incrementAndGet(); }
    public void incrementFilesSkipped() { filesSkipped.incrementAndGet(); }
    public void incrementFilesEmbedded() { filesEmbedded.incrementAndGet(); }
    public void incrementChunksWritten() { chunksWritten.incrementAndGet(); }

    // Add timing
    public void addWalkTime(long ms) { walkTime.addAndGet(ms); }
    public void addParseTime(long ms) { parseTime.addAndGet(ms); }
    public void addEmbedTime(long ms) { embedTime.addAndGet(ms); }
    public void addLuceneTime(long ms) { luceneTime.addAndGet(ms); }
    public void addCommitTime(long ms) { commitTime.addAndGet(ms); }
    public void setTotalTime(long ms) { totalTime.set(ms); }

    // Getters
    public int getFilesScanned() { return filesScanned.get(); }
    public int getFilesSkipped() { return filesSkipped.get(); }
    public int getFilesEmbedded() { return filesEmbedded.get(); }
    public int getChunksWritten() { return chunksWritten.get(); }

    public long getWalkTime() { return walkTime.get(); }
    public long getParseTime() { return parseTime.get(); }
    public long getEmbedTime() { return embedTime.get(); }
    public long getLuceneTime() { return luceneTime.get(); }
    public long getCommitTime() { return commitTime.get(); }
    public long getTotalTime() { return totalTime.get(); }

    public String getSummary() {
        return String.format("Scanned: %d, Skipped: %d, Embedded: %d, Chunks: %d, Total: %dms",
            getFilesScanned(), getFilesSkipped(), getFilesEmbedded(), getChunksWritten(), getTotalTime());
    }

    public String getDetailedTimings() {
        return String.format("Timings - Walk: %dms, Parse: %dms, Embed: %dms, Lucene: %dms, Commit: %dms",
            getWalkTime(), getParseTime(), getEmbedTime(), getLuceneTime(), getCommitTime());
    }

    public String toJson() {
        return String.format(java.util.Locale.ROOT,
            "{ \"case\":\"vectors=%s, embed_concurrency=%d, incremental_indexing\", " +
            "\"matched_files\":%d, \"files_scanned\":%d, \"files_skipped\":%d, " +
            "\"files_embedded\":%d, \"total_chunks\":%d, \"elapsed_ms\":%d, " +
            "\"index_steps_ms\": {\"walk\":%d, \"parse\":%d, \"embed\":%d, \"lucene_write\":%d, \"commit_refresh\":%d} }",
            "true", 4, getFilesScanned(), getFilesScanned(), getFilesSkipped(),
            getFilesEmbedded(), getChunksWritten(), getTotalTime(),
            getWalkTime(), getParseTime(), getEmbedTime(), getLuceneTime(), getCommitTime());
    }
}
