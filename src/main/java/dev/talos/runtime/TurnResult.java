package dev.talos.runtime;

import dev.talos.core.retrieval.RetrievalTrace;

import java.time.Duration;

/**
 * Result of a single runtime turn: the renderable result plus
 * runtime metadata (trace, timing, turn number, audit).
 *
 * <p>This is the boundary object between the runtime layer and the CLI/REPL
 * rendering layer. The CLI renders the {@link #result()}, while diagnostics
 * and transcript persistence consume the metadata.
 *
 * <p>The {@link #audit} component is optional; older callers and tests that
 * use the back-compat constructors get {@link TurnAudit#empty()}.
 */
public record TurnResult(
        Result result,
        RetrievalTrace trace,
        int turnNumber,
        Duration elapsed,
        TurnAudit audit
) {
    /** Normalize null audit to the empty snapshot. */
    public TurnResult {
        audit = (audit == null) ? TurnAudit.empty() : audit;
    }

    /** Back-compat constructor: no audit. */
    public TurnResult(Result result, RetrievalTrace trace, int turnNumber, Duration elapsed) {
        this(result, trace, turnNumber, elapsed, TurnAudit.empty());
    }

    /** Back-compat constructor for turns without trace or timing. */
    public TurnResult(Result result, int turnNumber) {
        this(result, null, turnNumber, Duration.ZERO, TurnAudit.empty());
    }
}
