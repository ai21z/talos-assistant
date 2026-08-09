package dev.talos.runtime;

/**
 * Read-only, fail-closed observation of one append-only turn log's physical
 * tail. An exact row digest covers the physical UTF-8 JSON bytes excluding
 * the required trailing LF; CRLF and any non-LF envelope are not exact. It is
 * diagnostic evidence only and does not imply snapshot recovery, cross-file
 * transactionality, or ordering across different session logs.
 */
public record TurnLogTailObservation(
        State state,
        TurnRecord turn,
        int appendOrdinal,
        String rowSha256
) {
    public enum State {
        ABSENT,
        EXACT,
        INVALID,
        UNAVAILABLE
    }

    public TurnLogTailObservation {
        state = state == null ? State.UNAVAILABLE : state;
        rowSha256 = rowSha256 == null ? "" : rowSha256;
        if (state != State.EXACT) {
            turn = null;
            appendOrdinal = 0;
            rowSha256 = "";
        } else if (turn == null || appendOrdinal < 1
                || !rowSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "an exact turn-log tail requires a row, ordinal, and digest");
        }
    }

    public static TurnLogTailObservation absent() {
        return new TurnLogTailObservation(State.ABSENT, null, 0, "");
    }

    public static TurnLogTailObservation exact(
            TurnRecord turn,
            int appendOrdinal,
            String rowSha256
    ) {
        return new TurnLogTailObservation(
                State.EXACT, turn, appendOrdinal, rowSha256);
    }

    public static TurnLogTailObservation invalid() {
        return new TurnLogTailObservation(State.INVALID, null, 0, "");
    }

    public static TurnLogTailObservation unavailable() {
        return new TurnLogTailObservation(State.UNAVAILABLE, null, 0, "");
    }

    public boolean exact() {
        return state == State.EXACT;
    }
}
