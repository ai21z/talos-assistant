package dev.talos.runtime;

/** Observed result of writing one trace plus its companion turn row. */
public record TurnEnvelopePersistenceReceipt(State state) {
    public enum State {
        BOTH_WRITES_OBSERVED,
        TRACE_WRITE_ONLY,
        TURN_WRITE_ONLY,
        NO_WRITES_OBSERVED,
        UNOBSERVED
    }

    public TurnEnvelopePersistenceReceipt {
        state = state == null ? State.NO_WRITES_OBSERVED : state;
    }

    public static TurnEnvelopePersistenceReceipt bothWritesObserved() {
        return new TurnEnvelopePersistenceReceipt(State.BOTH_WRITES_OBSERVED);
    }
}
