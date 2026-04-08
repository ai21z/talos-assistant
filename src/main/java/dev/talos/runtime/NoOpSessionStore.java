package dev.talos.runtime;
import java.util.Optional;
/**
 * V1 session store -- all operations are no-ops.
 *
 * <p>Sessions are ephemeral: conversation history lives in memory
 * and is lost when the REPL exits. This implementation satisfies
 * the {@link SessionStore} contract without any I/O.
 *
 * <p>Replace with a persistent implementation (e.g. {@code SqliteSessionStore})
 * when session resume capability is needed.
 */
public final class NoOpSessionStore implements SessionStore {
    @Override
    public void save(SessionData data) {
        // No-op: V1 sessions are ephemeral
    }
    @Override
    public Optional<SessionData> load(String sessionId) {
        return Optional.empty();
    }
    @Override
    public boolean delete(String sessionId) {
        return false;
    }
}
