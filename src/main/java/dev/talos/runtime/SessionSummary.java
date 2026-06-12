package dev.talos.runtime;

import java.time.Instant;

/**
 * Listing entry for one stored session of a workspace (T799). Built by
 * {@link SessionStore#listSessions} from whatever survives on disk: a
 * snapshot, a per-turn crash log, or both. {@code createdAt} falls back
 * to {@link Instant#EPOCH} when metadata is unreadable so corrupt files
 * sort oldest instead of newest.
 *
 * @param sessionId   full storage id ({@code <ws-hash>} for legacy files,
 *                    {@code <ws-hash>-<UTC timestamp>} for instance files)
 * @param createdAt   snapshot creation time, or the first turn-log
 *                    timestamp for crash logs; EPOCH when unknown
 * @param turnCount   snapshot turn count, or turn-log row count
 * @param model       model id recorded at save time, blank when unknown
 * @param hasSnapshot a {@code <id>.json} snapshot exists
 * @param hasTurnLog  a {@code <id>.turns.jsonl} per-turn log exists
 * @param legacy      the id is the bare workspace hash (pre-T799 file)
 */
public record SessionSummary(
        String sessionId,
        Instant createdAt,
        int turnCount,
        String model,
        boolean hasSnapshot,
        boolean hasTurnLog,
        boolean legacy
) {}
