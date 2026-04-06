package dev.talos.runtime;

import dev.talos.cli.repl.Result;
import dev.talos.core.retrieval.RetrievalTrace;

import java.time.Duration;

/**
 * Result of a single runtime turn: the renderable result plus
 * runtime metadata (trace, timing, turn number).
 *
 * <p>This is the boundary object between the runtime layer and the CLI/REPL
 * rendering layer. The CLI renders the {@link #result()}, while diagnostics
 * and future transcript persistence can consume the metadata.
 */
public record TurnResult(
        Result result,
        RetrievalTrace trace,
        int turnNumber,
        Duration elapsed
) {
    /** Convenience constructor for turns without trace or timing. */
    public TurnResult(Result result, int turnNumber) {
        this(result, null, turnNumber, Duration.ZERO);
    }
}

