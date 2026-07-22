package dev.talos.spi.types;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Runtime-owned lifecycle handle for one admitted provider transport operation.
 *
 * <p>The handle deliberately reports local control-plane observations only. In
 * particular, entering the JDK send call, interrupting its owner thread,
 * closing a response body, or observing a terminal protocol marker never
 * proves which bytes crossed a wire.
 */
public interface ProviderTransportAttempt {

    /** Whether the engine exposes and cooperates with the typed lifecycle. */
    enum ObservationMode {
        OBSERVED,
        LEGACY_UNOBSERVED
    }

    /** Ordered mechanically observed lifecycle facts. */
    enum Transition {
        HANDOFF_ADMITTED,
        SEND_API_ENTERED,
        RESPONSE_HEADERS_OBSERVED,
        RESPONSE_BODY_OPENED,
        PROVIDER_TERMINAL_OBSERVED,
        RESPONSE_BODY_COMPLETED,
        CANCELLATION_REQUESTED,
        CANCELLATION_ACKNOWLEDGED,
        SETTLED
    }

    /** Exactly one terminal disposition for the local transport owner. */
    enum Settlement {
        NONE,
        COMPLETED_PROVIDER_TERMINAL,
        COMPLETED_BODY_EOF,
        CANCELLED_BEFORE_SEND,
        CANCELLED_IN_FLIGHT,
        FAILED_BEFORE_HEADERS,
        FAILED_DURING_BODY,
        CLOSED_BEFORE_PROVIDER_TERMINAL,
        LIFECYCLE_UNOBSERVED,
        QUARANTINE_REQUIRED
    }

    /** Type-only failure classification; exception prose is never evidence. */
    enum Failure {
        NONE,
        CONNECTION,
        TIMEOUT,
        INTERRUPTION,
        CANCELLATION,
        BODY_IO,
        RUNTIME,
        UNKNOWN
    }

    /** Local cancellation knowledge, not a wire-delivery claim. */
    enum CancellationState {
        NOT_REQUESTED,
        REQUESTED,
        PRE_SEND_PREVENTED,
        SEND_ABORT_ACKNOWLEDGED,
        BODY_CLOSE_ACKNOWLEDGED,
        UNACKNOWLEDGED
    }

    /** Conservative wire knowledge for this program. */
    enum WireDisposition {
        SEND_API_NOT_ENTERED,
        UNKNOWN
    }

    /** T1188 owns the future exact managed-process receipt. */
    enum ProcessBinding {
        UNAVAILABLE
    }

    /** Closed cancellation reasons mapped from the generation owner. */
    enum CancellationReason {
        NONE,
        OPERATOR_CANCELLED,
        WALL_CLOCK_TIMEOUT,
        IDLE_TIMEOUT,
        REPETITION_LOOP,
        INTERRUPTION_CAUSE_UNPROVEN,
        RUNTIME_STREAM_CLOSED,
        SCOPE_CLOSED
    }

    @FunctionalInterface
    interface SendOperation<T> {
        T invoke() throws Exception;
    }

    /**
     * Atomically arbitrates cancellation against entry to the supplied send
     * operation. The observed implementation binds the executing thread as the
     * strongest cancellation handle exposed by synchronous JDK HttpClient.
     */
    <T> T invokeSend(SendOperation<T> operation) throws Exception;

    /** Called by the exact response body handler when response headers arrive. */
    void responseHeadersObserved();

    /** Binds a newly returned streaming response body as a late cancellation handle. */
    void responseBodyOpened(AutoCloseable responseBody);

    /** Records an exact provider-protocol terminal marker. */
    void providerTerminalObserved();

    /** Records raw body EOF or completed non-stream body collection. */
    void responseBodyCompleted();

    /** Records a typed body-consumption failure without persisting its prose. */
    void responseBodyFailed(Throwable failure);

    /** Records deterministic local closure of a body before raw EOF. */
    void responseBodyClosed();

    /** True only for the no-evidence compatibility handle. */
    default boolean unobserved() {
        return false;
    }

    static ProviderTransportAttempt unobservedAttempt() {
        return Unobserved.INSTANCE;
    }

    /** Typed cancellation used when the owner prevents send entry. */
    final class CancelledBeforeSend extends CancellationException {
        public CancelledBeforeSend() {
            super("provider transport was cancelled before send entry");
        }
    }

    /** A quarantined or otherwise settled operation rejected a late body handle. */
    final class ResponseBodyRejectedAfterSettlement extends CancellationException {
        public ResponseBodyRejectedAfterSettlement() {
            super("settled provider transport rejected a late response body");
        }
    }

    /** Behavior-neutral compatibility handle for engines outside the exact path. */
    final class Unobserved implements ProviderTransportAttempt {
        private static final Unobserved INSTANCE = new Unobserved();

        private Unobserved() {}

        @Override
        public <T> T invokeSend(SendOperation<T> operation) throws Exception {
            return Objects.requireNonNull(operation, "provider send operation").invoke();
        }

        @Override public void responseHeadersObserved() {}
        @Override public void responseBodyOpened(AutoCloseable responseBody) {}
        @Override public void providerTerminalObserved() {}
        @Override public void responseBodyCompleted() {}
        @Override public void responseBodyFailed(Throwable failure) {}
        @Override public void responseBodyClosed() {}
        @Override public boolean unobserved() { return true; }
    }
}
