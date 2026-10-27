package dev.talos.runtime;

/** Safe result returned by a settlement-aware session listener. */
public record TurnPublicationReceipt(Status status, Reason reason) {
    public enum Status {
        COMPLETE,
        SKIPPED,
        DEGRADED
    }

    public enum Reason {
        NONE,
        PERSISTENCE_UNOBSERVED,
        PERSISTENCE_PARTIAL,
        PERSISTENCE_FAILED,
        LISTENER_EXCEPTION,
        TURN_SETTLEMENT_FAILED
    }

    public TurnPublicationReceipt {
        status = status == null ? Status.DEGRADED : status;
        reason = reason == null ? Reason.NONE : reason;
        if ((status == Status.DEGRADED) == (reason == Reason.NONE)) {
            throw new IllegalArgumentException(
                    "only degraded publication receipts require a reason");
        }
    }

    public static TurnPublicationReceipt complete() {
        return new TurnPublicationReceipt(Status.COMPLETE, Reason.NONE);
    }

    public static TurnPublicationReceipt skipped() {
        return new TurnPublicationReceipt(Status.SKIPPED, Reason.NONE);
    }

    public static TurnPublicationReceipt degraded(Reason reason) {
        return new TurnPublicationReceipt(Status.DEGRADED, reason);
    }
}
