package dev.talos.runtime;

import java.util.List;
import java.util.Optional;

/**
 * Persistence seam for session state. V1 uses {@link NoOpSessionStore} (ephemeral).
 * Save is fire-and-forget (never throws), load returns empty if absent.
 *
 * <p>Alongside the full-session snapshot ({@link #save}/{@link #load}), stores
 * may implement per-turn append-only durability via {@link #appendTurn} and
 * {@link #loadTurns}. The default implementations are no-ops/empty so existing
 * stores keep compiling without change.
 */
public interface SessionStore {

    /** Persist session state (idempotent — overwrites on same ID). */
    void save(SessionData data);

    /** Load a previously saved session, or empty if absent. */
    Optional<SessionData> load(String sessionId);

    /** Delete a stored session. Returns true if found and removed. */
    boolean delete(String sessionId);

    /**
     * Append a single structured turn record. Append-per-turn durability
     * complements {@link #save}: the snapshot records the conversation
     * sketch + full-text memory for compact replay, while the per-turn log
     * records richer runtime truth (tool calls, approvals, trace summary)
     * that survives a crash before {@link #save} runs.
     *
     * <p>Default implementation is a no-op.
     */
    default void appendTurn(String sessionId, TurnRecord record) {
        // no-op by default
    }

    /**
     * Load all structured turn records for a session, in append order.
     * Default implementation returns empty.
     */
    default List<TurnRecord> loadTurns(String sessionId) {
        return List.of();
    }
}
