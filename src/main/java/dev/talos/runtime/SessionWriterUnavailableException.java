package dev.talos.runtime;

import java.util.Objects;

/**
 * Fail-closed result for session allocation, writer acquisition, or writer
 * capability validation.
 */
public final class SessionWriterUnavailableException extends IllegalStateException {

    public enum Reason {
        INVALID_SESSION_ID,
        INVALID_ENTROPY,
        RESERVATION_IO,
        RESERVATION_EXHAUSTED,
        WRITER_BUSY,
        LOCK_IO,
        PERSISTENCE_IO,
        INCOMPLETE_TURN_LOG,
        LEASE_REQUIRED,
        FOREIGN_LEASE,
        WRONG_SESSION,
        LEASE_CLOSED,
        LEASE_POISONED,
        STORE_CLOSED
    }

    private final Reason reason;

    SessionWriterUnavailableException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    SessionWriterUnavailableException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
