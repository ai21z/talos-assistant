package dev.talos.runtime;

import dev.talos.core.Config;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Immutable session context for a single Talos runtime invocation.
 * Carries workspace binding, configuration, turn tracking, and session memory.
 *
 * <p>A session is created once per REPL run (or per programmatic invocation)
 * and stays alive until the user quits. Turn count is the only mutable field
 * and is tracked via an atomic counter for safe concurrent access.
 *
 * <p>Call {@link #close()} when the session ends to fire lifecycle callbacks
 * and release resources. Session implements {@link AutoCloseable} for
 * try-with-resources support.
 *
 * <p>Session does <em>not</em> own Talos retrieval internals or LLM state.
 * Those are composed separately in the runtime context.
 */
public final class Session implements AutoCloseable {

    private final Path workspace;
    private final Config config;
    private final Instant startedAt;
    private final AtomicInteger turnCount;
    private final SessionMemory memory;
    private final SessionStore store;
    private final List<SessionListener> closeListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public Session(Path workspace, Config config) {
        this(workspace, config, new SessionMemory(), new NoOpSessionStore());
    }

    public Session(Path workspace, Config config, SessionMemory memory) {
        this(workspace, config, memory, new NoOpSessionStore());
    }

    /**
     * Primary constructor. All parameters are required — callers must pass
     * an explicit {@link SessionMemory} and {@link SessionStore}. Pass
     * {@link NoOpSessionStore} explicitly to keep the ephemeral-store
     * default; silent null-to-NoOp substitution is no longer supported at
     * this seam (CCR-016).
     *
     * <p>The 2-arg and 3-arg convenience constructors still provide
     * explicit {@code NoOpSessionStore} defaults for tests and ad-hoc call
     * sites — those are explicit wiring, not policy-by-null.
     */
    public Session(Path workspace, Config config, SessionMemory memory, SessionStore store) {
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.startedAt = Instant.now();
        this.turnCount = new AtomicInteger(0);
        this.memory = Objects.requireNonNull(memory,
                "memory must not be null — pass new SessionMemory() explicitly");
        this.store = Objects.requireNonNull(store,
                "store must not be null — pass NoOpSessionStore() explicitly "
                        + "to keep the ephemeral-store default (CCR-016)");
    }

    /** The workspace root this session is bound to. */
    public Path workspace() { return workspace; }

    /** Configuration snapshot for this session. */
    public Config config() { return config; }

    /** When this session was created. */
    public Instant startedAt() { return startedAt; }

    /** Current turn number (0-based, incremented per prompt — not per command). */
    public int turnCount() { return turnCount.get(); }

    /** Increment turn counter and return the new value. */
    public int nextTurn() { return turnCount.incrementAndGet(); }

    /** Session-scoped conversational memory (rolling window). */
    public SessionMemory memory() { return memory; }

    /** The session store used for persistence (NoOp by default). */
    public SessionStore store() { return store; }

    /** Register a listener to be notified when the session closes. */
    public void addCloseListener(SessionListener listener) {
        if (listener != null) {
            closeListeners.add(listener);
        }
    }

    /** Whether this session has been closed. */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Close the session, firing all registered close listeners.
     * Safe to call multiple times — only the first call fires listeners.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (SessionListener listener : closeListeners) {
                try {
                    listener.onSessionEnd();
                } catch (Exception ignored) {
                    // Close listener errors must not prevent other listeners from running
                }
            }
        }
    }
}

