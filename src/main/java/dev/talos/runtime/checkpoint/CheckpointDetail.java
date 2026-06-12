package dev.talos.runtime.checkpoint;

import java.util.List;
import java.util.Objects;

/** Read-model detail of one checkpoint: its summary plus manifest entries (T793). */
public record CheckpointDetail(
        CheckpointSummary summary,
        List<Entry> entries
) {
    public CheckpointDetail {
        Objects.requireNonNull(summary, "summary is required");
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * One manifest entry. {@code existedBefore=false} means a restore of
     * this checkpoint DELETES the path (it did not exist at capture time).
     */
    public record Entry(
            String relativePath,
            String entryType,
            boolean existedBefore,
            String blobSha256,
            long sizeBytes
    ) {
        public Entry {
            relativePath = Objects.toString(relativePath, "");
            entryType = Objects.toString(entryType, "FILE");
            blobSha256 = Objects.toString(blobSha256, "");
        }
    }
}
