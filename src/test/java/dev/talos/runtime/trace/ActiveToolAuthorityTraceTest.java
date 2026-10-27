package dev.talos.runtime.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Supplies the turn-owned authority scope required by lower-level tool-loop
 * characterization tests that deliberately bypass {@code TurnProcessor.process}.
 */
public abstract class ActiveToolAuthorityTraceTest {
    private static final AtomicLong NEXT_TRACE = new AtomicLong();

    @BeforeEach
    final void beginToolAuthorityTrace() {
        LocalTurnTraceCapture.clear();
        long ordinal = NEXT_TRACE.incrementAndGet();
        LocalTurnTraceCapture.begin(
                "trace-test-tool-authority-" + ordinal,
                "session-test-tool-authority",
                1,
                "2026-07-20T00:00:00Z",
                "workspace-test-tool-authority",
                "test",
                "test",
                "test",
                "lower-level deterministic tool authority fixture");
    }

    @AfterEach
    final void clearToolAuthorityTrace() {
        LocalTurnTraceCapture.clear();
    }
}
