package dev.talos.runtime;

import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;

import java.time.Instant;
import java.util.List;

/**
 * Serialisable snapshot of a session's conversational state.
 *
 * <p>Used by {@link SessionStore} to persist/restore sessions across
 * REPL invocations. All fields are nullable-safe - missing data is
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
        List<Turn> turns,
        String model,
        ActiveTaskContext activeTaskContext,
        ArtifactGoal artifactGoal
) {

    /** A single conversation turn (role + content + status), safe for JSON serialization. */
    public record Turn(String role, String content, String status) {
        public Turn {
            role    = (role == null ? "" : role);
            content = (content == null ? "" : content);
            status  = (status == null ? "" : status);
        }

        /** Backward-compatible constructor without status. */
        public Turn(String role, String content) {
            this(role, content, "");
        }
    }

    /** Defensive copy - normalize nulls. */
    public SessionData {
        sessionId = (sessionId == null ? "" : sessionId);
        workspace = (workspace == null ? "" : workspace);
        sketch    = (sketch == null ? "" : sketch);
        createdAt = (createdAt == null ? Instant.now() : createdAt);
        turns     = (turns == null ? List.of() : List.copyOf(turns));
        model     = (model == null ? "" : model);
        activeTaskContext = (activeTaskContext == null ? ActiveTaskContext.none() : activeTaskContext);
        artifactGoal = (artifactGoal == null ? ArtifactGoal.none() : artifactGoal);
    }

    /** Backward-compatible constructor without turns or model. */
    public SessionData(String sessionId, String workspace, String sketch,
                       int turnCount, Instant createdAt) {
        this(sessionId, workspace, sketch, turnCount, createdAt, List.of(), "");
    }

    /** Backward-compatible constructor without model. */
    public SessionData(String sessionId, String workspace, String sketch,
                       int turnCount, Instant createdAt, List<Turn> turns) {
        this(sessionId, workspace, sketch, turnCount, createdAt, turns, "");
    }

    /** Backward-compatible constructor without active context or artifact goal. */
    public SessionData(String sessionId, String workspace, String sketch,
                       int turnCount, Instant createdAt, List<Turn> turns, String model) {
        this(sessionId, workspace, sketch, turnCount, createdAt, turns, model,
                ActiveTaskContext.none(), ArtifactGoal.none());
    }
}


