package dev.talos.runtime;

import dev.talos.runtime.trace.LocalTurnTrace;

/**
 * Read-only, fail-closed observation of one physical terminal turn-log tail
 * and its exact trace artifact. The second tail read proves that a legitimate
 * append did not change the selected tail while the trace was observed.
 *
 * <p>The file-backed store evaluates this observation while holding the
 * session's existing writer-lock coordinate. This does not claim that the row
 * and trace were published transactionally, and it does not cover filesystem
 * writers that bypass Talos session ownership.
 */
public record TerminalTurnPairObservation(
        State state,
        TurnLogTailObservation rowObservation,
        TraceArtifactObservation traceObservation
) {
    public enum State {
        ABSENT,
        EXACT,
        INVALID,
        UNAVAILABLE
    }

    public TerminalTurnPairObservation {
        state = state == null ? State.UNAVAILABLE : state;
        if (state != State.EXACT) {
            rowObservation = null;
            traceObservation = null;
        } else {
            if (rowObservation == null || !rowObservation.exact()
                    || traceObservation == null || !traceObservation.exact()) {
                throw new IllegalArgumentException(
                        "an exact terminal-turn pair requires exact row and trace observations");
            }
            TurnRecord row = rowObservation.turn();
            LocalTurnTrace trace = traceObservation.trace();
            if (row == null || trace == null
                    || row.turnNumber() != trace.turnNumber()
                    || !row.traceId().equals(trace.traceId())) {
                throw new IllegalArgumentException(
                        "an exact terminal-turn pair requires matching turn and trace identities");
            }
        }
    }

    public static TerminalTurnPairObservation observe(
            SessionStore store,
            String sessionId
    ) {
        if (store == null) return unavailable();
        final TurnLogTailObservation before;
        try {
            before = store.observeTurnLogTail(sessionId);
        } catch (RuntimeException unavailable) {
            return unavailable();
        }
        if (before == null) return unavailable();
        if (before.state() == TurnLogTailObservation.State.ABSENT) {
            return absent();
        }
        if (before.state() == TurnLogTailObservation.State.INVALID) {
            return invalid();
        }
        if (!before.exact() || before.turn() == null
                || before.turn().traceId().isBlank()) {
            return unavailable();
        }

        final TraceArtifactObservation trace;
        final TurnLogTailObservation after;
        try {
            trace = store.observeTraceArtifact(
                    sessionId,
                    before.turn().turnNumber(),
                    before.turn().traceId());
            after = store.observeTurnLogTail(sessionId);
        } catch (RuntimeException unavailable) {
            return unavailable();
        }
        if (trace == null || after == null) return unavailable();
        if (!before.equals(after)) {
            return unavailable();
        }
        if (trace.state() == TraceArtifactObservation.State.ABSENT
                || trace.state() == TraceArtifactObservation.State.INVALID) {
            return invalid();
        }
        if (!trace.exact()) return unavailable();
        try {
            return exact(after, trace);
        } catch (IllegalArgumentException mismatch) {
            return invalid();
        }
    }

    public static TerminalTurnPairObservation absent() {
        return new TerminalTurnPairObservation(State.ABSENT, null, null);
    }

    public static TerminalTurnPairObservation exact(
            TurnLogTailObservation rowObservation,
            TraceArtifactObservation traceObservation
    ) {
        return new TerminalTurnPairObservation(
                State.EXACT, rowObservation, traceObservation);
    }

    public static TerminalTurnPairObservation invalid() {
        return new TerminalTurnPairObservation(State.INVALID, null, null);
    }

    public static TerminalTurnPairObservation unavailable() {
        return new TerminalTurnPairObservation(State.UNAVAILABLE, null, null);
    }

    public boolean exact() {
        return state == State.EXACT;
    }
}
