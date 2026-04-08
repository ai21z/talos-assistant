package dev.talos.runtime;

import java.util.Optional;

/**
 * Persistence seam for session state.
 *
 * <p>V1 uses {@link NoOpSessionStore} — sessions are ephemeral and all
 * methods are no-ops. Future implementations (e.g. {@code SqliteSessionStore})
 * can persist conversation sketches, entity lists, and turn summaries
 * to {@code ~/.talos/sessions/} for resume capability.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #save} is fire-and-forget — implementations must never throw.</li>
 *   <li>{@link #load} returns empty when no prior state exists.</li>
 *   <li>{@link #delete} returns {@code true} if state was present and removed.</li>
 * </ul>
 *
 * @see SessionData
 * @see NoOpSessionStore
 */
public interface SessionStore {

    /**
     * Persist session state. Implementations must be idempotent —
     * saving the same ID twice overwrites the previous snapshot.
     *
     * @param data non-null session data to persist
     */
    void save(SessionData data);

    /**
     * Load a previously saved session.
     *
     * @param sessionId the session identifier
     * @return the stored data, or empty if no session with that ID exists
     */
    Optional<SessionData> load(String sessionId);

    /**
     * Delete a stored session.
     *
     * @param sessionId the session identifier
     * @return {@code true} if a session was found and removed
     */
    boolean delete(String sessionId);
}

