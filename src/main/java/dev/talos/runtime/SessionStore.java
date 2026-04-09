package dev.talos.runtime;

import java.util.Optional;

/**
 * Persistence seam for session state. V1 uses {@link NoOpSessionStore} (ephemeral).
 * Save is fire-and-forget (never throws), load returns empty if absent.
 */
public interface SessionStore {

    /** Persist session state (idempotent — overwrites on same ID). */
    void save(SessionData data);

    /** Load a previously saved session, or empty if absent. */
    Optional<SessionData> load(String sessionId);

    /** Delete a stored session. Returns true if found and removed. */
    boolean delete(String sessionId);
}

