package dev.loqj.core.retrieval;

import java.util.List;

/**
 * Immutable output of a single pipeline stage execution.
 * Carries the updated candidate list and an optional diagnostic note
 * (e.g., skip reason). This keeps stages stateless — the note is a
 * value returned from the invocation, not stored in the stage.
 */
public record StageOutput(List<RetrievalCandidate> candidates, String note) {

    /** Create an output with candidates and no note. */
    public static StageOutput of(List<RetrievalCandidate> candidates) {
        return new StageOutput(candidates, null);
    }

    /** Create an output with candidates and a diagnostic note. */
    public static StageOutput of(List<RetrievalCandidate> candidates, String note) {
        return new StageOutput(candidates, note);
    }
}

