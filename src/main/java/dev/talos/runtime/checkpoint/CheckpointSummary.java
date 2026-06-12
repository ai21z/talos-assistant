package dev.talos.runtime.checkpoint;

import java.time.Instant;
import java.util.Objects;

/**
 * Read-model summary of one checkpoint (T793). Tolerant of pre-T793
 * checkpoints: missing metadata renders as unknowns rather than failing.
 *
 * @param id        the {@code chk-<uuid>} directory name
 * @param createdAt capture time, {@link Instant#EPOCH} when unavailable
 * @param turnNumber the turn that captured it, -1 when unavailable
 * @param trigger   what caused the capture (tool + target), "(unknown)" for
 *                  pre-T793 checkpoints
 * @param fileCount captured manifest entries
 * @param byteCount captured blob bytes
 * @param status    metadata status ("CREATED", or "(metadata unavailable)")
 */
public record CheckpointSummary(
        String id,
        Instant createdAt,
        int turnNumber,
        String trigger,
        int fileCount,
        long byteCount,
        String status
) {
    public CheckpointSummary {
        id = Objects.toString(id, "");
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        trigger = trigger == null || trigger.isBlank() ? "(unknown)" : trigger;
        status = Objects.toString(status, "");
    }
}
