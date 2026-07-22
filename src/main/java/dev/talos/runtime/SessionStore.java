package dev.talos.runtime;

import dev.talos.runtime.trace.LocalTurnTrace;

import java.util.List;
import java.util.Optional;

/**
 * Persistence seam for session state. The shipped REPL wires
 * {@link JsonSessionStore} explicitly at the composition root
 * ({@code TalosBootstrap}); {@link NoOpSessionStore} is an explicit,
 * intentionally-named ephemeral default for tests and ad-hoc call sites,
 * not a silent fallback (CCR-016). Constructors that accept a
 * {@code SessionStore} require a non-null value.
 *
 * <p>The snapshot {@link #save} contract is fire-and-forget; load returns
 * empty if absent. Per-turn envelope implementations report only the write
 * observations explicitly represented by their receipt.
 *
 * <p>Alongside the full-session snapshot ({@link #save}/{@link #load}), stores
 * may implement per-turn append-only durability via {@link #appendTurn} and
 * {@link #loadTurns}. The default implementations are no-ops/empty so existing
 * stores keep compiling without change.
 */
public interface SessionStore {

    /** Persist session state (idempotent - overwrites on same ID). */
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

    /** Persist the redacted local trace artifact for a terminal turn. */
    default void saveTrace(String sessionId, LocalTurnTrace trace) {
        // no-op by default
    }

    /**
     * Persists one row and its optional trace through one typed call. The
     * default preserves legacy store behavior but cannot observe write health.
     * File stores may report process-observed write outcomes; this is not a
     * cross-file transaction guarantee.
     */
    default TurnEnvelopePersistenceReceipt persistTurnEnvelope(
            String sessionId,
            TurnRecord record,
            LocalTurnTrace trace
    ) {
        if (trace != null) {
            try {
                saveTrace(sessionId, trace);
            } catch (Exception ignored) {
                // Legacy stores expose no write-health signal. Preserve their
                // fire-and-forget behavior while still attempting the row.
            }
        }
        try {
            appendTurn(sessionId, record);
        } catch (Exception ignored) {
            // The default cannot distinguish a failed write from an
            // unobservable one, so it must not infer a stronger receipt.
        }
        return new TurnEnvelopePersistenceReceipt(
                TurnEnvelopePersistenceReceipt.State.UNOBSERVED);
    }

    /** Load one local trace artifact by id, if available. */
    default Optional<LocalTurnTrace> loadTrace(String sessionId, String traceId) {
        return Optional.empty();
    }

    /** Load the newest local trace artifact for a session, if available. */
    default Optional<LocalTurnTrace> loadLatestTrace(String sessionId) {
        return Optional.empty();
    }

    /**
     * List stored sessions for a workspace, newest first. Covers the
     * legacy bare-hash file, instance files
     * ({@code <ws-hash>-<UTC timestamp>}), and orphan per-turn crash
     * logs that never got a snapshot. Default implementation returns
     * empty.
     */
    default List<SessionSummary> listSessions(String workspaceId) {
        return List.of();
    }
}
