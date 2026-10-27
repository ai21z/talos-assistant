package dev.talos.runtime;

import dev.talos.runtime.trace.LocalTurnTrace;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-forgeable, session-bound mutation capability backed by one exclusive
 * cross-process file lock. Read operations delegate to the owning store.
 */
public final class SessionWriterLease implements SessionStore, AutoCloseable {
    private final JsonSessionStore owner;
    private final String sessionId;
    private final Path reservationPath;
    private final Path lockPath;
    private final FileChannel channel;
    private final FileLock lock;
    private final Object operationMutex = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean poisoned = new AtomicBoolean(false);

    SessionWriterLease(
            JsonSessionStore owner,
            String sessionId,
            Path reservationPath,
            Path lockPath,
            FileChannel channel,
            FileLock lock
    ) {
        this.owner = owner;
        this.sessionId = sessionId;
        this.reservationPath = reservationPath;
        this.lockPath = lockPath;
        this.channel = channel;
        this.lock = lock;
    }

    public String sessionId() {
        return sessionId;
    }

    /** Persistent allocation marker authenticating this exact session ID. */
    public Optional<Path> reservationPath() {
        return Optional.ofNullable(reservationPath);
    }

    /** Stable lock sidecar retained after close and session deletion. */
    public Path lockPath() {
        return lockPath;
    }

    public boolean isOpen() {
        return !closed.get() && !poisoned.get()
                && channel.isOpen() && lock.isValid();
    }

    @Override
    public void save(SessionData data) {
        requireBound(data == null ? "" : data.sessionId());
        owner.save(this, data);
    }

    @Override
    public Optional<SessionData> load(String requestedSessionId) {
        return owner.load(requestedSessionId);
    }

    @Override
    public boolean delete(String requestedSessionId) {
        requireBound(requestedSessionId);
        return owner.delete(this, requestedSessionId);
    }

    @Override
    public void appendTurn(String requestedSessionId, TurnRecord record) {
        requireBound(requestedSessionId);
        owner.appendTurn(this, requestedSessionId, record);
    }

    @Override
    public List<TurnRecord> loadTurns(String requestedSessionId) {
        return owner.loadTurns(requestedSessionId);
    }

    @Override
    public TurnLogTailObservation observeTurnLogTail(String requestedSessionId) {
        return owner.observeTurnLogTail(requestedSessionId);
    }

    @Override
    public TraceArtifactObservation observeTraceArtifact(
            String requestedSessionId,
            int turnNumber,
            String traceId
    ) {
        return owner.observeTraceArtifact(
                requestedSessionId, turnNumber, traceId);
    }

    @Override
    public TerminalTurnPairObservation observeTerminalTurnPair(
            String requestedSessionId
    ) {
        return owner.observeTerminalTurnPair(requestedSessionId);
    }

    @Override
    public TurnLogInventoryObservation observeTurnLogInventory(
            String workspaceId
    ) {
        return owner.observeTurnLogInventory(workspaceId);
    }

    @Override
    public void saveTrace(String requestedSessionId, LocalTurnTrace trace) {
        requireBound(requestedSessionId);
        owner.saveTrace(this, requestedSessionId, trace);
    }

    @Override
    public TurnEnvelopePersistenceReceipt persistTurnEnvelope(
            String requestedSessionId,
            TurnRecord record,
            LocalTurnTrace trace
    ) {
        requireBound(requestedSessionId);
        return owner.persistTurnEnvelope(this, requestedSessionId, record, trace);
    }

    @Override
    public Optional<LocalTurnTrace> loadTrace(String requestedSessionId, String traceId) {
        return owner.loadTrace(requestedSessionId, traceId);
    }

    @Override
    public Optional<LocalTurnTrace> loadLatestTrace(String requestedSessionId) {
        return owner.loadLatestTrace(requestedSessionId);
    }

    @Override
    public List<SessionSummary> listSessions(String workspaceId) {
        return owner.listSessions(workspaceId);
    }

    @Override
    public Optional<Path> rawTurnLogPath(String requestedSessionId) {
        return owner.rawTurnLogPath(requestedSessionId);
    }

    <T> T underLock(
            JsonSessionStore expectedOwner,
            String requestedSessionId,
            LeaseAction<T> action
    ) {
        requireBound(requestedSessionId);
        synchronized (operationMutex) {
            expectedOwner.requireMatchingLease(this, requestedSessionId);
            try {
                return action.run();
            } catch (SessionWriterUnavailableException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new LeaseOperationException(failure);
            }
        }
    }

    void poison() {
        poisoned.set(true);
    }

    boolean belongsTo(JsonSessionStore candidateOwner) {
        return owner == candidateOwner;
    }

    boolean owns(String requestedSessionId) {
        return sessionId.equals(requestedSessionId);
    }

    boolean closed() {
        return closed.get();
    }

    boolean poisoned() {
        return poisoned.get();
    }

    boolean lockValid() {
        return channel.isOpen() && lock.isValid();
    }

    private void requireBound(String requestedSessionId) {
        if (!sessionId.equals(requestedSessionId)) {
            throw new SessionWriterUnavailableException(
                    SessionWriterUnavailableException.Reason.WRONG_SESSION,
                    "Session writer lease does not own the requested session");
        }
    }

    @Override
    public void close() {
        synchronized (operationMutex) {
            if (!closed.compareAndSet(false, true)) return;
            owner.release(this);
        }
    }

    void releaseResources() throws IOException {
        IOException failure = null;
        try {
            if (lock.isValid()) lock.release();
        } catch (IOException releaseFailure) {
            failure = releaseFailure;
        }
        try {
            channel.close();
        } catch (IOException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }

    @FunctionalInterface
    interface LeaseAction<T> {
        T run() throws Exception;
    }

    /** Internal checked-I/O carrier; the store converts it at its API boundary. */
    static final class LeaseOperationException extends RuntimeException {
        LeaseOperationException(Throwable cause) {
            super(cause);
        }
    }
}
