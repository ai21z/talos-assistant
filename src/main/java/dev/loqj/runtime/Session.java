package dev.loqj.runtime;

import dev.loqj.cli.repl.SessionMemory;
import dev.loqj.core.Config;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Immutable session context for a single Loqs runtime invocation.
 * Carries workspace binding, configuration, turn tracking, and session memory.
 *
 * <p>A session is created once per REPL run (or per programmatic invocation)
 * and stays alive until the user quits. Turn count is the only mutable field
 * and is tracked via an atomic counter for safe concurrent access.
 *
 * <p>Session does <em>not</em> own LOQ-J retrieval internals or LLM state.
 * Those are composed separately in the runtime context.
 */
public final class Session {

    private final Path workspace;
    private final Config config;
    private final Instant startedAt;
    private final AtomicInteger turnCount;
    private final SessionMemory memory;

    public Session(Path workspace, Config config) {
        this(workspace, config, new SessionMemory());
    }

    public Session(Path workspace, Config config, SessionMemory memory) {
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.startedAt = Instant.now();
        this.turnCount = new AtomicInteger(0);
        this.memory = (memory != null) ? memory : new SessionMemory();
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
}

