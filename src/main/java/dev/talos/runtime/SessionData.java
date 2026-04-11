package dev.talos.runtime;

import java.time.Instant;
import java.util.List;

/**
 * Serialisable snapshot of a session's conversational state.
 *
 * <p>Used by {@link SessionStore} to persist/restore sessions across
 * REPL invocations. All fields are nullable-safe — missing data is
 * represented as empty strings or empty lists, never null.
 *
 * @param sessionId    opaque identifier (e.g. workspace hash or UUID)
 * @param workspace    absolute path of the workspace this session is bound to
 * @param sketch       compact summary of older conversation turns (empty if none)
 * @param turnCount    number of completed user/assistant exchanges
 * @param createdAt    when the session was first created
 * @param turns        conversation turns (role + content pairs), newest last
 */
public record SessionData(
        String sessionId,
        String workspace,
        String sketch,
        int turnCount,
        Instant createdAt,
        List<Turn> turns
) {

    /** A single conversation turn (role + content), safe for JSON serialization. */
    public record Turn(String role, String content) {
        public Turn {
            role    = (role == null ? "" : role);
            content = (content == null ? "" : content);
        }
    }

    /** Defensive copy — normalize nulls. */
    public SessionData {
        sessionId = (sessionId == null ? "" : sessionId);
        workspace = (workspace == null ? "" : workspace);
        sketch    = (sketch == null ? "" : sketch);
        createdAt = (createdAt == null ? Instant.now() : createdAt);
        turns     = (turns == null ? List.of() : List.copyOf(turns));
    }

    /** Backward-compatible constructor without turns. */
    public SessionData(String sessionId, String workspace, String sketch,
                       int turnCount, Instant createdAt) {
        this(sessionId, workspace, sketch, turnCount, createdAt, List.of());
    }
}


